package com.ljyh.mei.ui.screen.source

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.DelegatableNode
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import io.github.neoruaa.glasmio.glass.render.Backdrop
import kotlinx.coroutines.flow.onEach

internal class LibrarySharePresenter(val backdrop: Backdrop, val isIdle: () -> Boolean)

internal val LocalLibrarySharePresenter = staticCompositionLocalOf<LibrarySharePresenter?> { null }
internal val LocalLibraryShareArtwork = staticCompositionLocalOf<LibraryShareArtwork?> { null }

internal class LibraryShareContent {
    val requests = mutableStateListOf<LibraryShareArtwork.Request>()
    var mountedProfileImages by mutableIntStateOf(0)
    var mayHaveNativeContent by mutableStateOf(false)
        private set

    fun recordNativeContent() {
        // A drawable or ripple may still be retained by an existing display list.
        mayHaveNativeContent = true
    }
}

internal data class LibraryShareArtwork(val content: LibraryShareContent, val key: String) {
    class Request(private val artwork: LibraryShareArtwork) {
        val key get() = artwork.key
        var stablePixels by mutableStateOf(false)
            private set

        fun check(state: AsyncImagePainter.State): AsyncImagePainter.State {
            val success = state as? AsyncImagePainter.State.Success
            val bitmap = (success?.result?.image as? BitmapImage)?.bitmap
            // A state without a painter contributes no pixels. Future results pass here
            // before Coil installs their painter or starts an animated drawable.
            val stable = state.painter == null || bitmap != null && !bitmap.isMutable && !bitmap.isRecycled
            if (!stable) artwork.content.recordNativeContent()
            stablePixels = stable
            return state
        }
    }
}

@Composable
internal fun LibraryShareAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    artwork: LibraryShareArtwork?,
) {
    if (artwork == null) {
        AsyncImage(model, contentDescription, modifier = modifier, contentScale = ContentScale.Crop)
        return
    }
    val request = remember(artwork, model) { LibraryShareArtwork.Request(artwork) }
    DisposableEffect(artwork, request) {
        artwork.content.requests.add(request)
        onDispose { artwork.content.requests.remove(request) }
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        // Check before the painter can start a native AnimatedImageDrawable.
        transform = request::check,
    )
}

/** Preserve the original indication while detecting a possible RenderThread-owned ripple. */
@Composable
internal fun LibraryShareIndication(content: LibraryShareContent?, rows: @Composable () -> Unit) {
    if (content == null) {
        rows()
        return
    }
    val original = LocalIndication.current as? IndicationNodeFactory
    if (original == null) {
        SideEffect(content::recordNativeContent)
        rows()
        return
    }
    val indication = remember(content, original) { LibraryShareIndicationFactory(content, original) }
    CompositionLocalProvider(LocalIndication provides indication, content = rows)
}

private data class LibraryShareIndicationFactory(
    val content: LibraryShareContent,
    val original: IndicationNodeFactory,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        val observedSource = object : InteractionSource {
            override val interactions = interactionSource.interactions.onEach { interaction ->
                when (interaction) {
                    is PressInteraction.Press, is HoverInteraction.Enter, is FocusInteraction.Focus ->
                        content.recordNativeContent()
                }
            }
        }
        return original.create(observedSource)
    }
}
