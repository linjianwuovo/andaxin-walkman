package com.andaxin.shell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andaxin.shell.IosShell

/**
 * 底栏那五格，顺序与 Android 版 RootShell.kt:153 的 Shelf 枚举一致：
 * Home / Timetable / Message / Notice / Me。
 */
internal enum class Shelf(val label: String, val icon: TabIcon) {
    Home("首页", TabIcon.House),
    Timetable("课表", TabIcon.Calendar),
    Message("消息", TabIcon.Chat),
    Notice("公告", TabIcon.Bell),
    Me("我的", TabIcon.Person),
}

@Composable
fun AndaxinApp() {
    AndaxinTheme {
        var shelf by remember { mutableStateOf(Shelf.Home) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackdrop)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (shelf) {
                    Shelf.Home -> HomeScreen()
                    Shelf.Timetable -> TimetableScreen()
                    Shelf.Message -> MessageScreen()
                    Shelf.Notice -> NoticeScreen()
                    Shelf.Me -> MeScreen(IosShell.version())
                }
            }
            NowPlayingBar()
            ShelfBar(selected = shelf, onSelect = { shelf = it })
        }
    }
}

/**
 * 底栏。选中格：点睛色 + 实心图标；未选中：灰 + 空心。
 * Android 版那层圆角玻璃底（GlasmioTabBar）等 glasmio 跨端化之后再换上来。
 */
@Composable
private fun ShelfBar(selected: Shelf, onSelect: (Shelf) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.bg0.copy(alpha = 0.96f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Ink.stroke, Ink.stroke))),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Shelf.entries.forEach { item ->
                val on = item == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(item) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabGlyph(
                        icon = item.icon,
                        color = if (on) Ink.accent else Ink.text3,
                        filled = on,
                        modifier = Modifier.size(GlyphSize),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        fontSize = 10.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Ink.accent else Ink.text3,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * 迷你播放条。Android 版这颗是播放器胶囊（点开进封面页），iOS 这一版先把它摆在
 * 底栏上方当占位：形状、层级、留白先对，播放引擎（AVAudioEngine）在下一轮接。
 */
@Composable
private fun NowPlayingBar() {
    var playing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.card.copy(alpha = 0.92f))
            .border(1.dp, Ink.stroke, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(Ink.accentSoft, Ink.mint.copy(alpha = 0.35f)))),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("还没有在放的曲子", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
            Text("播放引擎待接（AVAudioEngine）", fontSize = 11.sp, color = Ink.text3)
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (playing) Ink.accent else Ink.cardHi)
                .clickable { playing = !playing },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(if (playing) 9.dp else 10.dp)
                    .clip(if (playing) RoundedCornerShape(2.dp) else CircleShape)
                    .background(if (playing) Ink.bg0 else Ink.text2),
            )
        }
    }
}
