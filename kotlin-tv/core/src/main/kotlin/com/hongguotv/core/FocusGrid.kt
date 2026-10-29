// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 货架式二维焦点网络：把「每行卡片数量」转换成每个位置的四个方向目标。
 *
 * 电视端固定三条规则，都是踩过的坑：
 * 1. 行内左右夹在行首 / 行尾原地不动，不跨行环绕——环绕会让用户在末位反复按键却看不到反馈。
 * 2. 上下移动保持列号；目标行更短时夹到该行最后一张，避免焦点被甩回第一张。
 * 3. 越过首行 / 末行返回 null，由调用方接到顶部导航或页脚，网格本身不感知外部控件。
 */
class FocusGrid(val rowSizes: List<Int>) {
    data class Cell(val row: Int,val col: Int)

    init {
        require(rowSizes.all { it>0 }) { "每行至少一张卡片，空行应由调用方过滤" }
    }

    val isEmpty get()=rowSizes.isEmpty()
    val lastRow get()=rowSizes.lastIndex

    fun left(row: Int,col: Int): Cell = Cell(row,(col-1).coerceIn(0,rowSizes[row]-1))
    fun right(row: Int,col: Int): Cell = Cell(row,(col+1).coerceIn(0,rowSizes[row]-1))

    fun up(row: Int,col: Int): Cell? = if(row<=0) null else Cell(row-1,col.coerceAtMost(rowSizes[row-1]-1))
    fun down(row: Int,col: Int): Cell? = if(row>=lastRow) null else Cell(row+1,col.coerceAtMost(rowSizes[row+1]-1))

    /** 把任意行列夹进合法范围，用于还原上次焦点位置。 */
    fun clamp(row: Int,col: Int): Cell {
        val r=row.coerceIn(0,lastRow)
        return Cell(r,col.coerceIn(0,rowSizes[r]-1))
    }
}
