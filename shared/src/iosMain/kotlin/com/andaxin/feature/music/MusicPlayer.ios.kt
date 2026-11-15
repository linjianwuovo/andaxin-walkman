package com.andaxin.feature.music

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSURL

/*
 * iOS 这边的出声口：AVPlayer 一个实例走到底（换歌就是换 item）。
 *
 * 两件必须做的事，少一件就"看起来在放但没声音"：
 *  1. AVAudioSession 设成 playback —— 否则静音键一拨就一点声都没有，用户会以为解码失败。
 *  2. 每次出声前 setActive(true) —— 被电话/别的 App 抢走之后不重新激活就没有输出。
 *
 * 起停用 rate 而不是 play()/pause()：rate 是属性，跨版本都稳；
 * 而且状态机就在这一处，调用方不用去读 player.rate（缓冲时它是 0，拿它当"用户在放"会闪）。
 */
@OptIn(ExperimentalForeignApi::class)
class MusicPlayer {

    private val player = AVPlayer()

    fun play(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        activate()
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = nsUrl))
        player.rate = 1.0f
    }

    fun pause() {
        player.rate = 0.0f
    }

    fun resume() {
        activate()
        player.rate = 1.0f
    }

    private fun activate() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }
}
