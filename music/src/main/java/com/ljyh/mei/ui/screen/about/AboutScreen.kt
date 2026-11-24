package com.ljyh.mei.ui.screen.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.DevModeKey
import com.ljyh.mei.constants.Github
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberPreference
import java.time.LocalDate

@Composable
fun AboutScreen(viewModel: AboutViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (devMode, onDevModeChange) = rememberPreference(DevModeKey, false)
    // 库模块没有自己的 versionName，这里问宿主要；检测更新归宿主关于页那一张卡管
    @Suppress("DEPRECATION")
    val pkgInfo = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val appVersion = pkgInfo?.versionName?.trim().orEmpty()
    val appVersionCode = pkgInfo?.longVersionCode ?: 0L
    // 库模块里的 R.string.app_name 是上游的 MeiloX，这里读宿主装出来的真实应用名
    val appLabel = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    var clickCount by remember { mutableIntStateOf(0) }

    MeiloXPinnedListPage(
        title = stringResource(R.string.settings_about),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = stringResource(R.string.about_logo),
                    modifier = Modifier
                        .size(84.dp)
                        .clip(ContinuousRoundedRectangle(22.dp))
                        .clickable {
                            clickCount++
                            if (clickCount >= 7 && !devMode) {
                                onDevModeChange(true)
                                Toast.makeText(context, R.string.about_developer_enabled, Toast.LENGTH_SHORT).show()
                            }
                        },
                )
                Text(appLabel, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        R.string.about_version,
                        appVersion.ifBlank { "unknown" },
                        appVersionCode.toString(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            com.ljyh.mei.ui.screen.setting.SettingsGroup(stringResource(R.string.about_development)) {
                AboutEntry("chevron.left.forwardslash.chevron.right", stringResource(R.string.about_github)) { openUrl(context, Github) }
                AboutEntry("ladybug", stringResource(R.string.about_feedback)) { openUrl(context, "$Github/issues") }
                AboutEntry("apple.terminal", stringResource(R.string.about_logs)) { Screen.Log.navigate(navController) }
            }
        }
        item {
            com.ljyh.mei.ui.screen.setting.SettingsGroup(stringResource(R.string.about_acknowledgements)) {
                AboutEntry("chevron.left.forwardslash.chevron.right", "Mei", stringResource(R.string.about_upstream_description)) { openUrl(context, "https://github.com/ljyh223/Mei") }
                AboutEntry("iphone", "MeloX", stringResource(R.string.about_melox_description)) { openUrl(context, "https://github.com/youshen2/MeloX") }
                AboutEntry("square.stack.3d.up", "Glasmio", stringResource(R.string.about_glasmio_description)) { openUrl(context, "https://github.com/NEORUAA/Glasmio") }
                AboutEntry("quote.bubble", "amll-ttml-db", stringResource(R.string.about_amll_description)) { openUrl(context, "https://github.com/Steve-xmh/amll-ttml-db") }
                AboutEntry("music.note.list", "accompanist-lyrics-ui", stringResource(R.string.about_lyrics_ui_description)) { openUrl(context, "https://github.com/6xingyv/accompanist-lyrics-ui") }
            }
        }
        if (devMode) {
            item {
                com.ljyh.mei.ui.screen.setting.SettingsGroup(stringResource(R.string.about_developer_debug)) {
                    AboutEntry("cylinder.split.1x2", stringResource(R.string.about_delete_lyrics), stringResource(R.string.about_delete_lyrics_description)) {
                        viewModel.deleteAllCachedLyrics(); Toast.makeText(context, R.string.about_lyrics_deleted, Toast.LENGTH_SHORT).show()
                    }
                    AboutEntry("trash", stringResource(R.string.about_delete_qq_map), stringResource(R.string.about_delete_qq_map_description)) {
                        viewModel.deleteAllQQSongs(); Toast.makeText(context, R.string.about_qq_map_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp).fillMaxWidth()) {
                Text("$appLabel · ${LocalDate.now().year}", style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(R.string.about_compose),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

}

@Composable
private fun AboutSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
private fun AboutEntry(systemName: String, title: String, subtitle: String? = null, onClick: () -> Unit) {
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        systemName = systemName,
        onClick = onClick,
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
