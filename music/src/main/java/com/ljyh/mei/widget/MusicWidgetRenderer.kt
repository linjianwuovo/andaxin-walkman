package com.ljyh.mei.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.playback.PrivateFmMode
import com.ljyh.mei.source.shared.OnlineComment
import com.ljyh.mei.ui.component.player.component.mesh.AlbumTextureProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal class MusicWidgetRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val artworkChanged: () -> Unit,
) {
    private val manager = AppWidgetManager.getInstance(context)
    private val lastFrame = mutableMapOf<Int, Frame>()
    private val artwork = WidgetArtwork()
    private val heroPixels = (110 * context.resources.displayMetrics.density).toInt().coerceIn(144, 330)

    private val apple = AppleMusicWidgetViews(context)

    private data class Frame(
        val family: WidgetFamily,
        val playback: WidgetPlayback? = null,
        val progress: WidgetProgress? = null,
        val lyrics: WidgetLyrics? = null,
        val liked: Boolean? = null,
        val tracks: List<WidgetTrack> = emptyList(),
        val covers: Map<String, Bitmap?> = emptyMap(),
        val heroArtwork: String? = null,
        val heroSize: Int = ArtworkRequest.THUMBNAIL_PIXELS,
        val listening: WidgetListeningCalendar? = null,
        val comment: OnlineComment? = null,
        val selection: UniversalWidgetSelection? = null,
        val skin: Int = 0,
        val appleSizes: Map<SizeF, AppleWidgetSize> = emptyMap(),
    )

    fun invalidate() = lastFrame.clear()

    fun clear() {
        lastFrame.clear()
        artwork.retain(emptySet())
    }

    fun render(installed: Map<WidgetFamily, IntArray>, content: WidgetContent) {
        val frames = buildMap {
            for ((family, ids) in installed) {
                for (id in ids) put(id, frame(id, family, content))
            }
        }
        lastFrame.keys.retainAll(frames.keys)
        artwork.retain(frames.values.flatMapTo(mutableSetOf()) { frame ->
            frame.covers.mapNotNull { (url, bitmap) ->
                if (bitmap == null) artworkRequest(url, frame.heroArtwork, frame.heroSize) else null
            }
        })
        for ((id, frame) in frames) {
            val previous = lastFrame[id]
            if (previous == frame) continue
            try {
                if (frame.progress != null && previous?.copy(progress = frame.progress) == frame) {
                    manager.partiallyUpdateAppWidget(id, progressViews(frame.family, frame.progress))
                } else {
                    manager.updateAppWidget(id, renderFrame(id, frame))
                }
                lastFrame[id] = frame
            } catch (error: RuntimeException) {
                Timber.w(error, "Unable to render music widget %s", frame.family)
            }
        }
    }

    private fun frame(id: Int, family: WidgetFamily, content: WidgetContent): Frame {
        val selection = when (family) {
            WidgetFamily.UNIVERSAL, WidgetFamily.UNIVERSAL_WIDE,
            WidgetFamily.SHORTCUTS, WidgetFamily.SHORTCUTS_WIDE, WidgetFamily.SHORTCUTS_LARGE ->
                UniversalWidgetSelection.read(context, id)
            else -> null
        }
        val history = content.recent
        val previousFrame = lastFrame[id]
        val sizes = previousFrame?.appleSizes ?: appleSizes(id, family)
        val tablet = sizes.values.any { it >= AppleWidgetSize.TABLET_WIDE }
        val tracks = when (family) {
            WidgetFamily.PINS, WidgetFamily.PINS_LARGE -> WidgetCollection.read(context, id)
                .take(if (family == WidgetFamily.PINS) 4 else 6)
                .map { WidgetTrack(it.id, it.title, "", it.artwork) }
            WidgetFamily.RECOMMENDATIONS -> content.catalog.recommendations.take(2)
            WidgetFamily.RECOMMENDATIONS_LARGE -> content.catalog.recommendations.take(if (tablet) 8 else 4)
            WidgetFamily.CHARTS -> content.catalog.charts.take(1)
            WidgetFamily.CHARTS_MEDIUM -> content.catalog.charts.take(2)
            WidgetFamily.CHARTS_LARGE -> content.catalog.charts.take(if (tablet) 10 else 5)
            WidgetFamily.RECENT_LARGE -> {
                val current = content.playback.track
                val historySlots = when {
                    AppleWidgetSize.TABLET_PORTRAIT in sizes.values -> 6
                    AppleWidgetSize.TABLET_WIDE in sizes.values -> 4
                    else -> 3
                }
                if (current == null) history.take(historySlots + 1)
                else {
                    val historyId = current.historyId
                    history.filterNot { it.id == historyId }.take(historySlots)
                }
            }
            WidgetFamily.RECENT, WidgetFamily.NOW_PLAYING -> if (content.playback.track == null) history.take(1) else emptyList()
            WidgetFamily.NCM_RECENT -> history.take(5)
            WidgetFamily.VINYL -> content.catalog.playlists
            else -> when (selection) {
                UniversalWidgetSelection.RECENT -> history.take(5)
                UniversalWidgetSelection.PLAYLIST_DISCOVERY -> content.catalog.playlists.take(1)
                UniversalWidgetSelection.RECOMMENDATIONS -> content.catalog.recommendations.take(1)
                else -> emptyList()
            }
        }
        val playback = content.playback.takeIf {
            family.readsPlayback && (family !in radioFamilies || it.radioMode != null)
        }
        val urls = (tracks.map(WidgetTrack::artwork) + listOfNotNull(playback?.track?.artwork))
            .filter(String::isNotBlank)
        val heroArtwork = when (family) {
            WidgetFamily.NOW_PLAYING, WidgetFamily.RECENT, WidgetFamily.RECENT_LARGE,
            WidgetFamily.PLAYER_WIDE, WidgetFamily.PLAYER_LARGE ->
                (playback?.track ?: tracks.firstOrNull())?.artwork
            WidgetFamily.CHARTS -> tracks.firstOrNull()?.artwork
            else -> null
        }?.takeIf(String::isNotBlank)
        val heroSize = when (family) {
            WidgetFamily.PLAYER_WIDE -> (85 * context.resources.displayMetrics.density).toInt().coerceIn(144, 288)
            WidgetFamily.PLAYER_LARGE -> (160 * context.resources.displayMetrics.density).toInt().coerceIn(240, 480)
            else -> heroPixels
        }
        return Frame(
            family = family,
            playback = playback,
            progress = content.progress.takeIf { family.readsProgress },
            lyrics = if (family == WidgetFamily.PLAYER_LARGE) content.lyrics
                else content.lyrics?.current?.takeIf { family.readsLyrics }?.let { WidgetLyrics(null, it, null) },
            liked = content.liked.takeIf { family.readsFavorite && playback?.track != null },
            tracks = tracks,
            covers = urls.associateWith { url ->
                val request = artworkRequest(url, heroArtwork, heroSize)
                val retained = previousFrame?.let {
                    if (artworkRequest(url, it.heroArtwork, it.heroSize) == request) it.covers[url] else null
                }
                retained ?: artwork.get(request)
            },
            heroArtwork = heroArtwork,
            heroSize = heroSize,
            listening = content.listening.takeIf { family == WidgetFamily.LISTENING },
            comment = content.comment.takeIf { family in commentFamilies },
            selection = selection,
            skin = previousFrame?.skin ?: if (family in skinnableFamilies) {
                manager.getAppWidgetOptions(id).getInt(MusicWidgetProvider.SKIN_OPTION)
            } else 0,
            appleSizes = sizes,
        )
    }

    private fun artworkRequest(url: String, heroArtwork: String?, heroSize: Int) =
        ArtworkRequest(url, if (url == heroArtwork) heroSize else ArtworkRequest.THUMBNAIL_PIXELS)

    private fun renderFrame(id: Int, frame: Frame): RemoteViews {
        if (frame.family.apple) return renderApple(id, frame)
        return when (frame.family) {
            WidgetFamily.PLAYER, WidgetFamily.PLAYER_MINI_WIDE, WidgetFamily.PLAYER_BAR,
            WidgetFamily.PLAYER_WIDE, WidgetFamily.PLAYER_LARGE, WidgetFamily.VINYL -> player(id, frame)
            WidgetFamily.LYRICS, WidgetFamily.LYRICS_LARGE -> lyrics(frame)
            WidgetFamily.RADIO -> radio(frame)
            WidgetFamily.NCM_RECENT -> recent(frame)
            WidgetFamily.CLOSE_FRIENDS, WidgetFamily.CLOSE_FRIENDS_LARGE ->
                views(R.layout.widget_ncm_close_friends)
            WidgetFamily.LISTENING -> widgetListeningCalendarViews(context, frame.listening)
            WidgetFamily.COMMENT, WidgetFamily.COMMENT_LARGE -> comment(frame)
            WidgetFamily.SHORTCUTS, WidgetFamily.SHORTCUTS_WIDE, WidgetFamily.SHORTCUTS_LARGE,
            WidgetFamily.UNIVERSAL, WidgetFamily.UNIVERSAL_WIDE -> configurable(id, frame)
            else -> error("Unsupported widget family ${frame.family}")
        }
    }

    private fun appleSizes(id: Int, family: WidgetFamily): Map<SizeF, AppleWidgetSize> {
        if (family !in tabletAppleFamilies) return emptyMap()
        val options = manager.getAppWidgetOptions(id)
        val reportedSizes = BundleCompat.getParcelableArrayList(
            options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java,
        ).orEmpty()
        val sizes = if (reportedSizes.isEmpty() && family == WidgetFamily.RECENT_LARGE) {
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (width > 0 && height > 0) listOf(SizeF(width.toFloat(), height.toFloat())) else emptyList()
        } else reportedSizes
        return sizes.take(4).associateWith { size ->
            when {
                size.width >= 600 && size.height >= 295 && size.width > size.height -> AppleWidgetSize.TABLET_WIDE
                size.width >= 440 && size.height >= 410 -> AppleWidgetSize.TABLET_PORTRAIT
                else -> AppleWidgetSize.LARGE
            }
        }
    }

    private fun renderApple(id: Int, frame: Frame): RemoteViews {
        val (family, defaultSize) = when (frame.family) {
            WidgetFamily.NOW_PLAYING, WidgetFamily.CHARTS, WidgetFamily.OPEN_MUSIC ->
                frame.family to AppleWidgetSize.SMALL
            WidgetFamily.RECENT_LARGE -> WidgetFamily.RECENT to AppleWidgetSize.LARGE
            WidgetFamily.CHARTS_MEDIUM -> WidgetFamily.CHARTS to AppleWidgetSize.MEDIUM
            WidgetFamily.CHARTS_LARGE -> WidgetFamily.CHARTS to AppleWidgetSize.LARGE
            WidgetFamily.PINS_LARGE -> WidgetFamily.PINS to AppleWidgetSize.LARGE
            WidgetFamily.RECOMMENDATIONS_LARGE -> WidgetFamily.RECOMMENDATIONS to AppleWidgetSize.LARGE
            WidgetFamily.APPLE_RADIO -> WidgetFamily.APPLE_RADIO to AppleWidgetSize.LARGE
            else -> frame.family to AppleWidgetSize.MEDIUM
        }
        fun render(size: AppleWidgetSize, bounds: SizeF? = null) =
            apple.render(id, family, size, frame.playback, frame.tracks, frame.covers, bounds)
        val sizes = frame.appleSizes
        if (sizes.size <= 1 || frame.family != WidgetFamily.RECENT_LARGE && sizes.values.distinct().size == 1) {
            val first = sizes.entries.firstOrNull()
            return render(first?.value ?: defaultSize, first?.key)
        }
        return RemoteViews(sizes.mapValues { (bounds, size) -> render(size, bounds) })
    }

    private fun player(id: Int, frame: Frame): RemoteViews = views(playerLayout(frame.family)).apply {
        val state = frame.playback ?: WidgetPlayback(null)
        val track = state.track
        val compact = frame.family == WidgetFamily.PLAYER
        val vinyl = frame.family == WidgetFamily.VINYL
        val miniWide = frame.family == WidgetFamily.PLAYER_MINI_WIDE || vinyl
        val bar = frame.family == WidgetFamily.PLAYER_BAR
        val large = frame.family == WidgetFamily.PLAYER_LARGE
        val classic = !compact && !miniWide
        val icons = when {
            vinyl -> vinylIcons
            large -> largeIcons
            classic -> classicIcons
            else -> miniIcons
        }
        val open = MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_PLAYER)
        setOnClickPendingIntent(R.id.widget_root, open)
        val title = track?.title?.takeIf(String::isNotBlank)
        val artist = track?.artist.orEmpty()
        setTextViewText(
            R.id.widget_title,
            when {
                title == null -> text(R.string.widget_no_track)
                (compact || bar || large) && artist.isNotBlank() -> "$title - $artist"
                else -> title
            },
        )
        if (!bar && !compact && !large) setTextViewText(R.id.widget_subtitle, artist)
        val placeholder = when {
            large -> R.drawable.widget_ncm_classic_cover
            classic -> R.drawable.widget_ncm_classic_disc
            vinyl -> R.mipmap.ic_launcher_round
            else -> R.mipmap.ic_launcher
        }
        val cover = frame.covers[track?.artwork]
        if (compact || frame.family == WidgetFamily.PLAYER_MINI_WIDE) {
            val coverUrl = track?.artwork.orEmpty()
            val background = cover?.let { artwork.miniBackground(coverUrl, it, compact) }
            val fallback = if (compact) R.drawable.widget_ncm_mini_square else R.drawable.widget_ncm_mini_wide
            setCover(R.id.widget_mini_background, background, fallback)
            setCover(
                R.id.widget_artwork,
                cover?.let { artwork.discCover(coverUrl, it) },
                R.drawable.widget_ncm_mini_cover_placeholder,
            )
        } else if (vinyl) {
            setCover(R.id.widget_artwork, cover?.let { artwork.discCover(track?.artwork.orEmpty(), it, fullSize = true) }, placeholder)
        } else {
            setCover(R.id.widget_artwork, cover, placeholder)
        }
        playButton(track, state.playing, icons.play, icons.pause, open)
        if (!compact && !vinyl) {
            transport(R.id.widget_prev, icons.previous, R.string.widget_previous, MusicWidgets.ACTION_PREVIOUS, state.previous)
        }
        transport(R.id.widget_next, icons.next, R.string.widget_next, MusicWidgets.ACTION_NEXT, state.next)
        likeButton(
            track,
            frame.liked,
            if (vinyl && frame.liked == null) R.drawable.widget_ncm_vinyl_like_disabled else icons.like,
            icons.liked,
        )
        if (classic) bindProgress(frame.family, frame.progress ?: WidgetProgress())
        if (frame.family in skinnableFamilies) {
            setInt(R.id.widget_root, "setBackgroundResource", skins.getOrElse(frame.skin) { skins.first() })
            setOnClickPendingIntent(R.id.widget_skin, MusicWidgets.changeSkin(context, id, frame.family))
        }
        if (classic && !bar) {
            val mode = when {
                state.shuffle -> if (large) R.drawable.widget_ncm_classic_large_shuffle else R.drawable.widget_ncm_classic_shuffle
                state.repeatMode == Player.REPEAT_MODE_ONE ->
                    if (large) R.drawable.widget_ncm_classic_large_repeat_one else R.drawable.widget_ncm_classic_repeat_one
                else -> if (large) R.drawable.widget_ncm_classic_large_repeat else R.drawable.widget_ncm_classic_repeat
            }
            transport(R.id.widget_mode, mode, R.string.widget_play_mode, MusicWidgets.ACTION_PLAY_MODE, track != null)
            setOnClickPendingIntent(R.id.widget_search, MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_SEARCH))
        }
        if (large) {
            setTextViewText(R.id.widget_lyric_previous, frame.lyrics?.previous.orEmpty())
            setTextViewText(R.id.widget_lyric, frame.lyrics?.current.orEmpty())
            setTextViewText(R.id.widget_lyric_next, frame.lyrics?.next.orEmpty())
            setOnClickPendingIntent(R.id.widget_lyric, MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_LYRICS))
        }
        if (vinyl) {
            vinylSlots.forEachIndexed { index, view ->
                val playlist = frame.tracks.getOrNull(index)
                bindOutlinedCover(view, frame.covers[playlist?.artwork], 0)
                setOnClickPendingIntent(
                    view, MusicWidgets.open(
                        context,
                        if (playlist == null) MusicWidgets.ACTION_OPEN_PLAYLISTS else MusicWidgets.ACTION_OPEN_CONTENT,
                        playlist?.id,
                    ),
                )
            }
        }
    }

    private fun playerLayout(family: WidgetFamily): Int = when (family) {
        WidgetFamily.PLAYER -> R.layout.widget_player_compact
        WidgetFamily.PLAYER_MINI_WIDE -> R.layout.widget_player_mini_wide
        WidgetFamily.PLAYER_BAR -> R.layout.widget_player_bar
        WidgetFamily.PLAYER_WIDE -> R.layout.widget_player_wide
        WidgetFamily.PLAYER_LARGE -> R.layout.widget_player_large
        WidgetFamily.VINYL -> R.layout.widget_ncm_vinyl
        else -> error("Unsupported player widget family $family")
    }

    private fun progressViews(family: WidgetFamily, progress: WidgetProgress): RemoteViews =
        views(playerLayout(family)).apply { bindProgress(family, progress) }

    private fun RemoteViews.bindProgress(family: WidgetFamily, progress: WidgetProgress) {
        val duration = progress.durationMs.coerceIn(1, Int.MAX_VALUE.toLong())
        val position = progress.positionMs.coerceIn(0, duration)
        setProgressBar(R.id.widget_progress, duration.toInt(), position.toInt(), false)
        if (family != WidgetFamily.PLAYER_BAR) {
            setTextViewText(R.id.widget_elapsed, "${elapsed(position).padStart(5, '0')}/${elapsed(duration)}")
        }
    }

    private fun elapsed(millis: Long): String {
        val seconds = millis / 1_000L
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun lyrics(frame: Frame): RemoteViews =
        views(if (frame.family == WidgetFamily.LYRICS_LARGE) R.layout.widget_lyrics_large else R.layout.widget_lyrics).apply {
            val track = frame.playback?.track
            setTextViewText(R.id.widget_title, track?.title ?: text(R.string.widget_no_track))
            val artist = track?.artist.orEmpty()
            val wide = frame.family == WidgetFamily.LYRICS
            setTextViewText(R.id.widget_subtitle, if (wide && artist.isNotBlank()) "- $artist" else artist)
            setTextViewText(R.id.widget_lyric, frame.lyrics?.current ?: text(R.string.widget_ncm_lyrics_intro))
            bindOutlinedCover(R.id.widget_artwork, frame.covers[track?.artwork])
            setOnClickPendingIntent(R.id.widget_root, MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_LYRICS))
        }

    private fun radio(frame: Frame): RemoteViews = views(R.layout.widget_radio).apply {
        val track = frame.playback?.track
        val start = MusicWidgets.transport(context, MusicWidgets.ACTION_START_FM)
        setOnClickPendingIntent(R.id.widget_root, if (track == null) start else MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_PLAYER))
        val title = track?.title?.takeIf(String::isNotBlank)
        val artist = track?.artist.orEmpty()
        setTextViewText(R.id.widget_title, when {
            title == null -> text(R.string.widget_ncm_radio_welcome)
            artist.isBlank() -> title
            else -> "$title - $artist"
        })
        val mode = when (frame.playback?.radioMode) {
            PrivateFmMode.DEFAULT -> R.string.private_fm_default_mode
            PrivateFmMode.EXPLORE -> R.string.private_fm_explore_mode
            PrivateFmMode.SCENE -> R.string.private_fm_scene_mode
            else -> R.string.private_fm_familiar_mode
        }
        setTextViewText(R.id.widget_subtitle, text(mode))
        bindOutlinedCover(R.id.widget_artwork, frame.covers[track?.artwork], 0)
        playButton(track, frame.playback?.playing == true, R.drawable.widget_ncm_radio_play, R.drawable.widget_ncm_radio_pause, start)
        likeButton(track, frame.liked, R.drawable.widget_ncm_radio_like, R.drawable.widget_ncm_radio_liked)
        val background = track?.artwork?.let { url ->
            frame.covers[url]?.let { artwork.radioBackground(url, it) }
        }
        setCover(R.id.widget_radio_background, background, 0)
        for ((view, scene) in radioSlots) {
            setOnClickPendingIntent(
                view, MusicWidgets.transport(context, MusicWidgets.ACTION_START_FM, radioMode = PrivateFmMode.SCENE.name, radioScene = scene),
            )
        }
    }

    private fun comment(frame: Frame): RemoteViews =
        views(if (frame.family == WidgetFamily.COMMENT_LARGE) R.layout.widget_comment_card_large else R.layout.widget_comment_card).apply {
            val track = frame.playback?.track
            val date = LocalDate.now()
            val locale = context.resources.configuration.locales[0]
            val month = date.format(DateTimeFormatter.ofPattern("MMM'.'", Locale.US))
            val open = MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_COMMENTS, track?.id)
            setTextViewText(R.id.widget_comment_day, date.dayOfMonth.toString().padStart(2, '0'))
            setTextViewText(R.id.widget_comment_month, month)
            setTextViewText(R.id.widget_comment_weekday, date.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
            setTextViewText(R.id.widget_title, track?.title.orEmpty())
            setTextViewText(R.id.widget_subtitle, track?.artist?.takeIf(String::isNotBlank)?.let { "- $it" }.orEmpty())
            setTextViewText(R.id.widget_comment_quote, frame.comment?.content?.take(500) ?: text(R.string.widget_comment))
            val author = frame.comment?.user?.nickname?.takeIf(String::isNotBlank)?.let {
                if (it.length > 8) "${it.take(8)}..." else it
            }
            setTextViewText(R.id.widget_comment_author, author?.let { context.getString(R.string.widget_ncm_comment_author, it) }.orEmpty())
            bindOutlinedCover(R.id.widget_artwork, frame.covers[track?.artwork], R.mipmap.ic_launcher_round)
            setOnClickPendingIntent(R.id.widget_root, open)
            playButton(
                track, frame.playback?.playing == true,
                R.drawable.widget_ncm_comment_play, R.drawable.widget_ncm_comment_pause, open,
            )
        }

    private fun recent(frame: Frame): RemoteViews = views(R.layout.widget_ncm_recent).apply {
        val history = MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_HISTORY)
        setOnClickPendingIntent(R.id.widget_root, history)
        val leading = frame.tracks.firstOrNull()
        setTextViewText(R.id.widget_title, leading?.title ?: text(R.string.widget_recent))
        setTextViewText(R.id.widget_subtitle, leading?.artist ?: text(R.string.widget_no_history))
        bindOutlinedCover(R.id.widget_artwork, frame.covers[leading?.artwork])
        recentSlots.forEachIndexed { index, view ->
            val track = frame.tracks.getOrNull(index + 1)
            bindOutlinedCover(view, frame.covers[track?.artwork])
            setOnClickPendingIntent(view, track?.let { MusicWidgets.transport(context, MusicWidgets.ACTION_PLAY_RECENT, it.id) } ?: history)
        }
    }

    private fun configurable(id: Int, frame: Frame): RemoteViews {
        val configure = MusicWidgets.configure(context, id)
        val selection = frame.selection
        if (selection == null) {
            val layout = when (frame.family) {
                WidgetFamily.SHORTCUTS -> R.layout.widget_shortcuts
                WidgetFamily.SHORTCUTS_WIDE -> R.layout.widget_shortcuts_wide
                WidgetFamily.SHORTCUTS_LARGE -> R.layout.widget_shortcuts_large
                else -> R.layout.widget_universal
            }
            return views(layout).apply { setOnClickPendingIntent(R.id.widget_root, configure) }
        }
        val wide = frame.family != WidgetFamily.UNIVERSAL && frame.family != WidgetFamily.SHORTCUTS
        return views(if (wide) R.layout.widget_ncm_feature_wide else R.layout.widget_ncm_feature).apply {
            val track = frame.tracks.firstOrNull()
            val surface = when (selection) {
                UniversalWidgetSelection.DAILY -> R.drawable.widget_ncm_feature_daily
                UniversalWidgetSelection.FAVORITES -> R.drawable.widget_ncm_feature_favorites
                UniversalWidgetSelection.RADIO -> R.drawable.widget_ncm_feature_radio
                UniversalWidgetSelection.PLAYLIST_DISCOVERY, UniversalWidgetSelection.RECOMMENDATIONS ->
                    R.drawable.widget_ncm_feature_playlists
                UniversalWidgetSelection.RECENT -> R.drawable.widget_ncm_recent_background
                UniversalWidgetSelection.RECOGNITION -> R.drawable.widget_ncm_feature_background
            }
            setInt(R.id.widget_root, "setBackgroundResource", surface)
            val pending = when (selection) {
                UniversalWidgetSelection.RADIO -> MusicWidgets.transport(context, MusicWidgets.ACTION_START_FM)
                UniversalWidgetSelection.RECOMMENDATIONS, UniversalWidgetSelection.PLAYLIST_DISCOVERY ->
                    MusicWidgets.open(context, if (track == null) MusicWidgets.ACTION_OPEN_PLAYLISTS else MusicWidgets.ACTION_OPEN_CONTENT, track?.id)
                UniversalWidgetSelection.FAVORITES -> MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_FAVORITES)
                UniversalWidgetSelection.RECENT -> MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_HISTORY)
                UniversalWidgetSelection.DAILY -> MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_DAILY)
                UniversalWidgetSelection.RECOGNITION -> MusicWidgets.open(context, MainActivity.ACTION_OPEN_SONG_RECOGNITION)
            }
            setTextViewText(R.id.widget_title, text(selection.label))
            setTextViewText(R.id.widget_subtitle, track?.title.orEmpty())
            bindOutlinedCover(R.id.widget_artwork, frame.covers[track?.artwork])
            setOnClickPendingIntent(R.id.widget_root, pending)
            setOnClickPendingIntent(R.id.widget_settings, configure)
        }
    }

    private fun views(layout: Int) = RemoteViews(context.packageName, layout)
    private fun text(resource: Int) = context.getString(resource)

    private fun RemoteViews.setCover(id: Int, bitmap: Bitmap?, placeholder: Int = R.mipmap.ic_launcher) {
        if (bitmap == null) setImageViewResource(id, placeholder) else setImageViewBitmap(id, bitmap)
    }

    private fun RemoteViews.bindOutlinedCover(id: Int, bitmap: Bitmap?, placeholder: Int = R.mipmap.ic_launcher) {
        setBoolean(id, "setClipToOutline", true)
        setCover(id, bitmap, placeholder)
    }

    private fun RemoteViews.playButton(track: WidgetTrack?, playing: Boolean, play: Int, pause: Int, fallback: PendingIntent?) {
        transport(
            R.id.widget_play, if (playing) pause else play, if (playing) R.string.widget_pause else R.string.widget_play,
            MusicWidgets.ACTION_PLAY_PAUSE, enabled = track != null, fallback = fallback,
        )
    }

    private fun RemoteViews.likeButton(track: WidgetTrack?, liked: Boolean?, off: Int, on: Int) {
        transport(
            R.id.widget_like, if (liked == true) on else off,
            if (liked == true) R.string.widget_remove_favorite else R.string.widget_add_favorite,
            MusicWidgets.ACTION_LIKE, track != null && liked != null, track?.id,
        )
    }

    private fun RemoteViews.transport(
        id: Int, icon: Int, description: Int, action: String, enabled: Boolean,
        mediaId: String? = null, fallback: PendingIntent? = null,
    ) {
        setImageViewResource(id, icon)
        setContentDescription(id, text(description))
        setOnClickPendingIntent(id, if (enabled) MusicWidgets.transport(context, action, mediaId) else fallback)
    }

    private data class TransportIcons(val play: Int, val pause: Int, val previous: Int, val next: Int, val like: Int, val liked: Int)

    private companion object {
        val vinylIcons = TransportIcons(
            R.drawable.widget_ncm_vinyl_play, R.drawable.widget_ncm_vinyl_pause, R.drawable.widget_ncm_previous,
            R.drawable.widget_ncm_vinyl_next, R.drawable.widget_ncm_vinyl_like, R.drawable.widget_ncm_vinyl_liked,
        )
        val miniIcons = TransportIcons(
            R.drawable.widget_ncm_play, R.drawable.widget_ncm_pause, R.drawable.widget_ncm_previous,
            R.drawable.widget_ncm_next, R.drawable.widget_ncm_like, R.drawable.widget_ncm_liked,
        )
        val classicIcons = TransportIcons(
            R.drawable.widget_ncm_classic_play, R.drawable.widget_ncm_classic_pause, R.drawable.widget_ncm_classic_previous,
            R.drawable.widget_ncm_classic_next, R.drawable.widget_ncm_classic_like, R.drawable.widget_ncm_classic_liked,
        )
        val largeIcons = TransportIcons(
            R.drawable.widget_ncm_classic_large_play, R.drawable.widget_ncm_classic_large_pause, R.drawable.widget_ncm_classic_large_previous,
            R.drawable.widget_ncm_classic_large_next, R.drawable.widget_ncm_classic_large_like, R.drawable.widget_ncm_classic_large_liked,
        )
        val radioFamilies = setOf(WidgetFamily.RADIO, WidgetFamily.APPLE_RADIO)
        val tabletAppleFamilies = setOf(WidgetFamily.RECENT_LARGE, WidgetFamily.RECOMMENDATIONS_LARGE, WidgetFamily.CHARTS_LARGE)
        val commentFamilies = setOf(WidgetFamily.COMMENT, WidgetFamily.COMMENT_LARGE)
        val radioSlots = listOf(
            R.id.widget_action to "NIGHT_EMO", R.id.widget_radio_familiar to "EXERCISE",
            R.id.widget_radio_explore to "RELAX", R.id.widget_radio_scenes to "CHEERFUL",
        )
        val recentSlots = intArrayOf(R.id.widget_recent_one, R.id.widget_recent_two, R.id.widget_recent_three, R.id.widget_recent_four)
        val vinylSlots = intArrayOf(
            R.id.widget_vinyl_one, R.id.widget_vinyl_two, R.id.widget_vinyl_three, R.id.widget_vinyl_four, R.id.widget_vinyl_five,
        )
        val skinnableFamilies = setOf(WidgetFamily.PLAYER_BAR, WidgetFamily.PLAYER_WIDE)
        val skins = intArrayOf(
            R.drawable.widget_ncm_classic_skin_translucent_dark, R.drawable.widget_ncm_classic_skin_solid_dark,
            R.drawable.widget_ncm_classic_skin_translucent_light, R.drawable.widget_ncm_classic_skin_gradient_light,
            android.R.color.transparent,
        )
    }

    private data class ArtworkRequest(val url: String, val size: Int) {
        companion object {
            const val THUMBNAIL_PIXELS = 144
        }
    }

    private inner class WidgetArtwork {
        private val size = ArtworkRequest.THUMBNAIL_PIXELS
        private val lock = Any()
        private val cached = object : LruCache<ArtworkRequest, Bitmap>(6 * 1024 * 1024) {
            override fun sizeOf(key: ArtworkRequest, value: Bitmap) = value.byteCount
        }
        private val radioBackgrounds = LruCache<String, Bitmap>(3)
        private val miniBackgrounds = LruCache<String, Bitmap>(3)
        private val discCovers = LruCache<String, Bitmap>(4)
        private val jobs = mutableMapOf<ArtworkRequest, Job>()
        private val failed = mutableMapOf<ArtworkRequest, Long>()

        fun get(request: ArtworkRequest): Bitmap? = cached.get(request)

        fun radioBackground(url: String, source: Bitmap): Bitmap = radioBackgrounds.get(url) ?: run {
            val color = Palette.from(source).maximumColorCount(8).generate().getDominantColor(0xFF283248.toInt())
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
            }.also { radioBackgrounds.put(url, it) }
        }

        fun miniBackground(url: String, source: Bitmap, compact: Boolean): Bitmap {
            val key = "${if (compact) "square" else "wide"}:$url"
            return miniBackgrounds.get(key) ?: run {
                val width = if (compact) 32 else 67
                val height = 32
                val sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val top = (height - width) / 2f
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                Canvas(sample).drawBitmap(source, null, RectF(0f, top, width.toFloat(), top + width), paint)
                val radius = 10
                val blurred = AlbumTextureProcessor.stackBlur(AlbumTextureProcessor.stackBlur(sample, radius), radius)
                Canvas(blurred).drawColor(0x4C000000)
                blurred.also { miniBackgrounds.put(key, it) }
            }
        }

        fun discCover(url: String, source: Bitmap, fullSize: Boolean = false): Bitmap {
            val key = "${if (fullSize) "vinyl" else "mini"}:$url"
            return discCovers.get(key) ?: run {
                val radius = size * (if (fullSize) 1f else 0.5677f) / 2
                val inset = size / 2f - radius
                val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setLocalMatrix(Matrix().apply {
                    setScale(2 * radius / source.width, 2 * radius / source.height)
                    postTranslate(inset, inset)
                })
                val cover = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
                Canvas(cover).drawCircle(size / 2f, size / 2f, radius, paint)
                cover.also { discCovers.put(key, it) }
            }
        }

        fun retain(desired: Set<ArtworkRequest>) = synchronized(lock) {
            jobs.keys.filterNot(desired::contains).forEach { jobs.remove(it)?.cancel() }
            val now = SystemClock.elapsedRealtime()
            failed.entries.removeAll { it.key !in desired || now - it.value > 120_000L }
            for (request in desired) {
                if (cached.get(request) != null || request in jobs || request in failed) continue
                jobs[request] = scope.launch {
                    val bitmap = try {
                        withTimeoutOrNull(8_000L) { decode(request) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "Unable to load widget artwork")
                        null
                    }
                    synchronized(lock) {
                        if (jobs[request] != coroutineContext[Job]) return@launch
                        jobs.remove(request)
                        if (bitmap != null) cached.put(request, bitmap) else failed[request] = SystemClock.elapsedRealtime()
                    }
                    if (bitmap != null) artworkChanged()
                }
            }
        }

        private suspend fun decode(art: ArtworkRequest): Bitmap? {
            val size = art.size
            val request = ImageRequest.Builder(context).data(art.url).size(size).precision(Precision.EXACT).allowHardware(false).build()
            val source = SingletonImageLoader.get(context).execute(request).image?.toBitmap() ?: return null
            val scale = size.toFloat() / minOf(source.width, source.height)
            val width = source.width * scale
            val height = source.height * scale
            val left = (size - width) / 2
            val top = (size - height) / 2
            val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(square).drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            return square
        }
    }
}
