package com.hongguotv.core

/**
 * 电视网格焦点导航的纯计算：把「displayed 下标序列 + 偶尔插入的整行分节标题」
 * 映射成 (行,列) 坐标，再按「同列、上一/下一视觉行」找目标。
 *
 * 旧算法 `index±cols` 假设卡片均匀成行，遇到分节标题（本机/云端双区）会错列、
 * 甚至在区边界找不到目标导致按 ↓ 毫无反应。这里把标题行视作无卡片的空行，
 * 邻行查找时跳过空行，实现跨区无缝上下移动。
 */
object GridNav {

    /** 每张卡片的视觉坐标 (row,col)。sectionStart(i)=true 表示卡片 i 前有一个独占一行的分节标题。 */
    fun coordinates(count: Int, cols: Int, sectionStart: (Int) -> Boolean): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(count.coerceAtLeast(0))
        var row = 0
        var col = 0
        for (i in 0 until count) {
            if (i > 0 && sectionStart(i)) {
                if (col != 0) row++   // 收掉前一段未满的行
                row++                 // 标题独占一行（该行无卡片）
                col = 0
            }
            out += row to col
            col++
            if (col == cols) { col = 0; row++ }
        }
        return out
    }

    /**
     * 从 index 出发沿 dir（+1 下一行 / -1 上一行）找「同列最近的有卡片行」。
     * 中间的空行（分节标题行）直接跳过；找不到（已是首行/末行）返回 -1。
     */
    fun neighbor(coords: List<Pair<Int, Int>>, index: Int, dir: Int): Int {
        if (index !in coords.indices || dir == 0) return -1
        val (row, col) = coords[index]
        val maxRow = coords.maxOfOrNull { it.first } ?: return -1
        var target = row + dir
        while (target in 0..maxRow) {
            val hit = coords.indexOfFirst { it.first == target && it.second == col }
            if (hit >= 0) return hit
            target += dir   // 空行（分节标题）跳过继续找
        }
        return -1
    }
}
