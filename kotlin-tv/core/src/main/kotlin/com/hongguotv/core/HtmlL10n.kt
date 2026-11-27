// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 手机端网页（扫码快传 / 备份迁移 / 源配置 / 自定义背景）的运行时翻译。
 *
 * 这些页面是 Kotlin 里的 HTML 模板字符串，文案与脚本混在一起，逐条改 `${L10n.tr(...)}`
 * 既容易漏又破坏可读性。这里在整页返回前做一次结构感知的替换：
 * - HTML 文本节点（`>文案<`）与 title/placeholder/aria-label/alt 属性；
 * - 脚本里的单双引号字符串字面量（页面上的进度、错误提示都在这里）。
 *
 * 只替换「含中文且字典命中」的整串，其余原样保留，所以重复调用安全。
 */
internal object HtmlL10n {
    private val textNode = Regex(">([^<>]{1,300})<")
    private val attribute = Regex("(title|placeholder|aria-label|alt)=\"([^\"]{1,300})\"")
    private val singleQuoted = Regex("'([^'\\n]{1,300})'")
    private val doubleQuoted = Regex("\"([^\"\\n]{1,300})\"")

    fun translate(page: String): String {
        if (L10n.current() == L10n.ZH) return page
        var out = page
        out = textNode.replace(out) { m -> ">" + tr(m.groupValues[1]) + "<" }
        out = attribute.replace(out) { m -> m.groupValues[1] + "=\"" + tr(m.groupValues[2]) + "\"" }
        out = singleQuoted.replace(out) { m -> "'" + tr(m.groupValues[1]) + "'" }
        out = doubleQuoted.replace(out) { m -> "\"" + tr(m.groupValues[1]) + "\"" }
        // 正则按引号配对扫脚本字符串，遇到 `''`（空串）会错位一格，紧随其后的那句就被漏掉。
        // 页面里的状态提示是固定短语，最后整页再兜一遍（纯文本替换，对已译内容无副作用）。
        for (phrase in looseSentences) {
            val translated = L10n.tr(phrase)
            if (translated != phrase && out.contains(phrase)) out = out.replace(phrase, translated)
        }
        return out
    }

    /**
     * 各页面脚本里会出现、且容易因引号错位漏译的状态短语。
     * 只放「整句 UI 提示」，不要放短词，避免误伤别的句子。
     */
    private val looseSentences = listOf(
        "尚未选择文件",
        "请先点「选择文件」选取要发送的文件",
        "准备发送",
        "发送失败：",
        "已生成文件，请到手机「下载」里查看。",
        "当前浏览器不支持保存，请长按下方文本全选复制。",
        "图片不能超过 10 MB",
        "配置文件不能超过 256 KB",
        "备份文件不能超过 2 MB",
        "请先选择配置文件",
        "请选择 .json 后缀的配置文件",
        "请先选择备份文件",
        "请选择 .json 后缀的备份文件",
        "请先选择图片",
        "文件读取失败",
        "支持 JPEG / PNG / WebP。",
        "如无法上传，请点击右上角「⋯」→「在浏览器打开」，再用同一页面选择文件。",
    )

    private val segment = Regex("[^·•]+")

    private fun tr(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || !hasHan(text)) return raw
        val translated = L10n.tr(text)
        if (translated != text) return raw.replace(text, translated)
        // `红果 TV · 手机快传` 这类「品牌 · 功能」标题：按分隔符切开逐段查字典
        var out = raw
        if (text.length <= 60) {
            out = segment.replace(out) { m ->
                val part = m.value
                val trimmed = part.trim()
                if (trimmed.isEmpty()) part else {
                    val one = L10n.tr(trimmed)
                    if (one != trimmed) part.replace(trimmed, one) else part
                }
            }
            if (out != raw) return out
        }
        // 整句没命中：处理「中文片段 + 运行时变量」的拼接文案（如 “已选择 3 个文件：”），
        // 片段表按中文原文给出译文，替换后语序自然（英文为 Selected 3 files:）。
        for ((from, to) in fragments()) if (out.contains(from)) out = out.replace(from, to)
        return out
    }

    /**
     * 页面上的拼接文案片段。只在整串未命中时使用，避免覆盖已翻译的整句。
     *
     * 这些句子的中文里夹着运行时变量（`电视可用 __FREE__`、`已选择 3 个文件：`、
     * `最长 9 分钟`），整串永远查不到词条，只能靠「固定前缀/后缀 → 译文」的片段替换拼出来。
     * 顺序按长度从长到短，避免短片段先命中把长句拆坏。
     */
    private fun fragments(): List<Pair<String, String>> = when (L10n.current()) {
        L10n.EN -> listOf(
            "保存位置" to "Saved to",
            "电视可用" to "Free on TV:",
            "单个文件不超过" to "Single file up to",
            "单个文件不能超过" to "Single file must be under",
            "；失败的传输不会占用电视空间。" to "; failed transfers don't use TV space.",
            "关闭电视窗口即失效，最长" to "Expires when the TV window closes, up to",
            "分钟。仅限同一局域网。" to "min. Same Wi-Fi only.",
            " 个文件：" to " files: ",
            "，点击上传到电视。" to ", tap Upload to TV.",
            "已选择 " to "Selected ",
            "待发送 · " to "Pending · ",
            "已保存 · " to "Saved · ",
            "本次已接收 " to "Received ",
            "已接收 " to "Received ",
            "上传中断（" to "Upload interrupted (",
            "秒无数据），请重试" to "s without data), please retry",
            "个文件，请重新扫码" to " files, please rescan",
            "，正在电视上处理…" to ", processing on the TV…",
        )
        L10n.TW -> listOf(
            "保存位置" to "儲存位置",
            "电视可用" to "電視可用",
            "单个文件不超过" to "單個檔案不超過",
            "单个文件不能超过" to "單個檔案不能超過",
            "；失败的传输不会占用电视空间。" to "；失敗的傳輸不會佔用電視空間。",
            "关闭电视窗口即失效，最长" to "關閉電視視窗即失效，最長",
            "分钟。仅限同一局域网。" to "分鐘。僅限同一區域網。",
            " 个文件：" to " 個檔案：",
            "，点击上传到电视。" to "，點選上傳到電視。",
            "已选择 " to "已選擇 ",
            "待发送 · " to "待傳送 · ",
            "已保存 · " to "已儲存 · ",
            "上传中断（" to "上傳中斷（",
            "秒无数据），请重试" to "秒無資料），請重試",
            "个文件，请重新扫码" to "個檔案，請重新掃碼",
            "，正在电视上处理…" to "，正在電視上處理…",
        )
        else -> emptyList()
    }

    private fun hasHan(s: String): Boolean {
        for (c in s) if (c in '\u4e00'..'\u9fff') return true
        return false
    }
}
