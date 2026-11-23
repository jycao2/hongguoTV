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
        "比心" to "💕", "爱心" to "❤️", "生气" to "😠", "发怒" to "😡", "咒骂" to "🤬",
        "流泪" to "😢", "泣不成声" to "😭", "泪奔" to "😭", "大哭" to "😭", "晕" to "😵",
        "困" to "😪", "衰" to "😩", "擦汗" to "😓", "抠鼻" to "😤", "抓狂" to "😫",
        "震惊" to "😱", "惊讶" to "😮", "ok" to "👌", "耶" to "✌️", "酷" to "😎",
        "微笑" to "🙂", "害羞" to "😊", "抱抱" to "🤗", "白眼" to "🙄", "笑" to "😄",
        "鲜花" to "🌹", "玫瑰" to "🌹", "加油" to "💪", "胜利" to "✌️", "厉害" to "👍",
        "无语" to "😑", "叹气" to "😔", "吃瓜" to "🍉", "柠檬" to "🍋", "尴尬" to "😅",
        "绝望" to "😩", "暗中观察" to "🕵️", "心碎" to "💔", "礼物" to "🎁", "可爱" to "🥰",
        "拜托" to "🙏", "厉害了我的哥" to "😲",
        // Codes observed in live bullet-comment traffic that the initial guess set missed.
        "哭" to "😭", "爽" to "😆", "怒" to "😠", "送花" to "🌹", "盯" to "👀",
        // 红果弹幕高频 emote（用户 2026-11-17：[求爆更][舔屏] 这类要适配）。官方画的是自家图片，
        // 这里用同义的 Unicode emoji 近似，保证不再出现裸的「[求爆更]」文本。
        "求爆更" to "🚀", "爆更" to "🔥", "舔屏" to "😋", "打call" to "📣",
        "刷了个屏" to "🎊", "前方高能" to "⚡", "高能" to "💥", "名场面" to "🎬",
        "神操作" to "🤯", "泪目" to "🥲", "追更" to "🔖", "白嫖" to "💰",
        "好看" to "💯", "红包" to "🧧", "爪" to "🐾", "妙啊" to "🤩",
        "吃瓜群众" to "🍉", "yyds" to "🐐", "awsl" to "🥰", "aws1" to "🥰",
        "太美了" to "😍", "太帅了" to "🤩", "笑死" to "🤣", "害怕" to "😨",
        "上头" to "😖", "熬夜" to "🌙", "催更" to "⏰", "三连" to "👍",
        "尬笑" to "😅", "无语子" to "😑", "聪明" to "🧠", "感动" to "🥲",
        // 热榜剧真实弹幕探针 2026-11-18（1169 条）发现的缺码
        "没看够" to "🥺", "什么" to "❓", "送心" to "💕", "委屈" to "🥺", "快哭了" to "😢",
        // 与上游 guoapp（lifaxian/guoapp native/core/danmaku_emoji.go）对照表合并后补齐的码，
        // 2026-11-23 用户反馈「弹幕里还是有表情没适配」。guoapp 那份来自其线上验证；
        // 再补一批番茄/头条系表情面板的常见码（映射直白、无争议）。
        "调皮" to "😜", "色" to "😍", "鄙视" to "😒", "汗" to "😅", "闭嘴" to "🤐",
        "嘘" to "🤫", "睡" to "😴", "哈欠" to "🥱", "可怜" to "🥺", "吐" to "🤮",
        "强" to "👍", "给力" to "👍", "弱" to "👎", "奋斗" to "💪", "威武" to "💪",
        "心" to "❤️", "钱" to "💰", "飞吻" to "😘", "亲亲" to "😘",
        "馋" to "😋", "吐舌" to "😛", "鬼脸" to "😛", "撇嘴" to "🙁",
        "冷" to "🥶", "热" to "🥵", "冒汗" to "😅", "捂眼" to "🙈", "坏笑" to "😈",
        "再见" to "👋", "呵呵" to "🙃", "崩溃" to "😩", "偷看" to "👁️", "偷瞄" to "👀",
        "感冒" to "🤧", "口罩" to "😷", "晕倒" to "😵", "月亮" to "🌙", "太阳" to "☀️",
        "星星" to "⭐", "彩虹" to "🌈", "庆祝" to "🎉", "撒花" to "🎉", "气球" to "🎈",
        "皇冠" to "👑", "钻石" to "💎", "蛋糕" to "🎂", "生日" to "🎂", "干杯" to "🍻",
        "西瓜" to "🍉", "柠檬精" to "🍋", "猫" to "🐱", "狗" to "🐕", "猪" to "🐷",
        "兔子" to "🐰", "熊猫" to "🐼", "火箭" to "🚀", "飞机" to "✈️", "汽车" to "🚗",
        "手机" to "📱", "电视" to "📺", "音乐" to "🎵", "麦克风" to "🎤", "礼物盒" to "🎁",
        "奖杯" to "🏆", "足球" to "⚽", "篮球" to "🏀", "游泳" to "🏊", "跑步" to "🏃",
        "咖啡" to "☕", "啤酒" to "🍺", "冰淇淋" to "🍦", "鸡腿" to "🍗", "火锅" to "🍲",
        "拳头" to "✊", "击掌" to "🙌", "握手" to "🤝", "举手" to "🙋", "鞠躬" to "🙇",
        // 2026-11-23 真实弹幕探针（766 条）仍存在的漏码
        "做鬼脸" to "😜", "恐惧" to "😨", "石化" to "🗿", "赶稿中" to "📝",
    )

    /**
     * 电视 ROM 的 emoji 字体跟不上手机：Android 11 只到 Emoji 13.0，13.1/14.0 的新表情
     * （🫰🥹🫶 之类）一律画成豆腐块。这里把评论原文里的这类字符替换成同义旧字形，
     * 与 [TABLE] 解出的 emoji 一起兜底（用户 2026-11-18：弹幕还是有没有显示的表情）。
     */
    private val RAW_COMPAT: Array<Pair<String, String>> = arrayOf(
        "\uD83E\uDEF0" to "\uD83E\uDD0C", // 🫰 比心手 → 🤌
        "\uD83E\uDD79" to "\uD83E\uDD72", // 🥹 含泪 → 🥲
        "\uD83D\uDE2E\u200D\uD83D\uDCA8" to "\uD83D\uDE2E", // 😮‍💨 叹气 ZWJ → 😮
        "\uD83E\uDEF6" to "❤️", // 🫶 双手比心 → ❤️
        "\uD83E\uDEE0" to "🙂", // 🫠 融化脸 → 🙂
        "\uD83E\uDEE3" to "\uD83D\uDC41️", // 🫣 偷看 → 👁️
        "\uD83E\uDEE5" to "🤔", // 🫥 虚线脸 → 🤔
        "\uD83E\uDEE7" to "\uD83D\uDC80", // 🫧 泡泡 → 💀
        "\uD83E\uDEE8" to "\uD83D\uDE31", // 🫨 抖脸(15.0) → 😱
    )

    /** Expands every recognized "[code]" to its emoji, leaving the rest of [text] and unknown codes as-is. */
    fun display(text: String): String = expand(text).first

    /**
     * 展开结果附带本条弹幕里未被 [TABLE] 识别的「[码]」：调用方可以记日志，
     * 用来发现表格里还缺哪些 emote（用户 2026-11-18 反馈「还是有表情没显示」）。
     */
    fun expand(text: String): Pair<String, List<String>> {
        val hasCode = text.indexOf('[') >= 0
        val hasSticker = text.indexOf(STICKER) >= 0
        val unknown = if (hasCode) mutableListOf<String>() else null
        val expanded = if (!hasCode && !hasSticker) text else {
            val clean = if (hasSticker) text.replace(STICKER.toString(), "") else text
            if (!hasCode) clean
            else {
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
                    val code = clean.substring(open + 1, close)
                    val emoji = TABLE[code.lowercase()]
                    if (emoji != null) out.append(emoji) else {
                        out.append(clean, open, close + 1)
                        unknown?.add(code)
                    }
                    i = close + 1
                }
                out.toString()
            }
        }
        val finalText = RAW_COMPAT.fold(expanded) { acc, (from,to) -> if (acc.contains(from)) acc.replace(from,to) else acc }
        return finalText to (unknown?.toList() ?: emptyList())
    }

    private const val STICKER = '\uFFFC'
}
