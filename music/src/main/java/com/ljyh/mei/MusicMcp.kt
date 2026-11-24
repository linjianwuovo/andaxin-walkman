package com.ljyh.mei

import android.app.Application
import com.ljyh.mei.mcp.McpService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * MCP 的宿主接线缝。
 *
 * McpService 是 internal（McpServer/MeiloXTools 全是），宿主模块注入不到类型。
 * 上游在自己的 AppContext 里 @Inject + onCreate 里 start()；这里改成同模块的
 * 公开函数：宿主在 Application.onCreate 里调一次 [startMusicMcp]，缝内用
 * EntryPoint 取出单例、塞进 [AppContext.mcpService]、再 start()。
 * 可见性问题与 [AppContext.attachSourceArtwork] 同一套路。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MusicMcpEntryPoint {
    fun mcp(): McpService
}

fun startMusicMcp(app: Application) {
    val mcp = EntryPointAccessors.fromApplication(app, MusicMcpEntryPoint::class.java).mcp()
    AppContext.mcpService = mcp
    mcp.start()
}
