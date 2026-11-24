package com.ljyh.mei.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.source.core.SourceMutationAuthorization
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

internal data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = 19527,
    val allowLan: Boolean = false,
    val token: String? = null,
    val allowRead: Boolean = true,
    val allowPlayback: Boolean = true,
    val allowWrite: Boolean = false,
) {
    val host: String get() = if (allowLan) "0.0.0.0" else "127.0.0.1"
    fun permits(permission: McpPermission) = enabled && when (permission) {
        McpPermission.Read -> allowRead
        McpPermission.Playback -> allowPlayback
        McpPermission.Write -> allowWrite
    }
}

internal enum class McpStatus { Stopped, Starting, Running, PortInUse, Failed }

internal data class McpServiceState(
    val settings: McpSettings = McpSettings(),
    val status: McpStatus = McpStatus.Stopped,
    val clients: Int = 0,
)

@Singleton
internal class McpService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tools: Lazy<MeiloXTools>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(McpServiceState())
    val state = mutableState.asStateFlow()
    private val saveFailure = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveFailures = saveFailure.asSharedFlow()
    private val current = MutableStateFlow(McpSettings())

    @Synchronized
    fun start() {
        if (observer != null) return
        observer = scope.launch {
            var listener: McpServer? = null
            var playback: McpPlayback? = null
            var counts: Job? = null
            try {
                context.mcpDataStore.data.retryWhen { error, _ ->
                    if (error !is IOException) return@retryWhen false
                    current.value = McpSettings()
                    counts?.cancel()
                    listener?.close()
                    listener = null
                    playback?.close()
                    playback = null
                    mutableState.value = McpServiceState(status = McpStatus.Failed)
                    delay(2_000)
                    true
                }.map { prefs ->
                    decode(prefs).let { it.copy(allowLan = it.allowLan && context.canAccessMcpLan()) }
                }.collect { next ->
                    val previous = current.value
                    current.value = next
                    val endpointChanged = previous.endpoint() != next.endpoint()
                    if (!endpointChanged) {
                        if (!next.enabled) mutableState.value = McpServiceState(next)
                        else mutableState.value = mutableState.value.copy(settings = next)
                        return@collect
                    }
                    counts?.cancel()
                    listener?.close()
                    listener = null
                    playback?.close()
                    playback = null
                    if (!next.enabled || next.token == null) {
                        mutableState.value = McpServiceState(next)
                        return@collect
                    }
                    mutableState.value = McpServiceState(next, McpStatus.Starting)
                    val endpoint = next.endpoint()
                    val player = McpPlayback(context)
                    playback = player
                    fun permit(permission: McpPermission): Boolean = current.value.let { it.endpoint() == endpoint && it.permits(permission) }
                    try {
                        val protocol = McpProtocol(MeiloXMcpTools, ::permit, { tool, args ->
                            coroutineScope {
                                val operation = async(start = CoroutineStart.LAZY) {
                                    val requestContext = currentCoroutineContext()
                                    val check = {
                                        requestContext.ensureActive()
                                        if (!permit(tool.permission)) throw CancellationException("MCP authorization was revoked")
                                    }
                                    if (tool.permission == McpPermission.Write) {
                                        withContext(SourceMutationAuthorization(check)) {
                                            tools.get().execute(tool, args, player, check)
                                        }
                                    } else tools.get().execute(tool, args, player, check)
                                }
                                val revoke = launch {
                                    current.first { it.endpoint() != endpoint || !it.permits(tool.permission) }
                                    operation.cancel()
                                }
                                try {
                                    operation.await()
                                } finally {
                                    revoke.cancel()
                                }
                            }
                        }, BuildConfig.VERSION_NAME)
                        val server = McpServer(next.host, next.port, next.token, protocol) {
                            val settings = current.value
                            if (settings.endpoint() == endpoint) {
                                mutableState.value = McpServiceState(settings, McpStatus.Failed)
                            }
                        }
                        listener = server
                        mutableState.value = McpServiceState(next, McpStatus.Running)
                        counts = launch {
                            server.connectionCount.collect { count ->
                                val shown = mutableState.value
                                if (shown.settings.endpoint() == endpoint && shown.status == McpStatus.Running) {
                                    mutableState.compareAndSet(shown, shown.copy(clients = count))
                                }
                            }
                        }
                        server.start()
                    } catch (_: BindException) {
                        mutableState.value = McpServiceState(next, McpStatus.PortInUse)
                    } catch (_: IOException) {
                        mutableState.value = McpServiceState(next, McpStatus.Failed)
                    } catch (_: SecurityException) {
                        mutableState.value = McpServiceState(next, McpStatus.Failed)
                    }
                }
            } finally {
                counts?.cancel()
                listener?.close()
                withContext(NonCancellable) { playback?.close() }
            }
        }
    }

    private var observer: Job? = null

    fun update(transform: (McpSettings) -> McpSettings) {
        scope.launch {
            try {
                context.mcpDataStore.edit { prefs ->
                    val updated = transform(decode(prefs))
                    prefs[Enabled] = updated.enabled
                    prefs[Port] = updated.port
                    prefs[AllowLan] = updated.allowLan
                    prefs[AllowRead] = updated.allowRead
                    prefs[AllowPlayback] = updated.allowPlayback
                    prefs[AllowWrite] = updated.allowWrite
                    val token = updated.token ?: if (updated.enabled) newToken() else null
                    if (token != null) prefs[Token] = token
                }
            } catch (_: IOException) {
                saveFailure.tryEmit(Unit)
            }
        }
    }

    fun rotateToken() = update { it.copy(token = newToken()) }

    suspend fun clientConfiguration(settings: McpSettings): String? = withContext(Dispatchers.IO) {
        val token = settings.token ?: return@withContext null
        val host = if (settings.allowLan) lanAddress() ?: return@withContext null else settings.host
        jsonObject {
            add("mcpServers", jsonObject {
                add("meilox", jsonObject {
                    addProperty("url", "http://$host:${settings.port}/mcp")
                    add("headers", jsonObject { addProperty("Authorization", "Bearer $token") })
                })
            })
        }.toString()
    }

    private fun McpSettings.endpoint() = McpEndpoint(enabled, port, allowLan, token)

    private data class McpEndpoint(val enabled: Boolean, val port: Int, val lan: Boolean, val token: String?)

    private companion object {
        val Enabled = booleanPreferencesKey("mcp_enabled")
        val Port = intPreferencesKey("mcp_port")
        val AllowLan = booleanPreferencesKey("mcp_allow_lan")
        val Token = stringPreferencesKey("mcp_token")
        val AllowRead = booleanPreferencesKey("mcp_allow_read")
        val AllowPlayback = booleanPreferencesKey("mcp_allow_playback")
        val AllowWrite = booleanPreferencesKey("mcp_allow_write")

        fun decode(prefs: Preferences) = McpSettings(
            enabled = prefs[Enabled] ?: false,
            port = prefs[Port]?.takeIf { it in 1..65535 } ?: 19527,
            allowLan = prefs[AllowLan] ?: false,
            token = prefs[Token],
            allowRead = prefs[AllowRead] ?: true,
            allowPlayback = prefs[AllowPlayback] ?: true,
            allowWrite = prefs[AllowWrite] ?: false,
        )

        fun newToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
                ?.hostAddress
        }.getOrNull()
    }
}

private val Context.mcpDataStore by preferencesDataStore(
    name = "local_mcp",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal fun Context.canAccessMcpLan(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
    checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
