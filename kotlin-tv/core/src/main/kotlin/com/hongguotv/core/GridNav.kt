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
     * 从 index 出发沿 dir（+1 下一行 / -1 上一行）找目标卡片：
     * 走到第一个「有卡片」的视觉行，同列有卡就落同列；该行更短没有同列卡时，
     * 夹到该行最近的列（Leanback 网格的标准行为）。
     * 中间的空行（分节标题行）直接跳过；该方向已经没有任何卡片行返回 -1。
     *
     * 旧版只认「同列」，收藏页本机区只有 1 张卡（只占 col0）时，云端区 col1+ 往上
     * 全部找不到目标 → -1 → 焦点被丢给页头按钮，用户表现为「只有云端第一条能翻到本机」。
     */
    fun neighbor(coords: List<Pair<Int, Int>>, index: Int, dir: Int): Int {
        if (index !in coords.indices || dir == 0) return -1
        val (row, col) = coords[index]
        val maxRow = coords.maxOfOrNull { it.first } ?: return -1
        var target = row + dir
        while (target in 0..maxRow) {
            val rowCards = coords.withIndex().filter { it.value.first == target }
            if (rowCards.isNotEmpty()) {
                // 同列命中时距离为 0 自然胜出；并列时 minByOrNull 取列表靠前的（列号更小，与遍历顺序一致）
                return rowCards.minByOrNull { kotlin.math.abs(it.value.second - col) }!!.index
            }
            target += dir   // 空行（分节标题）跳过继续找
        }
        return -1
    }
}
