package com.ljyh.mei.ui.screen.comment

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.shared.OnlineComment
import com.ljyh.mei.source.shared.OnlineCommentPage
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal class CommentPagingSource(
    private val sortType: CommentSortType,
    private val loadPage: suspend (pageNo: Int, pageSize: Int, cursor: String) -> Resource<OnlineCommentPage>,
    private val onTotalReceived: (Int) -> Unit = {}
) : PagingSource<CommentPagingSource.Key, OnlineComment>() {
    data class Key(val pageNo: Int, val cursor: String)

    private val seenComments = HashSet<String>()
    private val seenCursors = HashSet<String>()

    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, OnlineComment> = try {
        val key = params.key ?: Key(1, "")
        when (val result = loadPage(key.pageNo, params.loadSize, key.cursor)) {
            is Resource.Success -> {
                val page = result.data
                if (key.pageNo == 1) onTotalReceived(page.totalCount)
                val comments = page.comments.filter { seenComments.add(it.commentId) }
                val nextCursor = page.cursor?.takeIf(String::isNotBlank)
                    ?: page.comments.lastOrNull()?.time?.toString()?.takeIf { sortType == CommentSortType.TIME }
                val repeatedCursor = nextCursor != null && !seenCursors.add(nextCursor)
                val canAppend = page.hasMore && !repeatedCursor && (nextCursor != null || comments.isNotEmpty())
                if (comments.size != page.comments.size || page.hasMore && !canAppend) {
                    Timber.d(
                        "Comment page sort=%s page=%d received=%d added=%d hasMore=%b cursorPresent=%b cursorRepeated=%b",
                        sortType, key.pageNo, page.comments.size, comments.size, page.hasMore, nextCursor != null, repeatedCursor
                    )
                }
                LoadResult.Page(
                    data = comments,
                    prevKey = null,
                    nextKey = if (canAppend) Key(key.pageNo + 1, nextCursor.orEmpty()) else null
                )
            }
            is Resource.Error -> LoadResult.Error(Exception(result.message))
            Resource.Loading -> LoadResult.Error(IllegalStateException("Comment request did not finish"))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<Key, OnlineComment>): Key? = null
}
