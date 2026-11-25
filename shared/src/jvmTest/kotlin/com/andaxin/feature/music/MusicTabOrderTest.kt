package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicTabOrderTest {
    private val base = listOf("首页", "发现", "播客", "音乐库", "搜索", "设置")

    @Test fun moveUpShiftsOnlyTheRequestedTab() {
        assertEquals(
            listOf("首页", "播客", "发现", "音乐库", "搜索", "设置"),
            moveMusicTabOrder(base, "播客", 1),
        )
    }

    @Test fun moveDownShiftsOnlyTheRequestedTab() {
        assertEquals(
            listOf("首页", "发现", "音乐库", "播客", "搜索", "设置"),
            moveMusicTabOrder(base, "播客", 3),
        )
    }

    @Test fun outOfRangeIndexClampsWithoutDroppingItems() {
        assertEquals(
            listOf("发现", "播客", "音乐库", "搜索", "设置", "首页"),
            moveMusicTabOrder(base, "首页", 99),
        )
    }

    @Test fun invalidOrRepeatedOrInPlaceEditsReturnNull() {
        assertNull(moveMusicTabOrder(base, "", 1))
        assertNull(moveMusicTabOrder(base, "不存在", 1))
        assertNull(moveMusicTabOrder(listOf("首页", "首页", "设置"), "首页", 2))
        assertNull(moveMusicTabOrder(base, "播客", 2))
        assertNull(moveMusicTabOrder(emptyList(), "首页", 0))
    }

    @Test fun twoMovesComposeToTheExpectedFinalOrder() {
        val first = moveMusicTabOrder(base, "播客", 1)!!
        val second = moveMusicTabOrder(first, "首页", 1)!!
        assertEquals(listOf("播客", "首页", "发现", "音乐库", "搜索", "设置"), second)
    }
}
