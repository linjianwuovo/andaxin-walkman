package com.ljyh.mei.mcp

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.gson.JsonObject
import com.ljyh.mei.constants.LoopPlaybackKey
import com.ljyh.mei.constants.PreviousPlaybackKey
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.toSystemLyricSong
import com.ljyh.mei.source.api.ContentType
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.PlaylistRef
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceFeatureCapability
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.api.SourcePage
import com.ljyh.mei.source.api.SourcePlaylist
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.lyric.LyricManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal class MeiloXTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: SourceRouter,
    private val preferences: SourcePreferences,
    private val history: HistoryRepository,
    private val lyrics: Lazy<LyricManager>,
) {
    suspend fun execute(tool: McpTool, args: JsonObject, playback: McpPlayback, checkPermission: () -> Unit): JsonObject {
        checkPermission()
        val page = PageRequest(
            offset = args.long("offset")?.toInt() ?: 0,
            limit = args.long("limit")?.toInt() ?: 20,
            cursor = args.string("cursor"),
        )
        return when (tool.name) {
            "get_playback_state" -> {
                val volume = mediaVolumePercent()
                playback.use { service ->
                    checkPermission()
                    val player = service.player
                    jsonObject {
                        add("track", player.currentMediaItem?.toJson())
                        addProperty("playing", player.isPlaying)
                        addProperty("playWhenReady", player.playWhenReady)
                        addProperty("positionMs", player.currentPosition)
                        addProperty("durationMs", player.duration.takeUnless { it == C.TIME_UNSET })
                        addProperty("volumePercent", volume)
                        addProperty("shuffle", player.shuffleModeEnabled || service.queueManager.shuffleModeIntent.value)
                        addProperty("repeat", when (player.repeatMode) {
                            Player.REPEAT_MODE_ONE -> "repeat_one"
                            Player.REPEAT_MODE_ALL -> "repeat_all"
                            else -> "off"
                        })
                        addProperty("personalFm", service.isFmMode() && !service.queueManager.queueAutoplayEnabled.value)
                        addProperty("queueAutoplay", service.queueManager.queueAutoplayEnabled.value)
                    }
                }
            }
            "get_current_lyrics" -> currentLyrics(playback, page, checkPermission)
            "get_queue" -> playback.use { service ->
                checkPermission()
                val player = service.player
                val order = player.playbackOrderIndices()
                val items = order.asSequence().drop(page.offset).take(page.limit).map { player.getMediaItemAt(it).toJson() }.toList()
                pageJson(items, page.offset, page.offset + items.size < order.size).apply {
                    addProperty("title", service.queueTitle?.take(256))
                    addProperty("currentIndex", order.indexOf(player.currentMediaItemIndex))
                    addProperty("total", order.size)
                }
            }
            "search_catalog" -> {
                val provider = provider(args, search = true)
                checkPermission()
                router.search(provider, args.string("query")!!, ContentType.TRACK, page).let { result ->
                    pageJson(result, page) { item -> (item as SourceItem.TrackItem).track.toJson() }
                        .apply { addProperty("providerId", provider) }
                }
            }
            "list_user_playlists" -> {
                val provider = provider(args)
                checkPermission()
                val result = router.library(provider, LibraryCollection.CREATED_PLAYLISTS, page)
                pageJson(result, page) { (it as SourceItem.PlaylistItem).playlist.toJson() }
            }
            "get_playlist_tracks" -> {
                val playlist = playlist(args)
                checkPermission()
                pageJson(router.playlistTracks(playlist, page), page, Track::toJson)
            }
            "get_listening_history" -> {
                val items = history.getRecentSongs(page.limit + 1, page.offset)
                checkPermission()
                pageJson(items.take(page.limit).map { item ->
                    item.song.toMediaMetadata().toSourceTrack().toJson().apply { addProperty("playedAt", item.playedAt) }
                }, page.offset, items.size > page.limit)
            }
            "play_tracks", "enqueue_tracks" -> {
                // Hydrate before modifying the current queue so a missing track cannot partially replace it.
                val refs = args.trackRefs()
                val tracks = router.getTracks(refs)
                if (tracks.size != refs.size) throw McpToolError("One or more tracks were not found")
                val items = tracks.map { it.toMediaMetadata().toMediaItem() }
                playback.use { service ->
                    checkPermission()
                    if (tool.name == "play_tracks") {
                        service.queueTitle = items.first().mediaMetadata.title?.toString()
                        service.queueManager.playQueue(
                            ListQueue(id = "mcp", title = service.queueTitle, items = items.map { it.mediaId to it }),
                            startInShuffleMode = service.player.shuffleModeEnabled || service.queueManager.shuffleModeIntent.value,
                            repeatMode = service.player.repeatMode,
                        )
                    } else service.addToQueue(items)
                }
                accepted()
            }
            "start_personal_fm" -> {
                val provider = provider(args)
                val capability = router.registry.provider(provider) as? SourceFeatureCapability
                if ("personalRadio" !in capability?.features?.get("discovery").orEmpty()) {
                    throw McpToolError("The selected source does not support personal radio")
                }
                playback.use { service ->
                    checkPermission()
                    service.queueTitle = "私人 FM"
                    service.queueManager.startFmModeById(null, provider)
                }
                accepted()
            }
            "set_volume" -> {
                checkPermission()
                val audio = context.getSystemService(AudioManager::class.java)
                    ?: throw McpToolError("Media volume is unavailable")
                if (audio.isVolumeFixed) throw McpToolError("Media volume is fixed on this device")
                val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = minimum + ((maximum - minimum) * args.long("percent")!!.toFloat() / 100f).roundToInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                accepted()
            }
            "like_track" -> {
                val ref = decodeTrack(args.string("trackId")!!)
                checkPermission()
                router.setLiked(ref, args.flag("liked"))
                accepted()
            }
            "create_playlist" -> {
                val provider = provider(args)
                checkPermission()
                router.createPlaylist(provider, args.string("title")!!, args.string("description")).toJson()
            }
            "modify_playlist_tracks" -> {
                val playlist = playlist(args)
                val refs = args.trackRefs().distinct()
                if (refs.any { it.providerId != playlist.providerId }) throw McpToolError("Tracks must belong to the playlist's provider")
                checkPermission()
                if (args.string("action") == "add") router.addTracks(playlist, refs)
                else router.removeTracks(playlist, refs)
                accepted()
            }
            else -> playbackCommand(tool, args, playback, checkPermission)
        }
    }

    private suspend fun currentLyrics(playback: McpPlayback, page: PageRequest, checkPermission: () -> Unit): JsonObject {
        val snapshot = playback.use { service ->
            checkPermission()
            val item = service.player.currentMediaItem ?: return@use null
            val manager = lyrics.get()
            val data = manager.lyricData.value.takeIf { manager.songId == item.mediaId }
            val position = service.player.currentPosition
            Triple(item, data, position)
        } ?: return JsonObject()
        val (item, data, position) = snapshot
        val lines = item.metadata?.toSystemLyricSong(data)?.lyrics.orEmpty()
        if (lines.isNotEmpty()) {
            val slice = lines.asSequence().drop(page.offset).take(page.limit).map { line ->
                jsonObject {
                    addProperty("startMs", line.begin)
                    addProperty("endMs", line.end)
                    addProperty("text", line.text?.take(256))
                    addProperty("translation", line.translation?.take(256))
                }
            }.toList()
            return pageJson(slice, page.offset, page.offset + slice.size < lines.size).apply {
                addProperty("trackId", item.mediaId)
                addProperty("positionMs", position)
            }
        }
        val providerLyrics = try {
            checkPermission()
            router.lyrics(decodeTrack(item.mediaId))
        } catch (error: SourceException) {
            if (error.code == SourceErrorCode.UNSUPPORTED) null else throw error
        }
        return playback.use { service ->
            checkPermission()
            if (service.player.currentMediaItem?.mediaId != item.mediaId) throw McpToolError("The track changed while loading lyrics")
            jsonObject {
                addProperty("trackId", item.mediaId)
                addProperty("positionMs", service.player.currentPosition)
                addProperty("text", providerLyrics?.text?.take(48_000))
                addProperty("translation", providerLyrics?.translation?.take(24_000))
            }
        }
    }

    private suspend fun playbackCommand(tool: McpTool, args: JsonObject, playback: McpPlayback, checkPermission: () -> Unit): JsonObject {
        val preferences = when (tool.name) {
            "skip_previous", "set_play_mode" -> context.dataStore.data.first()
            else -> null
        }
        return playback.use { service ->
            checkPermission()
            val player = service.player
            when (tool.name) {
                "play_pause" -> {
                    val shouldPlay = when (args.string("action")) {
                        "play" -> true
                        "pause" -> false
                        else -> !player.playWhenReady
                    }
                    if (shouldPlay) {
                        if (player.currentMediaItem == null) throw McpToolError("The queue is empty")
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.play()
                    } else player.pause()
                }
                "skip_next" -> {
                    if (!player.hasNextMediaItem()) throw McpToolError("No next track")
                    player.seekToNext()
                    player.play()
                }
                "skip_previous" -> {
                    if (player.currentMediaItem == null) throw McpToolError("The queue is empty")
                    if (preferences?.get(PreviousPlaybackKey) != false) {
                        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
                    } else player.seekToPrevious()
                    player.play()
                }
                "seek_to" -> {
                    if (!player.isCurrentMediaItemSeekable) throw McpToolError("The current track is not seekable")
                    player.seekTo(args.long("positionMs")!!.coerceAtMost(player.duration.takeIf { it >= 0 } ?: Long.MAX_VALUE))
                }
                "set_play_mode" -> {
                    val mode = args.string("mode")
                    service.setShuffleModeEnabled(mode == "shuffle")
                    player.repeatMode = when (mode) {
                        "repeat_one" -> Player.REPEAT_MODE_ONE
                        "repeat_all" -> Player.REPEAT_MODE_ALL
                        "shuffle" -> if (preferences?.get(LoopPlaybackKey) == false) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                "set_sleep_timer" -> {
                    val minutes = args.long("minutes")!!.toInt()
                    if (minutes == 0) service.sleepTimer.clear() else service.sleepTimer.start(minutes)
                }
                "set_queue_autoplay" -> {
                    if (!service.queueManager.setQueueAutoplayEnabled(args.flag("enabled"))) {
                        throw McpToolError("Autoplay is unavailable for the current queue")
                    }
                    service.schedulePlaybackSnapshot()
                }
                else -> throw McpToolError("Unknown tool")
            }
            accepted()
        }
    }

    private suspend fun provider(args: JsonObject, search: Boolean = false): String {
        router.registry.awaitInitialized()
        args.string("providerId")?.let { return router.registry.requireProvider(it).id }
        val settings = preferences.snapshot()
        if (!search) return router.registry.requireProvider(settings.preferredBrowseProvider).id
        val preferred = router.registry.provider(settings.preferredSearchProvider)
        if (preferred != null && SourceCapability.SEARCH in preferred.capabilities) return preferred.id
        return router.registry.providers.value.firstOrNull { SourceCapability.SEARCH in it.capabilities && router.registry.provider(it.id) === it }?.id
            ?: throw McpToolError("No searchable music source is available")
    }

    private suspend fun playlist(args: JsonObject) = PlaylistRef(provider(args), args.string("playlistId")!!)

    private fun JsonObject.trackRefs() = getAsJsonArray("trackIds").map { decodeTrack(it.asString) }
    private fun decodeTrack(id: String): TrackRef = TrackRefCodec.decode(id) ?: throw McpToolError("Invalid MeiloX track ID")

    private fun mediaVolumePercent(): Int? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maximum <= minimum) 100 else ((current - minimum) * 100f / (maximum - minimum)).roundToInt()
    }

    private fun accepted() = jsonObject { addProperty("accepted", true) }
}

private fun Track.toJson() = jsonObject {
    addProperty("trackId", TrackRefCodec.mediaId(ref))
    addProperty("providerId", ref.providerId)
    addProperty("title", title.take(256))
    addProperty("artists", artists.take(10).joinToString(" / ") { it.name.take(100) }.take(512))
    addProperty("album", album.title.take(256))
    addProperty("durationMs", durationMs)
}

private fun MediaItem.toJson() = metadata?.toSourceTrack()?.toJson() ?: jsonObject {
    addProperty("trackId", mediaId.take(1024))
    addProperty("title", mediaMetadata.title?.toString()?.take(256))
    addProperty("artists", mediaMetadata.artist?.toString()?.take(512))
}

private fun SourcePlaylist.toJson() = jsonObject {
    addProperty("playlistId", ref.playlistId)
    addProperty("providerId", ref.providerId)
    addProperty("title", title.take(256))
    addProperty("trackCount", trackCount)
}

private fun <T> pageJson(page: SourcePage<T>, request: PageRequest, map: (T) -> JsonObject) =
    pageJson(page.items.take(request.limit).map(map), request.offset, page.hasMore).apply {
        val cursor = page.nextCursor?.takeIf { it.isNotBlank() && it.length <= 4096 && it != request.cursor }
        if (page.hasMore && cursor != null) addProperty("nextCursor", cursor)
    }

private fun pageJson(items: List<JsonObject>, offset: Int, hasMore: Boolean) = jsonObject {
    add("items", items.jsonArray())
    addProperty("nextOffset", (offset + items.size).takeIf { hasMore && items.isNotEmpty() })
}
