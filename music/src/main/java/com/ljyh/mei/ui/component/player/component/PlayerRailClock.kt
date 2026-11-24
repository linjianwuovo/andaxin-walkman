package com.ljyh.mei.ui.component.player.component

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.component.player.LocalAutoMixVisuals
import com.ljyh.mei.ui.component.player.autoMixIncomingMetadataDominant
import com.ljyh.mei.ui.component.player.isIncomingMetadataDominant
import com.ljyh.mei.ui.local.LocalPlayerConnection

internal class PlayerRailClock(
    val positionMs: Long,
    val durationMs: Long,
    val showingIncoming: Boolean,
    val seekEnabled: Boolean,
    val seekSession: Any,
    val canCommitSeek: () -> Boolean,
)

@OptIn(UnstableApi::class)
@Composable
internal fun playerRailClock(positionMs: Long, durationMs: Long, mediaId: String?): PlayerRailClock {
    val mix = LocalAutoMixVisuals.current
    val connection = LocalPlayerConnection.current
    val showingIncoming = autoMixIncomingMetadataDominant(mix)
    val seekEnabled = !showingIncoming && durationMs > 0
    val seekSession = remember(mediaId, seekEnabled) { Any() }
    val currentSeekSession by rememberUpdatedState(seekSession)
    val currentMix by rememberUpdatedState(mix)

    // The caller already recomposes with the player's position tick. Sample the
    // incoming deck on that update without running a second progress timer.
    return PlayerRailClock(
        positionMs = if (showingIncoming && mix != null) mix.incomingPositionMs().coerceAtLeast(0) else positionMs,
        durationMs = if (showingIncoming && mix != null) mix.incoming.duration else durationMs,
        showingIncoming = showingIncoming,
        seekEnabled = seekEnabled,
        seekSession = seekSession,
        canCommitSeek = {
            seekEnabled && seekSession === currentSeekSession &&
                !currentMix.isIncomingMetadataDominant() &&
                connection?.player?.activeDeck?.currentMediaItem?.metadata?.trackRef
                    ?.let(TrackRefCodec::mediaId) == mediaId
        },
    )
}
