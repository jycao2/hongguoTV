// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.Locale

/**
 * 运行时多语言字典。
 *
 * 设计取舍：App 的 UI 文案几千条全部硬编码在 Kotlin 里（仓库几乎没有 strings.xml），
 * 抽成 Android 资源要给每条起 ID 并改几千处引用，风险与收益不成正比。这里改用
 * **中文原文作 key** 的运行时字典：调用方把原来写死的中文交给 [tr]，字典里查到就换译文，
 * 查不到原样返回——所以任何语言下都不会出现空文案或崩溃，新文案忘记翻译只是显示简体中文。
 *
 * 语言来源由宿主注入（app 层从 SharedPreferences 读），core 不依赖 Android：
 * - 手动选了语言 → 用手动值；
 * - 没选（空串）→ 跟随系统 Locale：zh-TW / zh-HK / zh-MO → tw；其它 zh-* → zh；英文系 → en；其余 → zh。
 *
 * 繁中字典由 [L10nDict] 提供（opencc s2twp 自动生成）；英文词条字典由 [L10nEnglish] 提供，
 * 只收录高频 UI 文案，未覆盖的回退简体中文——这是有意为之：半生不熟的机翻提示比中文更难懂。
 */
object L10n {
    /** 稳定语言代码。写进备份与偏好，不要改拼写。 */
    const val ZH = "zh"
    const val TW = "tw"
    const val EN = "en"
    const val FOLLOW_SYSTEM = ""

    val SUPPORTED = listOf(ZH, TW, EN)

    /** 当前手动选择；空 = 跟随系统。由 app 层注入。 */
    @Volatile
    private var preference: String = FOLLOW_SYSTEM

    /** 宿主可注入系统语言来源，便于单测。默认取 JVM Locale。 */
    @Volatile
    private var systemLanguage: () -> String = { localeToLanguage(Locale.getDefault()) }

    fun configure(pref: String?, system: (() -> String)? = null) {
        preference = if (pref in SUPPORTED || pref == FOLLOW_SYSTEM) pref ?: FOLLOW_SYSTEM else FOLLOW_SYSTEM
        if (system != null) systemLanguage = system
    }

    /** 当前生效语言。 */
    fun current(): String = if (preference != FOLLOW_SYSTEM) preference else systemLanguage()

    /** Locale → 语言代码。繁中地区单独识别，避免把 zh-TW 归到简中。 */
    fun localeToLanguage(locale: Locale): String {
        val lang = locale.language.lowercase(Locale.ROOT)
        return when {
            lang == "zh" && locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") -> TW
            lang == "zh" -> ZH
            lang.startsWith("en") -> EN
            else -> ZH // 其它系统语言的设备回退简体中文，比显示英文更贴近项目主体用户
        }
    }

    /** 翻译一段 UI 文案。未命中字典时原样返回。 */
    fun tr(text: String): String {
        if (text.isEmpty()) return text
        val language = current()
        val table = when (language) {
            TW -> L10nDict.zhTW
            EN -> L10nEnglish.words
            else -> return text
        }
        table[text]?.let { return it }
        // 词条里常带换行（`第一行\n第二行`），而人工维护的译文未必抄全换行位置；
        // 让「空白差异」不参与匹配，避免整段因为少一个 \n 就整条回退中文（2026-11-27 实踩）。
        val loose = looseIndex(language)
        loose.collapsed[looseKey(text)]?.let { return it }
        // 有的词条是把换行直接删掉抄的（「。」后紧接下一句），这一步兜住“整串去掉空白后相等”
        loose.stripped[stripKey(text)]?.let { return it }
        // 弹窗正文常是 appendLine 拼出来的整块说明，整块词条不存在，但每一行都是独立词条
        byLines(text, table, loose)?.let { return it }
        // 最后兜「带插值的整句」（`已收藏《某剧》`）：按片段表在串内替换可译的部分
        return phrases(text, language, table) ?: text
    }

    /**
     * 逐行翻译：`appendLine` 拼出来的多行说明（小爱配置、遥控地址等）整块没有词条，
     * 但每一行单独都是词条。保留缩进与空行，只有命中才替换。
     */
    private fun byLines(text: String, table: Map<String, String>, loose: LooseIndex): String? {
        if (text.indexOf('\n') < 0) return null
        var changed = false
        val out = StringBuilder(text.length)
        text.split('\n').forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            val key = line.trim()
            if (key.isEmpty()) { out.append(line); return@forEachIndexed }
            val hit = table[key] ?: loose.collapsed[looseKey(key)] ?: loose.stripped[stripKey(key)]
            if (hit == null) out.append(line) else { changed = true; out.append(line.replace(key, hit)) }
        }
        return if (changed) out.toString() else null
    }

    private class Phrase(val regex: Regex, val values: Map<String, String>)

    /** 语言 → 片段替换器。片段表见 [L10nPhrases]，译文仍取自各语言主字典。 */
    private val phraseCache = java.util.concurrent.ConcurrentHashMap<String, Phrase>()

    private fun phrases(text: String, language: String, table: Map<String, String>): String? {
        val phrase = phraseCache.getOrPut(language) {
            val pairs = L10nPhrases.keys.mapNotNull { key -> table[key]?.let { key to it } }
                .sortedByDescending { it.first.length } // 长片段优先，避免「已收藏《」被更短的键抢先切碎
            if (pairs.isEmpty()) return@getOrPut Phrase(NEVER, emptyMap())
            Phrase(Regex(pairs.joinToString("|") { Regex.escape(it.first) }), pairs.toMap())
        }
        if (phrase.values.isEmpty()) return null
        val replaced = phrase.regex.replace(text) { phrase.values[it.value] ?: it.value }
        return if (replaced == text) null else replaced
    }

    private val NEVER = Regex("(?!)")

    private val spaces = Regex("\\s+")
    private fun looseKey(text: String) = spaces.replace(text.trim(), " ")
    private fun stripKey(text: String) = spaces.replace(text, "")

    private class LooseIndex(val collapsed: Map<String, String>, val stripped: Map<String, String>)

    /** 语言 → 「空白归一化」词条表。按语言缓存，别把整张大 map 当 key（每次调用都要算哈希）。 */
    private val looseIndexes = java.util.concurrent.ConcurrentHashMap<String, LooseIndex>()

    private fun looseIndex(language: String): LooseIndex = looseIndexes.getOrPut(language) {
        val table = when (language) {
            TW -> L10nDict.zhTW
            EN -> L10nEnglish.words
            else -> return@getOrPut LooseIndex(emptyMap(), emptyMap())
        }
        // 归一后可能撞车（只差空白的两个词条），先到先得，不覆盖已有译文
        val collapsed = HashMap<String, String>(table.size * 2)
        val stripped = HashMap<String, String>(table.size * 2)
        for ((key, value) in table) {
            collapsed.putIfAbsent(looseKey(key), value)
            stripped.putIfAbsent(stripKey(key), value)
        }
        LooseIndex(collapsed, stripped)
    }

    /**
     * 带插值的文案翻译：传入模板（含 `{0}` `{1}` 占位）与参数，翻译模板后回填。
     * 例：`trFmt("已读取 {0} 部云端观看记录", count)` → `Read {0} cloud watch records`。
     * 占位符统一用花括号数字，避免与 Kotlin 字符串模板的 `$` 冲突。
     */
    fun trFmt(template: String, vararg args: Any): String {
        val translated = tr(template)
        var out = translated
        args.forEachIndexed { index, value -> out = out.replace("{$index}", value.toString()) }
        return out
    }

    /** 供手机遥控页下发字典用：返回当前语言的完整词条表（JSON 字符串由调用方序列化）。 */
    fun dictionary(lang: String): Map<String, String> = when (lang) {
        TW -> L10nDict.zhTW
        EN -> L10nEnglish.words
        else -> emptyMap()
    }
}
