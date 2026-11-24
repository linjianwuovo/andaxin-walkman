package com.ljyh.mei.mcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.playback.MusicService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
internal class McpPlayback(private val context: Context) {
    // The connection stays alive while the listener runs, but playback is bound on demand.
    // The Binder and Media3 player are only used on the main thread.
    private var connected: CompletableDeferred<MusicService>? = null
    private var connection: ServiceConnection? = null
    private var closed = false

    suspend fun <T> use(action: (MusicService) -> T): T = withContext(Dispatchers.Main.immediate) {
        if (closed) throw McpToolError("Service stopped")
        val service = (connected ?: bind()).await()
        currentCoroutineContext().ensureActive()
        if (closed) throw McpToolError("Service stopped")
        action(service)
    }

    private fun bind(): CompletableDeferred<MusicService> {
        val result = CompletableDeferred<MusicService>()
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (this != connection) return
                if (binder is MusicService.MusicBinder) result.complete(binder.service)
                else disconnect()
            }

            override fun onServiceDisconnected(name: ComponentName?) = disconnect()
            override fun onBindingDied(name: ComponentName?) = disconnect()
            override fun onNullBinding(name: ComponentName?) = disconnect()

            private fun disconnect() {
                if (this != connection) return
                result.completeExceptionally(McpToolError("Playback service is unavailable"))
                unbind()
            }
        }
        if (!context.bindService(Intent(context, MusicService::class.java), newConnection, Context.BIND_AUTO_CREATE)) {
            throw McpToolError("Playback service is unavailable")
        }
        connection = newConnection
        connected = result
        return result
    }

    private fun unbind() {
        connected?.cancel()
        connected = null
        connection?.let(context::unbindService)
        connection = null
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        closed = true
        unbind()
    }
}
