package com.ljyh.mei.ui.screen.setting

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.AppContext
import com.ljyh.mei.R
import com.ljyh.mei.mcp.McpStatus
import com.ljyh.mei.mcp.canAccessMcpLan
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertFieldGroup
import io.github.neoruaa.glasmio.ui.GlasmioAlertTextField
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import kotlinx.coroutines.launch

@Composable
internal fun LocalMcpSettings() {
    val context = LocalContext.current
    val service = AppContext.mcpService
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val state by service.state.collectAsStateWithLifecycle()
    val settings = state.settings
    LaunchedEffect(service, context) {
        service.saveFailures.collect { Toast.makeText(context, R.string.mcp_save_failed, Toast.LENGTH_SHORT).show() }
    }
    var editPort by rememberSaveable { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var rotateToken by rememberSaveable { mutableStateOf(false) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) service.update { it.copy(allowLan = true) }
        else Toast.makeText(context, R.string.mcp_lan_permission, Toast.LENGTH_SHORT).show()
    }
    val token = settings.token
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    fun copy(text: String, label: CharSequence) {
        if (clipboard == null) return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            // The platform also recognizes this key on Android 12.
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.mcp_copied, Toast.LENGTH_SHORT).show()
    }

    MeiloXPinnedListPage(
        title = stringResource(R.string.mcp_title),
        onNavigateBack = nav::navigateUp,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
    ) {
        item {
            SettingsGroupedList {
                GlasmioListRow(
                    title = stringResource(R.string.mcp_status),
                    subtitle = if (state.status == McpStatus.Running) stringResource(
                        R.string.mcp_listener, "${settings.host}:${settings.port}", state.clients,
                    ) else null,
                    trailing = { McpStatusBadge(state.status) },
                )
                McpToggle(R.string.mcp_enable, settings.enabled) { enabled -> service.update { it.copy(enabled = enabled) } }
                GlasmioListRow(
                    title = stringResource(R.string.mcp_port),
                    detail = settings.port.toString(),
                    onClick = { editPort = true },
                )
                McpToggle(R.string.mcp_lan, settings.allowLan) { allow ->
                    if (allow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && !context.canAccessMcpLan()) {
                        localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    } else service.update { it.copy(allowLan = allow) }
                }
            }
            if (settings.allowLan) {
                Text(
                    stringResource(R.string.mcp_lan_description),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalGlassColors.current.secondaryContent,
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.mcp_authentication)) {
                GlasmioListRow(
                    title = stringResource(R.string.mcp_token),
                    detail = if (token == null) null else "••••••••",
                    enabled = token != null,
                    onClick = { showToken = true },
                )
                GlasmioListRow(
                    title = stringResource(R.string.mcp_rotate_token),
                    enabled = token != null,
                    onClick = { rotateToken = true },
                    showDisclosure = false,
                )
                val copyLabel = stringResource(R.string.mcp_title)
                GlasmioListRow(
                    title = stringResource(R.string.mcp_copy_config),
                    enabled = token != null,
                    onClick = { scope.launch {
                        val configuration = service.clientConfiguration(service.state.value.settings)
                        if (configuration == null) Toast.makeText(context, R.string.mcp_lan_address_missing, Toast.LENGTH_SHORT).show()
                        else copy(configuration, copyLabel)
                    } },
                    showDisclosure = false,
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.mcp_permissions)) {
                McpToggle(R.string.mcp_allow_read, settings.allowRead) { allow -> service.update { it.copy(allowRead = allow) } }
                McpToggle(R.string.mcp_allow_playback, settings.allowPlayback) { allow -> service.update { it.copy(allowPlayback = allow) } }
                McpToggle(R.string.mcp_allow_write, settings.allowWrite) { allow -> service.update { it.copy(allowWrite = allow) } }
            }
        }
    }

    McpPortDialog(editPort, settings.port, { editPort = false }) { port ->
        editPort = false
        service.update { it.copy(port = port) }
    }
    val tokenLabel = stringResource(R.string.mcp_token)
    GlasmioAlertDialog(
        visible = showToken && token != null,
        onDismissRequest = { showToken = false },
        title = tokenLabel,
        message = token.orEmpty(),
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.done), role = GlasmioAlertButtonRole.Cancel, onClick = { showToken = false }),
            GlasmioAlertButtonSpec(stringResource(R.string.mcp_copy_token), onClick = {
                token?.let { copy(it, tokenLabel) }
                showToken = false
            }),
        ),
    )
    GlasmioAlertDialog(
        visible = rotateToken,
        onDismissRequest = { rotateToken = false },
        title = stringResource(R.string.mcp_rotate_token),
        message = stringResource(R.string.mcp_rotate_confirmation),
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.cancel), role = GlasmioAlertButtonRole.Cancel, onClick = { rotateToken = false }),
            GlasmioAlertButtonSpec(stringResource(R.string.confirm), onClick = {
                rotateToken = false
                service.rotateToken()
            }),
        ),
    )
}

@Composable
private fun McpStatusBadge(status: McpStatus) {
    val label = stringResource(when (status) {
        McpStatus.Stopped -> R.string.mcp_stopped
        McpStatus.Starting -> R.string.mcp_starting
        McpStatus.Running -> R.string.mcp_running
        McpStatus.PortInUse -> R.string.mcp_port_in_use
        McpStatus.Failed -> R.string.mcp_failed
    })
    val tint = when (status) {
        McpStatus.Running -> LocalGlassColors.current.accent
        McpStatus.PortInUse, McpStatus.Failed -> MaterialTheme.colorScheme.error
        else -> LocalGlassColors.current.secondaryContent
    }
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.12f), contentColor = tint) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun McpToggle(title: Int, checked: Boolean, setChecked: (Boolean) -> Unit) {
    val label = stringResource(title)
    GlasmioListRow(
        title = label,
        trailing = { MeiloXSwitch(checked, setChecked, Modifier.semantics { contentDescription = label }) },
    )
}

@Composable
private fun McpPortDialog(visible: Boolean, port: Int, onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var input by remember(visible, port) { mutableStateOf(TextFieldValue(port.toString(), TextRange(0, port.toString().length))) }
    val entered = input.text.toIntOrNull()?.takeIf { it in 1..65535 }
    GlasmioAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.mcp_port),
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.cancel), role = GlasmioAlertButtonRole.Cancel, onClick = onDismiss),
            GlasmioAlertButtonSpec(stringResource(R.string.confirm), enabled = entered != null, onClick = { entered?.let(onSubmit) }),
        ),
    ) {
        GlasmioAlertFieldGroup {
            GlasmioAlertTextField(
                value = input,
                onValueChange = { if (it.text.length <= 5 && it.text.all(Char::isDigit)) input = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}
