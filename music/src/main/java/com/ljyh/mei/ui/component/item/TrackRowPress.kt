package com.ljyh.mei.ui.component.item

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.requireLayoutCoordinates
import io.github.neoruaa.glasmio.core.LocalGlassColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Marks the real viewport so the press fill can extend through list padding without reflow. */
internal fun Modifier.trackPressViewport(): Modifier = then(TrackPressViewportElement)

private object TrackPressViewportElement : ModifierNodeElement<TrackPressViewportNode>() {
    override fun create() = TrackPressViewportNode()
    override fun update(node: TrackPressViewportNode) = Unit
    override fun hashCode() = System.identityHashCode(this)
    override fun equals(other: Any?) = other === this
}

private class TrackPressViewportNode : Modifier.Node(), TraversableNode {
    override val traverseKey = TrackPressViewportElement
}

@Composable
internal fun trackRowPressIndication(
    tint: Color = LocalGlassColors.current.content,
): IndicationNodeFactory = TrackRowPressIndication(tint.copy(alpha = 0.12f))

private data class TrackRowPressIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TrackRowPressNode(interactionSource, color)
}

private class TrackRowPressNode(
    private val source: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private val presses = mutableSetOf<PressInteraction.Press>()
    private var opacity by mutableFloatStateOf(0f)
    private var release: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses += interaction
                        release?.cancel()
                        opacity = 1f
                    }
                    is PressInteraction.Release -> endPress(interaction.press)
                    is PressInteraction.Cancel -> endPress(interaction.press)
                }
            }
        }
    }

    override fun onReset() = resetPress()
    override fun onDetach() = resetPress()

    private fun endPress(press: PressInteraction.Press) {
        if (!presses.remove(press) || presses.isNotEmpty()) return
        release = coroutineScope.launch {
            animate(opacity, 0f, animationSpec = tween(120, easing = LinearOutSlowInEasing)) { value, _ ->
                opacity = value
            }
        }
    }

    private fun resetPress() {
        presses.clear()
        release?.cancel()
        release = null
        opacity = 0f
    }

    override fun ContentDrawScope.draw() {
        if (opacity > 0f) {
            val viewport = findNearestAncestor(TrackPressViewportElement)
            val bounds = viewport?.let {
                requireLayoutCoordinates().localBoundingBoxOf(it.requireLayoutCoordinates(), clipBounds = false)
            }
            drawRect(
                color = color,
                alpha = opacity,
                topLeft = Offset(bounds?.left ?: 0f, 0f),
                size = Size(bounds?.width ?: size.width, size.height),
            )
        }
        drawContent()
    }
}
