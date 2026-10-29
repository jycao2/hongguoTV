package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class FocusGridTest {
    @Test fun `horizontal moves stop at row edges instead of wrapping`() {
        val grid=FocusGrid(listOf(3,4))
        assertEquals(FocusGrid.Cell(0,0),grid.left(0,0))
        assertEquals(FocusGrid.Cell(0,2),grid.right(0,2))
        assertEquals(FocusGrid.Cell(0,1),grid.right(0,0))
        assertEquals(FocusGrid.Cell(0,1),grid.left(0,2))
    }
    @Test fun `vertical keeps the column when the neighbour row is long enough`() {
        val grid=FocusGrid(listOf(4,4,4))
        assertEquals(FocusGrid.Cell(0,2),grid.up(1,2))
        assertEquals(FocusGrid.Cell(2,2),grid.down(1,2))
    }
    @Test fun `vertical clamps to the shorter row instead of jumping to its first card`() {
        val grid=FocusGrid(listOf(6,2))
        // 第 1 行只有 2 张，从第 4 列下来应落在最后一张而不是第 0 张
        assertEquals(FocusGrid.Cell(1,1),grid.down(0,4))
        // 反过来从短行往上，列号保持 1，不会因为长行更长就改变列
        assertEquals(FocusGrid.Cell(0,1),grid.up(1,1))
        assertEquals(FocusGrid.Cell(1,1),grid.down(0,5))
    }
    @Test fun `crossing the first or last row leaves the grid`() {
        val grid=FocusGrid(listOf(5,5))
        assertNull(grid.up(0,0))
        assertNull(grid.down(grid.lastRow,0))
    }
    @Test fun `single row grid only allows vertical exit`() {
        val grid=FocusGrid(listOf(4))
        assertNull(grid.up(0,2))
        assertNull(grid.down(0,2))
        assertEquals(FocusGrid.Cell(0,3),grid.right(0,2))
    }
    @Test fun `clamp restores an out of range position after rows shrink`() {
        val grid=FocusGrid(listOf(4,2))
        assertEquals(FocusGrid.Cell(1,1),grid.clamp(5,9))
        assertEquals(FocusGrid.Cell(0,0),grid.clamp(-3,-8))
    }
    @Test fun `empty grid is reported without throwing`() {
        val grid=FocusGrid(emptyList())
        assertTrue(grid.isEmpty)
        assertEquals(-1,grid.lastRow)
    }
}
