// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesTest {
    @Test fun stripsMarkdownAndBulletMarkers() {
        val raw = "## 修复\n- **自动更新**：清单恢复 -android8\n* 另一条\n\n`代码`"
        assertEquals("· 自动更新：清单恢复 -android8\n· 另一条\n· 代码", UpdateNotes.summarize(raw))
    }

    @Test fun foldsWhenTooManyItems() {
        val raw = (1..9).joinToString("\n") { "- 第 $it 条" }
        val text = UpdateNotes.summarize(raw)
        assertEquals(7, text.lines().size)
        assertTrue(text.endsWith("…共 9 条更新，完整说明见发布页"))
        assertTrue(text.contains("第 6 条"))
        assertFalse(text.contains("第 7 条"))
    }

    @Test fun clipsLongLineAndKeepsCount() {
        val long = "修复".repeat(80)
        val text = UpdateNotes.summarize("- $long")
        assertEquals(1, text.lines().size)
        assertTrue(text.startsWith("· 修复"))
        assertTrue(text.endsWith("…"))
        assertTrue(text.length <= 2 + UpdateNotes.DEFAULT_MAX_CHARS)
    }

    @Test fun handlesCrlfBlankLinesAndEmptyInput() {
        assertEquals("· A\n· B", UpdateNotes.summarize("- A\r\n\r\n- B\r\n"))
        assertEquals("", UpdateNotes.summarize(""))
        assertEquals("", UpdateNotes.summarize("\r\n  \n"))
    }

    @Test fun respectsCustomLimits() {
        val raw = (1..5).joinToString("\n") { "- 条目${it}号" }
        val text = UpdateNotes.summarize(raw, maxItems = 2, maxChars = 3)
        assertEquals("· 条目…\n· 条目…\n…共 5 条更新，完整说明见发布页", text)
        assertEquals("", UpdateNotes.summarize(raw, maxItems = 0))
    }
}
