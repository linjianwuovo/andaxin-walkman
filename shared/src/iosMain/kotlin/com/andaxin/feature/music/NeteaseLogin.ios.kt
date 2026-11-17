package com.andaxin.feature.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

/*
 * 网易云登录：把官方网页装进一个 WKWebView，人在里面登，登完从 cookie 仓库里把 MUSIC_U 捞出来。
 *
 * 为什么不自己画登录表单去调接口：网易的登录要 weapi/eapi 签名，签名的实现在那 8 个只发
 * arm64 Android 的 .so 里（Android 侧 music/src/main/jniLibs），iOS 跑不了。
 * 网页登录是网易自己签的名，我们只捡结果 —— 这一步绕开整个黑盒。
 *
 * MUSIC_U 是 HttpOnly 的：document.cookie 读不到它，只能问 WKWebsiteDataStore 的 cookie 仓库。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun NeteaseLoginScreen(onDone: (String) -> Unit, onCancel: () -> Unit) {
    val dataStore = remember { WKWebsiteDataStore.defaultDataStore() }
    var hint by remember { mutableStateOf("正在打开网易云…登录完成后会自动返回") }

    val webView = remember {
        WKWebView(
            frame = CGRectZero.readValue(),
            configuration = WKWebViewConfiguration().apply { websiteDataStore = dataStore },
        )
    }

    LaunchedEffect(Unit) {
        webView.loadRequest(NSURLRequest(NSURL.URLWithString("https://music.163.com/#/login")!!))
        while (true) {
            delay(800)
            val got = awaitMusicU(dataStore)
            if (!got.isNullOrBlank()) {
                hint = "已拿到登录凭据（MUSIC_U " + got.length + " 字符）"
                onDone(got)
                break
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        UIKitView(factory = { webView }, modifier = Modifier.fillMaxSize())
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            Text(text = hint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}


/** getAllCookies 是异步回调：必须用 Deferred 等它回来，不能在调用后同步读局部变量（那样永远是 null）。 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun awaitMusicU(dataStore: WKWebsiteDataStore): String? {
    val done = CompletableDeferred<String?>()
    dataStore.httpCookieStore.getAllCookies { cookies ->
        done.complete(
            cookies?.filterIsInstance<NSHTTPCookie>()
                ?.firstOrNull { it.name == "MUSIC_U" }
                ?.value
        )
    }
    return done.await()
}
