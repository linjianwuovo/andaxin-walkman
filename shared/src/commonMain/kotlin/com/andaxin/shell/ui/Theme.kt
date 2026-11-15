package com.andaxin.shell.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

/**
 * 一套固定的深色板。Android 版那套是 Glasmio 的玻璃底座 + 跟随壁纸的取色，
 * iOS 这一版先把它压成"墨底 + 青蓝点睛"两层，等 glasmio 跨端化之后再换。
 */
internal object Ink {
    val bg0 = Color(0xFF08080B)
    val bg1 = Color(0xFF101018)
    val bg2 = Color(0xFF15151E)
    val card = Color(0xFF1B1B25)
    val cardHi = Color(0xFF232331)
    val stroke = Color(0x1FFFFFFF)
    val accent = Color(0xFF7AA2FF)
    val accentSoft = Color(0x337AA2FF)
    val mint = Color(0xFF86E3BE)
    val amber = Color(0xFFFFB86B)
    val rose = Color(0xFFFF8A9E)
    val text = Color(0xFFF2F3F7)
    val text2 = Color(0xFFA6ACBA)
    val text3 = Color(0xFF6B7284)
}

internal val AppBackdrop: Brush
    get() = Brush.verticalGradient(listOf(Ink.bg0, Ink.bg1, Ink.bg0))

private val appColorScheme = darkColorScheme(
    primary = Ink.accent,
    onPrimary = Color(0xFF08101F),
    secondary = Ink.mint,
    background = Ink.bg0,
    onBackground = Ink.text,
    surface = Ink.card,
    onSurface = Ink.text,
    surfaceVariant = Ink.cardHi,
    onSurfaceVariant = Ink.text2,
    outline = Ink.stroke,
)

@Composable
internal fun AndaxinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = appColorScheme, content = content)
}
