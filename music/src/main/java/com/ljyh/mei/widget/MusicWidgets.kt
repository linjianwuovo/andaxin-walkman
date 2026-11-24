package com.ljyh.mei.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver.PendingResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.Player
import com.ljyh.mei.MainActivity
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.PrivateFmMode
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.SourceFeatureCapability
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.source.shared.OnlineComment
import com.ljyh.mei.ui.screen.sourceContentRef
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object MusicWidgets {
    private const val PREFIX = "com.ljyh.mei.widget."
    const val ACTION_PLAY_PAUSE = PREFIX + "PLAY_PAUSE"
    const val ACTION_PREVIOUS = PREFIX + "PREVIOUS"
    const val ACTION_NEXT = PREFIX + "NEXT"
    const val ACTION_LIKE = PREFIX + "LIKE"
    const val ACTION_PLAY_RECENT = PREFIX + "PLAY_RECENT"
    const val ACTION_PLAY_COLLECTION = PREFIX + "PLAY_COLLECTION"
    const val ACTION_START_FM = PREFIX + "START_FM"
    const val ACTION_PLAY_MODE = PREFIX + "PLAY_MODE"
    const val ACTION_WIDGET_SKIN = PREFIX + "WIDGET_SKIN"
    const val ACTION_OPEN_PLAYER = PREFIX + "OPEN_PLAYER"
    const val ACTION_OPEN_LYRICS = PREFIX + "OPEN_LYRICS"
    const val ACTION_OPEN_HISTORY = PREFIX + "OPEN_HISTORY"
    const val ACTION_OPEN_DAILY = PREFIX + "OPEN_DAILY"
    const val ACTION_OPEN_FAVORITES = PREFIX + "OPEN_FAVORITES"
    const val ACTION_OPEN_STATS = PREFIX + "OPEN_STATS"
    const val ACTION_OPEN_SEARCH = PREFIX + "OPEN_SEARCH"
    const val ACTION_OPEN_CHARTS = PREFIX + "OPEN_CHARTS"
    const val ACTION_OPEN_LIBRARY = PREFIX + "OPEN_LIBRARY"
    const val ACTION_OPEN_CONTENT = PREFIX + "OPEN_CONTENT"
    const val ACTION_OPEN_COMMENTS = PREFIX + "OPEN_COMMENTS"
    const val ACTION_OPEN_PLAYLISTS = PREFIX + "OPEN_PLAYLISTS"
    const val EXTRA_MEDIA_ID = PREFIX + "MEDIA_ID"
    const val EXTRA_RADIO_MODE = PREFIX + "RADIO_MODE"
    const val EXTRA_RADIO_SCENE = PREFIX + "RADIO_SCENE"
    private const val EXTRA_TOKEN = PREFIX + "TOKEN"
    private const val TOKEN = "command_token"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences get() = application.getSharedPreferences("music_widgets", Context.MODE_PRIVATE)
    private lateinit var application: Context
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val refreshes = Channel<PendingResult>(Channel.UNLIMITED)
    private val refreshRequested = AtomicBoolean(true)
    private var renderStarted = false
    @Volatile private var installed = emptyMap<WidgetFamily, IntArray>()
    private val families get() = installed.keys
    @Volatile private var playback: WidgetPlayback? = null
    @Volatile private var progress: Pair<String, WidgetProgress>? = null
    @Volatile private var lyrics: Pair<String, WidgetLyrics>? = null
    @Volatile private var favorite: Pair<String, Boolean?>? = null
    private var historyObserver: Job? = null
    private var catalogObserver: Job? = null
    private var listeningObserver: Job? = null
    private var commentObserver: Job? = null
    private val commentTrack = MutableStateFlow<String?>(null)
    @Volatile private var recent = emptyList<WidgetTrack>()
    @Volatile private var catalog = WidgetCatalog()
    @Volatile private var listening: WidgetListeningCalendar? = null
    @Volatile private var comment: Pair<String, OnlineComment>? = null
    private lateinit var renderer: MusicWidgetRenderer
    private var commandToken: String? = null
    var onRefresh: (() -> Unit)? = null

    @Synchronized
    private fun prepare(context: Context) {
        if (renderStarted) return
        application = context.applicationContext
        renderStarted = true
        updates.trySend(Unit)
        scope.launch {
            renderer = MusicWidgetRenderer(application, scope) { updates.trySend(Unit) }
            for (update in updates) {
                delay(35)
                val pending = buildList {
                    while (true) add(refreshes.tryReceive().getOrNull() ?: break)
                }
                try {
                    if (refreshRequested.getAndSet(false) || pending.isNotEmpty()) {
                        installed = installedWidgets(application)
                        renderer.invalidate()
                        withContext(Dispatchers.Main.immediate) { onRefresh?.invoke() }
                    }
                    render()
                } catch (error: RuntimeException) {
                    Timber.w(error, "Unable to update music widgets")
                } finally {
                    pending.forEach(PendingResult::finish)
                }
            }
        }
    }

    fun publish(context: Context, player: Player, radioMode: PrivateFmMode?) {
        prepare(context)
        if (families.none { it.readsPlayback }) return
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        val track = item?.let {
            val tag = it.metadata
            WidgetTrack(
                id = it.mediaId,
                title = (metadata.title?.toString() ?: tag?.title).orEmpty().take(240),
                artist = (metadata.artist?.toString() ?: tag?.artists?.joinToString { artist -> artist.name }).orEmpty().take(240),
                artwork = (metadata.artworkUri?.toString()?.takeIf(String::isNotBlank) ?: tag?.coverUrl).orEmpty(),
            )
        }
        val next = WidgetPlayback(
            track = track,
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            previous = track != null && player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            next = track != null && player.hasNextMediaItem() &&
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            radioMode = radioMode,
        )
        if (playback != next) {
            playback = next
            updates.trySend(Unit)
        }
        publishProgress(player)
    }

    fun publishProgress(player: Player) {
        if (families.none { it.readsProgress }) return
        val duration = player.duration
        val next = player.currentMediaItem?.mediaId?.let {
            val position = player.currentPosition.coerceAtLeast(0L)
            it to WidgetProgress(position / 1_000L * 1_000L, duration.takeUnless { it == C.TIME_UNSET } ?: 0L)
        }
        if (progress != next) {
            progress = next
            updates.trySend(Unit)
        }
    }

    fun hasProgressWidgets() = families.any { it.readsProgress }

    fun hasLyricsWidgets(context: Context): Boolean {
        prepare(context)
        return families.any { it.readsLyrics }
    }

    fun hasLikeWidgets(context: Context): Boolean {
        prepare(context)
        return families.any { it.readsFavorite }
    }

    fun publishLyrics(context: Context, mediaId: String, line: WidgetLyrics) {
        if (!hasLyricsWidgets(context)) return
        val next = mediaId to line
        if (lyrics != next) {
            lyrics = next
            updates.trySend(Unit)
        }
    }

    fun publishLiked(context: Context, mediaId: String, liked: Boolean?) {
        if (!hasLikeWidgets(context)) return
        val next = mediaId to liked
        if (favorite != next) {
            favorite = next
            updates.trySend(Unit)
        }
    }

    fun refresh(context: Context, pending: PendingResult?) {
        prepare(context)
        if (pending != null) refreshes.trySend(pending)
        refreshRequested.set(true)
        updates.trySend(Unit)
    }

    private fun updateObservers() {
        if (families.none { it.readsHistory }) {
            historyObserver?.cancel()
            historyObserver = null
            recent = emptyList()
        } else if (historyObserver?.isActive != true) {
            historyObserver = scope.launch {
                recentUpdates(application).collect {
                    recent = it
                    updates.trySend(Unit)
                }
            }
        }
        if (families.none { it.readsCatalog }) {
            catalogObserver?.cancel()
            catalogObserver = null
            catalog = WidgetCatalog()
        } else if (catalogObserver?.isActive != true) {
            catalogObserver = scope.launch {
                observeWidgetCatalog(application).collect {
                    catalog = it
                    updates.trySend(Unit)
                }
            }
        }
        if (WidgetFamily.LISTENING !in families) {
            listeningObserver?.cancel()
            listeningObserver = null
            listening = null
        } else if (listeningObserver?.isActive != true) {
            listeningObserver = scope.launch {
                observeWidgetListeningCalendar(application).collect {
                    listening = it
                    updates.trySend(Unit)
                }
            }
        }
        if (WidgetFamily.COMMENT !in families && WidgetFamily.COMMENT_LARGE !in families) {
            commentObserver?.cancel()
            commentObserver = null
            comment = null
        } else if (commentObserver?.isActive != true) {
            commentObserver = scope.launch { observeComments() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeComments() {
        val dependencies = EntryPointAccessors.fromApplication(application, WidgetListeningDependencies::class.java)
        val registry = dependencies.providerRegistry()
        combine(commentTrack, registry.revision) { id, _ ->
            val ref = id?.let(TrackRefCodec::decode)
            Triple(id, ref, ref?.let { registry.provider(it.providerId) })
        }.distinctUntilChanged().flatMapLatest { (id, ref, provider) ->
            val features = (provider as? SourceFeatureCapability)?.features
            if (id == null || ref == null || "comments" !in features?.get("social").orEmpty()) {
                return@flatMapLatest flowOf<Pair<String, OnlineComment>?>(null)
            }
            val revisions = (provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
            revisions.distinctUntilChanged().flatMapLatest {
                flow<Pair<String, OnlineComment>?> {
                    emit(null)
                    val result = withTimeoutOrNull(6_000L) {
                        try {
                            dependencies.onlineRepository().screen("widget-comment", ref.providerId)
                                .getComment(ref.trackId, sortType = CommentSortType.HOT, pageSize = 5)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val first = (result as? Resource.Success)?.data?.comments?.firstOrNull()
                    if (first != null) emit(id to first)
                }
            }
        }.collect {
            comment = it
            updates.trySend(Unit)
        }
    }

    private suspend fun render() {
        updateObservers()
        if (installed.isEmpty()) {
            renderer.clear()
            return
        }
        val saved = playback ?: WidgetPlayback.read(preferences)
        if (playback != null && installed.keys.any { it.readsPlayback }) saved.persist(preferences)
        val id = saved.track?.id
        if (commentObserver != null) commentTrack.value = id
        val content = WidgetContent(
            playback = saved,
            progress = progress?.takeIf { it.first == id }?.second ?: WidgetProgress(),
            lyrics = lyrics?.takeIf { it.first == id }?.second,
            liked = favorite?.takeIf { it.first == id }?.second,
            recent = recent,
            catalog = catalog,
            listening = listening,
            comment = comment?.takeIf { it.first == id }?.second,
        )
        renderer.render(installed, content)
    }

    @Synchronized
    private fun token(context: Context, create: Boolean): String? {
        commandToken?.let { return it }
        val prefs = context.getSharedPreferences("music_widgets", Context.MODE_PRIVATE)
        var value = prefs.getString(TOKEN, null)
        if (value == null && create) {
            value = UUID.randomUUID().toString()
            if (!prefs.edit().putString(TOKEN, value).commit()) return null
        }
        commandToken = value
        return value
    }

    fun isWidgetCommand(context: Context, intent: Intent?): Boolean {
        val provided = intent?.getStringExtra(EXTRA_TOKEN) ?: return false
        return provided == token(context, create = false)
    }

    fun transport(
        context: Context,
        action: String,
        mediaId: String? = null,
        radioMode: String? = null,
        radioScene: String? = null,
    ): PendingIntent? {
        val privateToken = token(context, create = true) ?: return null
        val intent = Intent(context, WidgetPlaybackActivity::class.java).setAction(action)
            .setIdentifier(mediaId ?: radioScene?.let { "$radioMode:$it" } ?: radioMode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_TOKEN, privateToken)
            .putExtra(EXTRA_MEDIA_ID, mediaId)
            .putExtra(EXTRA_RADIO_MODE, radioMode)
            .putExtra(EXTRA_RADIO_SCENE, radioScene)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun open(context: Context, action: String, mediaId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(action)
            .setIdentifier(mediaId)
            .putExtra(EXTRA_MEDIA_ID, mediaId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun configure(
        context: Context,
        appWidgetId: Int,
        activity: Class<out ComponentActivity> = UniversalWidgetConfigureActivity::class.java,
    ): PendingIntent {
        val intent = Intent(context, activity)
            .setIdentifier(appWidgetId.toString())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun changeSkin(context: Context, id: Int, family: WidgetFamily): PendingIntent {
        val intent = Intent(context, family.provider).setAction(ACTION_WIDGET_SKIN)
            .setIdentifier(id.toString()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun installedWidgets(context: Context): Map<WidgetFamily, IntArray> {
        val manager = AppWidgetManager.getInstance(context)
        return buildMap {
            for (family in WidgetFamily.entries) {
                val ids = manager.getAppWidgetIds(ComponentName(context, family.provider))
                if (ids.isNotEmpty()) put(family, ids)
            }
        }
    }
}

abstract class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) = refresh(context)
    override fun onDisabled(context: Context) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in newWidgetIds) {
            manager.updateAppWidgetOptions(id, Bundle().apply {
                putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true)
            })
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MusicWidgets.ACTION_WIDGET_SKIN) {
            super.onReceive(context, intent)
            return
        }
        if (this !is PlayerBarWidget && this !is PlayerWideWidget) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        if (id !in manager.getAppWidgetIds(ComponentName(context, javaClass))) return
        val options = manager.getAppWidgetOptions(id)
        options.putInt(SKIN_OPTION, (options.getInt(SKIN_OPTION) + 1) % 5)
        manager.updateAppWidgetOptions(id, options)
        refresh(context)
    }

    private fun refresh(context: Context) {
        MusicWidgets.refresh(context, goAsync())
    }

    internal companion object {
        const val SKIN_OPTION = "com.ljyh.mei.widget.skin"
    }
}

class PlayerWidget : MusicWidgetProvider()
class PlayerMiniWideWidget : MusicWidgetProvider()
class PlayerBarWidget : MusicWidgetProvider()
class PlayerWideWidget : MusicWidgetProvider()
class PlayerLargeWidget : MusicWidgetProvider()
class NowPlayingWidget : MusicWidgetProvider()
class RecentMusicWidget : MusicWidgetProvider()
class RecentMusicLargeWidget : MusicWidgetProvider()
class NcmRecentMusicWidget : MusicWidgetProvider()
class LyricsWidget : MusicWidgetProvider()
class LyricsLargeWidget : MusicWidgetProvider()
class CommentWidget : MusicWidgetProvider()
class CommentLargeWidget : MusicWidgetProvider()
class CloseFriendsWidget : MusicWidgetProvider()
class CloseFriendsLargeWidget : MusicWidgetProvider()
class PrivateRadioWidget : MusicWidgetProvider()
class VinylMusicWidget : MusicWidgetProvider()
class AppleRadioWidget : MusicWidgetProvider()
class MusicShortcutsWidget : ConfigurableMusicWidget()
class MusicShortcutsWideWidget : ConfigurableMusicWidget()
class MusicShortcutsLargeWidget : ConfigurableMusicWidget()
class UniversalMusicWidget : ConfigurableMusicWidget()
class UniversalMusicWideWidget : ConfigurableMusicWidget()

abstract class ConfigurableMusicWidget : MusicWidgetProvider() {
    override fun onDeleted(context: Context, ids: IntArray) {
        UniversalWidgetSelection.delete(context, ids)
        super.onDeleted(context, ids)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        UniversalWidgetSelection.restore(context, oldWidgetIds, newWidgetIds)
        super.onRestored(context, oldWidgetIds, newWidgetIds)
    }
}
class ListeningWidget : MusicWidgetProvider()
class OpenMusicWidget : MusicWidgetProvider()
class RecommendationsWidget : MusicWidgetProvider()
class RecommendationsLargeWidget : MusicWidgetProvider()
class ChartsWidget : MusicWidgetProvider()
class ChartsMediumWidget : MusicWidgetProvider()
class ChartsLargeWidget : MusicWidgetProvider()
class PinnedMusicWidget : ConfigurablePinnedMusicWidget()
class PinnedMusicLargeWidget : ConfigurablePinnedMusicWidget()

abstract class ConfigurablePinnedMusicWidget : MusicWidgetProvider() {
    override fun onDeleted(context: Context, ids: IntArray) {
        WidgetCollection.delete(context, ids)
        super.onDeleted(context, ids)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        WidgetCollection.restore(context, oldWidgetIds, newWidgetIds)
        super.onRestored(context, oldWidgetIds, newWidgetIds)
    }
}

internal fun decodeWidgetContent(value: String): com.ljyh.mei.source.api.ContentRef? {
    if (value.length > 1024) return null
    val uri = Uri.parse(value)
    if (uri.scheme != "meilox" || uri.authority != "widget-content" || uri.query != null || uri.fragment != null) return null
    val parts = uri.pathSegments
    if (parts.size != 3 || parts.any(String::isBlank)) return null
    val (provider, type, id) = parts
    return sourceContentRef(provider, type, id)
}
