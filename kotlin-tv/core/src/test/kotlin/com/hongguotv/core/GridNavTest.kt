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
    fun `down on last row and up on first row return minus one`() {
        val c = coords(12, 6)
        assertEquals(-1, GridNav.neighbor(c, 6, +1))
        assertEquals(-1, GridNav.neighbor(c, 0, -1))
    }

    @Test
    fun `partial last row still reachable from same column above`() {
        // 8 张卡 6 列：row1 只有 col0-1
        val c = coords(8, 6)
        assertEquals(6, GridNav.neighbor(c, 0, +1))
        assertEquals(7, GridNav.neighbor(c, 1, +1))
        assertEquals(-1, GridNav.neighbor(c, 2, +1))  // col2 下一行不存在
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
