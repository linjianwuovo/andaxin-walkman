package com.andaxin.feature.music

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGSizeMake
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/*
 * 锁屏 / 控制中心：两件事分开干。
 *
 *  —— MPNowPlayingInfoCenter：告诉系统"现在在放什么、多长、放到哪、封面是哪张"。
 *     这首一换、一暂停、一跳，都要重新写一遍，否则锁屏上的进度会自己走（系统按上一次的 rate 外推）。
 *
 *  —— MPRemoteCommandCenter：接锁屏/耳机线上的按键。回调在系统的主队列上，
 *     这里只把"该做什么"转给 controller，网络请求（下一首要取直链）一律回主线程再发。
 *
 * 封面用 MPMediaItemArtwork 包一张 UIImage：boundsSize 给 300×300 就够系统缩，
 * 不用去读 image.size（那要 useContents 拆 CValue，没必要）。
 */
@OptIn(ExperimentalForeignApi::class)
internal class NowPlaying(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onToggle: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeek: (Double) -> Unit,
) {
    private var lastInfo: Map<Any?, Any?>? = null
    private var artwork: MPMediaItemArtwork? = null
    private val targets = mutableListOf<Pair<MPRemoteCommand, Any>>()

    init {
        register()
    }

    fun setCover(image: UIImage?) {
        artwork = image?.let { cover ->
            MPMediaItemArtwork(boundsSize = CGSizeMake(300.0, 300.0)) { _ -> cover }
        }
    }

    fun update(title: String, artist: String, duration: Double, elapsed: Double, rate: Double) {
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to title,
            MPMediaItemPropertyArtist to artist,
            MPMediaItemPropertyPlaybackDuration to duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to elapsed,
            MPNowPlayingInfoPropertyPlaybackRate to rate,
        )
        artwork?.let { info[MPMediaItemPropertyArtwork] = it }
        if (rate == 0.0 && info == lastInfo) return
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
        lastInfo = info
    }

    fun clear() {
        lastInfo = null
        artwork = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    fun dispose() {
        targets.forEach { (command, target) -> command.removeTarget(target) }
        targets.clear()
        clear()
    }

    // K/N 把 ObjC 的 "-isEnabled" 映射成属性 enabled（getter 叫 isEnabled()），写成 isEnabled = true 会报
    // "Function invocation 'isEnabled()' expected / Variable expected" —— 这一条是 CI 编出来的，不是猜的。
    private fun register() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.enabled = true
        center.pauseCommand.enabled = true
        center.togglePlayPauseCommand.enabled = true
        center.nextTrackCommand.enabled = true
        center.previousTrackCommand.enabled = true
        center.changePlaybackPositionCommand.enabled = true

        // MPRemoteCommandHandlerStatus 在这一版 K/N 平台库里是 typealias NSInteger（= Long），
        // 状态值是**顶层 const val**（dump-metadata 原文：
        //   typealias MPRemoteCommandHandlerStatus = platform/darwin/NSInteger
        //   public final const val MPRemoteCommandHandlerStatusSuccess: kotlin/Long = 0L
        //   fun addTargetWithHandler(handler: Function1<MPRemoteCommandEvent?, Long>): Any
        // ），所以既没有枚举成员，也不该写 MPRemoteCommandHandlerStatus.xxx。
        val ok = MPRemoteCommandHandlerStatusSuccess
        targets += center.playCommand to center.playCommand.addTargetWithHandler {
            onPlay()
            ok
        }
        targets += center.pauseCommand to center.pauseCommand.addTargetWithHandler {
            onPause()
            ok
        }
        targets += center.togglePlayPauseCommand to center.togglePlayPauseCommand.addTargetWithHandler {
            onToggle()
            ok
        }
        targets += center.nextTrackCommand to center.nextTrackCommand.addTargetWithHandler {
            onNext()
            ok
        }
        targets += center.previousTrackCommand to center.previousTrackCommand.addTargetWithHandler {
            onPrevious()
            ok
        }
        targets += center.changePlaybackPositionCommand to center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (position != null) onSeek(position)
            ok
        }
    }
}
