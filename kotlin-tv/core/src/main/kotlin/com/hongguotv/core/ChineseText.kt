// SPDX-License-Identifier: GPL-3.0-only
// 繁简转换：OpenCC 1.4.2 词典（STPhrases/STCharacters/TWVariants 与 TSPhrases/TSCharacters，
// Apache-2.0），资源打包在 core/src/main/resources/opencc/。
//
// 为什么不走官方接口：官网对 Accept-Language / lang / locale / language 全部无响应（2026-11-28
// 实测五种变体逐字一致），官方 App 自身也没有繁体版（台湾上架语言栏只列简中与英文），
// 剧名/简介/弹幕/评论是运营与用户上传的简中原文，服务端没有翻译层。
// 因此内容繁化只能在客户端做显示层转换——发给平台的数据一律保持简中原文（搜索词先转简再发出）。
//
// 与 L10n 的分工：L10nDict/L10nEnglish 是「界面文案」的人工/自动译文整串替换；本文件只处理
// 「接口下发的动态内容」（剧名、简介、官方错误文案等）以及译文里残留的未译片段。
// 转换是幂等的：已是繁体的文本不在词典源字符集里，原样返回。
package com.hongguotv.core

object ChineseText {
    /** OpenCC 词典行：`源词\t映射1 映射2 …`，取第一个映射（OpenCC 语义：最常见）。 */
    private fun loadDict(vararg files: String): Map<String, String> {
        val out = HashMap<String, String>(64_000)
        for (file in files) {
            val stream =
                ChineseText::class.java.getResourceAsStream("/opencc/$file")
                    ?: error("缺少 OpenCC 词典资源 /opencc/$file")
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val clean = line.removePrefix("\uFEFF") // OpenCC txt 带 BOM，第一行的键要去掉
                    val tab = clean.indexOf('\t')
                    if (tab <= 0) continue
                    val value = clean.substring(tab + 1).substringBefore(' ')
                    if (value.isNotEmpty()) out.putIfAbsent(clean.substring(0, tab), value)
                }
            }
        }
        return out
    }

    /** 词典索引：按首字符分组的候选词（词长降序，供最大前向匹配）+ 需要转换的源字符快检集。 */
    private class Index(entries: Map<String, String>) {
        val map = entries
        val byFirstChar: Map<Char, List<String>> =
            entries.keys.groupBy { it[0] }.mapValues { (_, keys) -> keys.sortedByDescending { it.length } }
        // 快检集：词典全部源字符（含词组首字——「皇后」首字「皇」单字不变，但词组整体仍需转换）。
        val chars: Set<Char> = entries.keys.flatMap { it.toList() }.toSet()
    }

    @Volatile private var s2tBase: Index? = null
    @Volatile private var s2tTW: Map<Char, String>? = null
    @Volatile private var t2s: Index? = null

    private fun ensureS2T(): Pair<Index, Map<Char, String>> {
        val base = s2tBase ?: Index(loadDict("STPhrases.txt", "STCharacters.txt")).also { s2tBase = it }
        // TWVariants（OpenCC s2tw 链最后一段）的键全为单字异体，收敛成 Char→String
        val tw = s2tTW ?: loadDict("TWVariants.txt").entries.associate { it.key[0] to it.value }.also { s2tTW = it }
        return base to tw
    }

    private fun ensureT2S(): Index = t2s ?: Index(loadDict("TSPhrases.txt", "TSCharacters.txt")).also { t2s = it }

    /** 简体 → 台湾正体。词组优先（避免「皇后→皇後」这类单字歧义错字），再单字，再 TWVariants 异体规范化。 */
    @JvmStatic fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        val (base, tw) = ensureS2T()
        return convert(text, base, tw)
    }

    /** 繁体 → 简体。搜索词送出前转简（官方接口只认简中）。 */
    @JvmStatic fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        return convert(text, ensureT2S(), emptyMap())
    }

    private fun convert(text: String, index: Index, post: Map<Char, String>): String {
        // 快检：文本不含词典源字符（含 TWVariants 的繁体异体键）时原串返回，零开销兜住纯繁体/英文/数字的调用。
        var needs = false
        for (c in text) if (c in index.chars || c in post) { needs = true; break }
        if (!needs) return text
        val out = StringBuilder(text.length + text.length / 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val candidates = index.byFirstChar[c]
            var matched: String? = null
            if (candidates != null) {
                for (candidate in candidates) {
                    if (text.regionMatches(i, candidate, 0, candidate.length)) { matched = candidate; break }
                }
            }
            if (matched != null) {
                out.append(index.map.getValue(matched))
                i += matched.length
            } else {
                out.append(c)
                i++
            }
        }
        if (post.isEmpty() || out.isEmpty()) return out.toString()
        // TWVariants 是 OpenCC s2tw 链的最后一段（繁体异体规范化，如 僞→偽），对转换结果逐字套用
        var touched = false
        for (c in out) if (c in post) { touched = true; break }
        if (!touched) return out.toString()
        val final = StringBuilder(out.length)
        for (c in out) final.append(post[c] ?: c)
        return final.toString()
    }
}
