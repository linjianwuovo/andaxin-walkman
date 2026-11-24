package com.ljyh.mei.ui.component.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.rememberPlayerSheetLayers
import com.ljyh.mei.ui.component.sheet.PlayerSheetArtworkOverlay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicPlayer
import com.ljyh.mei.ui.component.player.component.classic.ClassicPlayer
import com.ljyh.mei.ui.component.player.overlay.CommonOverlayHandler
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.overlay.rememberOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.player.state.rememberPlayerStateContainer
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.ui.GlasmioSheetGroupedListStyle
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetGroupedListStyle
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalPlayerConnection
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.rememberRasterizedBackdrop
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.playback.PrivateFmMode
import com.ljyh.mei.utils.rememberEnumPreference
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCombinedBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop

internal val LocalPlayerArtwork = staticCompositionLocalOf<Pair<String, Painter>?> { null }

internal val LocalPlayerLyricsRequest = staticCompositionLocalOf<MutableState<Boolean>?> { null }

internal class AutoMixVisuals(
    val incoming: MediaMetadata,
    private val audioProgress: () -> Float,
    private val durationMs: Long,
    val incomingPositionMs: () -> Long,
) {
    fun artworkProgress(): Float = autoMixArtworkEasing.transform(afterPivot(3_000f))

    fun backgroundProgress(): Float = autoMixBackgroundEasing.transform(afterPivot(6_000f))

    fun progress(): Float = EaseInOut.transform(afterPivot(300f))

    private fun afterPivot(targetDurationMs: Float): Float {
        // MeiloX's gain envelopes meet at their midpoint. Short mixes have less
        // time left before the incoming deck takes over, so fit the handoff there.
        val remainingMs = durationMs * 0.5f
        val windowMs = targetDurationMs.coerceAtMost(remainingMs)
        return ((audioProgress() - 0.5f) * durationMs / windowMs).coerceIn(0f, 1f)
    }
}

private val autoMixArtworkEasing = CubicBezierEasing(0.62f, 0f, 0.8f, 1f)
private val autoMixBackgroundEasing = CubicBezierEasing(0f, 0f, 0.30f, 1f)
internal val LocalAutoMixVisuals = compositionLocalOf<AutoMixVisuals?> { null }
internal val LocalAutoMixUpcomingArtworkUrl = compositionLocalOf<String?> { null }

internal val LocalPlayerRoamingModeLabel = staticCompositionLocalOf<String?> { null }

internal val LocalPlayerBackgroundReadyChanged = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Stable
internal class PlayerSheetPresentation(val sheet: GlasmioBottomSheetState?) {
    // Presentation scopes need the endpoints, while placement and drawing read the live motion.
    val isCollapsed by derivedStateOf(structuralEqualityPolicy()) { sheet?.isCollapsed != false }
    val isExpanded by derivedStateOf(structuralEqualityPolicy()) { sheet?.isExpanded != false }
    val isContentVisible by derivedStateOf(structuralEqualityPolicy()) { sheet?.isContentVisible == true }
}

private val LocalPlayerSheetPresentation = staticCompositionLocalOf<PlayerSheetPresentation?> { null }

@Composable
internal fun rememberPlayerSheetPresentation(sheet: GlasmioBottomSheetState?): PlayerSheetPresentation {
    val provided = LocalPlayerSheetPresentation.current
    return if (provided != null && provided.sheet === sheet) provided
    else remember(sheet) { PlayerSheetPresentation(sheet) }
}

@OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun BottomSheetPlayer(
    state: GlasmioBottomSheetState,
    modifier: Modifier = Modifier,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
    onBackgroundReadyChanged: (Boolean) -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val device = rememberDeviceInfo()
    val collapsedBackdrop = LocalGlassBackdrop.current
    // Record the mesh background directly so glass shares the same live Compose layer.
    val playerBackgroundBackdrop = rememberLayerBackdrop()
    // Record foreground content independently to keep glass out of its own sample source.
    // Window-space wrappers preserve sampling when a Popup or ModalBottomSheet animates
    // in another Compose owner.
    val playerContentBackdrop = rememberLayerBackdrop()
    // The expanded cover is intentionally rendered above the player content. Record it in a
    // separate source so sheets can sample it without changing that visual z-order.
    val playerCoverBackdrop = rememberLayerBackdrop()
    val presentation = rememberPlayerSheetPresentation(state)
    val progressUpdatesEnabled = presentation.isContentVisible
    val playerBackdrop = rememberCombinedBackdrop(
        rememberRasterizedBackdrop(playerBackgroundBackdrop, enabled = progressUpdatesEnabled),
        rememberRasterizedBackdrop(playerContentBackdrop, enabled = progressUpdatesEnabled),
        rememberRasterizedBackdrop(playerCoverBackdrop, enabled = progressUpdatesEnabled),
    )

    // 获取播放器样式
    val playerStyle by rememberEnumPreference(PlayerStyleKey, defaultValue = PlayerStyle.AppleMusic)

    // 创建公共状态容器
    val stateContainer = rememberPlayerStateContainer(
        playerViewModel = playerViewModel,
        playerConnection = playerConnection,
        progressUpdatesEnabled = progressUpdatesEnabled,
    )
    val favoriteHaptics = LocalHapticFeedback.current
    LaunchedEffect(playerViewModel, favoriteHaptics) {
        playerViewModel.likeFailures.collect {
            favoriteHaptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }
    val roamingMode by playerConnection.privateFmMode.collectAsStateWithLifecycle()
    val roamingScene by playerConnection.privateFmScene.collectAsStateWithLifecycle()
    val roamingModeLabel = if (stateContainer.isFMMode.value &&
        playerConnection.service.queueManager.similarFmSourceIds == null
    ) {
        when (roamingMode) {
            PrivateFmMode.DEFAULT -> stringResource(R.string.private_fm_default_mode)
            PrivateFmMode.FAMILIAR -> stringResource(R.string.private_fm_familiar_mode)
            PrivateFmMode.EXPLORE -> stringResource(R.string.private_fm_explore_mode)
            PrivateFmMode.SCENE -> roamingScene?.let {
                stringResource(R.string.private_fm_named_scene_mode, it.title)
            } ?: stringResource(R.string.private_fm_scene_mode)
        }
    } else null

    // 创建弹窗处理器
    val sheetLayers = rememberPlayerSheetLayers(state)
    val overlayHandler = rememberOverlayHandler(stateContainer)

    val currentMetadata = stateContainer.mediaMetadata.value
    val upcomingMixTrack by playerConnection.upcomingAutoMixTrack.collectAsStateWithLifecycle()
    val mixTracks by playerConnection.autoMixTracks.collectAsStateWithLifecycle()
    val mixStatus = playerConnection.autoMixStatus.collectAsStateWithLifecycle()
    // The wrapper player's cached MediaItem may lag behind the physical deck.
    val playingMetadata = playerConnection.player.activeDeck.currentMediaItem?.metadata
    val playingRef = playingMetadata?.trackRef
    val displayedMix = mixTracks?.takeIf {
        progressUpdatesEnabled &&
            (playingRef == it.incoming.trackRef || (!it.completed && playingRef == it.outgoing?.trackRef))
    }
    val mixPromoted = displayedMix?.let {
        it.completed || (playingRef == it.incoming.trackRef && playingRef != it.outgoing?.trackRef)
    } == true
    val context = LocalContext.current
    val currentUrl = currentMetadata?.coverUrl
    val activeMix = displayedMix?.takeIf {
        !mixPromoted || (it.incoming.trackRef != currentMetadata?.trackRef && it.outgoing?.trackRef == currentMetadata?.trackRef)
    }
    val upcomingArtworkUrl = (activeMix?.incoming ?: upcomingMixTrack)?.coverUrl?.takeIf { progressUpdatesEnabled }
    val mixArtworkUrl = displayedMix?.incoming?.coverUrl?.takeIf {
        it.isNotBlank() && (!mixPromoted || it == playingMetadata?.coverUrl)
    }
    // Keep painter identity as the prepared artwork moves into the current slot.
    val artworks = listOfNotNull(currentUrl, mixArtworkUrl, upcomingArtworkUrl).distinct().associateWith { url ->
        key(url) {
            rememberAsyncImagePainter(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(1440)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
    }
    val artwork = currentUrl?.let { url -> artworks[url]?.let { url to it } }
    val decodedMixArtwork = mixArtworkUrl?.let(artworks::get)?.state?.collectAsStateWithLifecycle()?.value
    val readyMixPainter = (decodedMixArtwork as? AsyncImagePainter.State.Success)?.painter
    val retainedMixPainter = remember(mixArtworkUrl) { arrayOfNulls<Painter>(1) }
    SideEffect { if (readyMixPainter != null) retainedMixPainter[0] = readyMixPainter }
    val mixVisuals = remember(activeMix, mixPromoted, mixStatus, playerConnection) {
        activeMix?.let {
            AutoMixVisuals(
                incoming = it.incoming,
                audioProgress = { if (mixPromoted) 1f else mixStatus.value.progress },
                durationMs = it.durationMs,
                incomingPositionMs = playerConnection::incomingMixPositionMs,
            )
        }
    }
    val mixArtwork = remember(mixArtworkUrl, displayedMix, mixPromoted, readyMixPainter ?: retainedMixPainter[0], mixVisuals) {
        val painter = readyMixPainter ?: retainedMixPainter[0]
        if (mixArtworkUrl != null && painter != null &&
            displayedMix.outgoing?.coverUrl != mixArtworkUrl
        ) {
            AutoMixArtworkFrame(
                url = mixArtworkUrl,
                painter = painter,
                completed = mixPromoted,
                fraction = { if (mixPromoted) 1f else mixVisuals?.artworkProgress() ?: 0f },
            )
        } else null
    }


    // 单入口、双实现 - 根据样式渲染不同的播放器
    CompositionLocalProvider(
        LocalPlayerSheet provides sheetLayers,
        LocalPlayerSheetPresentation provides presentation,
        LocalPlayerArtwork provides artwork,
        LocalAutoMixVisuals provides mixVisuals,
        LocalAutoMixArtworkFrame provides mixArtwork,
        LocalAutoMixUpcomingArtworkUrl provides upcomingArtworkUrl,
        LocalPlayerRoamingModeLabel provides roamingModeLabel,
        LocalPlayerBackgroundReadyChanged provides onBackgroundReadyChanged,
        LocalGlassBackdrop provides playerBackdrop,
        LocalBlurBackdrop provides playerBackdrop,
    ) {
        Box(Modifier.fillMaxSize()) {
            when (playerStyle) {
                PlayerStyle.AppleMusic -> {
                    // 横屏模式下直接进入经典模式
                    if( device.isLandscape){
                        ClassicPlayer(
                            state = state,
                            modifier = modifier,
                            stateContainer = stateContainer,
                            overlayHandler = overlayHandler,
                            collapsedBackdrop = collapsedBackdrop,
                            playerBackgroundBackdrop = playerBackgroundBackdrop,
                            playerContentBackdrop = playerContentBackdrop,
                            compactMiniPlayerProgress = compactMiniPlayerProgress,
                            miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                        )
                    }else{
                        AppleMusicPlayer(
                            state = state,
                            modifier = modifier,
                            stateContainer = stateContainer,
                            overlayHandler = overlayHandler,
                            collapsedBackdrop = collapsedBackdrop,
                            playerBackgroundBackdrop = playerBackgroundBackdrop,
                            playerContentBackdrop = playerContentBackdrop,
                            playerCoverBackdrop = playerCoverBackdrop,
                            compactMiniPlayerProgress = compactMiniPlayerProgress,
                            miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                        )
                    }

                }
                PlayerStyle.Classic -> {
                    ClassicPlayer(
                        state = state,
                        modifier = modifier,
                        stateContainer = stateContainer,
                        overlayHandler = overlayHandler,
                        collapsedBackdrop = collapsedBackdrop,
                        playerBackgroundBackdrop = playerBackgroundBackdrop,
                        playerContentBackdrop = playerContentBackdrop,
                        compactMiniPlayerProgress = compactMiniPlayerProgress,
                        miniPlayerVerticalOffset = miniPlayerVerticalOffset,
                    )
                }
            }
            PlayerSheetArtworkOverlay(sheetLayers)
        }
    }

    // 公共的弹窗处理层
    val overlayBackdrop = if (presentation.isCollapsed) collapsedBackdrop else playerBackdrop
    CompositionLocalProvider(
        LocalGlassBackdrop provides overlayBackdrop,
        LocalBlurBackdrop provides overlayBackdrop,
        LocalGlasmioSheetGroupedListStyle provides GlasmioSheetGroupedListStyle.ToolbarTint,
    ) {
        CommonOverlayHandler(
            overlayHandler = overlayHandler,
            stateContainer = stateContainer,
            sheetState = state,
        )
    }
}
