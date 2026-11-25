package com.andaxin.feature.music

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * DSP 内核的钉子。这层之所以值得逐样本钉：iOS 上它跑在音频线程里，
 * 出错的表现只会是"声音发毛/爆音/一边没声"，在模拟器和截图里都看不出来。
 */
class TenBandEqualizerKernelTest {

    private fun kernel(config: EqConfig, sampleRate: Int = 44100, channels: Int = 2) =
        TenBandEqualizerKernel(sampleRate, channels, config)

    @Test
    fun 关着或全平时整段旁路_样本一个字节都不动() {
        val off = kernel(EqConfig(enabled = false, preamp = 6f, gains = EqPreset.Rock.gains))
        val buf = FloatArray(64) { it * 0.01f }
        val copy = buf.copyOf()
        off.processInterleaved(buf, 32)
        assertEquals(copy.toList(), buf.toList())
        assertTrue(off.bypass)

        val flat = kernel(EqConfig(enabled = true, preamp = 0f, gains = EqPreset.Flat.gains))
        val buf2 = FloatArray(64) { it * 0.01f }
        flat.processInterleaved(buf2, 32)
        assertEquals(copy.toList(), buf2.toList())
        assertTrue(flat.bypass)
    }

    @Test
    fun 全零输入_输出仍是全零_且不产生NaN() {
        val k = kernel(EqConfig(enabled = true, gains = EqPreset.Rock.gains, preamp = -5f))
        val buf = FloatArray(200)
        k.processInterleaved(buf, 100)
        assertTrue(buf.all { it == 0f })
    }

    @Test
    fun 前置增益单独生效_全零段也能放大() {
        // 安卓 refreshConfiguration 的旁路条件是
        //   !enabled || |preamp| < EPS || 十段 |gain| 全 < EPS
        // 也就是"十段全 0 但前置非 0"**不旁路**（前置要生效），这个语义必须一致。
        val flatNoPreamp = kernel(EqConfig(enabled = true, preamp = 0f, gains = EqPreset.Flat.gains.toList()))
        val flatWithPreamp = kernel(EqConfig(enabled = true, preamp = 6f, gains = EqPreset.Flat.gains.toList()))
        assertTrue(flatNoPreamp.bypass, "全平且前置 0 应旁路")
        assertTrue(!flatWithPreamp.bypass, "前置非 0 时不该旁路（前置要生效）")
        // 前置 +6 dB 对常量 0.25 的稳态输出应约为 ×1.995
        val buf = FloatArray(64) { 0.25f }
        flatWithPreamp.processInterleaved(buf, 32)
        val tail = buf[32]
        assertTrue(abs(tail - 0.25f * 1.9953f) < 0.01f, "前置 +6 dB 之后应约 0.4988，实际 $tail")
        // 前置的线性换算是 10^(db/20)
        assertTrue(abs(linearFromDb(0f) - 1f) < 1e-6f)
        assertTrue(abs(linearFromDb(6f) - 1.9953f) < 1e-3f)
        assertTrue(abs(linearFromDb(-6f) - 0.5012f) < 1e-3f)
        assertTrue(abs(linearFromDb(-12f) - 0.2512f) < 1e-3f)
    }

    @Test
    fun 提升低频段_让低频正弦变响_而高频基本不动() {
        val sr = 44100
        val bass = EqConfig(enabled = true, preamp = 0f, gains = EqPreset.BassBoost.gains)
        val kLow = kernel(bass, sr, channels = 1)
        val kHigh = kernel(bass, sr, channels = 1)

        fun rmsAfter(kernel: TenBandEqualizerKernel, hz: Double): Float {
            val n = sr // 一秒
            val buf = FloatArray(n) { (0.5 * sin(2 * Math.PI * hz * it / sr)).toFloat() }
            kernel.processInterleaved(buf, n.coerceAtMost(buf.size))
            // 跳过前 4096 帧的瞬态，只算后半段能量
            var sum = 0.0
            var count = 0
            for (i in 8192 until n) { sum += buf[i] * buf[i]; count++ }
            return Math.sqrt(sum / count).toFloat()
        }

        val lowIn = 0.5 / Math.sqrt(2.0)
        val lowOut = rmsAfter(kLow, 60.0)
        val highOut = rmsAfter(kHigh, 12000.0)
        // BassBoost 在 60–125 Hz 抬 5～6 dB（约 ×2），12 kHz 那一段是 0 dB（不动）
        assertTrue(lowOut > lowIn * 1.5, "低频该被明显抬起：in=$lowIn out=$lowOut")
        assertTrue(abs(highOut - lowIn) < lowIn * 0.35, "高频不该被抬：in=$lowIn out=$highOut")
    }

    @Test
    fun 衰减段真的变小_且不会自激发散() {
        val sr = 44100
        val cut = EqConfig(enabled = true, preamp = 0f, gains = List(10) { -12f })
        val k = kernel(cut, sr, channels = 1)
        val n = sr
        val buf = FloatArray(n) { (0.5 * sin(2 * Math.PI * 1000.0 * it / sr)).toFloat() }
        k.processInterleaved(buf, n)
        // 输出必须有限（IIR 不稳会飙到 Inf/NaN）
        assertTrue(buf.all { !it.isNaN() && !it.isInfinite() }, "输出出现 NaN/Inf = 滤波器不稳")
        val peak = buf.drop(8192).maxOf { abs(it) }
        assertTrue(peak < 0.5f, "全段 −12 dB 之后峰值应明显小于输入 0.5，实际 $peak")
    }

    @Test
    fun 交错与非交错两种排布处理结果一致() {
        val cfg = EqConfig(enabled = true, preamp = -2f, gains = EqPreset.Vocal.gains)
        val n = 2048
        val left = FloatArray(n) { (0.4 * sin(2 * Math.PI * 440.0 * it / 44100.0)).toFloat() }
        val right = FloatArray(n) { (0.4 * sin(2 * Math.PI * 3000.0 * it / 44100.0)).toFloat() }

        val planar = kernel(cfg, channels = 2)
        val pl = left.copyOf(); val pr = right.copyOf()
        planar.processPlanar(0, pl, n)
        planar.processPlanar(1, pr, n)

        val inter = kernel(cfg, channels = 2)
        val il = FloatArray(n * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        inter.processInterleaved(il, n)

        for (i in 0 until n) {
            assertTrue(abs(pl[i] - il[i * 2]) < 1e-5f, "左声道第 $i 帧：planar=${pl[i]} interleaved=${il[i * 2]}")
            assertTrue(abs(pr[i] - il[i * 2 + 1]) < 1e-5f, "右声道第 $i 帧：planar=${pr[i]} interleaved=${il[i * 2 + 1]}")
        }
    }

    @Test
    fun 换配置会清历史_不留上一份预设的余音() {
        val k = kernel(EqConfig(enabled = true, gains = EqPreset.BassBoost.gains))
        val buf = FloatArray(256) { 0.5f }
        k.processInterleaved(buf, 128)
        assertTrue(buf.any { it != 0.5f }, "BassBoost 应该改变了常量输入的稳态")
        k.reconfigure(44100, 2, EqConfig(enabled = true, gains = EqPreset.TrebleBoost.gains))
        // 清零历史之后，第一帧从零状态起跑：对同一段常量输入，前一两个样本应接近原始值
        val after = FloatArray(4) { 0.5f }
        k.processInterleaved(after, 2)
        assertTrue(abs(after[0] - 0.5f) < 0.35f, "换配置后第一帧应接近输入（历史已清），实际 ${after[0]}")
    }

    @Test
    fun 声道数不匹配时不会越界崩() {
        val k = kernel(EqConfig(enabled = true, gains = EqPreset.Rock.gains), channels = 2)
        // 给一段"宣称 8 声道、实际只有 4 个样本"的缓冲：不该抛，也不该越界写
        val buf = FloatArray(4)
        k.processInterleaved(buf, 8)
        assertTrue(buf.all { !it.isNaN() })
        // 越界声道号直接忽略
        k.processPlanar(5, buf, 4)
        assertTrue(buf.all { it == 0f })
    }

    @Test
    fun 采样率会真的进系数_48k与44k结果不同() {
        val cfg = EqConfig(enabled = true, gains = EqPreset.Rock.gains)
        val n = 1024
        val input = FloatArray(n) { (0.3 * sin(2 * Math.PI * 8000.0 * it / 48000.0)).toFloat() }
        val a = kernel(cfg, 44100, 1); val b = kernel(cfg, 48000, 1)
        val ba = input.copyOf(); val bb = input.copyOf()
        a.processInterleaved(ba, n); b.processInterleaved(bb, n)
        // 8 kHz 在两种采样率下的归一化频率不同，处理结果必须不同（证明 sr 真进了系数）
        assertTrue(ba.indices.any { abs(ba[it] - bb[it]) > 1e-4f })
    }
}