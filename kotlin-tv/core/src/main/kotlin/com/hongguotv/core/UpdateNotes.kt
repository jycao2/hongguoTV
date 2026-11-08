// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 把 Release 说明压成弹窗能放下的短摘要。
 *
 * 更新说明由发布端 `RELEASE_NOTES` 经 `update.json` 下发，属于**远程内容**；
 * 直接整段塞进弹窗会让弹窗高度随文案长度变化（0.30.10 的 16 条技术日志就把弹窗撑满）。
 * 这里统一做三件事：去 Markdown 标记、限制条数与每条字数、超出折叠。
 */
object UpdateNotes {
    const val DEFAULT_MAX_ITEMS = 6
    const val DEFAULT_MAX_CHARS = 60
    private const val FOLD_TEMPLATE = "…共 %d 条更新，完整说明见发布页"

    fun summarize(
        raw: String,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        if (maxItems <= 0 || maxChars <= 0) return ""
        val items = raw.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("**", "")
            .replace("`", "")
            .lineSequence()
            .map { it.trim() }
            // 标题行只是分节标签，弹窗里没意义
            .filter { it.isNotEmpty() && !it.matches(Regex("^#{1,6}\\s+.*")) }
            .map { it.replace(Regex("^[-*·]\\s+"), "").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (items.isEmpty()) return ""
        val shown = items.take(maxItems).map { "· " + clip(it, maxChars) }
        return (if (items.size > maxItems) shown + FOLD_TEMPLATE.format(items.size) else shown)
            .joinToString("\n")
    }

    private fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"
}
