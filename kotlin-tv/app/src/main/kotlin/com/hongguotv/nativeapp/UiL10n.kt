// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.hongguotv.core.L10n

/**
 * 渲染后的界面兜底翻译（2026-11-27）。
 *
 * App 的文案绝大多数硬编码在 Kotlin 里，出口分散在几十个页面/弹窗，逐个包 L10n.tr 容易漏。
 * 这里在页面（或弹窗）构建完成后遍历一次 View 树，把带中文的 text / hint /
 * contentDescription 过一遍运行时字典：查得到就换，查不到原样保留——
 * 因此对已翻译过的控件是无操作，重复调用也安全。
 *
 * 除整串命中，还处理两类「运行时才成形」的文案：
 * - 拼接串（`热门发现 · 漫剧`）：按 `·`、`，`、`/` 等分隔符切开后逐段查字典；
 * - 数字模板（`第 12 集 · 00:14`）：用 [patterns] 里的正则改写。
 */
object UiL10n {
    /** 当前语言是简体（原文）时不用遍历，直接返回。 */
    fun apply(root: View?) {
        if (root == null || L10n.current() == L10n.ZH) return
        walk(root)
    }

    /** 把一个文本串翻成当前语言；剥前缀圆点 → 整串/逐行 → 分段 → 数字模板 四级递进。 */
    fun text(value: String): String {
        if (!hasHan(value)) return value
        // 自检按钮、列表项常在文字前拼一个着色圆点（`●  首页`），圆点不属于文案，先剥下来
        val bullet = BULLET.find(value)
        if (bullet != null) {
            val head = bullet.groupValues[0]
            val rest = value.substring(head.length)
            val translated = text(rest)
            return if (translated == rest) value else head + translated
        }
        val whole = L10n.tr(value)
        if (whole != value) return whole
        val segmented = segments(value)
        if (segmented != value) return segmented
        return patterns(value)
    }

    private val BULLET = Regex("^[●○◆◇■□▪▫·•]+\\s*")

    /**
     * 回写翻译结果。原文是 Spanned（自检按钮的圆点用了 ReplacementSpan）时不能直接
     * `view.text = string`——那会把 span 全丢掉、彩色圆点变成普通字符。
     * 这里只替换首尾相同的部分之外的中间段，span 位置由 builder 自己校正。
     */
    private fun applyText(view: TextView, old: CharSequence, translated: String) {
        if (old !is android.text.Spanned) { view.text = translated; return }
        val source = old.toString()
        var prefix = 0
        val maxPrefix = minOf(source.length, translated.length)
        while (prefix < maxPrefix && source[prefix] == translated[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(source.length, translated.length) - prefix
        while (suffix < maxSuffix && source[source.length - 1 - suffix] == translated[translated.length - 1 - suffix]) suffix++
        val builder = android.text.SpannableStringBuilder(old)
        builder.replace(prefix, source.length - suffix, translated.substring(prefix, translated.length - suffix))
        view.text = builder
    }

    private fun walk(view: View) {
        when (view) {
            is EditText -> {
                // 用户输入的内容不能动，只翻占位提示
                val hint = view.hint?.toString()
                if (!hint.isNullOrEmpty() && hasHan(hint)) view.hint = text(hint)
            }
            is TextView -> {
                val current = view.text
                val raw = current?.toString()
                if (!raw.isNullOrEmpty() && hasHan(raw)) {
                    val translated = text(raw)
                    if (translated != raw) applyText(view, current, translated)
                }
                val hint = view.hint?.toString()
                if (!hint.isNullOrEmpty() && hasHan(hint)) view.hint = text(hint)
                val desc = view.contentDescription?.toString()
                if (!desc.isNullOrEmpty() && hasHan(desc)) view.contentDescription = text(desc)
            }
            is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
    }

    private val SEGMENT = Regex("[^·•，、/｜]+")

    /** `热门发现 · 漫剧` → 分段翻译后拼回。用替换而不是 split：Kotlin 的 split 会丢掉分隔符。 */
    private fun segments(value: String): String {
        if (!SEGMENT.containsMatchIn(value)) return value
        if (value.length > 200) return value
        var changed = false
        val out = SEGMENT.replace(value) { match ->
            val part = match.value
            val trimmed = part.trim()
            if (trimmed.isEmpty()) part
            else {
                // 分段内同样要过一遍数字模板：`快进步长  ·  15 秒` 的后半段只有正则能译
                val translated = L10n.tr(trimmed).let { if (it == trimmed) patterns(trimmed) else it }
                if (translated != trimmed) { changed = true; part.replace(trimmed, translated) }
                else part
            }
        }
        return if (changed) out else value
    }

    /** 数字模板：`7599万热度`、`第 12 集`、`16 集` 这类运行时才成形的文案。 */
    private fun patterns(value: String): String = when (L10n.current()) {
        L10n.EN -> value
            .replace(Regex("([\\d.]+)\\s*万热度"), "$1M views")
            .replace("官方接口限流中，", "Throttled by the API, ")
            .replace("电视可用", "Free on TV: ")
            .replace("单个文件不超过", "Single file up to ")
            .replace("单个文件不能超过", "Single file must be under ")
            .replace("弹幕 开", "Danmaku on")
            .replace("弹幕 关", "Danmaku off")
            .replace(Regex("定位到\\s*([\\d:]+)"), "Go to $1")
            .replace(Regex("剩余\\s*([\\d:]+)"), "left $1")
            // 详情页评分行：`9.4 分（1.9万 人评分） · 175.1万收藏`
            .replace(Regex("([\\d.]+)\\s*分（([\\d.]+)\\s*万\\s*人评分）"), "$1 ($2M ratings)")
            // 只剩「9.2 分」时的分数后缀
            .replace(Regex("([\\d.]+)\\s*分(?=\\s|（|\\()"), "$1")
            .replace(Regex("([\\d.]+)\\s*万收藏"), "$1M saved")
            .replace(Regex("系列\\s*(\\d+)\\s*季"), "Series $1 seasons")
            .replace(Regex("已跳过\\s*(\\d+)\\s*集"), "skipped $1 eps")
            .replace(Regex("已跳过\\s*(\\d+)\\s*部"), "skipped $1 shows")
            .replace(Regex("(\\d+)\\s*秒后自动重试"), "retry in $1s")
            .replace(Regex("正在自动切换到\\s*(\\d+)\\s*P"), "auto switching to $1P")
            .replace(Regex("正在切换到\\s*(\\d+)\\s*P"), "switching to $1P")
            // 榜单副标题是站点下发的 `11月27日已更新 · 基于红果内观看/互动等综合热度排序`
            .replace(Regex("(\\d+)\\s*月\\s*(\\d+)\\s*日已更新"), "Updated $1/$2")
            // 分段翻译会先按「·」切开，尾巴这一句到不了 patterns，单独兜一条
            .replace("基于红果内观看/互动等综合热度排序", "ranked by views and engagement on Hongguo")
            .replace(" · 基于红果内观看/互动等综合热度排序", " · ranked by views and engagement on Hongguo")
            // 选项面板里的纯数量标签：`5 分钟`、`3 行`
            .replace(Regex("^(\\d+)\\s*分钟$"), "$1 min")
            .replace(Regex("^(\\d+)\\s*小时$"), "$1 h")
            .replace(Regex("^(\\d+)\\s*行$"), "$1 rows")
            // 导航模块「5/5 显示」这类「已启用/总数」
            .replace(Regex("(\\d+)\\s*/\\s*(\\d+)\\s*显示"), "$1/$2 shown")
            // 顺序要紧：先匹配带前缀的完整说法，再落到通用的「N 集 / N 部」
            .replace(Regex("已更新\\s*(\\d+)\\s*集"), "$1 new eps")
            .replace(Regex("新增\\s*(\\d+)\\s*集"), "+$1 eps")
            .replace(Regex("更新至\\s*(\\d+)\\s*集"), "up to $1 eps")
            .replace(Regex("第\\s*(\\d+)\\s*集"), "EP $1")
            .replace(Regex("第\\s*(\\d+)\\s*季"), "S$1")
            .replace(Regex("第\\s*(\\d+)\\s*名"), "#$1")
            .replace(Regex("\\s*(\\d+)\\s*部"), " $1 shows")
            .replace(Regex("\\s*(\\d+)\\s*个"), " $1 items")
            .replace(Regex("\\s*(\\d+)\\s*集"), " $1 eps")
            .replace(Regex("\\s*(\\d+)\\s*秒"), "$1s")
            .replace(Regex("\\s{2,}"), " ")
        L10n.TW -> value
            .replace("万热度", "萬熱度")
            .replace(Regex("(\\d+)\\s*万"), "$1萬")
            // 带插值的长句分不出整串词条，只有「中文前缀 + 数字」这层能靠正则补繁
            .replace("电视可用", "電視可用")
            .replace("单个文件不超过", "單個檔案不超過")
            .replace("单个文件不能超过", "單個檔案不能超過")
            .replace("剩余", "剩餘")
            .replace("正在自动切换到", "正在自動切換到")
            .replace("正在切换到", "正在切換到")
            .replace("已跳过", "已跳過")
            .replace("秒后自动重试", "秒後自動重試")
            .replace("弹幕 开", "彈幕 開")
            .replace("弹幕 关", "彈幕 關")
            .replace(Regex("(\\d+)\\s*/\\s*(\\d+)\\s*显示"), "$1/$2 顯示")
            .replace(" · 基于红果内观看/互动等综合热度排序", " · 基於紅果內觀看／互動等綜合熱度排序")
            .replace(Regex("(\\d+)\\s*月\\s*(\\d+)\\s*日"), "$1 月 $2 日")
            .replace(Regex("^(\\d+)\\s*分钟$"), "$1 分鐘")
            .replace(Regex("^(\\d+)\\s*小时$"), "$1 小時")
            .replace(Regex("\\s*(\\d+)\\s*个"), " $1 個")
        else -> value
    }

    private fun hasHan(s: String): Boolean {
        for (c in s) if (c in '\u4e00'..'\u9fff') return true
        return false
    }
}
