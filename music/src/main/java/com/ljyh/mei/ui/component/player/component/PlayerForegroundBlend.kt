package com.ljyh.mei.ui.component.player.component

import android.content.pm.ActivityInfo
import android.graphics.Paint
import android.graphics.RuntimeXfermode
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

@Composable
private fun playerUsesHdrWindow(): Boolean =
    LocalActivity.current?.window?.colorMode == ActivityInfo.COLOR_MODE_HDR

@Composable
internal fun usesLegacyPlayerBlendClamp(): Boolean =
    Build.VERSION.SDK_INT in 33..35 && playerUsesHdrWindow()

/** Keep additive player colors within SDR white, even on a floating-point HDR target. */
@Composable
internal fun Modifier.playerAdditiveLayer(
    opacity: () -> Float = { 1f },
    translation: (() -> Offset)? = null,
    layerOutsets: Density.(Size) -> LayerOutsets = { LayerOutsets.Zero },
): Modifier {
    val hdr = playerUsesHdrWindow()
    if (hdr && Build.VERSION.SDK_INT >= 36) {
        val positioned = if (translation == null) this else graphicsLayer {
            val offset = translation()
            translationX = offset.x
            translationY = offset.y
        }
        return positioned.drawWithCache {
            val outsets = layerOutsets(size)
            val left = -outsets.left.toPx()
            val top = -outsets.top.toPx()
            val right = size.width + outsets.right.toPx()
            val bottom = size.height + outsets.bottom.toPx()
            val paint = Paint().apply { xfermode = PlayerBlendApi36.additiveXfermode }
            onDrawWithContent {
                paint.alpha = (opacity().coerceIn(0f, 1f) * 255f).roundToInt()
                if (paint.alpha > 0) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val checkpoint = canvas.save()
                    try {
                        canvas.clipRect(left, top, right, bottom)
                        canvas.saveLayer(left, top, right, bottom, paint)
                        drawContent()
                    } finally {
                        canvas.restoreToCount(checkpoint)
                    }
                }
            }
        }
    }

    return graphicsLayer {
        val offset = translation?.invoke() ?: Offset.Zero
        translationX = offset.x
        translationY = offset.y
        alpha = opacity()
        outsets = layerOutsets(size)
        blendMode = BlendMode.Plus
        compositingStrategy = CompositingStrategy.Offscreen
    }
}

@RequiresApi(36)
private object PlayerBlendApi36 {
    val additiveXfermode = RuntimeXfermode(
        """
        half4 main(half4 source, half4 destination) {
            half alpha = min(source.a + destination.a, 1.0);
            // Android can scale SDR white below 1 in a normalized HDR destination.
            half3 white = fromLinearSrgb(half3(1.0));
            half3 ceiling = max(destination.rgb, white * alpha);
            return half4(min(source.rgb + destination.rgb, ceiling), alpha);
        }
        """.trimIndent(),
    )
}
