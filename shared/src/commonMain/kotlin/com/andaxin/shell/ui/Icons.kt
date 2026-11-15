package com.andaxin.shell.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 底栏那五枚图标。Android 版用的是 SF Pro 字体里的 SF Symbol（SfSymbol），
 * iOS 侧这里没有字体依赖，于是用 Path 现画 —— 形状对齐 SF Symbol 的轮廓
 * （房子/日历/对话/铃/人），不引入任何图形资源。
 */
internal enum class TabIcon { House, Calendar, Chat, Bell, Person }

@Composable
internal fun TabGlyph(icon: TabIcon, color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = (w * 0.09f).coerceAtLeast(1.4f))
        val line = Path()

        when (icon) {
            TabIcon.House -> {
                line.moveTo(w * 0.12f, h * 0.46f)
                line.lineTo(w * 0.5f, h * 0.14f)
                line.lineTo(w * 0.88f, h * 0.46f)
                line.lineTo(w * 0.88f, h * 0.86f)
                line.lineTo(w * 0.62f, h * 0.86f)
                line.lineTo(w * 0.62f, h * 0.62f)
                line.lineTo(w * 0.38f, h * 0.62f)
                line.lineTo(w * 0.38f, h * 0.86f)
                line.lineTo(w * 0.12f, h * 0.86f)
                line.close()
            }

            TabIcon.Calendar -> {
                line.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = w * 0.12f, top = h * 0.2f, right = w * 0.88f, bottom = h * 0.88f,
                        radiusX = w * 0.13f, radiusY = w * 0.13f,
                    )
                )
                drawLine(color, Offset(w * 0.12f, h * 0.4f), Offset(w * 0.88f, h * 0.4f), stroke.width)
                for (x in listOf(0.3f, 0.5f, 0.7f)) {
                    drawCircle(color, radius = w * 0.045f, center = Offset(w * x, h * 0.62f))
                }
            }

            TabIcon.Chat -> {
                line.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = w * 0.12f, top = h * 0.16f, right = w * 0.88f, bottom = h * 0.7f,
                        radiusX = w * 0.2f, radiusY = w * 0.2f,
                    )
                )
                line.moveTo(w * 0.3f, h * 0.7f)
                line.lineTo(w * 0.26f, h * 0.88f)
                line.lineTo(w * 0.46f, h * 0.7f)
            }

            TabIcon.Bell -> {
                line.moveTo(w * 0.5f, h * 0.12f)
                line.cubicTo(w * 0.24f, h * 0.12f, w * 0.22f, h * 0.42f, w * 0.22f, h * 0.6f)
                line.lineTo(w * 0.12f, h * 0.72f)
                line.lineTo(w * 0.88f, h * 0.72f)
                line.lineTo(w * 0.78f, h * 0.6f)
                line.cubicTo(w * 0.78f, h * 0.42f, w * 0.76f, h * 0.12f, w * 0.5f, h * 0.12f)
                line.close()
            }

            TabIcon.Person -> {
                drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.3f), style = stroke)
                line.moveTo(w * 0.16f, h * 0.9f)
                line.cubicTo(w * 0.16f, h * 0.62f, w * 0.84f, h * 0.62f, w * 0.84f, h * 0.9f)
            }
        }

        if (filled) {
            drawPath(line, color)
        } else {
            drawPath(line, color, style = stroke)
        }
    }
}

internal val GlyphSize = 22.dp
