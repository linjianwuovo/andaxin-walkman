package com.ljyh.mei.widget

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.ljyh.mei.R
import com.ljyh.mei.playback.PrivateFmMode
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.PlaylistRef
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.component.player.component.mesh.AlbumTextureProcessor

internal class AppleMusicWidgetViews(private val context: Context) {
    private val backdrops = LruCache<String, Bitmap>(12)
    private val heroCovers = object : LruCache<Int, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    fun render(
        id: Int,
        family: WidgetFamily,
        size: AppleWidgetSize,
        playback: WidgetPlayback?,
        tracks: List<WidgetTrack>,
        covers: Map<String, Bitmap?>,
        bounds: SizeF? = null,
    ): RemoteViews {
        return when (family) {
            WidgetFamily.NOW_PLAYING -> recent(AppleWidgetSize.SMALL, playback, tracks, covers)
            WidgetFamily.RECENT -> recent(size, playback, tracks, covers, bounds)
            WidgetFamily.PINS -> pins(id, size >= AppleWidgetSize.LARGE, tracks, covers)
            WidgetFamily.RECOMMENDATIONS -> recommendations(size, tracks, covers)
            WidgetFamily.CHARTS -> charts(size, tracks, covers)
            WidgetFamily.APPLE_RADIO -> radio(playback, covers)
            WidgetFamily.OPEN_MUSIC -> views(R.layout.widget_apple_open).apply {
                click(R.id.widget_apple_root, open(MusicWidgets.ACTION_OPEN_SEARCH))
            }
            else -> error("Unsupported Apple-style widget family $family")
        }
    }

    private fun recent(
        size: AppleWidgetSize,
        playback: WidgetPlayback?,
        tracks: List<WidgetTrack>,
        covers: Map<String, Bitmap?>,
        bounds: SizeF? = null,
    ): RemoteViews {
        val layout = when (size) {
            AppleWidgetSize.SMALL -> R.layout.widget_apple_now_playing
            AppleWidgetSize.MEDIUM -> R.layout.widget_apple_recent
            AppleWidgetSize.LARGE -> R.layout.widget_apple_recent_large
            AppleWidgetSize.TABLET_WIDE -> R.layout.widget_apple_recent_tablet
            AppleWidgetSize.TABLET_PORTRAIT -> R.layout.widget_apple_recent_tablet_portrait
        }
        val current = playback?.track
        val hero = current ?: tracks.firstOrNull()
        val open = open(if (current != null) MusicWidgets.ACTION_OPEN_PLAYER else MusicWidgets.ACTION_OPEN_HISTORY)
        return views(layout).apply {
            click(R.id.widget_apple_root, open)
            if (size != AppleWidgetSize.SMALL) click(R.id.widget_apple_hero, open)
            ambience(hero, covers)
            val play = when {
                current != null -> transport(MusicWidgets.ACTION_PLAY_PAUSE)
                hero != null -> transport(MusicWidgets.ACTION_PLAY_RECENT, hero.id)
                else -> null
            }
            val cornerRadius = when (size) {
                AppleWidgetSize.SMALL -> 5f
                AppleWidgetSize.TABLET_WIDE -> 10f
                else -> 8f
            }
            val large = size == AppleWidgetSize.LARGE
            if (large) {
                val art = R.id.widget_apple_hero_art
                if (bounds != null) {
                    val contentWidth = bounds.width - 32f
                    val contentHeight = bounds.height - 32f
                    val heroHeight = contentHeight * .36f - 12f
                    val side = minOf(heroHeight, contentWidth - 16f - 88f).coerceAtLeast(0f)
                    setViewLayoutWidth(art, side, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(art, side, TypedValue.COMPLEX_UNIT_DIP)
                    val captionHeight = 13f * context.resources.configuration.fontScale
                    val rowSide = ((contentHeight * .64f - 11f - captionHeight - 8f - 24f) / 3f)
                        .coerceIn(0f, (contentWidth - 100f).coerceAtLeast(0f))
                    repeat(3) { index ->
                        val cover = rows[index].cover
                        setViewLayoutWidth(cover, rowSide, TypedValue.COMPLEX_UNIT_DIP)
                        setViewLayoutHeight(cover, rowSide, TypedValue.COMPLEX_UNIT_DIP)
                    }
                }
                setTextViewText(
                    R.id.widget_apple_hero_section,
                    text(if (current != null) R.string.widget_apple_now_playing else R.string.widget_recent),
                )
                setViewVisibility(R.id.widget_apple_hero_section, if (hero == null) View.GONE else View.VISIBLE)
            }
            hero(hero, covers, playback?.playing == true, R.string.widget_apple_no_recent, play, cornerRadius, !large)
            if (size >= AppleWidgetSize.LARGE) {
                val slots = when (size) {
                    AppleWidgetSize.LARGE -> 3
                    AppleWidgetSize.TABLET_WIDE -> 4
                    else -> 6
                }
                val heroHistoryId = hero?.historyId
                val history = tracks.filter { it.id != heroHistoryId }.take(slots)
                setViewVisibility(R.id.widget_apple_history, if (history.isEmpty()) View.INVISIBLE else View.VISIBLE)
                bindRows(history, covers, slots, layoutHasDetail = true, preserveEmptySlots = large,
                    play = { transport(MusicWidgets.ACTION_PLAY_RECENT, it.id) },
                    click = ::openRecentItem,
                )
            }
        }
    }

    private fun pins(id: Int, tall: Boolean, tracks: List<WidgetTrack>, covers: Map<String, Bitmap?>): RemoteViews =
        views(if (tall) R.layout.widget_apple_pins_large else R.layout.widget_apple_pins).apply {
            click(
                R.id.widget_apple_root,
                if (tracks.isEmpty()) MusicWidgets.configure(context, id, WidgetCollectionConfigureActivity::class.java)
                else open(MusicWidgets.ACTION_OPEN_LIBRARY),
            )
            ambience(tracks.firstOrNull(), covers)
            bindRows(tracks, covers, if (tall) 6 else 4, layoutHasDetail = false, preserveEmptySlots = true) {
                transport(MusicWidgets.ACTION_PLAY_COLLECTION, it.id)
            }
            setViewVisibility(R.id.widget_apple_pin_tiles, if (tracks.isEmpty()) View.GONE else View.VISIBLE)
            if (tall) setViewVisibility(R.id.widget_apple_pin_extra, if (tracks.isEmpty()) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_apple_empty, if (tracks.isEmpty()) View.VISIBLE else View.GONE)
        }

    private fun recommendations(
        size: AppleWidgetSize,
        tracks: List<WidgetTrack>,
        covers: Map<String, Bitmap?>,
    ): RemoteViews {
        val (layout, slots) = when (size) {
            AppleWidgetSize.SMALL, AppleWidgetSize.MEDIUM -> R.layout.widget_apple_recommendations to 2
            AppleWidgetSize.LARGE -> R.layout.widget_apple_recommendations_large to 4
            AppleWidgetSize.TABLET_WIDE -> R.layout.widget_apple_recommendations_wide to 8
            AppleWidgetSize.TABLET_PORTRAIT -> R.layout.widget_apple_recommendations_portrait to 6
        }
        return views(layout).apply {
            click(R.id.widget_apple_root, open(MusicWidgets.ACTION_OPEN_DAILY))
            ambience(tracks.firstOrNull(), covers)
            bindRows(tracks, covers, slots, layoutHasDetail = true, play = ::playContent, click = ::openContent)
            setViewVisibility(R.id.widget_apple_empty, if (tracks.isEmpty()) View.VISIBLE else View.GONE)
        }
    }

    private fun charts(
        size: AppleWidgetSize,
        tracks: List<WidgetTrack>,
        covers: Map<String, Bitmap?>,
    ): RemoteViews {
        val chart = open(MusicWidgets.ACTION_OPEN_CHARTS)
        if (size == AppleWidgetSize.SMALL) return views(R.layout.widget_apple_charts).apply {
            val leading = tracks.firstOrNull()
            click(R.id.widget_apple_root, leading?.let(::openContent) ?: chart)
            ambience(leading, covers)
            val play = leading?.let(::playContent)
            val cornerRadius = if (context.resources.configuration.fontScale >= 1.35f) 3f else 5f
            hero(leading, covers, false, R.string.widget_apple_top_charts, play, cornerRadius)
        }
        val (layout, slots) = when (size) {
            AppleWidgetSize.SMALL, AppleWidgetSize.MEDIUM -> R.layout.widget_apple_charts_medium to 2
            AppleWidgetSize.LARGE -> R.layout.widget_apple_charts_large to 5
            AppleWidgetSize.TABLET_WIDE -> R.layout.widget_apple_charts_wide to 10
            AppleWidgetSize.TABLET_PORTRAIT -> R.layout.widget_apple_charts_portrait to 9
        }
        return views(layout).apply {
            click(R.id.widget_apple_root, chart)
            ambience(tracks.firstOrNull(), covers)
            bindRows(tracks, covers, slots, layoutHasDetail = true, play = ::playContent, click = ::openContent)
            repeat(minOf(tracks.size, slots)) { index ->
                setTextViewText(numberViews[index], (index + 1).toString())
            }
        }
    }

    private fun radio(playback: WidgetPlayback?, covers: Map<String, Bitmap?>): RemoteViews =
        views(R.layout.widget_apple_radio).apply {
            val current = playback?.track
            ambience(current, covers)
            val start = transport(MusicWidgets.ACTION_START_FM)
            click(R.id.widget_apple_root, start)
            radioStations.forEach { station ->
                val active = playback?.playing == true && current != null && playback.radioMode == station.mode
                val action = if (active) {
                    transport(MusicWidgets.ACTION_PLAY_PAUSE)
                } else {
                    MusicWidgets.transport(context, MusicWidgets.ACTION_START_FM, radioMode = station.mode.storageKey)
                }
                setImageViewResource(station.icon, if (active) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
                setContentDescription(station.root, text(station.label) + ", " + text(if (active) R.string.widget_pause else R.string.widget_play))
                click(station.root, action)
            }
        }

    private fun RemoteViews.hero(
        track: WidgetTrack?,
        covers: Map<String, Bitmap?>,
        playing: Boolean,
        fallbackLabel: Int,
        play: PendingIntent?,
        cornerRadius: Float,
        intrinsicArtwork: Boolean = true,
    ) {
        val art = R.id.widget_apple_hero_art
        val bitmap = covers[track?.artwork]?.let { if (intrinsicArtwork) heroArtwork(it) else it }
        if (bitmap == null) setImageViewResource(art, R.drawable.widget_apple_placeholder)
        else setImageViewBitmap(art, bitmap)
        setViewOutlinePreferredRadius(art, cornerRadius, TypedValue.COMPLEX_UNIT_DIP)
        setViewVisibility(art, if (track == null) View.INVISIBLE else View.VISIBLE)
        setTextViewText(R.id.widget_apple_hero_title, track?.title ?: text(fallbackLabel))
        val subtitle = track?.artist.orEmpty()
        setTextViewText(R.id.widget_apple_hero_subtitle, subtitle)
        setViewVisibility(R.id.widget_apple_hero_subtitle, if (subtitle.isBlank()) View.GONE else View.VISIBLE)
        val playLabel = text(if (playing) R.string.widget_pause else R.string.widget_play)
        setImageViewResource(R.id.widget_apple_hero_play_icon, if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
        setTextViewText(R.id.widget_apple_hero_play_label, playLabel)
        setViewVisibility(R.id.widget_apple_hero_play, if (play == null) View.GONE else View.VISIBLE)
        setContentDescription(R.id.widget_apple_hero_play, playLabel)
        click(R.id.widget_apple_hero_play, play)
    }

    private fun RemoteViews.bindRows(
        tracks: List<WidgetTrack>,
        covers: Map<String, Bitmap?>,
        slots: Int,
        layoutHasDetail: Boolean,
        preserveEmptySlots: Boolean = false,
        play: ((WidgetTrack) -> PendingIntent?)? = null,
        click: (WidgetTrack) -> PendingIntent?,
    ) {
        repeat(slots) { index ->
            val row = rows[index]
            val track = tracks.getOrNull(index)
            setViewVisibility(row.root, when {
                track != null -> View.VISIBLE
                preserveEmptySlots -> View.INVISIBLE
                else -> View.GONE
            })
            if (track != null) {
                cover(row.cover, track, covers)
                setTextViewText(row.title, track.title)
                if (layoutHasDetail) {
                    val detail = detailViews[index]
                    setTextViewText(detail, track.artist)
                    setViewVisibility(detail, if (track.artist.isBlank()) View.GONE else View.VISIBLE)
                }
                click(row.root, click(track))
                if (play != null) {
                    val playIntent = play(track)
                    setViewVisibility(row.play, if (playIntent == null) View.GONE else View.VISIBLE)
                    click(row.play, playIntent)
                }
            }
        }
    }

    private fun RemoteViews.cover(view: Int, track: WidgetTrack?, covers: Map<String, Bitmap?>) {
        val bitmap = covers[track?.artwork]
        if (bitmap == null) setImageViewResource(view, R.drawable.widget_apple_placeholder)
        else setImageViewBitmap(view, bitmap)
    }

    private fun heroArtwork(bitmap: Bitmap): Bitmap {
        val key = bitmap.generationId
        heroCovers.get(key)?.let { return it }
        // Let the host measure a square within the allotted space, including tablet sizes.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
        copy.density = (copy.width * DisplayMetrics.DENSITY_DEFAULT / 1024).coerceAtLeast(1)
        heroCovers.put(key, copy)
        return copy
    }

    private fun RemoteViews.ambience(track: WidgetTrack?, covers: Map<String, Bitmap?>) {
        val url = track?.artwork.orEmpty()
        val bitmap = covers[url]?.let { source ->
            backdrops.get(url) ?: createBackdrop(source).also { backdrops.put(url, it) }
        }
        setViewVisibility(R.id.widget_apple_ambience, if (bitmap == null) View.GONE else View.VISIBLE)
        if (bitmap != null) setImageViewBitmap(R.id.widget_apple_ambience, bitmap)
    }

    private fun createBackdrop(source: Bitmap): Bitmap {
        val size = 32
        val small = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        Canvas(small).drawBitmap(source, null, Rect(0, 0, size, size), paint)
        val blurred = AlbumTextureProcessor.stackBlur(AlbumTextureProcessor.stackBlur(small, 8), 8)
        paint.shader = LinearGradient(
            0f, 0f, 0f, size.toFloat(), 0x73000000, 0xB3000000.toInt(), Shader.TileMode.CLAMP,
        )
        Canvas(blurred).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return blurred
    }

    private fun RemoteViews.click(id: Int, pending: PendingIntent?) = setOnClickPendingIntent(id, pending)
    private fun text(id: Int) = context.getString(id)
    private fun views(layout: Int) = RemoteViews(context.packageName, layout)
    private fun open(action: String) = MusicWidgets.open(context, action)
    private fun openContent(track: WidgetTrack) = MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_CONTENT, track.id)
    private fun openRecentItem(track: WidgetTrack): PendingIntent =
        TrackRefCodec.decode(track.id)?.let { ref ->
            MusicWidgets.open(context, MusicWidgets.ACTION_OPEN_CONTENT, encodeWidgetContent(ref))
        } ?: open(MusicWidgets.ACTION_OPEN_HISTORY)

    private fun playContent(track: WidgetTrack): PendingIntent? = when (decodeWidgetContent(track.id)) {
        is TrackRef, is AlbumRef, is PlaylistRef -> transport(MusicWidgets.ACTION_PLAY_COLLECTION, track.id)
        else -> null
    }

    private fun transport(action: String, id: String? = null) = MusicWidgets.transport(context, action, id)

    private data class Row(val root: Int, val cover: Int, val title: Int, val play: Int)
    private data class RadioStation(val root: Int, val icon: Int, val label: Int, val mode: PrivateFmMode)
    private companion object {
        val radioStations = listOf(
            RadioStation(R.id.widget_apple_station_1, R.id.widget_apple_station_icon_1,
                R.string.private_fm_default_mode, PrivateFmMode.DEFAULT),
            RadioStation(R.id.widget_apple_station_2, R.id.widget_apple_station_icon_2,
                R.string.private_fm_explore_mode, PrivateFmMode.EXPLORE),
            RadioStation(R.id.widget_apple_station_3, R.id.widget_apple_station_icon_3,
                R.string.private_fm_familiar_mode, PrivateFmMode.FAMILIAR),
        )
        val rows = listOf(
            Row(R.id.widget_apple_cell_1, R.id.widget_apple_cover_1, R.id.widget_apple_title_1, R.id.widget_apple_row_play_1),
            Row(R.id.widget_apple_cell_2, R.id.widget_apple_cover_2, R.id.widget_apple_title_2, R.id.widget_apple_row_play_2),
            Row(R.id.widget_apple_cell_3, R.id.widget_apple_cover_3, R.id.widget_apple_title_3, R.id.widget_apple_row_play_3),
            Row(R.id.widget_apple_cell_4, R.id.widget_apple_cover_4, R.id.widget_apple_title_4, R.id.widget_apple_row_play_4),
            Row(R.id.widget_apple_cell_5, R.id.widget_apple_cover_5, R.id.widget_apple_title_5, R.id.widget_apple_row_play_5),
            Row(R.id.widget_apple_cell_6, R.id.widget_apple_cover_6, R.id.widget_apple_title_6, R.id.widget_apple_row_play_6),
            Row(R.id.widget_apple_cell_7, R.id.widget_apple_cover_7, R.id.widget_apple_title_7, R.id.widget_apple_row_play_7),
            Row(R.id.widget_apple_cell_8, R.id.widget_apple_cover_8, R.id.widget_apple_title_8, R.id.widget_apple_row_play_8),
            Row(R.id.widget_apple_cell_9, R.id.widget_apple_cover_9, R.id.widget_apple_title_9, R.id.widget_apple_row_play_9),
            Row(R.id.widget_apple_cell_10, R.id.widget_apple_cover_10, R.id.widget_apple_title_10, R.id.widget_apple_row_play_10),
        )
        val detailViews = intArrayOf(
            R.id.widget_apple_detail_1, R.id.widget_apple_detail_2, R.id.widget_apple_detail_3,
            R.id.widget_apple_detail_4, R.id.widget_apple_detail_5, R.id.widget_apple_detail_6,
            R.id.widget_apple_detail_7, R.id.widget_apple_detail_8, R.id.widget_apple_detail_9,
            R.id.widget_apple_detail_10,
        )
        val numberViews = intArrayOf(
            R.id.widget_apple_number_1, R.id.widget_apple_number_2, R.id.widget_apple_number_3,
            R.id.widget_apple_number_4, R.id.widget_apple_number_5, R.id.widget_apple_number_6,
            R.id.widget_apple_number_7, R.id.widget_apple_number_8, R.id.widget_apple_number_9,
            R.id.widget_apple_number_10,
        )
    }
}
