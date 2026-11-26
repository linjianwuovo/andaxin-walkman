@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.andaxin.feature.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.math.roundToInt
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSizeMake
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UISlider
import platform.UIKit.UIControlStateHighlighted
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

@Composable
internal fun PlayerVolumeSection(foreground: Color, modifier: Modifier = Modifier) {
    val thumb = remember { volumeImage(1.0, 1.0) {} }
    val volumeView = remember { MPVolumeView(frame = CGRectZero.readValue()) }
    val drag = remember { RelativeVolumeDrag() }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    var pressed by remember { mutableStateOf(false) }
    var pull by remember { mutableStateOf(0f) }
    val emphasis by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.72f, stiffness = 320f), label = "volumePress",
    )
    val elastic by animateFloatAsState(
        pull / (kotlin.math.abs(pull) + 0.08f),
        spring(dampingRatio = 0.65f, stiffness = 300f), label = "volumeElastic",
    )
    val stretch = kotlin.math.abs(elastic)
    val trackHeight = 6f + emphasis * 10f - stretch * 2f
    val trackScale = 1f + emphasis * 0.05f + stretch * 0.025f
    val physicalPull = elastic * if (ltr) 1f else -1f
    val iconScale = 1f + emphasis * 0.1f
    val activeTrack = remember(foreground, trackHeight, emphasis) {
        volumeTrackImage(foreground.volumeColor((0.58 + emphasis * 0.24).coerceIn(0.0, 1.0)), trackHeight.toDouble())
    }
    val inactiveTrack = remember(foreground, trackHeight, emphasis) {
        volumeTrackImage(foreground.volumeColor((0.22 + emphasis * 0.08).coerceIn(0.0, 1.0)), trackHeight.toDouble())
    }
    val tint = foreground.volumeColor((0.55 + emphasis * 0.4).coerceIn(0.0, 1.0))
    Row(
        modifier = modifier.fillMaxWidth().height(52.dp).testTag("music-volume-control"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            UIKitView(
                factory = { UIImageView(image = UIImage.systemImageNamed("speaker.fill")).apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                } },
                modifier = Modifier.requiredSize((18f * iconScale).dp)
                    .offset { IntOffset(((-4f * emphasis + physicalPull * 6f).dp.toPx()).roundToInt(), 0) },
                update = { it.tintColor = tint },
                properties = UIKitInteropProperties(interactionMode = null),
            )
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).height(48.dp).testTag("music-volume-slider")
                .pointerInput(volumeView) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            pressed = false
                            pull = 0f
                        }
                    }
                }
                .pointerInput(volumeView, ltr) {
                    var slider: UISlider? = null
                    detectHorizontalDragGestures(
                        onDragStart = {
                            volumeView.layoutIfNeeded()
                            slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
                            slider?.let { drag.start(it.value) }
                            pull = 0f
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            slider?.let {
                                val width = (size.width - 6.dp.toPx()).coerceAtLeast(1f)
                                it.setValue(drag.move(delta / width * if (ltr) 1f else -1f), animated = false)
                                pull = drag.overflow
                                it.sendActionsForControlEvents(UIControlEventValueChanged)
                            }
                        },
                        onDragEnd = { slider = null; pull = 0f },
                        onDragCancel = { slider = null; pull = 0f },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            UIKitView(
                factory = { volumeView.apply {
                    showsRouteButton = false
                    setVolumeThumbImage(thumb, forState = UIControlStateNormal)
                    setVolumeThumbImage(thumb, forState = UIControlStateHighlighted)
                } },
                modifier = Modifier.requiredWidth(maxWidth * trackScale).height(48.dp)
                    .offset { IntOffset((physicalPull * 8f).dp.roundToPx(), 0) },
                update = {
                    it.setMinimumVolumeSliderImage(activeTrack, forState = UIControlStateNormal)
                    it.setMaximumVolumeSliderImage(inactiveTrack, forState = UIControlStateNormal)
                    it.setMinimumVolumeSliderImage(activeTrack, forState = UIControlStateHighlighted)
                    it.setMaximumVolumeSliderImage(inactiveTrack, forState = UIControlStateHighlighted)
                },
                properties = UIKitInteropProperties(
                    interactionMode = null,
                    isNativeAccessibilityEnabled = true,
                ),
            )
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            UIKitView(
                factory = { UIImageView(image = UIImage.systemImageNamed("speaker.wave.3.fill")).apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                } },
                modifier = Modifier.requiredSize((22f * iconScale).dp)
                    .offset { IntOffset(((4f * emphasis + physicalPull * 6f).dp.toPx()).roundToInt(), 0) },
                update = { it.tintColor = tint },
                properties = UIKitInteropProperties(interactionMode = null),
            )
        }
    }
}

private fun Color.volumeColor(opacity: Double) = UIColor(
    red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha * opacity,
)

private fun volumeTrackImage(color: UIColor, height: Double): UIImage {
    val thickness = height.coerceAtLeast(1.0)
    val radius = thickness / 2.0
    return volumeImage(thickness * 2.0, thickness) {
        color.setFill()
        UIBezierPath.bezierPathWithRoundedRect(
            CGRectMake(0.0, 0.0, thickness * 2.0, thickness), cornerRadius = radius,
        ).fill()
    }.resizableImageWithCapInsets(UIEdgeInsetsMake(0.0, radius, 0.0, radius))
}

private fun volumeImage(width: Double, height: Double, draw: () -> Unit): UIImage {
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), false, 0.0)
    return try {
        draw()
        checkNotNull(UIGraphicsGetImageFromCurrentImageContext())
    } finally {
        UIGraphicsEndImageContext()
    }
}

private class RelativeVolumeDrag {
    private var value = 0f
    var overflow = 0f
        private set

    fun start(current: Float) {
        value = current.coerceIn(0f, 1f)
        overflow = 0f
    }

    fun move(delta: Float): Float {
        val requested = value + delta
        value = requested.coerceIn(0f, 1f)
        overflow = if (requested < 0f || requested > 1f) {
            (overflow + requested - value).coerceIn(-0.5f, 0.5f)
        } else 0f
        return value
    }
}
