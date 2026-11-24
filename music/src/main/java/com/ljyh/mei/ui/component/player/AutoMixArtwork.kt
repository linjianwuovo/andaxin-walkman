package com.ljyh.mei.ui.component.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics

internal class AutoMixArtworkFrame(
    val url: String,
    val painter: Painter,
    val completed: Boolean,
    val fraction: () -> Float,
)

internal val LocalAutoMixArtworkFrame = compositionLocalOf<AutoMixArtworkFrame?> { null }

@Composable
internal fun PlayerArtworkTransition(
    coverUrl: String?,
    modifier: Modifier,
    transitionSpec: AnimatedContentTransitionScope<String?>.() -> ContentTransform,
    contentDescription: String? = null,
    content: @Composable AnimatedContentScope.(String?) -> Unit,
) {
    val frame = LocalAutoMixArtworkFrame.current
    val promoted = frame?.takeIf { it.completed }
    Box(modifier, propagateMinConstraints = true) {
        AnimatedContent(
            targetState = promoted?.url ?: coverUrl,
            transitionSpec = {
                if (promoted?.url == targetState && promoted != null) {
                    (EnterTransition.None togetherWith ExitTransition.None).using(null)
                } else transitionSpec()
            },
            modifier = if (promoted == null) Modifier else Modifier
                .graphicsLayer { alpha = 0f }
                .clearAndSetSemantics { },
            label = "CoverTransition",
            content = content,
        )
        AutoMixIncomingArtwork(contentDescription.takeIf { promoted != null })
    }
}

@Composable
internal fun BoxScope.AutoMixIncomingArtwork(
    contentDescription: String? = null,
    parentFraction: () -> Float = { 1f },
) {
    val frame = LocalAutoMixArtworkFrame.current ?: return
    // Keep the same image node mounted as the incoming deck becomes current.
    key(frame.url) {
        Image(
            painter = frame.painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer {
                alpha = frame.fraction() * parentFraction()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        )
    }
}
