package com.andaxin.data.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/*
 * AI 课堂"有哪几门课"。
 *
 * 走 FIF 自己的 `myClassroom`〔B1〕，不再拿课表的 `jxbh` 去猜它的班号 ——
 * 清点完抓包发现这条**有 A 级实样**（24 次、`data.dataList` 15 条、27 个键带样例），
 * 而"课表 jxbh == FIF teachClassId"从头到尾只是我形状对得上的猜测，
 * 用它当入参的结果是：对不上就一直显示空，而空看着像"这门课没内容"。那是最坏的一种错。
 *
 * 顺序是先过单点登录、再拿学期、最后按选中的那个学期要课。
 * 学期那条〔B2〕53 条，`selected` 那一条才是现在这个学期。
 */
class AiCourseFeed(
    private val fif: FifSession,
    private val api: FifApi,
) {

    private data class Cached(val owner: String, val courses: List<AiCourse>)

    @Volatile private var cached: Cached? = null

    suspend fun courses(force: Boolean = false): Result<List<AiCourse>> {
        currentCoroutineContext().ensureActive()
        return try {
            val owner = fif.owner() ?: throw Exception("校内还没登录")
            val hit = fif.withOwner(owner) { cached?.takeIf { it.owner == owner } }
            if (!force && hit != null) return Result.success(hit.courses)
            fif.ensure().getOrThrow()
            fif.withOwner(owner) { }
            val terms = api.terms()
            fif.withOwner(owner) { }
            currentCoroutineContext().ensureActive()
            val term = terms.firstOrNull { it.selected } ?: terms.firstOrNull()
                ?: throw Exception("没能取得 AI 课堂学期，请重试。")
            val courses = api.courses(term.year, term.num)
            currentCoroutineContext().ensureActive()
            fif.withOwner(owner) {
                cached = Cached(owner, courses)
                Result.success(courses)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        }
    }

    suspend fun find(id: String): AiCourse? = courses().getOrNull()?.firstOrNull { it.id == id }

    /** 当前学期。直录播那发要它的 `id` 当 `termId`。 */
    suspend fun currentTerm(): AiTerm? {
        val owner = fif.owner() ?: return null
        fif.ensure().getOrNull() ?: return null
        fif.withOwner(owner) { }
        val terms = api.terms()
        return fif.withOwner(owner) { terms.selected() }
    }

    private fun List<AiTerm>.selected(): AiTerm? = firstOrNull { it.selected } ?: firstOrNull()

    fun invalidate() { cached = null }
}
