@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.andaxin.feature.music

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
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
    val activeTrack = remember(foreground) { volumeTrackImage(foreground.volumeColor(0.58)) }
    val inactiveTrack = remember(foreground) { volumeTrackImage(foreground.volumeColor(0.22)) }
    val thumb = remember { volumeImage(1.0, 1.0) {} }
    val tint = foreground.volumeColor(0.55)
    val volumeView = remember { MPVolumeView(frame = CGRectZero.readValue()) }
    val drag = remember { RelativeVolumeDrag() }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    Row(
        modifier = modifier.fillMaxWidth().height(52.dp).testTag("music-volume-control"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UIKitView(
            factory = { UIImageView(image = UIImage.systemImageNamed("speaker.fill")).apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
            } },
            modifier = Modifier.size(18.dp),
            update = { it.tintColor = tint },
            properties = UIKitInteropProperties(interactionMode = null),
        )
        Box(
            modifier = Modifier.weight(1f).height(48.dp).testTag("music-volume-slider")
                .pointerInput(volumeView, ltr) {
                    var slider: UISlider? = null
                    detectHorizontalDragGestures(
                        onDragStart = {
                            volumeView.layoutIfNeeded()
                            slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
                            slider?.let { drag.start(it.value) }
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            slider?.let {
                                val width = (size.width - 6.dp.toPx()).coerceAtLeast(1f)
                                it.setValue(drag.move(delta / width * if (ltr) 1f else -1f), animated = false)
                                it.sendActionsForControlEvents(UIControlEventValueChanged)
                            }
                        },
                        onDragEnd = { slider = null },
                        onDragCancel = { slider = null },
                    )
                },
        ) {
            UIKitView(
                factory = { volumeView.apply {
                    showsRouteButton = false
                    setVolumeThumbImage(thumb, forState = UIControlStateNormal)
                    setVolumeThumbImage(thumb, forState = UIControlStateHighlighted)
                } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
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
        UIKitView(
            factory = { UIImageView(image = UIImage.systemImageNamed("speaker.wave.3.fill")).apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
            } },
            modifier = Modifier.size(22.dp),
            update = { it.tintColor = tint },
            properties = UIKitInteropProperties(interactionMode = null),
        )
    }
}

private fun Color.volumeColor(opacity: Double) = UIColor(
    red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha * opacity,
)

private fun volumeTrackImage(color: UIColor): UIImage = volumeImage(12.0, 6.0) {
    color.setFill()
    UIBezierPath.bezierPathWithRoundedRect(CGRectMake(0.0, 0.0, 12.0, 6.0), cornerRadius = 3.0).fill()
}.resizableImageWithCapInsets(UIEdgeInsetsMake(0.0, 3.0, 0.0, 3.0))

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

    fun start(current: Float) { value = current.coerceIn(0f, 1f) }

    fun move(delta: Float): Float {
        value = (value + delta).coerceIn(0f, 1f)
        return value
    }
}
