// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 繁简转换引擎：词典打包在 resources/opencc/，纯 JVM 可测。 */
class ChineseTextTest {
    @Test fun `单字转换`() {
        assertEquals("頭髮", ChineseText.toTraditional("头发"))
        assertEquals("簡體", ChineseText.toTraditional("简体"))
        assertEquals("头", ChineseText.toSimplified("頭"))
    }

    @Test fun `词组优先避免单字歧义`() {
        // 「皇后」逐字转换会得到「皇後」（错字），词组表必须先命中保护
        assertEquals("皇后", ChineseText.toTraditional("皇后"))
        // 「之後」正确：单字「后」→「後」
        assertEquals("之後", ChineseText.toTraditional("之后"))
        // 「干」多音歧义由词组表兜：干杯→乾杯、干活→幹活
        assertEquals("乾杯", ChineseText.toTraditional("干杯"))
        assertEquals("幹活", ChineseText.toTraditional("干活"))
    }

    @Test fun `幂等 繁体输入原样返回`() {
        val s = "母憑子貴後被皇家寵上天"
        assertEquals(s, ChineseText.toTraditional(s))
        // 简体输入转繁后再转一次不变
        val t = ChineseText.toTraditional("母凭子贵后被皇家宠上天")
        assertEquals(t, ChineseText.toTraditional(t))
    }

    @Test fun `混合内容 英文数字接口占位不受影响`() {
        assertEquals("1080P高清", ChineseText.toTraditional("1080P高清"))
        assertEquals("v1_abc123", ChineseText.toSimplified("v1_abc123"))
        assertEquals("", ChineseText.toTraditional(""))
    }

    @Test fun `TWVariants 异体规范化`() {
        // 僞（繁体异体）→ 偽（台湾正体）
        assertEquals("偽", ChineseText.toTraditional("伪"))
        assertEquals("偽", ChineseText.toTraditional("僞"))
    }

    @Test fun `长句简介`() {
        val src = "陈极一行人深入西天佛境，周旋金蝉获授《浮屠镇世经》，融半佛半魔根基铸成浮屠金身。"
        val want = "陳極一行人深入西天佛境，周旋金蟬獲授《浮屠鎮世經》，融半佛半魔根基鑄成浮屠金身。"
        assertEquals(want, ChineseText.toTraditional(src))
    }

    @Test fun `搜索词繁转简`() {
        assertEquals("谁让这个文盲修仙", ChineseText.toSimplified("誰讓這個文盲修仙"))
        assertEquals("修仙", ChineseText.toSimplified("修仙"))
    }

    @Test fun `性能 200字简介毫秒级`() {
        val src = "陈极一行人深入西天佛境，周旋金蝉获授《浮屠镇世经》，融半佛半魔根基铸成浮屠金身。".repeat(5)
        val start = System.nanoTime()
        ChineseText.toTraditional(src)
        val ms = (System.nanoTime() - start) / 1_000_000
        org.junit.Assert.assertTrue("转换耗时 ${ms}ms 超预期", ms < 100)
    }
}
