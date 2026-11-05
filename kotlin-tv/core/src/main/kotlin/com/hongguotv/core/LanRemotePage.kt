// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 手机遥控页面。以文件形式放在 `core/src/main/resources/remote.html`，
 * 而不是硬编码成 Kotlin 字符串：页面能正常编辑与 diff，也不会再把大文件撑大一份。
 * `core` 是纯 JVM 模块，读自身 resource 不需要 Android Context。
 */
object LanRemotePage {
    val html: String by lazy {
        runCatching {
            LanRemotePage::class.java.getResourceAsStream("/remote.html")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: FALLBACK
    }

    /** 打包漏掉 resource 时至少还能连上并看到原因，而不是白屏。 */
    private val FALLBACK = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>红果TV 遥控</title></head>
        <body style="font:16px sans-serif;background:#0E1116;color:#E7EAEE;padding:32px">
        <h1 style="font-size:18px">遥控页面未打包</h1>
        <p style="color:#8695A8;line-height:1.7">APK 里缺少 remote.html。请重新安装完整版本。</p>
        </body></html>
    """.trimIndent()
}
