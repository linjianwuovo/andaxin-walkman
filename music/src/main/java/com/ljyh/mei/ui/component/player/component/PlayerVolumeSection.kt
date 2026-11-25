package com.ljyh.mei.ui.component.player.component

import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.ui.GlasmioVolumeSlider
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
    GlasmioVolumeSlider(
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
        modifier = modifier.semantics { contentDescription = label },
    )
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
