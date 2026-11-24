package com.ljyh.mei.widget

import android.content.Context
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.ContentRef
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.PlaylistRef
import com.ljyh.mei.source.api.SourceFeedPage
import com.ljyh.mei.source.api.SourceFeedTarget
import com.ljyh.mei.source.core.SourceHomeCacheOwner
import com.ljyh.mei.source.core.SourceHomeFeedCache
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

internal data class WidgetCatalog(
    val recommendations: List<WidgetTrack> = emptyList(),
    val charts: List<WidgetTrack> = emptyList(),
    val playlists: List<WidgetTrack> = emptyList(),
)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetCatalogDependencies {
    fun router(): SourceRouter
    fun preferences(): SourcePreferences
    fun homeFeedCache(): SourceHomeFeedCache
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeWidgetCatalog(context: Context): Flow<WidgetCatalog> {
    val dependencies = EntryPointAccessors.fromApplication(context, WidgetCatalogDependencies::class.java)
    val router = dependencies.router()
    val registry = router.registry
    val source = dependencies.preferences().settings.map { it.preferredBrowseProvider }.distinctUntilChanged()
    return combine(source, registry.revision) { providerId, _ -> registry.provider(providerId) }
        .distinctUntilChanged().flatMapLatest { provider ->
            if (provider == null) return@flatMapLatest flowOf(WidgetCatalog())
            val revisions = (provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
            revisions.distinctUntilChanged().flatMapLatest { revision ->
                flow {
                    emit(WidgetCatalog())
                    val owner = SourceHomeCacheOwner(provider.id, provider.instanceId, provider.version, revision)
                    val feed = withTimeoutOrNull(8_000L) {
                        try {
                            dependencies.homeFeedCache().readSnapshot(owner, provider, allowStale = true)
                                ?: router.getFeed(provider.id, FeedKind.HOME, page = PageRequest(limit = 12))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val current = registry.provider(provider.id) === provider && revisions.first() == revision
                    if (current && feed != null) emit(feed.toWidgetCatalog())
                }
            }
        }
}

private fun SourceFeedPage.toWidgetCatalog(): WidgetCatalog {
    val recommendations = mutableListOf<WidgetTrack>()
    val charts = mutableListOf<WidgetTrack>()
    for (section in sections) {
        val isChart = section.id.contains("toplist", true) || section.id.contains("charts", true) ||
            section.title.contains("排行榜") || section.title.contains("榜单") || section.title.contains("charts", true)
        val destination = if (isChart) charts else recommendations
        destination += section.items.map { item -> item.ref.widgetTrack(item.title, section.title, item.artworkUrl) }
        destination += section.cards.mapNotNull { card ->
            (card.target as? SourceFeedTarget.Content)?.ref?.widgetTrack(card.title, card.subtitle, card.artworkUrl)
        }
    }
    val playlists = recommendations.filter { decodeWidgetContent(it.id) is PlaylistRef }.distinctBy { it.id }
    return WidgetCatalog(recommendations.distinctBy { it.id }.take(8), charts.distinctBy { it.id }.take(10), playlists.take(5))
}

private fun ContentRef.widgetTrack(title: String, subtitle: String, artwork: String): WidgetTrack =
    WidgetTrack(
        encodeWidgetContent(this), title, subtitle, artwork,
    )
