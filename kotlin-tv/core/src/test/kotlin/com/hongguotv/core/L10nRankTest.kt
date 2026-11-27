package com.hongguotv.core
import org.junit.Assert.assertEquals
import org.junit.Test
class L10nRankTest {
    @Test fun `榜单标题与切换按钮`() {
        L10n.configure(L10n.EN)
        assertEquals("Comic drama trending", L10n.tr("漫剧热播榜"))
        assertEquals("Drama board", L10n.tr("看短剧榜"))
        assertEquals("Source: Hongguo comic drama trending", L10n.tr("来源：红果漫剧热播榜"))
    }
}
