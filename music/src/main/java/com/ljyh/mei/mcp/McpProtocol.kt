package com.ljyh.mei.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.ljyh.mei.source.api.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Base64

internal const val McpVersion = "2026-07-28"
internal const val McpHandshakeVersion = "2025-11-25"
internal const val McpLegacyVersion = "2024-11-05"
private val handshakeVersions = listOf(McpHandshakeVersion, "2025-06-18")
private val streamableVersions = listOf(McpVersion) + handshakeVersions
private const val VersionKey = "io.modelcontextprotocol/protocolVersion"
private const val ClientCapabilitiesKey = "io.modelcontextprotocol/clientCapabilities"
private const val ServerInfoKey = "io.modelcontextprotocol/serverInfo"

internal enum class McpPermission { Read, Playback, Write }

internal class McpToolError(message: String) : Exception(message)

internal data class McpResponse(val message: JsonObject?, val status: Int = 200)

internal class McpProtocol(
    private val tools: List<McpTool>,
    private val permitted: (McpPermission) -> Boolean,
    private val execute: suspend (McpTool, JsonObject) -> JsonObject,
    private val appVersion: String,
) {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()

    suspend fun handle(body: String, legacySse: Boolean = false, headers: Map<String, String> = emptyMap()): McpResponse {
        val message = try {
            gson.fromJson(body, JsonElement::class.java)
        } catch (_: JsonParseException) {
            return error(JsonNull.INSTANCE, -32700, "Parse error", 400)
        }
        if (message == null) return error(JsonNull.INSTANCE, -32700, "Parse error", 400)
        if (!message.isJsonObject) return error(JsonNull.INSTANCE, -32600, "Invalid request", 400)
        val request = message.asJsonObject
        val id = request.get("id")
        val method = request.string("method")
        if (request.string("jsonrpc") != "2.0" || method == null || method.isBlank() ||
            (id != null && (!id.isJsonPrimitive ||
                (!id.asJsonPrimitive.isString && id.toString().toLongOrNull() == null)))
        ) return error(JsonNull.INSTANCE, -32600, "Invalid request", 400)
        val responseId = id ?: JsonNull.INSTANCE
        val params = request.get("params")
        if (params != null && !params.isJsonObject) return error(responseId, -32602, "Invalid params", 400)
        val arguments = params?.asJsonObject ?: JsonObject()
        val meta = arguments.get("_meta")?.takeIf { it.isJsonObject }?.asJsonObject
        val versionHeader = headers["mcp-protocol-version"]
        val bodyVersion = meta?.string(VersionKey)
        val modern = !legacySse && (method == "server/discover" || "mcp-method" in headers || bodyVersion != null ||
            versionHeader != null && versionHeader !in handshakeVersions)
        if (modern) {
            if (versionHeader == null || bodyVersion == null || versionHeader != bodyVersion || headers["mcp-method"] != method) {
                return error(responseId, -32020, "Header mismatch", 400)
            }
            val namedParameter = when (method) {
                "tools/call", "prompts/get" -> "name"
                "resources/read" -> "uri"
                else -> null
            }
            if (namedParameter != null) {
                val name = headers["mcp-name"]?.let(::decodeName)
                if (name == null || name != arguments.string(namedParameter)) return error(responseId, -32020, "Header mismatch", 400)
            }
            if (versionHeader != McpVersion) {
                return error(responseId, -32022, "Unsupported protocol version", 400, jsonObject {
                    add("supported", streamableVersions.map(::JsonPrimitive).jsonArray())
                    addProperty("requested", versionHeader)
                })
            }
            if (meta.get(ClientCapabilitiesKey)?.isJsonObject != true) {
                return error(responseId, -32602, "Client capabilities are required", 400)
            }
        }
        // Notifications never execute tools or receive a success payload.
        if (id == null) return McpResponse(null, 202)
        return when (method) {
            "server/discover" -> if (modern) result(id, jsonObject {
                add("supportedVersions", streamableVersions.map(::JsonPrimitive).jsonArray())
                add("capabilities", capabilities())
            }, modern) else error(id, -32601, "Method not found")
            "initialize" -> {
                if (modern) return error(id, -32601, "Method not found", 404)
                val requested = arguments.string("protocolVersion")
                    ?: return error(id, -32602, "protocolVersion is required")
                val version = if (legacySse) McpLegacyVersion else requested.takeIf { it in handshakeVersions } ?: McpHandshakeVersion
                result(id, jsonObject {
                    addProperty("protocolVersion", version)
                    add("capabilities", capabilities())
                    add("serverInfo", serverInfo())
                })
            }
            "ping" -> result(id, JsonObject(), modern)
            "tools/list" -> result(id, jsonObject {
                add("tools", tools.filter { permitted(it.permission) }.map { it.schema() }.jsonArray())
            }, modern)
            "tools/call" -> call(id, arguments, modern)
            else -> error(id, -32601, "Method not found", if (modern) 404 else 200)
        }
    }

    private suspend fun call(id: JsonElement, parameters: JsonObject, modern: Boolean): McpResponse {
        val invalidParamsStatus = if (modern) 400 else 200
        val name = parameters.string("name") ?: return error(id, -32602, "Tool name is required", invalidParamsStatus)
        val tool = tools.firstOrNull { it.name == name } ?: return error(id, -32602, "Unknown tool", invalidParamsStatus)
        if (!permitted(tool.permission)) return error(id, -32602, "Tool permission is disabled", invalidParamsStatus)
        val input = parameters.get("arguments") ?: JsonObject()
        if (!input.isJsonObject || !tool.accepts(input.asJsonObject)) return error(id, -32602, "Invalid tool arguments", invalidParamsStatus)
        val response = try {
            toolResult(execute(tool, input.asJsonObject).toString(), false)
        } catch (cancelled: CancellationException) {
            // A transport request still cancels normally. A revoked permission is a tool failure.
            currentCoroutineContext().ensureActive()
            toolResult("Operation was cancelled because the permission, playback or source state changed", true)
        } catch (error: McpToolError) {
            toolResult(error.message.orEmpty(), true)
        } catch (error: SourceException) {
            toolResult(error.code.name, true)
        } catch (_: Exception) {
            toolResult("Operation failed", true)
        }
        return result(id, response, modern)
    }

    private fun capabilities() = jsonObject { add("tools", JsonObject()) }

    private fun serverInfo() = jsonObject {
        addProperty("name", "MeiloX")
        addProperty("version", appVersion)
    }

    private fun toolResult(text: String, isError: Boolean) = jsonObject {
        add("content", listOf(jsonObject {
            addProperty("type", "text")
            addProperty("text", text)
        }).jsonArray())
        addProperty("isError", isError)
    }

    private fun result(id: JsonElement, payload: JsonObject, modern: Boolean = false): McpResponse {
        if (modern) {
            payload.addProperty("resultType", "complete")
            payload.add("_meta", jsonObject { add(ServerInfoKey, serverInfo()) })
        }
        return McpResponse(jsonObject {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", payload)
        })
    }

    private fun error(id: JsonElement, code: Int, message: String, status: Int = 200, data: JsonObject? = null) =
        McpResponse(jsonObject {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", jsonObject {
                addProperty("code", code)
                addProperty("message", message)
                if (data != null) add("data", data)
            })
        }, status)

    private fun decodeName(name: String): String? = if (name.startsWith("=?base64?") && name.endsWith("?=")) {
        runCatching { Base64.getDecoder().decode(name.removePrefix("=?base64?").removeSuffix("?=")).toString(Charsets.UTF_8) }.getOrNull()
    } else name.takeIf { it.all { character -> character.code in 32..126 || character == '\t' } }
}

internal inline fun jsonObject(block: JsonObject.() -> Unit) = JsonObject().apply(block)
internal fun Iterable<JsonElement>.jsonArray() = JsonArray().also { array -> forEach(array::add) }
internal fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.toString()?.toLongOrNull()
internal fun JsonObject.flag(key: String): Boolean = get(key).asBoolean
