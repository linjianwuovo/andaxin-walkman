package com.andaxin.feature.music

import kotlin.math.pow

/*
 * 十段均衡器的**逐样本内核**（纯 Kotlin，不碰 AVFoundation / MTAudioProcessingTap）。
 *
 * 为什么把它单独拎出来：iOS 那边真正处理音频的回调长在 C 函数指针里
 * （MTAudioProcessingTap 的 process 回调），线程是音频线程、缓冲区是裸指针 ——
 * 那一段没法在 JVM 上测，写错了只会在真机上表现为"声音发毛/爆音"，而且没人看得见。
 * 所以真正的算法全放这儿：可以拿 JVM 跑、可以逐样本对拍，平台那层只剩
 * "把指针包成 FloatArray、调这里、再写回去"。
 *
 * 算法与安卓 `TenBandEqualizerProcessor` 同一套：每声道十个双二阶（RBJ peaking，Q=1.4），
 * 串起来跑，前面乘一次前置增益（线性）。系数由 [eqPeakingCoefficients] 给（同一份口径）。
 */

/** 一段的运行态：系数 + 两级历史（直接 I 型转置，最省状态）。 */
private class Biquad(private val c: FloatArray) {
    var x1 = 0f; private set
    var x2 = 0f; private set
    var y1 = 0f; private set
    var y2 = 0f; private set

    /** 旁路系数（1,0,0,0,0）时直接原样返回，省两次乘加。 */
    private val passthrough = c[0] == 1f && c[1] == 0f && c[2] == 0f && c[3] == 0f && c[4] == 0f

    fun process(x: Float): Float {
        if (passthrough) return x
        val y = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}

/** 一个声道的十段链 + 它的前置增益。 */
private class ChannelChain(config: EqConfig, coefficients: List<FloatArray>) {
    private val filters = coefficients.map { Biquad(it) }
    private val preamp = linearFromDb(config.preamp)

    fun process(x: Float): Float {
        var v = x * preamp
        for (f in filters) v = f.process(v)
        return v
    }

    fun reset() = filters.forEach { it.reset() }
}

/**
 * 一整套运行态：声道数 + 十段系数。配置变了就 [reconfigure]（系数重算、历史清零，
 * 与安卓 setAudioProcessor 换系数时一致 —— 不清历史会在换预设那一下留一声"咔"）。
 */
class TenBandEqualizerKernel(
    var sampleRate: Int,
    channels: Int,
    config: EqConfig,
) {
    var channels: Int = channels
        private set
    private var config: EqConfig = config.sanitized()
    private var chains: List<ChannelChain> = build()

    /** 真正的处理开关：关着或全平就整段旁路（安卓 BYPASS_EPSILON 那一道）。 */
    val bypass: Boolean get() = !config.enabled || config.isFlat()

    private fun build(): List<ChannelChain> {
        val gains = config.effectiveGains()
        val coeffs = EQ_BANDS.mapIndexed { i, band ->
            eqPeakingCoefficients(band.centerHz, gains[i], sampleRate)
        }
        return List(channels) { ChannelChain(config, coeffs) }
    }

    /** 换配置 / 换采样率 / 换声道数。三者任一变了就整份重建。 */
    fun reconfigure(sampleRate: Int, channels: Int, config: EqConfig) {
        val next = config.sanitized()
        if (sampleRate == this.sampleRate && channels == this.channels && next == this.config) return
        this.sampleRate = sampleRate
        this.channels = channels
        this.config = next
        chains = build()
    }

    fun reset() = chains.forEach { it.reset() }

    /**
     * 处理一段**交错**样本（AVFoundation 给的就是交错 float32）。
     * 越界的声道数按现有链走 —— 多出来的声道不处理（宁可原样出声，也不要越界崩在音频线程上）。
     */
    fun processInterleaved(buffer: FloatArray, frames: Int) {
        if (bypass) return
        val usable = frames.coerceAtMost(buffer.size / channels)
        for (frame in 0 until usable) {
            val base = frame * channels
            for (ch in 0 until channels) {
                val i = base + ch
                buffer[i] = chains[ch].process(buffer[i])
            }
        }
    }

    /**
     * 处理一段**非交错**样本（MTAudioProcessingTap 在 mNumberBuffers == 声道数 时就是这种排布，
     * 每块一个声道）。
     */
    fun processPlanar(channel: Int, buffer: FloatArray, frames: Int) {
        if (bypass) return
        if (channel !in 0 until channels) return
        val chain = chains[channel]
        val n = frames.coerceAtMost(buffer.size)
        for (i in 0 until n) buffer[i] = chain.process(buffer[i])
    }
}

/** dB → 线性倍数。前置增益在落点上就是乘这一次。 */
fun linearFromDb(db: Float): Float = 10.0.pow((db / 20.0).toDouble()).toFloat()