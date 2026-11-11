package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 网格焦点导航坐标计算：收藏页「本机/云端」分节标题行的上下移动是本次修复的根因场景。 */
class GridNavTest {

    private fun coords(count: Int, cols: Int, sections: Set<Int> = emptySet()) =
        GridNav.coordinates(count, cols) { it in sections }

    @Test
    fun `plain grid without sections rows and columns`() {
        val c = coords(12, 6)
        assertEquals(0 to 0, c[0])
        assertEquals(0 to 5, c[5])
        assertEquals(1 to 0, c[6])
        assertEquals(1 to 5, c[11])
    }

    @Test
    fun `section header inserts an empty row and resets column`() {
        // 3 张本机 + 标题 + 12 张云端，6 列：本机占 row0 col0-2，标题占 row1，云端从 row2 col0
        val c = coords(15, 6, sections = setOf(3))
        assertEquals(0 to 2, c[2])
        assertEquals(2 to 0, c[3])
        assertEquals(2 to 5, c[8])
        assertEquals(3 to 0, c[9])
    }

    @Test
    fun `down crosses section header seamlessly`() {
        val c = coords(15, 6, sections = setOf(3))
        assertEquals(3, GridNav.neighbor(c, 0, +1))   // 本机 row0 col0 ↓ → 云端 row2 col0（跳过标题行）
        assertEquals(4, GridNav.neighbor(c, 1, +1))
        assertEquals(12, GridNav.neighbor(c, 6, +1))  // 云端 row2 col3 ↓ → row3 col3
    }

    @Test
    fun `up crosses section header seamlessly`() {
        val c = coords(15, 6, sections = setOf(3))
        assertEquals(0, GridNav.neighbor(c, 3, -1))   // 云端首行 ↑ → 本机 row0
        assertEquals(-1, GridNav.neighbor(c, 0, -1))  // 全网格首行没有上一行
    }

    @Test
    fun `up from any cloud column reaches the short local row instead of the header`() {
        // 用户报的 bug：本机 1 张（row0 只占 col0）+ 标题 + 云端 4 张（row2 col0-3），
        // 云端 col1-3 ↑ 旧版找不到同列卡 → -1 → 跳页头按钮；新版应夹到本机行最近的列（只有 col0）
        val c = coords(5, 6, sections = setOf(1))
        assertEquals(0, GridNav.neighbor(c, 1, -1))   // 云端 col0 ↑ → 本机 col0（同列）
        assertEquals(0, GridNav.neighbor(c, 2, -1))   // 云端 col1 ↑ → 本机 col0（夹列）
        assertEquals(0, GridNav.neighbor(c, 3, -1))
        assertEquals(0, GridNav.neighbor(c, 4, -1))
    }

    @Test
    fun `up clamps to nearest column of a shorter upper section`() {
        // 本机 3 张（col0-2）+ 云端 6 张：云端 row2 col0-5，col3-5 ↑ 夹到本机行最近的列
        val c = coords(9, 6, sections = setOf(3))
        assertEquals(2, GridNav.neighbor(c, 6, -1))   // 云端 col3 ↑ → 本机 col2
        assertEquals(2, GridNav.neighbor(c, 7, -1))   // 云端 col4 ↑ → 本机 col2
        assertEquals(2, GridNav.neighbor(c, 8, -1))   // 云端 col5 ↑ → 本机 col2
        // 云端两行时，row3 的 col0-2 ↑ 仍走同列命中（row2 是满行）
        val c2 = coords(12, 6, sections = setOf(3))
        assertEquals(3, GridNav.neighbor(c2, 9, -1))  // row3 col0 ↑ → row2 col0 同列
        assertEquals(4, GridNav.neighbor(c2, 10, -1)) // row3 col1 ↑ → row2 col1 同列
        assertEquals(5, GridNav.neighbor(c2, 11, -1)) // row3 col2 ↑ → row2 col2 同列
    }

    @Test
    fun `down on last row and up on first row return minus one`() {
        val c = coords(12, 6)
        assertEquals(-1, GridNav.neighbor(c, 6, +1))
        assertEquals(-1, GridNav.neighbor(c, 0, -1))
    }

    @Test
    fun `down clamps to nearest column of partial last row`() {
        // 8 张卡 6 列：row1 只有 col0-1。col2-5 ↓ 不再静默吃键，夹到最近的列
        val c = coords(8, 6)
        assertEquals(6, GridNav.neighbor(c, 0, +1))
        assertEquals(7, GridNav.neighbor(c, 1, +1))
        assertEquals(7, GridNav.neighbor(c, 2, +1))   // col2 ↓ → row1 col1（夹列）
        assertEquals(7, GridNav.neighbor(c, 5, +1))   // col5 ↓ → row1 col1（夹列）
    }

    @Test
    fun `ragged section start closes previous partial row`() {
        // 本机 3 张不满行：标题行必须在第 1 行，不能和卡片挤在同一行
        val c = coords(5, 6, sections = setOf(3))
        val rows = c.map { it.first }.distinct().sorted()
        assertEquals(listOf(0, 2), rows)  // row1 是标题行，没有卡片
    }

    @Test
    fun `neighbor on empty or out of range index is safe`() {
        val c = coords(4, 2)
        assertEquals(-1, GridNav.neighbor(c, -1, +1))
        assertEquals(-1, GridNav.neighbor(c, 99, +1))
        assertEquals(-1, GridNav.neighbor(c, 0, 0))
    }
}
