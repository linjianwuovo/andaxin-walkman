package com.andaxin.data.local

import com.andaxin.data.Dashboard
import com.andaxin.data.DashboardFeed
import com.andaxin.data.MessageCategory
import com.andaxin.data.MessageFeed
import com.andaxin.data.MessageItem
import com.andaxin.data.NoticeBody
import com.andaxin.data.NoticeFeed
import com.andaxin.data.NoticeItem
import com.andaxin.data.TimetableFeed
import com.andaxin.data.TimetableSlot
import kotlinx.coroutines.delay

/*
 * 假数据源：让每一屏在没有抓包之前就能点、能看、能量帧率。
 * 这些值不是校方给的，是排版用的占位，界面上不许拿它当真实数据展示给用户。
 */

class LocalTimetable : TimetableFeed {
    override suspend fun week(weekOffset: Int): Result<List<TimetableSlot>> {
        delay(120)
        return Result.success(
            listOf(
                TimetableSlot("c1", "高等数学", "王老师", "教三 204", day = 1, startPeriod = 1, periodCount = 2),
                TimetableSlot("c2", "大学英语", "Ms. Li", "教二 111", day = 1, startPeriod = 3, periodCount = 2),
                TimetableSlot("c3", "数据结构", "张老师", "实验楼 A501", day = 2, startPeriod = 5, periodCount = 2),
                TimetableSlot("c4", "体育（跑步考勤）", "教练组", "南操场", day = 3, startPeriod = 1, periodCount = 1),
                TimetableSlot("c5", "毛概", "刘老师", "教一 302", day = 4, startPeriod = 7, periodCount = 2),
                TimetableSlot("c6", "操作系统", "陈老师", "实验楼 B203", day = 5, startPeriod = 2, periodCount = 2),
            )
        )
    }
}

class LocalMessages : MessageFeed {
    override suspend fun page(cursor: Int): Result<List<MessageItem>> {
        delay(120)
        return Result.success(
            listOf(
                MessageItem("m1", "课表变更通知", "周四毛概调至第 9-10 节", "10-19 09:12", true, MessageCategory.Academic),
                MessageItem("m2", "查寝提醒", "今晚 22:30 开始查寝", "10-18 21:40", true, MessageCategory.Dorm),
                MessageItem("m3", "跑步未达标", "本周还差 1.2 公里", "10-18 08:02", false, MessageCategory.Sports),
                MessageItem("m4", "系统消息", "校园网计费周期调整", "10-17 15:20", false, MessageCategory.System),
            )
        )
    }

    override suspend fun markRead(id: String) = Result.success(Unit)
}

class LocalNotices : NoticeFeed {
    override suspend fun list(): Result<List<NoticeItem>> {
        delay(120)
        return Result.success(
            listOf(
                NoticeItem("n1", "关于本学期期中考试安排的通知", "10-19", "教务处", null, "教学"),
                NoticeItem("n2", "图书馆开放时间调整", "10-18", "图书馆", null, "场馆"),
                NoticeItem("n3", "校园卡系统维护公告", "10-16", "信息化中心", null, "服务"),
            )
        )
    }

    /*
     * 正文故意写长：未登录时看到的就是这一条占位公告，长度要够滚不止一屏 ——
     * 二级页顶上那层「随滚动浮出的渐变模糊」得能滑出来才验收得到。
     * 接上真账号以后走的是校方那发正文，这段只影响占位数据。
     */
    override suspend fun detail(id: String) = Result.success(
        NoticeBody(
            title = "关于本学期期中考试安排的通知",
            publishedAt = "10-19 08:30",
            paragraphs = listOf(
                "各学院、各教学单位：",
                "为做好本学期期中考试的组织工作，现将有关事项通知如下，请各单位提前部署、按时落实。",
                "一、考试时间",
                "本学期期中考试定于第 9-10 教学周（10 月 27 日至 11 月 7 日）进行。公共基础课由教务处统一排定，专业课程由各学院在第 8 教学周结束前自行安排，并将安排表报教务处备案。",
                "每天考试时段为上午 8:30-10:30、下午 14:00-16:00、晚上 19:00-21:00 三场，同一学生同一时段不得安排两门课程。",
                "二、考场与座位",
                "考场统一设在教一、教二、教三楼，座位按学号顺序蛇形排列，考前三天在教务系统公布。考生须凭学生证和身份证入场，两证缺一不得参加考试。",
                "考试开始 15 分钟后不得入场，开考 30 分钟内不得离场。考场内一律关闭手机等电子设备，随身物品放在讲台一侧。",
                "三、命题与试卷",
                "试卷由课程组集体命题，实行教考分离的课程从题库抽题组卷。命题教师须在第 8 教学周周三前将试卷（A、B 卷各一份）及答案评分标准交学院教学秘书，由学院统一密封后送教务处印制。",
                "试卷印制期间严格执行保密规定，接触试卷的人员须签署保密承诺书，不得以任何形式向学生透露试题内容。",
                "四、监考安排",
                "请各教学单位按时提交监考名单，每个考场安排监考教师两名，其中至少一名为本课程任课教师。监考教师须提前 20 分钟到达考场，核对考生证件、宣读考场规则。",
                "监考期间不得擅离考场、不得使用手机，发现违纪行为应当场制止并如实记录，考试结束后连同考场记录表一并交学院。",
                "五、成绩录入",
                "考试结束后一周内完成阅卷，两周内在教务系统录入成绩。成绩提交后如需更正，须填写成绩更正申请单，经任课教师、系主任、教学院长签字后报教务处审核。",
                "六、缓考与补考",
                "因病或其他特殊原因不能参加考试的，须在考前提出书面申请并附相关证明，经学院审核同意后报教务处办理缓考手续，缓考安排在本学期末统一进行。无故缺考者按零分计，不予补考。",
                "七、其他事项",
                "考试期间各教学楼正常开放，图书馆延长开放至 22:30，为同学们复习提供便利。请同学们合理安排复习时间，诚信应考。",
                "如有疑问，请与教务处考试中心联系，办公地点：行政楼 305 室。",
                "特此通知。",
                "教务处",
                "2025 年 10 月 19 日",
            ),
            attachments = emptyList(),
        )
    )
}

class LocalDashboard(
    private val messages: MessageFeed,
    private val timetable: TimetableFeed,
) : DashboardFeed {
    override suspend fun snapshot(): Result<Dashboard> {
        val unread = messages.page(0).getOrDefault(emptyList()).count { it.unread }
        val lessons = timetable.week(0).getOrDefault(emptyList())
            .count { it.day == com.andaxin.core.schedule.Periods.today() }
        return Result.success(
            Dashboard(
                greeting = greetAt(java.time.LocalTime.now().hour),
                todayLessons = lessons,
                nextLesson = "第 3 节 · 大学英语 · 教二 111",
                unreadMessages = unread,
                dormCheckState = "今晚 22:30",
                // 跑量那两格没有可编的口径 —— 校方那发只给"一共/其中晨跑"，
                // 编一个"3.2 公里"会让人拿假数当真数看，所以给 null，界面上是"—"。
                runSemesterKm = null,
                runMorningKm = null,
                runWhy = null,
            )
        )
    }
}

/** 问候语跟表走，不跟着占位数据走：这一格是唯一一处本机就能算对的话。 */
internal fun greetAt(hour: Int) = when (hour) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}
