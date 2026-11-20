// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuEmojiTest {
    @Test fun `single code is replaced by its emoji`() {
        assertEquals("😄", DanmakuEmoji.display("[大笑]"))
    }

    @Test fun `consecutive repeated codes are each replaced`() {
        assertEquals("😍😍😍", DanmakuEmoji.display("[爱慕][爱慕][爱慕]"))
    }

    @Test fun `unknown code is kept verbatim`() {
        assertEquals("[不存在的表情]", DanmakuEmoji.display("[不存在的表情]"))
        assertEquals("前缀[不存在的表情]后缀", DanmakuEmoji.display("前缀[不存在的表情]后缀"))
    }

    @Test fun `plain text without brackets is returned unchanged`() {
        val text = "百万富翁了真的好看"
        assertEquals(text, DanmakuEmoji.display(text))
    }

    @Test fun `surrounding text survives replacement`() {
        assertEquals("百万富翁了😍真的😄", DanmakuEmoji.display("百万富翁了[爱慕]真的[大笑]"))
        assertEquals("门给关上呀🤦🤦🤦", DanmakuEmoji.display("门给关上呀[捂脸][捂脸][捂脸]"))
    }

    @Test fun `code lookup is case insensitive`() {
        assertEquals("🐶", DanmakuEmoji.display("[Doge]"))
        assertEquals("🐶", DanmakuEmoji.display("[doge]"))
        assertEquals("👌", DanmakuEmoji.display("[OK]"))
        assertEquals("😲", DanmakuEmoji.display("[What]"))
    }

    @Test fun `unclosed bracket is kept verbatim`() {
        assertEquals("[大笑", DanmakuEmoji.display("[大笑"))
    }

    @Test fun `codes seen in live traffic are all replaced`() {
        // These five codes were missing from the initial guess set and only showed up in real traffic.
        assertEquals("😭", DanmakuEmoji.display("[哭]"))
        assertEquals("😆", DanmakuEmoji.display("[爽]"))
        assertEquals("😠", DanmakuEmoji.display("[怒]"))
        assertEquals("🌹", DanmakuEmoji.display("[送花]"))
        assertEquals("👀", DanmakuEmoji.display("[盯]"))
    }

    @Test fun `image sticker placeholder is dropped`() {
        assertEquals("还是躲在水里最自在", DanmakuEmoji.display("还是躲在水里\uFFFC最自在"))
        assertEquals("", DanmakuEmoji.display("\uFFFC"))
    }

    @Test fun `sticker placeholder inside bracketed text still drops`() {
        assertEquals("好看😄", DanmakuEmoji.display("好看\uFFFC[大笑]"))
    }

    @Test fun `codes from the 2026-11-18 rank drama probe are all replaced`() {
        // 1169 条真实弹幕探针发现：这五个码此前显示成裸的「[xx]」
        assertEquals("🥺", DanmakuEmoji.display("[没看够]"))
        assertEquals("❓", DanmakuEmoji.display("[什么]"))
        assertEquals("💕", DanmakuEmoji.display("[送心]"))
        assertEquals("🥺", DanmakuEmoji.display("[委屈]"))
        assertEquals("😢", DanmakuEmoji.display("[快哭了]"))
    }

    @Test fun `raw emojis beyond the tv font are swapped to older shapes`() {
        // 电视(Android 11)只带 Emoji 13.0 字体，13.1+ 的字符会画成豆腐块，必须换成旧字形
        assertEquals("😱", DanmakuEmoji.display("\uD83E\uDEE8"))       // 🫨(15.0) → 😱
        assertEquals("🤌", DanmakuEmoji.display("\uD83E\uDEF0"))       // 🫰(14.0) → 🤌
        assertEquals("🥲", DanmakuEmoji.display("\uD83E\uDD79"))       // 🥹(14.0) → 🥲
        assertEquals("❤️", DanmakuEmoji.display("\uD83E\uDEF6"))       // 🫶(14.0) → ❤️
        assertEquals("好😱", DanmakuEmoji.display("好\uD83E\uDEE8"))   // 混排也能换
    }

    @Test fun `expand reports unknown codes for log collection`() {
        val (text, unknown) = DanmakuEmoji.expand("[没看够][再来一次]")
        assertEquals("🥺[再来一次]", text)
        assertEquals(listOf("再来一次"), unknown)
        assertEquals("普通文本" to emptyList<String>(), DanmakuEmoji.expand("普通文本"))
    }
}
