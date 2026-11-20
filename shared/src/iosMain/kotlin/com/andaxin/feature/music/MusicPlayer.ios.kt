package com.andaxin.feature.music

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
// AVPlayer 这一族（rate / currentItem / currentTime / seekToTime / replaceCurrentItemWithPlayerItem）
// 在头文件里挂在 @interface AVPlayer (AVPlayerItemControl) 这个 category 下，K/N 把它们绑成**顶层扩展**，
// 不 import 就只剩"没有这些方法"，报错是 "Unresolved reference 'seekToTime' on receiver of type 'AVPlayer'"。
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
// duration 挂在 AVPlayerItem 上，但 K/N 里也是 @interface AVPlayerItem (AVPlayerItemDuration) 的
// 顶层扩展属性（dump-metadata 原文：public final val platform/AVFoundation/AVPlayerItem.duration:
// CValue<CMTime>），不 import 就报 "Unresolved reference 'duration' on receiver of type 'AVPlayerItem'"。
import platform.AVFoundation.duration
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
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
 *
 * 进度用"每 0.5 秒问一次当前位置"（调用方起协程轮），不用 addPeriodicTimeObserver：
 * 那个 API 在 AVPlayer 的 category 里，K/N 要额外 import，跑偏一次就是一发红；
 * 而 currentTime() 是主接口上的普通方法。0.5 秒的粒度做进度条和锁屏时间都够了。
 */
@OptIn(ExperimentalForeignApi::class)
class MusicPlayer {

    private val player = AVPlayer()
    private var generation = 0L
    private var wantsPlayback = false
    var seeking = false
        private set

    /**
     * startAt > 0 只出现在"接着上次放"：刚落 item 时它的 status 还是 unknown，
     * 这时候直接 seek 有可能被吞掉，结果从头开始放、界面却显示着上次的位置。
     * 所以带完成回调 seek，落定了再 rate=1，出声就是从那个位置开始的。
     */
    fun play(url: String, startAt: Double = 0.0) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        generation++
        wantsPlayback = true
        seeking = false
        activate()
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = nsUrl))
        if (startAt > 0.5) {
            seek(startAt)
        } else {
            player.rate = 1.0f
        }
    }

    fun stop() {
        generation++
        wantsPlayback = false
        seeking = false
        player.rate = 0.0f
        player.replaceCurrentItemWithPlayerItem(null)
    }

    fun pause() {
        wantsPlayback = false
        player.rate = 0.0f
    }

    fun resume() {
        wantsPlayback = true
        activate()
        if (!seeking) player.rate = 1.0f
    }

    fun seek(seconds: Double) {
        val request = ++generation
        seeking = true
        player.seekToTime(CMTimeMakeWithSeconds(seconds, 600)) { finished ->
            if (request == generation) {
                seeking = false
                if (finished && wantsPlayback) player.rate = 1.0f
            }
        }
    }

    /** 当前位置；还没开播就是 0。 */
    fun positionSeconds(): Double {
        val seconds = CMTimeGetSeconds(player.currentTime())
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return 0.0
        return seconds
    }

    /** 还没读到时长的返回 0（刚换 item 的头几百毫秒就是这样，调用方自己决定要不要显示）。 */
    fun durationSeconds(): Double {
        val item = player.currentItem ?: return 0.0
        val seconds = CMTimeGetSeconds(item.duration)
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return 0.0
        return seconds
    }

    private fun activate() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }
}
