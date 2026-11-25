package com.andaxin.feature.music

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.setAudioMix
import platform.AVFoundation.tracks
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreMedia.CMItemCountVar
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PostEffects
import platform.posix.memcpy
import platform.posix.uint32_tVar

/*
 * iOS 的均衡器落点：MTAudioProcessingTap。
 *
 * 安卓那边是 media3 的 AudioProcessor（解码后一段 ByteBuffer 过一遍）；iOS 的 AVPlayer
 * 不给 PCM 管线，唯一能挂自己代码的地方就是 AudioMix 上的 tap —— process 回调里拿到
 * AudioBufferList，就地改。链路：
 *   AVPlayerItem → AVMutableAudioMix → AVMutableAudioMixInputParameters → tap
 * 算法本身在 logicMain 的 TenBandEqualizerKernel（JVM 逐样本钉，见同名测试文件）。
 *
 * 回调跑在**音频线程**上，三条纪律：
 *  1. 不在回调里分配大对象：处理缓冲是一块复用的 FloatArray，不够才长；
 *  2. 不碰 Compose、不加锁等主线程：配置读的是本对象上一个不可变快照；
 *  3. 认不出的采样格式原样放行 —— 宁可没效果，也不要在一段陌生字节上乱算。
 *
 * 这一层的正确性只能靠真机听感 + 音频线程不崩来证明，模拟器截图看不出来，
 * 所以账本里它始终标 implemented_pending_native。
 */
@OptIn(ExperimentalForeignApi::class)
object IosEqualizerTap {

    /** 正在生效的配置快照（音频线程读它；写它的是主线程的 [attach] / [update]）。 */
    private var config: EqConfig = EqConfig()

    /** 内核：prepare 里拿到真实采样率/声道数时重建，之后按配置原地 reconfigure。 */
    private var kernel: TenBandEqualizerKernel? = null

    /** 处理缓冲（复用；音频线程专用）。 */
    private var scratch = FloatArray(0)

    /** 挂到某个 item 上；配置关着就直接把 mix 摘掉（不挂 = 零开销）。 */
    fun attach(item: AVPlayerItem, config: EqConfig) {
        this.config = config.sanitized()
        if (!this.config.enabled) {
            item.setAudioMix(null)
            return
        }
        val track = item.tracks.filterIsInstance<AVPlayerItemTrack>()
            .firstNotNullOfOrNull { it.assetTrack as? AVAssetTrack }
            ?: return
        val tap = createTap() ?: return
        val params = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(track)
        params.setAudioTapProcessor(tap)
        val mix = AVMutableAudioMix()
        mix.setInputParameters(listOf(params))
        item.setAudioMix(mix)
    }

    /** 用户改了开关/预设/某一段：已挂上的 tap 下一次 process 就按新配置跑。 */
    fun update(config: EqConfig) {
        val next = config.sanitized()
        this.config = next
        val k = kernel ?: return
        if (!next.enabled) return
        k.reconfigure(k.sampleRate, k.channels, next)
    }

    /**
     * 造一个 tap；系统拒绝（OSStatus != 0）回 null，调用方就当没有均衡器。
     *
     * callbacks 是 C 结构体（CStructVar），只能用 alloc 拿一块再逐字段填；
     * 直接 `MTAudioProcessingTapCallbacks(version = …)` 这种具名构造在 K/N 里不存在
     * （它唯一的构造参数是 rawPtr）。version 必须填，少了系统直接拒绝。
     */
    private fun createTap(): MTAudioProcessingTapRef? = memScoped {
        val callbacks = alloc<MTAudioProcessingTapCallbacks>()
        callbacks.version = kMTAudioProcessingTapCallbacksVersion_0
        callbacks.clientInfo = null
        callbacks.init = staticCFunction { _, _, _ -> }
        callbacks.finalize = staticCFunction { _ -> }
        callbacks.prepare = staticCFunction { _, _, format: CPointer<AudioStreamBasicDescription>? ->
            if (format != null) {
                val sr = format.pointed.mSampleRate.toInt().takeIf { it >= 8_000 } ?: 44_100
                val ch = format.pointed.mChannelsPerFrame.toInt().takeIf { it in 1..8 } ?: 2
                val k = kernel
                if (k == null) kernel = TenBandEqualizerKernel(sr, ch, config)
                else k.reconfigure(sr, ch, config)
            }
        }
        callbacks.unprepare = staticCFunction { _ -> }
        callbacks.process = staticCFunction { tap, frames, flags, bufferList, framesOut, flagsOut ->
            processInto(tap, frames, flags, bufferList, framesOut, flagsOut)
        }
        val out = alloc<CPointerVar<cnames.structs.opaqueMTAudioProcessingTap>>()
        val status = MTAudioProcessingTapCreate(
            allocator = null,
            callbacks = callbacks.ptr,
            flags = kMTAudioProcessingTapCreationFlag_PostEffects,
            tapOut = out.ptr,
        )
        if (status != 0) null else out.value
    }

    /**
     * process 回调的实体：先让系统把这一批源音频填进 bufferList，再逐块就地改。
     *
     * 两种排布都可能：mNumberBuffers == 每块声道数 → 非交错（每块一个声道）；
     * 否则按交错处理。mDataByteSize 是字节数，按 float32 折算。
     */
    private fun processInto(
        tap: MTAudioProcessingTapRef?,
        frames: Long,
        @Suppress("UNUSED_PARAMETER") flags: UInt,
        bufferList: CPointer<AudioBufferList>?,
        framesOut: CPointer<CMItemCountVar>?,
        flagsOut: CPointer<uint32_tVar>?,
    ) {
        if (tap == null || bufferList == null) return
        // 参数顺序照 dump-metadata：tap, numberFrames, bufferListInOut, flagsOut, timeRangeOut, numberFramesOut。
        val status = MTAudioProcessingTapGetSourceAudio(
            tap = tap,
            numberFrames = frames,
            bufferListInOut = bufferList,
            flagsOut = flagsOut,
            timeRangeOut = null,
            numberFramesOut = framesOut,
        )
        if (status != 0) return
        val k = kernel ?: return
        if (k.bypass) return
        val list = bufferList.pointed
        val count = list.mNumberBuffers.toInt()
        if (count <= 0) return
        val buffers = list.mBuffers
        for (i in 0 until count) {
            val ab = buffers[i]
            val data = ab.mData?.reinterpret<ByteVar>() ?: continue
            val ch = ab.mNumberChannels.toInt().coerceAtLeast(1)
            val total = (ab.mDataByteSize.toInt() / Float.SIZE_BYTES).coerceAtLeast(0)
            if (total == 0) continue
            val floats = scratchFor(total)
            floats.usePinned { pinned -> memcpy(pinned.addressOf(0), data, ab.mDataByteSize.toULong()) }
            if (count == ch) {
                for (c in 0 until ch) k.processPlanar(c, floats, total / ch)
            } else {
                k.processInterleaved(floats, total / ch)
            }
            floats.usePinned { pinned -> memcpy(data, pinned.addressOf(0), ab.mDataByteSize.toULong()) }
        }
    }

    private fun scratchFor(size: Int): FloatArray {
        if (scratch.size < size) scratch = FloatArray(size)
        return scratch
    }
}