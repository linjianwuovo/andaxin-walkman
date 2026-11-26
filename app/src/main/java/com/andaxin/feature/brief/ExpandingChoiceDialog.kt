package com.andaxin.feature.brief

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

@Composable
internal fun ExpandingChoiceDialog(
    sourceBounds: Rect,
    onDismiss: () -> Unit,
    content: @Composable (close: (() -> Unit) -> Unit, contentAlpha: Float) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var targetBounds by remember { mutableStateOf(Rect.Zero) }
    val close: (() -> Unit) -> Unit = { complete ->
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                complete()
            }
        }
    }
    Dialog(
        onDismissRequest = { close(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val previous = window?.attributes?.dimAmount
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
            onDispose {
                previous?.let { window?.setDimAmount(it) }
            }
        }
        LaunchedEffect(targetBounds) {
            if (targetBounds != Rect.Zero && !closing) {
                progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f * progress.value))
                .clickable(remember { MutableInteractionSource() }, indication = null) { close(onDismiss) })
            Box(
                Modifier.padding(horizontal = 24.dp).fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        targetBounds = Rect(coordinates.localToScreen(Offset.Zero), Size(
                            coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                    }
                    .graphicsLayer {
                        val p = progress.value
                        if (sourceBounds.width > 0f && targetBounds.width > 0f) {
                            scaleX = sourceBounds.width / targetBounds.width * (1f - p) + p
                            scaleY = sourceBounds.height / targetBounds.height * (1f - p) + p
                            translationX = (sourceBounds.center.x - targetBounds.center.x) * (1f - p)
                            translationY = (sourceBounds.center.y - targetBounds.center.y) * (1f - p)
                        }
                        alpha = if (targetBounds == Rect.Zero) 0f else (p / 0.15f).coerceIn(0f, 1f)
                    },
            ) { content(close, ((progress.value - 0.75f) / 0.25f).coerceIn(0f, 1f)) }
        }
    }
}
