package com.ljyh.mei.ui.component.player.component

import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
    val opacity = if (volume.adjustable) 1f else 0.45f
    val tint = Color.White.copy(alpha = 0.55f * opacity)
    Row(
        modifier = modifier.fillMaxWidth().height(52.dp).swipeBackExcluded(volume.adjustable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SfIcon("speaker.fill", contentDescription = null, tint = tint, size = 18.dp)
        Slider(
            value = volume.normalized,
            onValueChange = { requested ->
                if (audio != null && volume.adjustable) {
                    val next = (volume.minimum + requested * (volume.maximum - volume.minimum))
                        .roundToInt().coerceIn(volume.minimum, volume.maximum)
                    if (next != volume.level) audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                    refresh()
                }
            },
            onValueChangeFinished = refresh,
            enabled = volume.adjustable,
            modifier = Modifier.weight(1f).height(48.dp).testTag("music-volume-slider")
                .semantics { contentDescription = label },
            thumb = {},
            track = { slider ->
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val inset = size.height / 2f
                    val start = Offset(if (ltr) inset else size.width - inset, size.height / 2f)
                    val end = Offset(if (ltr) size.width - inset else inset, size.height / 2f)
                    drawLine(Color.White.copy(alpha = 0.22f * opacity), start, end, size.height, StrokeCap.Round)
                    if (slider.value > 0f) {
                        val filled = Offset(start.x + (end.x - start.x) * slider.value, start.y)
                        drawLine(Color.White.copy(alpha = 0.58f * opacity), start, filled, size.height, StrokeCap.Round)
                    }
                }
            },
        )
        SfIcon("speaker.wave.3.fill", contentDescription = null, tint = tint, size = 22.dp)
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
