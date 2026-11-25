package com.andaxin.feature.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/*
 * 均衡器的口径（十段、十四个预设、前置增益与每段增益的夹取、CSV 序列化），
 * 逐项对齐安卓 `music/src/main/java/com/ljyh/mei/playback/TenBandEqualizer.kt`：
 * 中心频率、预设名与每个预设的 (preamp, gains)、Custom 空列表、preamp ∈ [-12, 6]、
 * gain ∈ [-12, 12]、每段 Q = 1.4（安卓 biquad 里 alpha = sin(w0)/(2*1.4)）。
 *
 * 为什么不直接把安卓那份搬进来：那份牵着 media3 的 AudioProcessor（ByteBuffer 管线），
 * 而 iOS 的落点是 MTAudioProcessingTap 拿到的 AudioBufferList —— 采样格式、声道排布、
 * 回调线程都不一样。**能共用的只有口径**，所以口径写这儿一份，两端各自照它算。
 */

/** 一段：中心频率（Hz）和它对外的标题。顺序就是第 1..10 段。 */
data class EqBand(val centerHz: Double, val title: String)

val EQ_BANDS = listOf(
    EqBand(31.25, "31 Hz"),
    EqBand(62.5, "62 Hz"),
    EqBand(125.0, "125 Hz"),
    EqBand(250.0, "250 Hz"),
    EqBand(500.0, "500 Hz"),
    EqBand(1_000.0, "1 kHz"),
    EqBand(2_000.0, "2 kHz"),
    EqBand(4_000.0, "4 kHz"),
    EqBand(8_000.0, "8 kHz"),
    EqBand(16_000.0, "16 kHz"),
)

/** 每段的 Q。安卓 ten-band 那台写死 1.4（不是 0.707 那种通用值），照抄。 */
const val EQ_BAND_Q: Double = 1.4

/** 增益夹取：每段 ±12 dB，前置 −12..+6 dB（安卓两边都这么夹）。 */
const val EQ_GAIN_LIMIT: Float = 12f
const val EQ_PREAMP_MIN: Float = -12f
const val EQ_PREAMP_MAX: Float = 6f

/** 预设：名字 + 前置增益 + 十段增益。Custom 的 gains 是空表（表示"跟用户手上那组走"）。 */
enum class EqPreset(val preamp: Float, val gains: List<Float>) {
    Flat(0f, listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    Acoustic(-4f, listOf(4f, 3f, 2f, 1f, 0f, 1f, 2f, 3f, 4f, 3f)),
    BassBoost(-6f, listOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
    Classical(-4f, listOf(5f, 4f, 3f, 0f, -2f, -2f, 0f, 3f, 4f, 5f)),
    Dance(-6f, listOf(6f, 5f, 2f, 0f, 0f, -3f, -4f, -4f, 0f, 0f)),
    Electronic(-5f, listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 5f, 6f)),
    HipHop(-5f, listOf(6f, 5f, 2f, 3f, -1f, -1f, 2f, -1f, 2f, 3f)),
    Jazz(-4f, listOf(4f, 3f, 2f, 2f, -2f, -2f, 0f, 2f, 3f, 4f)),
    Pop(-5f, listOf(-1f, 2f, 4f, 5f, 2f, -2f, -2f, -2f, -1f, -1f)),
    Rock(-5f, listOf(5f, 3f, -1f, -3f, -2f, 1f, 3f, 5f, 5f, 5f)),
    SpokenWord(-3f, listOf(-6f, -5f, -3f, 0f, 3f, 5f, 5f, 3f, 0f, -2f)),
    TrebleBoost(-6f, listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f)),
    Vocal(-4f, listOf(-2f, -3f, -3f, 1f, 4f, 5f, 4f, 2f, 0f, -2f)),

    /** 用户自己拖过任意一段之后就是它：gains 空着，界面和落点都用存下来的那组。 */
    Custom(0f, emptyList()),
}

/** 一份生效中的配置（开关 + 前置 + 十段）。默认关闭、全平，跟安卓 data class 的默认值一致。 */
data class EqConfig(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val gains: List<Float> = EqPreset.Flat.gains,
) {
    /** 十段都夹在 ±12 内、前置夹在 −12..+6 内；长度不对的整份退回 Flat。 */
    fun sanitized(): EqConfig {
        val g = gains.takeIf { it.size == EQ_BANDS.size }
            ?: EqPreset.Flat.gains
        return copy(
            preamp = preamp.coerceIn(EQ_PREAMP_MIN, EQ_PREAMP_MAX),
            gains = g.map { it.coerceIn(-EQ_GAIN_LIMIT, EQ_GAIN_LIMIT) },
        )
    }

    /** 全平 + 前置 0：落点可以整条旁路（安卓那边是 BYPASS_EPSILON 那一道）。 */
    fun isFlat(): Boolean = preamp == 0f && gains.all { it == 0f }

    /** 实际要落的那组增益：Custom 空表时退回 Flat，越界值夹掉。 */
    fun effectiveGains(): List<Float> = sanitized().gains
}

/** 存盘用的那行 CSV（安卓 `joinToString(",")` 的同一个格式，坏值逐项丢掉）。 */
fun encodeEqGains(gains: List<Float>): String = gains.joinToString(",")

/**
 * 读回存的那行 CSV。条目数不对就当没有（安卓 `takeIf { it.size == bands.size }`），
 * 调用方按预设/Flat 兜底。
 */
fun decodeEqGains(text: String?): List<Float>? =
    text?.split(',')
        ?.mapNotNull { it.trim().toFloatOrNull() }
        ?.takeIf { it.size == EQ_BANDS.size }

/** 预设名存盘/读回：认不出的名字退 [fallback]。 */
fun decodeEqPreset(name: String?, fallback: EqPreset = EqPreset.Flat): EqPreset =
    name?.let { raw -> EqPreset.entries.firstOrNull { it.name == raw } } ?: fallback

/**
 * 把预设落到一份配置上（对齐安卓 `applyPreset`）：
 * 非 Custom 的预设把 preamp 和十段一起换掉；Custom 只换名字、增益保持不动。
 */
fun EqConfig.withPreset(preset: EqPreset): EqConfig = when (preset) {
    EqPreset.Custom -> this
    else -> copy(preamp = preset.preamp, gains = preset.gains)
}

/**
 * 拖了第 [index] 段之后的状态（对齐安卓 `updateGain`）：预设自动变 Custom，
 * 增益夹在 ±12，其余段不动。
 */
fun EqConfig.withBandGain(index: Int, value: Float): EqConfig {
    if (index !in EQ_BANDS.indices) return this
    val next = effectiveGains().toMutableList()
    next[index] = value.coerceIn(-EQ_GAIN_LIMIT, EQ_GAIN_LIMIT)
    return copy(gains = next)
}

/**
 * 双二阶（peaking）系数，跟安卓 `BiquadCoefficients.peaking` 同一套公式：
 * amplitude = 10^(gain/40)，alpha = sin(w0)/(2Q)，按 RBJ cookbook 算。
 *
 * 返回 (b0, b1, b2, a1, a2) —— a0 已经除进每一项，落点直接逐样本差分即可：
 *   y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
 *
 * 采样率不够（fc >= 0.49*sr）或增益几乎为 0 时回旁路系数 (1,0,0,0,0)。
 */
fun eqPeakingCoefficients(centerHz: Double, gainDb: Float, sampleRate: Int): FloatArray {
    if (sampleRate <= 0 || centerHz <= 0.0 || centerHz >= sampleRate * 0.49 || abs(gainDb) < 0.0001f) {
        return floatArrayOf(1f, 0f, 0f, 0f, 0f)
    }
    val amplitude = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * PI * centerHz / sampleRate
    val alpha = sin(w0) / (2.0 * EQ_BAND_Q)
    val cosw0 = cos(w0)
    val a0 = 1.0 + alpha / amplitude
    return floatArrayOf(
        ((1.0 + alpha * amplitude) / a0).toFloat(),
        ((-2.0 * cosw0) / a0).toFloat(),
        ((1.0 - alpha * amplitude) / a0).toFloat(),
        ((-2.0 * cosw0) / a0).toFloat(),
        ((1.0 - alpha / amplitude) / a0).toFloat(),
    )
}

