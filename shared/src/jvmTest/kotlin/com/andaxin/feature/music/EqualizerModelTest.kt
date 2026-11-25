package com.andaxin.feature.music

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 均衡器口径的钉子。左边一列数字全是从安卓 `TenBandEqualizer.kt` 逐项抄过来的
 * （中心频率、十四个预设的 preamp/gains、±12 / −12..+6 的夹取、Q=1.4、CSV 格式），
 * 所以这份测试真正钉的是「两端口径没漂」这件事，不是「我这里算得对不对」。
 */
class EqualizerModelTest {

    @Test
    fun 十段中心频率与标题照抄安卓() {
        assertEquals(10, EQ_BANDS.size)
        assertEquals(
            listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0),
            EQ_BANDS.map { it.centerHz },
        )
        assertEquals(
            listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz"),
            EQ_BANDS.map { it.title },
        )
    }

    @Test
    fun 十四档预设的数值与安卓逐字相同() {
        assertEquals(14, EqPreset.entries.size)
        assertEquals(0f, EqPreset.Flat.preamp)
        assertTrue(EqPreset.Flat.gains.all { it == 0f })
        assertEquals(-6f, EqPreset.BassBoost.preamp)
        assertEquals(listOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f), EqPreset.BassBoost.gains)
        assertEquals(-3f, EqPreset.SpokenWord.preamp)
        assertEquals(listOf(-6f, -5f, -3f, 0f, 3f, 5f, 5f, 3f, 0f, -2f), EqPreset.SpokenWord.gains)
        assertEquals(-6f, EqPreset.TrebleBoost.preamp)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f), EqPreset.TrebleBoost.gains)
        // 除 Custom 外每档都必须是十段；Custom 是空表（"跟用户手上那组走"）。
        EqPreset.entries.filter { it != EqPreset.Custom }.forEach {
            assertEquals(10, it.gains.size, "${it.name} 的段数不是 10")
            assertTrue(it.gains.all { g -> abs(g) <= EQ_GAIN_LIMIT }, "${it.name} 有越界增益")
            assertTrue(it.preamp in EQ_PREAMP_MIN..EQ_PREAMP_MAX, "${it.name} 的 preamp 越界")
        }
        assertTrue(EqPreset.Custom.gains.isEmpty())
    }

    @Test
    fun 增益和前置按安卓的区间夹取() {
        val hot = EqConfig(enabled = true, preamp = 40f, gains = List(10) { 99f }).sanitized()
        assertEquals(EQ_PREAMP_MAX, hot.preamp)
        assertTrue(hot.gains.all { it == EQ_GAIN_LIMIT })
        val cold = EqConfig(preamp = -99f, gains = List(10) { -99f }).sanitized()
        assertEquals(EQ_PREAMP_MIN, cold.preamp)
        assertTrue(cold.gains.all { it == -EQ_GAIN_LIMIT })
    }

    @Test
    fun 段数不对的整份退回全平() {
        val wrong = EqConfig(preamp = 3f, gains = listOf(1f, 2f, 3f)).sanitized()
        assertEquals(EqPreset.Flat.gains, wrong.gains)
        assertEquals(3f, wrong.preamp)
    }

    @Test
    fun CSV_编码解码与原格式一致() {
        assertEquals("7.0,6.0,5.0", encodeEqGains(listOf(7f, 6f, 5f)))
        // 往返：十段的编码解回来必须一模一样（Android 存的就是这行 CSV）
        assertEquals(EqPreset.BassBoost.gains, decodeEqGains(encodeEqGains(EqPreset.BassBoost.gains)))
        assertEquals(List(10) { -3f }, decodeEqGains("  -3, -3, -3, -3, -3, -3, -3, -3, -3, -3 "))
        // 条目数不对 = 当作没有（安卓 takeIf { size == bands.size }）
        assertNull(decodeEqGains("1,2,3"))
        assertNull(decodeEqGains(""))
        assertNull(decodeEqGains(null))
        // 坏项被丢掉的同样凑不满十段
        assertNull(decodeEqGains("0,0,0,0,0,0,0,0,0,x"))
        assertEquals(List(10) { 0f }, decodeEqGains("0,0,0,0,0,0,0,0,0,0"))
    }

    @Test
    fun 预设名认不出就退回兜底() {
        assertEquals(EqPreset.BassBoost, decodeEqPreset("BassBoost"))
        assertEquals(EqPreset.Flat, decodeEqPreset("这不是预设"))
        assertEquals(EqPreset.Flat, decodeEqPreset(null))
        assertEquals(EqPreset.Rock, decodeEqPreset("不存在", EqPreset.Rock))
    }

    @Test
    fun 套预设会连前置一起换掉_Custom_不动增益() {
        val base = EqConfig(enabled = true, preamp = 1f, gains = List(10) { 2f })
        val rock = base.withPreset(EqPreset.Rock)
        assertEquals(-5f, rock.preamp)
        assertEquals(EqPreset.Rock.gains, rock.gains)
        // Custom 只换名字：增益保持调用前那组
        assertEquals(base.gains, base.withPreset(EqPreset.Custom).gains)
        assertEquals(1f, base.withPreset(EqPreset.Custom).preamp)
    }

    @Test
    fun 拖一段只动那一段并夹在正负十二() {
        val cfg = EqConfig(gains = EqPreset.Flat.gains)
        val up = cfg.withBandGain(0, 99f)
        assertEquals(12f, up.gains[0])
        assertTrue(up.gains.drop(1).all { it == 0f })
        val down = cfg.withBandGain(9, -99f)
        assertEquals(-12f, down.gains[9])
        // 越界索引整份不动
        assertEquals(cfg.gains, cfg.withBandGain(10, 5f).gains)
        assertEquals(cfg.gains, cfg.withBandGain(-1, 5f).gains)
    }

    @Test
    fun 全平判定() {
        assertTrue(EqConfig().isFlat())
        assertTrue(EqConfig(enabled = true).isFlat())
        assertTrue(!EqConfig(preamp = 1f).isFlat())
        assertTrue(!EqConfig(gains = EqPreset.Flat.gains.toMutableList().also { it[3] = 1f }).isFlat())
    }

    @Test
    fun 双二阶系数_旁路条件与公式() {
        // 增益为 0 / 采样率不够 / 频率越界 → 旁路系数
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f), eqPeakingCoefficients(1000.0, 0f, 44100).toList())
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f), eqPeakingCoefficients(1000.0, 6f, 0).toList())
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f), eqPeakingCoefficients(30000.0, 6f, 44100).toList())
        // 正经一段：b0>1（提增益时分子大于分母）、a1/a2 与 b1/b2 同号同值（RBJ 的 peaking 形状）
        val c = eqPeakingCoefficients(1000.0, 6f, 44100)
        assertTrue(c[0] > 1f, "提增益时 b0 应大于 1，实际 ${c[0]}")
        // 安卓那套 RBJ peaking：b1 与 a1 是同一个 −2cos(w0)/a0（分母相同），
        // b2 与 a2 只在 alpha 项上差一个 amplitude，所以**不相等**，只能钉 b1 == a1。
        assertEquals(c[1], c[3])
        // 反向：等量衰减时 b0 < 1
        val cut = eqPeakingCoefficients(1000.0, -6f, 44100)
        assertTrue(cut[0] < 1f, "衰减时 b0 应小于 1，实际 ${cut[0]}")
        // 中心频率处的直流/奈奎斯特都该稳住（系数有限、没有 NaN）
        assertTrue(c.all { !it.isNaN() && !it.isInfinite() })
    }

    @Test
    fun 直流增益为零_证明peaking不改变整体电平() {
        // peaking 滤波器的直流增益 = (b0+b1+b2)/(1+a1+a2)，应为 1（因为它只抬一段、不改整体）。
        listOf(1f, 6f, -6f, 12f).forEach { db ->
            val c = eqPeakingCoefficients(1000.0, db, 44100)
            val h0 = (c[0] + c[1] + c[2]) / (1f + c[3] + c[4])
            assertTrue(abs(h0 - 1f) < 1e-3f, "gain=$db dB 时直流增益应为 1，实际 $h0")
        }
    }
}