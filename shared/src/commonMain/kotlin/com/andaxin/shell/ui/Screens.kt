package com.andaxin.shell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 这一文件里所有列表内容都是**骨架数据**（写死的示例），不是真实接口。
 * 目的是先把版式、层级、留白、字号压到和 Android 版一致，让"iOS 版长什么样"
 * 这件事可以直接看；真实数据接进来是下一轮（Ktor + 登录态）的事。
 */

@Composable
private fun PageTitle(main: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)) {
        Text(main, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink.text)
        Spacer(Modifier.height(4.dp))
        Text(hint, fontSize = 12.sp, color = Ink.text3)
    }
}

@Composable
private fun CardBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(Ink.card.copy(alpha = 0.9f))
            .border(1.dp, Ink.stroke, shape)
            .padding(14.dp),
    ) { content() }
}

// ---------------------------------------------------------------- 首页

@Composable
internal fun HomeScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("下午好，林同学", "2026 年 11 月 15 日 · 第 12 教学周")

        CardBox(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("今天 · 3 节课", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
                    Spacer(Modifier.weight(1f))
                    Text("查看课表", fontSize = 11.sp, color = Ink.accent)
                }
                Spacer(Modifier.height(12.dp))
                ClassRow("08:00", "高等数学 A", "博学楼 305", Ink.accent)
                ClassRow("10:00", "数据结构", "实验楼 2-14", Ink.mint)
                ClassRow("14:00", "大学英语", "外语楼 108", Ink.amber)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("一卡通", "¥ 84.20", Ink.accent, Modifier.weight(1f))
            StatCard("待办", "3 件", Ink.amber, Modifier.weight(1f))
            StatCard("步数", "4,210", Ink.mint, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        CardBox(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.rose))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("关于 2026 年秋季学期选课的通知", fontSize = 13.sp, color = Ink.text)
                    Text("教务处 · 今天 09:12", fontSize = 11.sp, color = Ink.text3)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("继续听")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverTile("夜航", "陈婧霏", listOf(Ink.accentSoft, Ink.bg2))
            CoverTile("Faye", "王菲", listOf(Ink.mint.copy(alpha = 0.3f), Ink.bg2))
            CoverTile("橙月", "方大同", listOf(Ink.amber.copy(alpha = 0.3f), Ink.bg2))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ClassRow(time: String, name: String, place: String, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Spacer(Modifier.width(10.dp))
        Text(time, fontSize = 12.sp, color = Ink.text2, modifier = Modifier.width(46.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.text)
            Text(place, fontSize = 11.sp, color = Ink.text3)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    CardBox(modifier, RoundedCornerShape(16.dp)) {
        Column {
            Text(label, fontSize = 11.sp, color = Ink.text3)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun CoverTile(title: String, artist: String, colors: List<Color>) {
    Column(Modifier.width(104.dp)) {
        Box(
            Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(colors)),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink.text, maxLines = 1)
        Text(artist, fontSize = 10.sp, color = Ink.text3, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 课表

private val periods = listOf("08:00", "10:00", "12:00", "14:00", "16:00", "19:00")
private val weekdays = listOf("一", "二", "三", "四", "五")
private val timetable = mapOf(
    (0 to 0) to ("高等数学" to Ink.accent),
    (0 to 1) to ("数据结构" to Ink.mint),
    (0 to 4) to ("大学英语" to Ink.amber),
    (1 to 2) to ("体育" to Ink.rose),
    (2 to 0) to ("大学物理" to Ink.accent),
    (2 to 3) to ("形势与政策" to Ink.mint),
    (3 to 4) to ("数据结构" to Ink.mint),
    (4 to 1) to ("毛泽东概论" to Ink.amber),
)

@Composable
internal fun TimetableScreen() {
    Column(Modifier.fillMaxSize()) {
        PageTitle("课表", "第 12 周 · 点格子看详情（下一轮接）")
        Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 6.dp)) {
            weekdays.forEach { day ->
                Text(
                    day,
                    fontSize = 12.sp,
                    color = Ink.text2,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(periods.size) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        periods[row],
                        fontSize = 10.sp,
                        color = Ink.text3,
                        modifier = Modifier.width(40.dp).padding(top = 8.dp),
                    )
                    weekdays.forEachIndexed { col, _ ->
                        val cell = timetable[row to col]
                        Box(
                            Modifier
                                .weight(1f)
                                .height(62.dp)
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (cell != null) cell.second.copy(alpha = 0.16f) else Ink.card.copy(alpha = 0.5f))
                                .border(
                                    1.dp,
                                    if (cell != null) cell.second.copy(alpha = 0.4f) else Ink.stroke,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(6.dp),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (cell != null) {
                                Text(cell.first, fontSize = 10.sp, color = cell.second, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 消息

private data class MsgRow(val name: String, val snippet: String, val time: String, val unread: Boolean)

private val messages = listOf(
    MsgRow("辅导员 · 王老师", "下周三的班会改到 19:00，别迟到。", "10:24", true),
    MsgRow("宿舍管理", "503 的水表报修已派单，师傅下午上门。", "09:41", true),
    MsgRow("爱课堂", "你关注的《数据结构》上传了第 7 章课件。", "昨天", false),
    MsgRow("图书馆", "你借的《算法导论》将在 3 天后到期。", "昨天", false),
    MsgRow("校园卡", "11 月 12 日 食堂二楼 消费 ¥12.50。", "11-12", false),
)

@Composable
internal fun MessageScreen() {
    Column(Modifier.fillMaxSize()) {
        PageTitle("消息", "2 条未读")
        LazyColumn(Modifier.fillMaxSize()) {
            items(messages) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.verticalGradient(listOf(Ink.accentSoft, Ink.bg2))),
                        contentAlignment = Alignment.Center,
                    ) { Text(row.name.take(1), fontSize = 15.sp, color = Ink.text) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
                            if (row.unread) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.accent))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(row.snippet, fontSize = 11.sp, color = Ink.text2, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(row.time, fontSize = 10.sp, color = Ink.text3)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 公告

private data class Notice(val tag: String, val title: String, val date: String, val accent: Color)

private val notices = listOf(
    Notice("教务处", "关于 2026 年秋季学期选课的通知", "11-15", Ink.accent),
    Notice("学工部", "国家奖学金评审结果公示", "11-14", Ink.mint),
    Notice("后勤", "东区 3 号楼 11 月 16 日停水检修", "11-13", Ink.amber),
    Notice("保卫处", "校园电动车充电点迁移公告", "11-11", Ink.rose),
)

@Composable
internal fun NoticeScreen() {
    Column(Modifier.fillMaxSize()) {
        PageTitle("公告", "来自学校各部门")
        LazyColumn(Modifier.fillMaxSize()) {
            items(notices) { n ->
                CardBox(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(n.accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) { Text(n.tag, fontSize = 10.sp, color = n.accent) }
                            Spacer(Modifier.weight(1f))
                            Text(n.date, fontSize = 10.sp, color = Ink.text3)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(n.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.text)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 我的

private val meEntries = listOf(
    "个人信息" to "学籍、头像、紧急联系人",
    "我的申请" to "请假 / 报修 / 离校",
    "校园卡" to "余额与流水",
    "第二课堂" to "学分与活动",
    "成绩与考试" to "绩点、准考证",
    "宿舍" to "电费、报修进度",
    "运动校园" to "跑步记录",
    "设置" to "主题、通知、缓存",
)

@Composable
internal fun MeScreen(version: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(62.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(Ink.accentSoft, Ink.bg2))),
                contentAlignment = Alignment.Center,
            ) { Text("林", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink.text) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("林同学", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink.text)
                Spacer(Modifier.height(3.dp))
                Text("2024 级 · 计算机科学与技术 · 3 班", fontSize = 11.sp, color = Ink.text3)
            }
        }

        meEntries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (title, subtitle) ->
                    CardBox(Modifier.weight(1f)) {
                        Column {
                            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, fontSize = 10.sp, color = Ink.text3, maxLines = 2)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            "安大信 iOS " + version + " · 界面骨架，数据待接",
            fontSize = 10.sp,
            color = Ink.text3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
