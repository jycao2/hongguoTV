// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「一句话开播」的标题匹配：宁可不播，也不要把无关推荐当成目标剧。 */
class TitleMatchTest {

    private fun series(title: String) = Series(id = title, title = title)

    @Test
    fun `完全一致优先于靠前的不相关结果`() {
        val items = listOf(series("1000集让我留下来了"), series("黄石第五季"))
        assertEquals(1, TitleMatch.best("黄石第五季", items))
    }

    @Test
    fun `标题包含关键词就算命中`() {
        val items = listOf(series("谁让这文盲修仙的第六季"))
        assertEquals(0, TitleMatch.best("文盲修仙", items))
    }

    @Test
    fun `说了第几季但库里只有正片也算命中`() {
        val items = listOf(series("黄石"))
        assertEquals(0, TitleMatch.best("黄石第五季", items))
    }

    @Test
    fun `书名号和空格不影响匹配`() {
        val items = listOf(series("《末日 重生》"))
        assertEquals(0, TitleMatch.best("末日重生", items))
    }

    /** 实测踩过的坑：主站对搜不到的词返回热门推荐，旧逻辑照单全收就播了别的剧。 */
    @Test
    fun `对不上就返回负一不兜底`() {
        val items = listOf(series("1000集让我留下来了"), series("重启六零岁月"))
        assertEquals(-1, TitleMatch.best("黄石第五季", items))
    }

    @Test
    fun `单字关键词不猜`() {
        assertEquals(-1, TitleMatch.best("看", listOf(series("看门狗"))))
    }

    @Test
    fun `空列表返回负一`() {
        assertEquals(-1, TitleMatch.best("黄石第五季", emptyList()))
    }
}
