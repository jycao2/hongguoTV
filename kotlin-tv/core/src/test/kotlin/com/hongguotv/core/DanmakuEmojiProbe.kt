// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Test

/** 临时探针：拉真实弹幕，统计 DanmakuEmoji 表格没覆盖的「[码]」与电视字体画不出的新 emoji。 */
class DanmakuEmojiProbe {
    private fun emojiBlocks(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            val len = Character.charCount(c)
            if (c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0xFE00..0xFE0F || c in 0x1F1E6..0x1F1FF) {
                // 收集连续的 emoji 段（含 ZWJ 组合）
                val sb = StringBuilder()
                var j = i
                while (j < text.length) {
                    val cc = text.codePointAt(j)
                    if (cc in 0x1F000..0x1FAFF || cc in 0x2600..0x27BF || cc in 0xFE00..0xFE0F || cc == 0x200D || cc in 0x1F1E6..0x1F1FF || cc in 0x1F3FB..0x1F3FF) {
                        sb.appendCodePoint(cc); j += Character.charCount(cc)
                    } else break
                }
                out.add(sb.toString()); i = j
            } else i += len
        }
        return out
    }

    @Test fun probeRealDanmaku() {
        val repo = ContentRepository()
        val danmaku = DanmakuRepository()
        val titles = mutableListOf<String>()
        try { titles += repo.comicRanking().items.take(4).map { it.id } } catch (e: Exception) { println("comic rank failed: $e") }
        try { titles += repo.realRanking().items.take(4).map { it.id } } catch (e: Exception) { println("real rank failed: $e") }
        try { titles += repo.search("凡人修仙").items.take(1).map { it.id } } catch (e: Exception) { println("search failed: $e") }
        val unknown = LinkedHashMap<String, Int>()
        val emojis = LinkedHashMap<String, Int>()
        var samples = 0
        outer@ for (id in titles) {
            val detail = try { repo.detail(id) } catch (e: Exception) { println("detail failed: $e"); continue }
            val vid = detail.episodes.firstOrNull() ?: continue
            var start = 0L
            repeat(10) {
                val win = try { danmaku.window(id, vid, 240_000, start) } catch (e: Exception) { println("window failed: $e"); return@repeat }
                for (c in win.comments) {
                    samples++
                    val (_, codes) = DanmakuEmoji.expand(c.text)
                    codes.forEach { unknown[it] = (unknown[it] ?: 0) + 1 }
                    emojiBlocks(c.text).forEach { emojis[it] = (emojis[it] ?: 0) + 1 }
                }
                start = win.nextStartMs
            }
            if (samples > 600) break@outer
        }
        println("PROBE samples=$samples")
        println("PROBE unknown codes: ${unknown.entries.sortedByDescending { it.value }}")
        println("PROBE raw emojis: ${emojis.entries.sortedByDescending { it.value }.take(50)}")
    }
}
