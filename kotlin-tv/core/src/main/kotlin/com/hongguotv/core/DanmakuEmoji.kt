// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The platform embeds its own emoji codes in bullet-comment text, e.g. "[爱慕]". Drawing the raw
 * text shows literal brackets instead of the picture the official app renders, so the canvas must
 * expand known codes to Unicode emoji first. Unknown codes are left untouched so nothing is lost.
 * The text also carries U+FFFC wherever the official app draws one of its own picture stickers;
 * those cannot be reconstructed from the payload, so the placeholder is dropped rather than left
 * to render as a missing-glyph box.
 */
object DanmakuEmoji {
    /** Codes are stored without brackets; keys are lower-cased so lookups stay case-insensitive. */
    private val TABLE: Map<String, String> = mapOf(
        "大笑" to "😄", "捂脸" to "🤦", "爱慕" to "😍", "赞" to "👍", "笑哭" to "😂",
        "狗头" to "🐶", "doge" to "🐶", "666" to "🙌", "灵光一闪" to "💡", "滑稽" to "🤪",
        "看" to "👀", "what" to "😲", "星星眼" to "🤩", "打脸" to "🤚", "思考" to "🤔",
        "疑问" to "❓", "憨笑" to "😁", "奸笑" to "😏", "酷拽" to "😎", "黑脸" to "🌚",
        "呲牙" to "😁", "偷笑" to "🤭", "得意" to "😌", "小鼓掌" to "👏", "鼓掌" to "👏",
        "比心" to "🫰", "爱心" to "❤️", "生气" to "😠", "发怒" to "😡", "咒骂" to "🤬",
        "流泪" to "😢", "泣不成声" to "😭", "泪奔" to "😭", "大哭" to "😭", "晕" to "😵",
        "困" to "😪", "衰" to "😩", "擦汗" to "😓", "抠鼻" to "😤", "抓狂" to "😫",
        "震惊" to "😱", "惊讶" to "😮", "ok" to "👌", "耶" to "✌️", "酷" to "😎",
        "微笑" to "🙂", "害羞" to "😊", "抱抱" to "🤗", "白眼" to "🙄", "笑" to "😄",
        "鲜花" to "🌹", "玫瑰" to "🌹", "加油" to "💪", "胜利" to "✌️", "厉害" to "👍",
        "无语" to "😑", "叹气" to "😮‍💨", "吃瓜" to "🍉", "柠檬" to "🍋", "尴尬" to "😅",
        "绝望" to "😩", "暗中观察" to "🕵️", "心碎" to "💔", "礼物" to "🎁", "可爱" to "🥰",
        "拜托" to "🙏", "厉害了我的哥" to "😲",
        // Codes observed in live bullet-comment traffic that the initial guess set missed.
        "哭" to "😭", "爽" to "😆", "怒" to "😠", "送花" to "🌹", "盯" to "👀",
    )

    /** Expands every recognized "[code]" to its emoji, leaving the rest of [text] and unknown codes as-is. */
    fun display(text: String): String {
        val hasCode = text.indexOf('[') >= 0
        val hasSticker = text.indexOf(STICKER) >= 0
        // Budget the common case: literal text needs no work at all.
        if (!hasCode && !hasSticker) return text
        val clean = if (hasSticker) text.replace(STICKER.toString(), "") else text
        if (!hasCode) return clean
        val out = StringBuilder(clean.length)
        var i = 0
        while (i < clean.length) {
            val open = clean.indexOf('[', i)
            if (open < 0) {
                out.append(clean, i, clean.length)
                break
            }
            val close = clean.indexOf(']', open + 1)
            if (close < 0) {
                out.append(clean, i, clean.length)
                break
            }
            out.append(clean, i, open)
            val emoji = TABLE[clean.substring(open + 1, close).lowercase()]
            if (emoji != null) out.append(emoji) else out.append(clean, open, close + 1)
            i = close + 1
        }
        return out.toString()
    }

    private const val STICKER = '\uFFFC'
}
