package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.R
import com.ljyh.mei.playback.AutoMixStatus
import com.ljyh.mei.ui.component.utils.rememberLifecycleStarted
import com.ljyh.mei.ui.local.LocalPlayerConnection

internal const val AutoMixIndicatorFadeMs = 650
internal const val AutoMixQualityBadgeFadeMs = 250
internal val AutoMixIndicatorFadeEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private const val ShimmerHalfCycleMs = 1_300
private const val ShimmerRestMs = 100
internal const val AutoMixIndicatorFirstCycleMs = AutoMixQualityBadgeFadeMs + AutoMixIndicatorFadeMs + ShimmerHalfCycleMs * 2
private val AutoMixSheenExitEasing = CubicBezierEasing(0.33f, 0f, 0.83f, 1f)
private val labelColor = Color.White.copy(alpha = 0.45f)

@Composable
internal fun rememberIsAutoMixing(status: State<AutoMixStatus>?): State<Boolean> =
    remember(status) { derivedStateOf { status?.value?.active == true } }

@Composable
internal fun rememberAutoMixRailGlow(): State<Float> {
    val status = LocalPlayerConnection.current?.autoMixStatus?.collectAsStateWithLifecycle()
    return animateFloatAsState(
        targetValue = if (rememberIsAutoMixing(status).value) 1f else 0f,
        animationSpec = tween(AutoMixIndicatorFadeMs, easing = AutoMixIndicatorFadeEasing),
        label = "AutoMix rail glow",
    )
}

@Composable
internal fun AutoMixProgressBadge(status: State<AutoMixStatus>, style: TextStyle) {
    val title by remember(status) { derivedStateOf { status.value.incomingTitle } }
    val activeLabel = stringResource(R.string.player_automix_active)
    val nextSongLabel = title?.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.player_automix_transitioning_to, it)
    }
    val label = if (nextSongLabel == null) activeLabel else "$activeLabel, $nextSongLabel"
    val lifecycleStarted by rememberLifecycleStarted()
    val shimmer = if (lifecycleStarted) {
        rememberInfiniteTransition(label = "AutoMix label sheen").animateFloat(
            initialValue = 0f,
            targetValue = (ShimmerHalfCycleMs * 2 + ShimmerRestMs).toFloat(),
            animationSpec = infiniteRepeatable(
                tween(
                    durationMillis = ShimmerHalfCycleMs * 2 + ShimmerRestMs,
                    easing = LinearEasing,
                ),
                initialStartOffset = StartOffset(AutoMixQualityBadgeFadeMs + AutoMixIndicatorFadeMs),
            ),
            label = "AutoMix sheen time",
        )
    } else null
    Text(
        text = stringResource(R.string.player_automix_mixing),
        modifier = (if (shimmer == null) Modifier else Modifier.autoMixTextSheen(shimmer))
            .clearAndSetSemantics { contentDescription = label },
        style = style.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        color = if (shimmer == null) labelColor else Color.White,
        maxLines = 1,
        softWrap = false,
    )
}

private fun Modifier.autoMixTextSheen(time: State<Float>) = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val width = size.width + 43.dp.toPx()
    val feather = (24.dp.toPx() / width).coerceAtMost(0.5f)
    val brush = Brush.horizontalGradient(
        0f to labelColor,
        feather to Color.White,
        (1f - feather) to Color.White,
        1f to labelColor,
        endX = width,
    )
    onDrawWithContent {
        drawContent()
        val elapsed = time.value
        val middle = -24.dp.toPx()
        val left = if (elapsed < ShimmerHalfCycleMs) {
            val progress = AutoMixIndicatorFadeEasing.transform(elapsed / ShimmerHalfCycleMs)
            -width + (middle + width) * progress
        } else {
            val progress = AutoMixSheenExitEasing.transform(((elapsed - ShimmerHalfCycleMs) / ShimmerHalfCycleMs).coerceAtMost(1f))
            middle + (width - middle) * progress
        }
        translate(left = left) {
            drawRect(brush, topLeft = Offset(-left, 0f), size = size, blendMode = BlendMode.DstIn)
        }
    }
}
