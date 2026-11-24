package com.andaxin.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Static material for content recorded inside a backdrop host; never samples that host. */
@Composable
internal fun Modifier.safeGlassSurface(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surface,
    selected: Boolean = false,
): Modifier {
    val dark = LocalInk.current.dark
    val accent = MaterialTheme.colorScheme.primary
    val base = tint.compositeOver(MaterialTheme.colorScheme.surface)
    val wash = if (selected) if (dark) 0.28f else 0.12f else 0.025f
    val body = lerp(base, accent, wash)
    val highlight = lerp(body, Color.White, if (dark) 0.035f else 0.18f)
    return background(
        Brush.linearGradient(listOf(highlight.copy(alpha = 0.98f), body.copy(alpha = 0.94f))),
        shape,
    ).glassEdge(shape, selected)
}

@Composable
internal fun Modifier.glassEdge(shape: Shape, selected: Boolean = false): Modifier {
    val dark = LocalInk.current.dark
    val accent = MaterialTheme.colorScheme.primary
    return border(
        width = 1.dp,
        brush = Brush.linearGradient(
            listOf(
                if (selected) accent.copy(alpha = 0.48f) else Color.White.copy(alpha = if (dark) 0.22f else 0.90f),
                if (selected) accent.copy(alpha = 0.24f) else LocalInk.current.strong.copy(alpha = if (dark) 0.10f else 0.09f),
            ),
        ),
        shape = shape,
    )
}

@Composable
internal fun Modifier.glassPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return background(
        Brush.verticalGradient(
            listOf(lerp(scheme.background, scheme.primary, 0.035f), scheme.background),
        ),
    )
}
