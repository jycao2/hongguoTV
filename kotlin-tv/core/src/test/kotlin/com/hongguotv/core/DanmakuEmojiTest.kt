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
}
