package com.ljyh.mei.ui.component.player.component

import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.icons.SfIcon
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PlayerVolumeSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var volume by remember(audio) { mutableStateOf(readMediaVolume(audio)) }
    val refresh = remember(audio) { { volume = readMediaVolume(audio) } }

    DisposableEffect(audio, context, lifecycleOwner) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        audio?.registerAudioDeviceCallback(devices, handler)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        refresh()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            audio?.unregisterAudioDeviceCallback(devices)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    val label = stringResource(R.string.player_media_volume)
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val currentVolume by rememberUpdatedState(volume)
    val drag = remember { RelativeVolumeDrag() }
    val setVolume by rememberUpdatedState { requested: Float ->
        val current = currentVolume
        if (audio != null && current.adjustable) {
            val next = (current.minimum + requested.coerceIn(0f, 1f) * (current.maximum - current.minimum))
                .roundToInt().coerceIn(current.minimum, current.maximum)
            if (next != current.level) audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            refresh()
        }
    }
    var pressed by remember { mutableStateOf(false) }
    var pull by remember { mutableStateOf(0f) }
    var minimumHit by remember { mutableIntStateOf(0) }
    var maximumHit by remember { mutableIntStateOf(0) }
    val minimumPulse = volumeEndpointPulses(minimumHit, 1).first()
    val maximumPulses = volumeEndpointPulses(maximumHit, 4)
    val emphasis by animateFloatAsState(
        if (pressed && volume.adjustable) 1f else 0f,
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
    val direction = if (ltr) 1f else -1f
    val minimumOffset = -8f * emphasis * direction + physicalPull * if (elastic < 0f) 10f else 3.5f
    val maximumOffset = 8f * emphasis * direction + physicalPull * if (elastic > 0f) 10f else 3.5f
    val opacity = if (volume.adjustable) 1f else 0.45f
    val tint = Color.White.copy(alpha = ((0.55f + emphasis * 0.4f) * opacity).coerceIn(0f, 1f))
    Row(
        modifier = modifier.fillMaxWidth().height(52.dp).swipeBackExcluded(volume.adjustable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SfIcon("speaker.fill", contentDescription = null, tint = tint, size = 18.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale * (1f + minimumPulse * 0.2f)
                scaleY = iconScale * (1f + minimumPulse * 0.2f)
                translationX = minimumOffset.dp.toPx()
            })
        Box(
            modifier = Modifier.weight(1f).height(48.dp).testTag("music-volume-slider")
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(volume.normalized, 0f..1f,
                        (volume.maximum - volume.minimum - 1).coerceAtLeast(0))
                    if (volume.adjustable) setProgress { requested -> setVolume(requested); true }
                    else disabled()
                }
                .onKeyEvent { event ->
                    if (!currentVolume.adjustable || event.type != KeyEventType.KeyDown) false
                    else {
                        val step = 1f / (currentVolume.maximum - currentVolume.minimum)
                        val direction = if (ltr) 1f else -1f
                        val next = when (event.key) {
                            Key.DirectionLeft -> currentVolume.normalized - step * direction
                            Key.DirectionRight -> currentVolume.normalized + step * direction
                            Key.MoveHome -> 0f
                            Key.MoveEnd -> 1f
                            else -> return@onKeyEvent false
                        }
                        setVolume(next)
                        true
                    }
                }
                .focusable(enabled = volume.adjustable)
                .pointerInput(audio, volume.adjustable) {
                    if (!volume.adjustable) return@pointerInput
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
                .pointerInput(audio, volume.adjustable, ltr) {
                    if (!volume.adjustable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { drag.start(currentVolume.normalized); pull = 0f },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            val width = (size.width - 6.dp.toPx()).coerceAtLeast(1f)
                            setVolume(drag.move(delta / width * if (ltr) 1f else -1f))
                            pull = drag.overflow
                            when (drag.endpointHit) {
                                -1 -> minimumHit++
                                1 -> maximumHit++
                            }
                        },
                        onDragEnd = { pull = 0f; refresh() },
                        onDragCancel = { pull = 0f; refresh() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(trackHeight.coerceAtLeast(1f).dp)
                .graphicsLayer {
                    scaleX = trackScale
                    translationX = (physicalPull * 8f).dp.toPx()
                }) {
                val inset = size.height / 2f
                val start = Offset(if (ltr) inset else size.width - inset, size.height / 2f)
                val end = Offset(if (ltr) size.width - inset else inset, size.height / 2f)
                drawLine(Color.White.copy(alpha = ((0.22f + emphasis * 0.08f) * opacity).coerceIn(0f, 1f)), start, end, size.height, StrokeCap.Round)
                if (volume.normalized > 0f) {
                    val filled = Offset(start.x + (end.x - start.x) * volume.normalized, start.y)
                    drawLine(Color.White.copy(alpha = ((0.58f + emphasis * 0.24f) * opacity).coerceIn(0f, 1f)), start, filled, size.height, StrokeCap.Round)
                }
            }
        }
        SfIcon("speaker.wave.3.fill", contentDescription = null, tint = tint, size = 22.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale; scaleY = iconScale
                translationX = maximumOffset.dp.toPx()
            }.drawWithContent {
                if (maximumPulses.all { kotlin.math.abs(it) < 0.0001f }) drawContent()
                else {
                    val bands = listOf(0f, 0.48f, 0.65f, 0.80f, 1f)
                    maximumPulses.forEachIndexed { index, pulse ->
                        val left = size.width * bands[index]
                        val right = size.width * bands[index + 1]
                        val horizontal = if (index == 0) 0.2f else 0.16f
                        val vertical = if (index == 0) 0.2f else 0.26f
                        scale(1f + pulse * horizontal, 1f + pulse * vertical,
                            pivot = Offset((left + right) / 2f, size.height / 2f)) {
                            clipRect(left, -size.height, right, size.height * 2f) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
                }
            })
    }
}

private data class MediaVolume(
    val minimum: Int = 0,
    val maximum: Int = 0,
    val level: Int = 0,
    val adjustable: Boolean = false,
) {
    val normalized: Float
        get() = if (maximum > minimum) {
            ((level - minimum).toFloat() / (maximum - minimum)).coerceIn(0f, 1f)
        } else 0f
}

private fun readMediaVolume(audio: AudioManager?): MediaVolume {
    if (audio == null) return MediaVolume()
    val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return MediaVolume(
        minimum = minimum,
        maximum = maximum,
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        adjustable = !audio.isVolumeFixed && maximum > minimum,
    )
}

@Composable
private fun volumeEndpointPulses(trigger: Int, layers: Int): List<Float> {
    val pulses = remember(layers) { List(layers) { Animatable(0f) } }
    LaunchedEffect(trigger) {
        if (trigger > 0) coroutineScope {
            pulses.forEachIndexed { index, pulse ->
                launch {
                    delay(index * 50L)
                    pulse.animateTo(1f, tween(durationMillis = 70))
                    pulse.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = 360f))
                }
            }
        }
    }
    return pulses.map { it.value }
}

private class RelativeVolumeDrag {
    private var value = 0f
    var overflow = 0f
        private set
    var endpointHit = 0
        private set

    fun start(current: Float) {
        value = current.coerceIn(0f, 1f)
        overflow = 0f
        endpointHit = 0
    }

    fun move(delta: Float): Float {
        if (delta == 0f) {
            endpointHit = 0
            return value
        }
        val previousOverflow = overflow
        val requested = value + delta
        value = requested.coerceIn(0f, 1f)
        overflow = if (requested < 0f || requested > 1f) {
            (overflow + requested - value).coerceIn(-0.5f, 0.5f)
        } else 0f
        endpointHit = when {
            overflow < 0f && previousOverflow >= 0f -> -1
            overflow > 0f && previousOverflow <= 0f -> 1
            else -> 0
        }
        return value
    }
}
