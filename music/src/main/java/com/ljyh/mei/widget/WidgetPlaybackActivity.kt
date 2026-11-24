package com.ljyh.mei.widget

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.playback.MusicService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@androidx.annotation.OptIn(UnstableApi::class)
class WidgetPlaybackActivity : ComponentActivity() {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as? MusicService.MusicBinder)?.service ?: run {
                finish()
                return
            }
            val command = service.runWidgetCommand(intent)
            lifecycleScope.launch {
                try {
                    withTimeoutOrNull(15_000L) {
                        command.join()
                        // Keep the foreground launch context until Media3 takes ownership of playback.
                        withTimeoutOrNull(3_000L) {
                            while (service.player.playWhenReady && !service.isPlaybackOngoing) delay(50L)
                        }
                    }
                } finally {
                    command.cancel()
                    finish()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = finish()
        override fun onNullBinding(name: ComponentName) = finish()
        override fun onBindingDied(name: ComponentName) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || !MusicWidgets.isWidgetCommand(this, intent)) {
            finish()
            return
        }
        bound = bindService(Intent(this, MusicService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) finish()
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
