package com.ljyh.mei.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.ljyh.mei.mcp.McpPermission.Playback
import com.ljyh.mei.mcp.McpPermission.Read
import com.ljyh.mei.mcp.McpPermission.Write

internal data class McpTool(
    val name: String,
    val description: String,
    val permission: McpPermission,
    val fields: Map<String, McpField>,
) {
    fun schema() = jsonObject {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", jsonObject {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", jsonObject { fields.forEach { (name, field) -> add(name, field.schema) } })
            add("required", fields.filterValues { it.required }.keys.map(::JsonPrimitive).jsonArray())
        })
        add("annotations", jsonObject { addProperty("readOnlyHint", permission == Read) })
    }

    fun accepts(input: JsonObject): Boolean = input.keySet().all { it in fields } && fields.all { (key, field) ->
        val value = input.get(key)
        if (value == null) !field.required else field.accepts(value)
    }
}

internal class McpField(
    val schema: JsonObject,
    val required: Boolean,
    val accepts: (JsonElement) -> Boolean,
)

private fun text(required: Boolean = false, max: Int = 256) = McpField(
    jsonObject { addProperty("type", "string"); addProperty("minLength", 1); addProperty("maxLength", max) }, required,
) { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() && it.asString.length <= max }

private fun toggle() = McpField(jsonObject { addProperty("type", "boolean") }, true) {
    it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
}

private fun integer(min: Long, max: Long, required: Boolean = false) = McpField(
    jsonObject { addProperty("type", "integer"); addProperty("minimum", min); addProperty("maximum", max) }, required,
) { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && (it.toString().toLongOrNull()?.let { number -> number in min..max } == true) }

private fun choice(vararg values: String) = McpField(
    jsonObject { addProperty("type", "string"); add("enum", values.map(::JsonPrimitive).jsonArray()) }, true,
) { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString in values }

private val trackField = text(max = 1024)
private val tracksField = McpField(jsonObject {
    addProperty("type", "array")
    addProperty("minItems", 1)
    addProperty("maxItems", 50)
    add("items", trackField.schema)
}, true) { it.isJsonArray && it.asJsonArray.size() in 1..50 && it.asJsonArray.all(trackField.accepts) }
private val range = arrayOf("offset" to integer(0, 1_000_000), "limit" to integer(1, 50))
private val providerPage = range + ("cursor" to text(max = 4096))
private val provider = "providerId" to text(max = 64)
private val playlist = "playlistId" to text(required = true)

private fun tool(name: String, description: String, permission: McpPermission, vararg fields: Pair<String, McpField>) =
    McpTool(name, description, permission, fields.toMap())

internal val MeiloXMcpTools = listOf(
    tool("get_playback_state", "Get current track, play state, position, repeat, shuffle and autoplay.", Read),
    tool("get_current_lyrics", "Get a page of lyric lines and timings for the current track, or provider lyrics when unavailable.", Read, *range),
    tool("get_queue", "Get an ordered page of queue tracks.", Read, *range),
    tool("search_catalog", "Search tracks in the preferred source or a specified providerId.", Read,
        "query" to text(required = true), provider, *providerPage),
    tool("list_user_playlists", "List playlists created by the current account in the selected source.", Read, provider, *providerPage),
    tool("get_playlist_tracks", "Get tracks for a source playlist by playlistId.", Read, provider, playlist, *providerPage),
    tool("get_listening_history", "Get a page of tracks played on this device, most recent first.", Read, *range),
    tool("play_pause", "Play, pause or toggle the current track.", Playback, "action" to choice("play", "pause", "toggle")),
    tool("skip_next", "Skip to the next track.", Playback),
    tool("skip_previous", "Skip back to the previous track.", Playback),
    tool("seek_to", "Seek to a position on the current track in milliseconds.", Playback, "positionMs" to integer(0, Long.MAX_VALUE, true)),
    tool("set_play_mode", "Choose repeat off, repeat all, repeat one or shuffle.", Playback,
        "mode" to choice("off", "repeat_all", "repeat_one", "shuffle")),
    tool("set_volume", "Set the device media stream volume as a percentage.", Playback, "percent" to integer(0, 100, true)),
    tool("set_sleep_timer", "Set a timer in minutes. Use 0 to cancel or -1 to stop at the end of the track.", Playback,
        "minutes" to integer(-1, 1440, true)),
    tool("play_tracks", "Replace the queue and start playback from track media IDs returned by MeiloX.", Playback,
        "trackIds" to tracksField),
    tool("enqueue_tracks", "Append tracks by the media IDs returned by MeiloX.", Playback, "trackIds" to tracksField),
    tool("start_personal_fm", "Start personal radio for the selected or preferred source.", Playback, provider),
    tool("set_queue_autoplay", "Enable or disable automatic recommendations after the queue ends.", Playback, "enabled" to toggle()),
    tool("like_track", "Set whether a track is liked, using a media ID returned by MeiloX.", Write,
        "trackId" to text(required = true, max = 1024), "liked" to toggle()),
    tool("create_playlist", "Create a playlist in the selected or preferred source.", Write, provider,
        "title" to text(required = true), "description" to text(max = 1024)),
    tool("modify_playlist_tracks", "Add or remove tracks from a playlist. Tracks must belong to the same source.", Write,
        provider, playlist, "action" to choice("add", "remove"), "trackIds" to tracksField),
)
