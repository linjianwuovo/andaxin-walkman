package com.ljyh.mei.widget

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.ljyh.mei.R
import com.ljyh.mei.constants.AccentColorKey
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.DefaultAccentColorArgb
import com.ljyh.mei.constants.HorizontalSafeAreaEnabledKey
import com.ljyh.mei.constants.LiquidGlassEnabledKey
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.ui.theme.MusicTheme
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.IosPageHorizontalPadding
import io.github.neoruaa.glasmio.core.LocalGlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlasmioHorizontalSafeAreaEnabled
import io.github.neoruaa.glasmio.core.glasmioSafeDrawing
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import io.github.neoruaa.glasmio.glass.render.isRuntimeShaderSupported
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.GlasmioListPage
import io.github.neoruaa.glasmio.scaffold.GlasmioScaffold
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioTopBar

@Composable
internal fun ComponentActivity.WidgetConfigurePage(
    title: String,
    saving: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val appearance by rememberEnumPreference(AppAppearanceKey, AppAppearance.System)
    val dark = when (appearance) {
        AppAppearance.System -> isSystemInDarkTheme()
        AppAppearance.Light -> false
        AppAppearance.Dark -> true
    }
    val accentArgb by rememberPreference(AccentColorKey, DefaultAccentColorArgb)
    val accent = if (dark && accentArgb == DefaultAccentColorArgb) Color(0xFFFF4245)
        else Color(accentArgb.toInt())
    val liquidGlass by rememberPreference(LiquidGlassEnabledKey, true)
    val reduceTransparency by rememberPreference(ReduceTransparencyKey, false)
    val horizontalSafeArea by rememberPreference(HorizontalSafeAreaEnabledKey, true)
    val supportsShaders = isRuntimeShaderSupported()

    BackHandler(enabled = saving) {}
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(
        LocalGlassAppearance provides GlassAppearance(
            liquidGlassEnabled = supportsShaders && liquidGlass,
            reduceTransparency = !supportsShaders || reduceTransparency,
        ),
        LocalGlasmioHorizontalSafeAreaEnabled provides horizontalSafeArea,
    ) {
        MusicTheme(seedColor = accent, isDark = dark) {
            val background = LocalGlassColors.current.groupedBackground
            val backdrop = rememberCanvasBackdrop { drawRect(background) }
            val toolbarInsets = WindowInsets.glasmioSafeDrawing
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            GlasmioScaffold(topBar = {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    GlasmioTopBar(
                        title = title,
                        modifier = Modifier.windowInsetsPadding(toolbarInsets),
                        navigation = {
                            GlasmioIconButton(onClick = ::finish, enabled = !saving) {
                                SfIcon("xmark", stringResource(android.R.string.cancel))
                            }
                        },
                        actions = {
                            GlasmioIconButton(onClick = onSave, enabled = canSave && !saving) {
                                SfIcon("checkmark", stringResource(R.string.widget_collection_save))
                            }
                        },
                    )
                }
            }) { padding ->
                val direction = LocalLayoutDirection.current
                GlasmioListPage(
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(direction) + IosPageHorizontalPadding,
                        top = padding.calculateTopPadding() + 12.dp,
                        end = padding.calculateEndPadding(direction) + IosPageHorizontalPadding,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    content = content,
                )
            }
        }
    }
}
