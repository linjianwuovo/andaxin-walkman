package com.ljyh.mei.widget

import android.content.Context
import androidx.annotation.StringRes
import com.ljyh.mei.R

internal enum class UniversalWidgetSelection(@StringRes val label: Int) {
    RECOMMENDATIONS(R.string.widget_discover),
    RECENT(R.string.widget_recent),
    FAVORITES(R.string.widget_favorites),
    RADIO(R.string.widget_radio),
    DAILY(R.string.widget_daily),
    PLAYLIST_DISCOVERY(R.string.widget_universal_playlists),
    RECOGNITION(R.string.song_recognition);

    companion object {
        fun read(context: Context, appWidgetId: Int): UniversalWidgetSelection? {
            val value = preferences(context).getString(appWidgetId.toString(), null)
            return entries.firstOrNull { it.name == value }
        }

        fun save(context: Context, appWidgetId: Int, selection: UniversalWidgetSelection): Boolean =
            preferences(context).edit().putString(appWidgetId.toString(), selection.name).commit()

        fun delete(context: Context, appWidgetIds: IntArray) {
            preferences(context).edit().apply {
                appWidgetIds.forEach { remove(it.toString()) }
            }.apply()
        }

        fun restore(context: Context, previousIds: IntArray, restoredIds: IntArray) {
            val preferences = preferences(context)
            val values = previousIds.map { preferences.getString(it.toString(), null) }
            preferences.edit().apply {
                previousIds.forEach { remove(it.toString()) }
                restoredIds.zip(values).forEach { (id, value) ->
                    if (value != null) putString(id.toString(), value)
                }
            }.apply()
        }

        private fun preferences(context: Context) =
            context.getSharedPreferences("widget_universal", Context.MODE_PRIVATE)
    }
}
