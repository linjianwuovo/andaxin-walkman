package io.github.neoruaa.glasmio.core

import android.os.Build

// Mi 11 (venus / M2011K2C / M2011K2G) on HyperOS renders the nativeControlLens
// RuntimeShader + mip-atlas pipeline as a solid black block (HANDOFF.md:61;
// 11-02 ThemeScreen slider note). OPPO and every other device are unaffected.
// Forcing the library's tested reduceTransparency path keeps the water-drop
// indicator as an opaque capsule and disables every lens shader on these phones.
val nativeControlLensUnsupported: Boolean = run {
    val maker = Build.MANUFACTURER.orEmpty()
    val xiaomi = maker.equals("Xiaomi", ignoreCase = true) ||
        maker.equals("Redmi", ignoreCase = true) ||
        maker.equals("POCO", ignoreCase = true)
    val device = Build.DEVICE.orEmpty()
    val model = Build.MODEL.orEmpty()
    val mi11 = device.equals("venus", ignoreCase = true) ||
        model.equals("M2011K2C", ignoreCase = true) ||
        model.equals("M2011K2G", ignoreCase = true)
    xiaomi && mi11 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
