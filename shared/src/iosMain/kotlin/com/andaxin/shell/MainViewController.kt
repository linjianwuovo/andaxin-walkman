package com.andaxin.shell

import androidx.compose.ui.window.ComposeUIViewController
import com.andaxin.shell.ui.AndaxinApp
import platform.UIKit.UIViewController

/**
 * Swift 侧的入口：`MainViewControllerKt.MainViewController()`。
 *
 * 全部界面都在 commonMain 的 Compose 里，这里只负责把它包成一个 UIViewController
 * 交给 UIKit 的 window —— 和 Android 那边 setContent { } 是同一件事的两种说法。
 */
fun MainViewController(): UIViewController = ComposeUIViewController { AndaxinApp() }
