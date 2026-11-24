package com.ljyh.mei

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 原来这里是 MeiloX 自己的 Application（@HiltAndroidApp + Coil 的 SingletonImageLoader.Factory）。
 * 并进安大信之后一个 APK 只能有一个 Application，那个位置归宿主的 AndaXinApp，
 * 所以这里退成一个只放 Context 的口袋：启动时由宿主塞进来，其余 30 多处
 * `AppContext.instance.xxx` 的读法一行都不用改。
 *
 * 图片加载也不再由这里提供 —— 音乐在 MusicActivity 的 setContent 里已经
 * setSingletonImageLoaderFactory 过一份带网易 UA 的 ImageLoader。
 *
 * [attachSourceArtwork] 是给宿主留的一扇门：上游在 AppContext.onCreate 里直接调
 * SourceArtworkRegistry.attach(sourceArtwork)，而那个 attach 是 internal（只在 music
 * 模块内可见）。宿主 Application 在另一个 Gradle 模块里，调不到，也不想为了这一行
 * 去改上游文件，所以在这里包一层。
 */
object AppContext {
    lateinit var instance: Context

    /** 宿主经 startMusicMcp 注入的本地 MCP 服务；类型是 internal，字段也只能 internal，同模块读。 */
    internal lateinit var mcpService: com.ljyh.mei.mcp.McpService

    fun attachSourceArtwork(store: com.ljyh.mei.source.script.SourceArtworkStore) {
        com.ljyh.mei.source.script.SourceArtworkRegistry.attach(store)
    }

}

/**
 * 上游 AppContext 还承担 Coil 图片缓存的两个查询（`AppContext.instance.hasCachedImage(...)`
 * 这种读法）。宿主已注册 SingletonImageLoader factory，这里做成 Context 扩展转调同一份 loader。
 */
internal suspend fun Context.hasCachedImage(url: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            coil3.SingletonImageLoader.get(applicationContext).diskCache
                ?.openSnapshot(url)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

internal suspend fun Context.clearImageCache() {
    coil3.SingletonImageLoader.get(applicationContext).let { loader ->
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
