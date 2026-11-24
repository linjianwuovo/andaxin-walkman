package com.ljyh.mei.mcp

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal class McpServer(
    host: String,
    port: Int,
    private val token: String,
    private val protocol: McpProtocol,
    private val onFailure: () -> Unit,
) : Closeable {
    private val listener = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(host), port), MaxClients)
        } catch (error: IOException) {
            close()
            throw error
        }
    }
    val listeningPort: Int get() = listener.localPort
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableSetOf<Socket>()
    private val mutableConnectionCount = MutableStateFlow(0)
    val connectionCount = mutableConnectionCount.asStateFlow()
    private val streamSlots = Semaphore(4)
    private val streams = ConcurrentHashMap<String, Channel<JsonObject>>()

    fun start() {
        scope.launch {
            try {
                while (isActive) {
                    val client = listener.accept()
                    if (!admit(client)) {
                        client.close()
                        continue
                    }
                    launch { serve(client) }
                }
            } catch (_: IOException) {
                if (!listener.isClosed) {
                    close()
                    onFailure()
                }
            }
        }
    }

    private fun admit(client: Socket): Boolean = synchronized(clients) {
        if (listener.isClosed || clients.size >= MaxClients) return false
        clients.add(client)
        mutableConnectionCount.value = clients.size
        true
    }

    private suspend fun serve(socket: Socket) {
        val readTimeout = scope.launch {
            delay(10_000)
            socket.close()
        }
        var requestTimeout = scope.launch {
            delay(40_000)
            socket.close()
        }
        try {
            socket.soTimeout = 5_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            try {
                val request = HttpRequest.read(input)
                authorize(request, socket)
                val path = request.target.substringBefore('?')
                val legacy = path == "/messages"
                if (request.method == "GET" && request.target == "/sse") {
                    if (!request.accepts("text/event-stream")) throw HttpFailure(406, "Not Acceptable")
                    readTimeout.cancel()
                    requestTimeout.cancel()
                    requestTimeout = scope.launch {
                        delay(15 * 60_000)
                        socket.close()
                    }
                    legacyStream(output)
                    return
                }
                if (request.target != "/mcp" && !legacy) throw HttpFailure(404, "Not Found")
                if (request.method != "POST") {
                    output.respond(405, "Method Not Allowed", extraHeaders = "Allow: POST\r\n")
                    return
                }
                if (request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                    throw HttpFailure(415, "Unsupported Media Type")
                }
                val wantsJson = request.accepts("application/json")
                val wantsEvents = request.accepts("text/event-stream")
                if (!legacy && !wantsJson && !wantsEvents) throw HttpFailure(406, "Not Acceptable")
                val stream = if (legacy) {
                    val id = request.target.substringAfter("?sessionId=", "")
                    streams[id] ?: throw HttpFailure(404, "Not Found")
                } else null
                // Supporting 100-continue avoids a deadlock with HTTP clients sending larger requests.
                request.headers["expect"]?.let {
                    if (!it.equals("100-continue", ignoreCase = true)) throw HttpFailure(417, "Expectation Failed")
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                val body = request.body(input)
                readTimeout.cancel()
                val response = withTimeoutOrNull(25_000) { protocol.handle(body, legacy, request.headers) }
                    ?: throw HttpFailure(504, "Gateway Timeout")
                when {
                    stream != null -> {
                        if (response.message != null && stream.trySend(response.message).isFailure) {
                            throw HttpFailure(503, "Service Unavailable")
                        }
                        output.respond(202, "Accepted")
                    }
                    response.message == null -> output.respond(202, "Accepted")
                    response.status == 400 -> output.respond(400, "Bad Request", body = response.message.toString())
                    response.status == 404 -> output.respond(404, "Not Found", body = response.message.toString())
                    wantsJson -> output.respond(200, "OK", body = response.message.toString())
                    else -> {
                        output.beginEvents()
                        output.event("message", response.message.toString())
                    }
                }
            } catch (error: HttpFailure) {
                val authHeader = if (error.status == 401) "WWW-Authenticate: Bearer\r\n" else ""
                output.respond(error.status, error.reason, extraHeaders = authHeader)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // A disconnected or slow client should never terminate the listener.
        } finally {
            readTimeout.cancel()
            requestTimeout.cancel()
            runCatching { socket.close() }
            synchronized(clients) {
                clients.remove(socket)
                mutableConnectionCount.value = clients.size
            }
        }
    }

    private fun authorize(request: HttpRequest, socket: Socket) {
        // Native MCP clients do not send an Origin. Disallow browser and DNS-rebinding requests.
        val host = request.headers["host"] ?: throw HttpFailure(400, "Bad Request")
        if (request.headers.containsKey("origin") || !validHost(host, socket.localAddress, listeningPort)) {
            throw HttpFailure(403, "Forbidden")
        }
        val authorization = request.headers["authorization"].orEmpty()
        if (!authorization.startsWith("Bearer ", ignoreCase = true) || !MessageDigest.isEqual(
                token.toByteArray(Charsets.US_ASCII), authorization.substring(7).toByteArray(Charsets.ISO_8859_1),
            )
        ) throw HttpFailure(401, "Unauthorized")
    }

    private suspend fun legacyStream(output: OutputStream) {
        if (!streamSlots.tryAcquire()) throw HttpFailure(503, "Service Unavailable")
        val id = UUID.randomUUID().toString()
        val stream = Channel<JsonObject>(8)
        streams[id] = stream
        try {
            output.beginEvents()
            output.event("endpoint", "/messages?sessionId=$id")
            while (true) {
                val next = withTimeoutOrNull(10_000) { stream.receive() }
                if (next == null) {
                    output.write(": keep-alive\n\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                } else output.event("message", next.toString())
            }
        } finally {
            streams.remove(id, stream)
            stream.close()
            streamSlots.release()
        }
    }

    override fun close() {
        runCatching { listener.close() }
        scope.cancel()
        synchronized(clients) { clients.forEach { runCatching { it.close() } } }
    }

    private companion object {
        const val MaxClients = 12
    }
}

internal fun validHost(header: String, localAddress: InetAddress, port: Int): Boolean {
    val hostname = header.substringBefore(':')
    // ADB forwarding and reverse tunnels may intentionally use a different external port.
    val suffix = header.substringAfter(':', "")
    if (':' in header && (suffix.toIntOrNull() ?: 0) !in 1..65535) return false
    if (localAddress.isLoopbackAddress && hostname.lowercase() in setOf("127.0.0.1", "localhost")) return true
    return hostname == localAddress.hostAddress && (':' !in header || suffix.toIntOrNull() == port)
}

private class HttpFailure(val status: Int, val reason: String) : IOException(reason)

private data class HttpRequest(val method: String, val target: String, val headers: Map<String, String>) {
    fun accepts(type: String): Boolean = headers["accept"]?.split(',')?.any { value ->
        val parts = value.split(';').map { it.trim() }
        (parts.firstOrNull() in listOf(type, "*/*", type.substringBefore('/') + "/*")) &&
            parts.none { it.startsWith("q=") && (it.substring(2).toDoubleOrNull() ?: 0.0) <= 0.0 }
    } ?: true

    fun body(input: BufferedInputStream): String {
        val length = headers["content-length"]
        val transfer = headers["transfer-encoding"]
        if (length != null && transfer != null) throw HttpFailure(400, "Bad Request")
        val bytes = if (transfer == null) {
            val count = length?.toIntOrNull()?.takeIf { it >= 0 } ?: throw HttpFailure(411, "Length Required")
            if (count > MaxBody) throw HttpFailure(413, "Content Too Large")
            input.readExact(count)
        } else {
            if (!transfer.equals("chunked", ignoreCase = true)) throw HttpFailure(400, "Bad Request")
            val payload = ByteArrayOutputStream()
            while (true) {
                val count = input.line(256).substringBefore(';').toIntOrNull(16)?.takeIf { it >= 0 }
                    ?: throw HttpFailure(400, "Bad Request")
                if (count > MaxBody - payload.size()) throw HttpFailure(413, "Content Too Large")
                if (count == 0) break
                payload.write(input.readExact(count))
                if (input.line(2).isNotEmpty()) throw HttpFailure(400, "Bad Request")
            }
            // Consume trailer lines without treating any of them as request headers.
            var trailerBytes = 0
            while (true) {
                val line = input.line(MaxLine)
                trailerBytes += line.length + 2
                if (trailerBytes > MaxHeaders) throw HttpFailure(431, "Request Header Fields Too Large")
                if (line.isEmpty()) break
            }
            payload.toByteArray()
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw HttpFailure(400, "Bad Request")
        }
    }

    companion object {
        private const val MaxBody = 64 * 1024
        private const val MaxHeaders = 16 * 1024
        private const val MaxLine = 8 * 1024
        private val headerName = Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")

        fun read(input: BufferedInputStream): HttpRequest {
            val parts = input.line(MaxLine).split(' ')
            if (parts.size != 3 || parts[2] != "HTTP/1.1" || !parts[1].startsWith('/')) {
                throw HttpFailure(400, "Bad Request")
            }
            val headers = mutableMapOf<String, String>()
            var size = 0
            while (true) {
                val line = input.line(MaxLine)
                if (line.isEmpty()) break
                size += line.length + 2
                if (size > MaxHeaders) throw HttpFailure(431, "Request Header Fields Too Large")
                val name = line.substringBefore(':').lowercase()
                val value = line.substringAfter(':', "").trim()
                if (':' !in line || !headerName.matches(name) || value.any { it.code < 32 && it != '\t' } || name in headers) {
                    throw HttpFailure(400, "Bad Request")
                }
                headers[name] = value
            }
            return HttpRequest(parts[0], parts[1], headers)
        }
    }
}

private fun BufferedInputStream.line(limit: Int): String {
    val line = StringBuilder()
    while (line.length <= limit) {
        when (val next = read()) {
            -1 -> throw EOFException()
            '\r'.code -> {
                if (read() != '\n'.code) throw HttpFailure(400, "Bad Request")
                return line.toString()
            }
            '\n'.code -> throw HttpFailure(400, "Bad Request")
            else -> line.append(next.toChar())
        }
    }
    throw HttpFailure(431, "Request Header Fields Too Large")
}

private fun BufferedInputStream.readExact(count: Int): ByteArray {
    val data = ByteArray(count)
    var readBytes = 0
    while (readBytes < count) {
        val read = read(data, readBytes, count - readBytes)
        if (read < 0) throw EOFException()
        readBytes += read
    }
    return data
}

private fun OutputStream.respond(status: Int, reason: String, body: String = "", extraHeaders: String = "") {
    val payload = body.toByteArray(Charsets.UTF_8)
    val type = if (payload.isNotEmpty()) "Content-Type: application/json\r\n" else ""
    write(("HTTP/1.1 $status $reason\r\nConnection: close\r\nCache-Control: no-store\r\n" +
        "Content-Length: ${payload.size}\r\n$type$extraHeaders\r\n").toByteArray(Charsets.US_ASCII))
    write(payload)
    flush()
}

private fun OutputStream.beginEvents() {
    write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
        "Cache-Control: no-cache, no-store\r\nX-Accel-Buffering: no\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
    flush()
}

private fun OutputStream.event(event: String, data: String) {
    write("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
    flush()
}
