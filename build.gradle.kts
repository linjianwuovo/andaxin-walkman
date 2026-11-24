plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // iOS 壳模块（shared）：根工程只登记不应用
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    // music 模块（MeiloX 源码）整套用 Hilt + KSP 织入
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
