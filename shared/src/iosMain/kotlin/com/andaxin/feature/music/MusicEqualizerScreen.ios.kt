package com.andaxin.feature.music

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/*
 * 「设置 → 均衡器」这一块（iOS）。
 *
 * 对齐安卓 `EqualizerSettings.kt` 的形状：一颗总开关、一排预设、十段竖向滑杆、一个复位。
 * 差别只在摆法 —— 安卓那边是 Glasmio 的卡片，这里按 iOS 这几屏惯用的 SectionTitle 排版；
 * 数值口径完全一致（都由 logicMain 的 EqualizerModel 给，不在这里另算一遍）。
 *
 * 落效的那一头是 IosEqualizerTap（MTAudioProcessingTap）：改这里就是改那一份配置，
 * 下一次音频回调就按新参数跑。所以这一屏本身能不能"听出效果"，必须在真机上验；
 * 截图只能证明控件在、能拨、能存住。
 */
@Composable
internal fun EqualizerSection() {
    var config by remember { mutableStateOf(IosEqualizerSettings.current()) }
    var preset by remember { mutableStateOf(IosEqualizerSettings.presetName()) }

    fun apply(next: EqConfig, nextPreset: EqPreset) {
        config = next.sanitized()
        preset = nextPreset
        IosEqualizerSettings.save(config, preset)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("十段均衡器", style = MaterialTheme.typography.bodyLarge)
            Text(
                "开着才处理声音；关掉等于不装这一段（对齐安卓的旁路）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = config.enabled,
            onCheckedChange = { on -> apply(config.copy(enabled = on), preset) },
            modifier = Modifier.testTag("music-eq-switch"),
        )
    }
    // 预设：横向一排 chip，最后一个 Custom 只在用户拖过滑杆之后才有意义。
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EqPreset.entries.forEach { p ->
            TextButton(
                onClick = { apply(config.withPreset(p), p) },
                modifier = Modifier.testTag("music-eq-preset-${p.name}"),
            ) {
                Text(
                    text = eqPresetLabel(p),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (p == preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "前置 ${fmtDb(config.preamp)} dB",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("music-eq-preamp"),
    )
    // 前置滑杆：−12..+6（安卓同区间）
    Slider(
        value = config.preamp,
        onValueChange = { v -> apply(config.copy(preamp = v), EqPreset.Custom) },
        valueRange = EQ_PREAMP_MIN..EQ_PREAMP_MAX,
        modifier = Modifier.fillMaxWidth().testTag("music-eq-preamp-slider"),
    )
    // 十段：每段一行，左边标题、中间滑杆、右边读数（手机竖屏放不下十根竖杆，
    // 安卓那边是竖向滑杆横排，这里改成横排十行 —— 手指在竖屏上更好点）
    EQ_BANDS.forEachIndexed { index, band ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(band.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
            Slider(
                value = config.effectiveGains()[index],
                onValueChange = { v -> apply(config.withBandGain(index, v), EqPreset.Custom) },
                valueRange = -EQ_GAIN_LIMIT..EQ_GAIN_LIMIT,
                modifier = Modifier.weight(1f).testTag("music-eq-band-$index"),
            )
            Text(
                fmtDb(config.effectiveGains()[index]),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(48.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = { apply(config.withPreset(EqPreset.Flat), EqPreset.Flat) },
            modifier = Modifier.testTag("music-eq-reset"),
        ) { Text("复位") }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "预设与增益都和安卓一致（十段：31 Hz 到 16 kHz，Q=1.4，每段 ±12 dB）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 预设的展示名：Custom 单独叫「自定义」，其余按枚举名配上中文。 */
private fun eqPresetLabel(preset: EqPreset): String = when (preset) {
    EqPreset.Flat -> "平直"
    EqPreset.Acoustic -> "原声"
    EqPreset.BassBoost -> "低音增强"
    EqPreset.Classical -> "古典"
    EqPreset.Dance -> "舞曲"
    EqPreset.Electronic -> "电子"
    EqPreset.HipHop -> "嘻哈"
    EqPreset.Jazz -> "爵士"
    EqPreset.Pop -> "流行"
    EqPreset.Rock -> "摇滚"
    EqPreset.SpokenWord -> "人声"
    EqPreset.TrebleBoost -> "高音增强"
    EqPreset.Vocal -> "演唱"
    EqPreset.Custom -> "自定义"
}

/** 一位小数带符号（"+3.0" / "-2.5"），两边读数口径一致。 */
private fun fmtDb(v: Float): String {
    val rounded = kotlin.math.round(v * 10f) / 10f
    val sign = if (rounded > 0f) "+" else ""
    return sign + rounded.toString()
}