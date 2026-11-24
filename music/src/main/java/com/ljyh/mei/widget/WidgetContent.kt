package com.ljyh.mei.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.PrivateFmMode
import com.ljyh.mei.source.shared.OnlineComment
import com.ljyh.mei.source.api.TrackRefCodec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class WidgetTrack(val id: String, val title: String, val artist: String, val artwork: String) {
    val historyId: String get() = TrackRefCodec.decode(id)?.let(TrackRefCodec::storageKey) ?: id
}

internal data class WidgetPlayback(
    val track: WidgetTrack?,
    val playing: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val radioMode: PrivateFmMode? = null,
) {
    fun persist(prefs: SharedPreferences) {
        if (track == null) {
            if (prefs.contains("track_id")) prefs.edit().remove("track_id").remove("title")
                .remove("artist").remove("artwork").remove("next").remove("previous")
                .remove("repeat").remove("shuffle").remove("radio").apply()
        } else if (read(prefs).copy(playing = playing) != this) {
            prefs.edit().putString("track_id", track.id).putString("title", track.title)
                .putString("artist", track.artist).putString("artwork", track.artwork)
                .putBoolean("previous", previous).putBoolean("next", next)
                .putInt("repeat", repeatMode).putBoolean("shuffle", shuffle)
                .putString("radio", radioMode?.name).apply()
        }
    }

    companion object {
        fun read(prefs: SharedPreferences): WidgetPlayback {
            val id = prefs.getString("track_id", null) ?: return WidgetPlayback(null)
            return WidgetPlayback(
                track = WidgetTrack(id, prefs.getString("title", "").orEmpty(), prefs.getString("artist", "").orEmpty(), prefs.getString("artwork", "").orEmpty()),
                previous = prefs.getBoolean("previous", false),
                next = prefs.getBoolean("next", false),
                repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF),
                shuffle = prefs.getBoolean("shuffle", false),
                radioMode = PrivateFmMode.entries.firstOrNull { it.name == prefs.getString("radio", null) },
            )
        }
    }
}

internal data class WidgetLyrics(val previous: String?, val current: String?, val next: String?)

internal data class WidgetProgress(val positionMs: Long = 0, val durationMs: Long = 0)

internal data class WidgetContent(
    val playback: WidgetPlayback,
    val progress: WidgetProgress,
    val lyrics: WidgetLyrics?,
    val liked: Boolean?,
    val recent: List<WidgetTrack>,
    val catalog: WidgetCatalog,
    val listening: WidgetListeningCalendar?,
    val comment: OnlineComment?,
)

internal enum class AppleWidgetSize { SMALL, MEDIUM, LARGE, TABLET_WIDE, TABLET_PORTRAIT }

internal enum class WidgetFamily(
    val provider: Class<out MusicWidgetProvider>,
    val readsPlayback: Boolean = false,
    val readsHistory: Boolean = false,
    val readsCatalog: Boolean = false,
    val readsProgress: Boolean = false,
    val readsLyrics: Boolean = false,
    val readsFavorite: Boolean = false,
    val apple: Boolean = false,
) {
    PLAYER(PlayerWidget::class.java, readsPlayback = true, readsFavorite = true),
    PLAYER_MINI_WIDE(PlayerMiniWideWidget::class.java, readsPlayback = true, readsFavorite = true),
    PLAYER_BAR(PlayerBarWidget::class.java, readsPlayback = true, readsProgress = true, readsFavorite = true),
    PLAYER_WIDE(PlayerWideWidget::class.java, readsPlayback = true, readsProgress = true, readsFavorite = true),
    PLAYER_LARGE(PlayerLargeWidget::class.java, readsPlayback = true, readsProgress = true, readsLyrics = true, readsFavorite = true),
    NOW_PLAYING(NowPlayingWidget::class.java, readsPlayback = true, readsHistory = true, apple = true),
    RECENT(RecentMusicWidget::class.java, readsPlayback = true, readsHistory = true, apple = true),
    RECENT_LARGE(RecentMusicLargeWidget::class.java, readsPlayback = true, readsHistory = true, apple = true),
    NCM_RECENT(NcmRecentMusicWidget::class.java, readsHistory = true),
    LYRICS(LyricsWidget::class.java, readsPlayback = true, readsLyrics = true),
    LYRICS_LARGE(LyricsLargeWidget::class.java, readsPlayback = true, readsLyrics = true),
    COMMENT(CommentWidget::class.java, readsPlayback = true),
    COMMENT_LARGE(CommentLargeWidget::class.java, readsPlayback = true),
    CLOSE_FRIENDS(CloseFriendsWidget::class.java),
    CLOSE_FRIENDS_LARGE(CloseFriendsLargeWidget::class.java),
    RADIO(PrivateRadioWidget::class.java, readsPlayback = true, readsFavorite = true),
    VINYL(VinylMusicWidget::class.java, readsPlayback = true, readsCatalog = true, readsFavorite = true),
    APPLE_RADIO(AppleRadioWidget::class.java, readsPlayback = true, apple = true),
    SHORTCUTS(MusicShortcutsWidget::class.java, readsHistory = true, readsCatalog = true),
    SHORTCUTS_WIDE(MusicShortcutsWideWidget::class.java, readsHistory = true, readsCatalog = true),
    SHORTCUTS_LARGE(MusicShortcutsLargeWidget::class.java, readsHistory = true, readsCatalog = true),
    UNIVERSAL(UniversalMusicWidget::class.java, readsHistory = true, readsCatalog = true),
    UNIVERSAL_WIDE(UniversalMusicWideWidget::class.java, readsHistory = true, readsCatalog = true),
    LISTENING(ListeningWidget::class.java),
    OPEN_MUSIC(OpenMusicWidget::class.java, apple = true),
    PINS(PinnedMusicWidget::class.java, apple = true),
    PINS_LARGE(PinnedMusicLargeWidget::class.java, apple = true),
    RECOMMENDATIONS(RecommendationsWidget::class.java, readsCatalog = true, apple = true),
    RECOMMENDATIONS_LARGE(RecommendationsLargeWidget::class.java, readsCatalog = true, apple = true),
    CHARTS(ChartsWidget::class.java, readsCatalog = true, apple = true),
    CHARTS_MEDIUM(ChartsMediumWidget::class.java, readsCatalog = true, apple = true),
    CHARTS_LARGE(ChartsLargeWidget::class.java, readsCatalog = true, apple = true),
}

internal fun recentUpdates(context: Context) = AppDatabase.getDatabase(context).historyDao().observeRecentSongs(8)
    .map { songs ->
        songs.map { song ->
            val track = song.toMediaMetadata()
            WidgetTrack(song.id, track.title, track.artists.joinToString { it.name }, track.coverUrl)
        }
    }
    .distinctUntilChanged()
