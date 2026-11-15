/*
 * iOS 壳模块。第一版只装"能开"这一件事：
 * 不挂 android 目标、不引 Compose —— 这条流水线要的只有一个能链进 ipa 的 framework，
 * 少一层插件就少一处编不过的地方。业务界面的平移（music 那 492 个 kt、app 那 112 个）
 * 是后面几轮的事，不在这版里。
 *
 * 静态 framework（isStatic = true）：侧载链路（Sideloadly / 爱思）在 Windows 上重签时
 * 不用再处理嵌套可执行文件；Xcode 侧因此必须 embed: false，只链不嵌。
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
}
