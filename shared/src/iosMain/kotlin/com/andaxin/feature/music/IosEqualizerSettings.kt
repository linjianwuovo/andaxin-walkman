package com.andaxin.feature.music

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/*
 * 均衡器的本机存取（iOS）。
 *
 * 键名跟安卓 PreferenceKeys 里那四条一一对应，值格式也照抄（增益是 CSV 那行、
 * preset 是枚举名、enabled 是布尔）—— 两端各存各的，但口径与默认值必须一致。
 *
 * 读数一律 objectForKey + 自己判类型：XCUITest 用启动参数喂进来的是**字符串**
 * （argument domain 只存字符串），直接 boolForKey 会得到 false。
 * 这一课在 MusicStore 那边已经付过（见 5fbc5a6），这里照同一套写法来。
 */
object IosEqualizerSettings {

    private const val KEY_ENABLED = "music.equalizer.enabled"
    private const val KEY_PREAMP = "music.equalizer.preamp"
    private const val KEY_GAINS = "music.equalizer.gains"
    private const val KEY_PRESET = "music.equalizer.preset"

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    /** 现读一份配置（坏值各自夹取/退回默认，绝不抛）。 */
    fun current(): EqConfig {
        val enabled = when (val v = defaults.objectForKey(KEY_ENABLED)) {
            is NSNumber -> v.boolValue
            is Any -> when (v.toString().trim().lowercase()) {
                "1", "true", "yes" -> true
                else -> false
            }
            else -> false
        }
        val preamp = when (val v = defaults.objectForKey(KEY_PREAMP)) {
            is NSNumber -> v.floatValue
            is Any -> v.toString().trim().toFloatOrNull() ?: 0f
            else -> 0f
        }
        val gains = decodeEqGains(defaults.stringForKey(KEY_GAINS)) ?: EqPreset.Flat.gains
        return EqConfig(enabled = enabled, preamp = preamp, gains = gains).sanitized()
    }

    /** 当前选中的预设名（界面上那颗 chip 用它点亮）。 */
    fun presetName(): EqPreset = decodeEqPreset(defaults.stringForKey(KEY_PRESET))

    /** 整份落盘；顺手把已挂上的 tap 也推一遍（不必等下一次换歌才生效）。 */
    fun save(config: EqConfig, preset: EqPreset) {
        val clean = config.sanitized()
        defaults.setBool(clean.enabled, forKey = KEY_ENABLED)
        defaults.setObject(clean.preamp.toString(), forKey = KEY_PREAMP)
        defaults.setObject(encodeEqGains(clean.gains), forKey = KEY_GAINS)
        defaults.setObject(preset.name, forKey = KEY_PRESET)
        IosEqualizerTap.update(clean)
    }
}