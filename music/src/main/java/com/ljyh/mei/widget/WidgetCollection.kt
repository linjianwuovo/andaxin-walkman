package com.ljyh.mei.widget

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.ljyh.mei.source.api.ContentRef
import com.ljyh.mei.source.api.type
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class WidgetCollection(val id: String, val title: String, val artwork: String) {
    companion object {
        const val MAX_SELECTION = 6

        fun read(context: Context, appWidgetId: Int): List<WidgetCollection> {
            val stored = preferences(context).getString(appWidgetId.toString(), null) ?: return emptyList()
            val entries = try {
                JSONArray(stored)
            } catch (_: JSONException) {
                return emptyList()
            }
            return (0 until minOf(entries.length(), MAX_SELECTION)).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                WidgetCollection(id, entry.optString("title"), entry.optString("artwork"))
            }
        }

        fun save(context: Context, appWidgetId: Int, entries: List<WidgetCollection>): Boolean {
            val stored = JSONArray()
            entries.take(MAX_SELECTION).forEach { entry ->
                stored.put(JSONObject().put("id", entry.id).put("title", entry.title).put("artwork", entry.artwork))
            }
            return preferences(context).edit().putString(appWidgetId.toString(), stored.toString()).commit()
        }

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

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("widget_collections", Context.MODE_PRIVATE)
    }
}

internal fun encodeWidgetContent(ref: ContentRef): String =
    Uri.Builder().scheme("meilox").authority("widget-content")
        .appendPath(ref.providerId).appendPath(ref.type.wireName).appendPath(ref.contentId).build().toString()
