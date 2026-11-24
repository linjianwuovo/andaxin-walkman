package com.ljyh.mei

import android.content.Intent
import com.ljyh.mei.widget.MusicWidgets

/**
 * 桌面小组件的深链中转。
 *
 * 小组件点击的 PendingIntent 指向 [MainActivity]（上游同样如此），但真正会消费
 * `ACTION_OPEN_*` 的是渲染 [MusicScreen] 的那一处。上游两者是同一个类，这里被拆成
 * 壳 + 屏，所以留一个模块内的口袋：壳在 onCreate/onNewIntent 读一次塞进来，
 * 屏侧消费之后作废。
 */
object WidgetDeepLink {
    internal val pending = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String?>?>(null)

    private val openActions = setOf(
        MusicWidgets.ACTION_OPEN_PLAYER,
        MusicWidgets.ACTION_OPEN_LYRICS,
        MusicWidgets.ACTION_OPEN_HISTORY,
        MusicWidgets.ACTION_OPEN_DAILY,
        MusicWidgets.ACTION_OPEN_FAVORITES,
        MusicWidgets.ACTION_OPEN_STATS,
        MusicWidgets.ACTION_OPEN_SEARCH,
        MusicWidgets.ACTION_OPEN_CHARTS,
        MusicWidgets.ACTION_OPEN_LIBRARY,
        MusicWidgets.ACTION_OPEN_PLAYLISTS,
        MusicWidgets.ACTION_OPEN_COMMENTS,
        MusicWidgets.ACTION_OPEN_CONTENT,
    )

    /** 只认小组件的跳转动作；返回是否消费了这条 intent。 */
    internal fun read(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action !in openActions) return false
        pending.value = action to intent.getStringExtra(MusicWidgets.EXTRA_MEDIA_ID)
        intent.action = null
        return true
    }
}
