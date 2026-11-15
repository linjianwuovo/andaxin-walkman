/*
 * iOS 侧共享模块。
 *
 * 这一版开始装界面了：Compose Multiplatform 的 iOS 渲染（Skia）+ 一套按 Android 版
 * 导航结构画出来的真实界面。android 目标仍然不挂 —— 这个模块只服务 iOS，
 * 少一层 Android 插件就少一处 AGP 与 Compose 版本打架的地方。
 *
 * Compose 依赖走显式坐标而不是 org.jetbrains.compose 插件：那套插件在 AGP 9 的
 * 构建里有已知冲突（同仓 app 侧也用 androidx BOM 显式坐标），直引 artifact 更稳。
 *
 * 静态 framework（isStatic = true）：侧载链路（Sideloadly / 爱思）在 Windows 上重签时
 * 不用再处理嵌套可执行文件；Xcode 侧因此必须 embed: false，只链不嵌。
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    iosArm64 {
        binaries {
            framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }
    iosSimulatorArm64 {
        binaries {
            framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            implementation("org.jetbrains.compose.ui:ui:1.12.0")
        }
    }
}
