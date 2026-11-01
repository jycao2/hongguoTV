// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 手机端网页共用的兼容提示：微信内置 X5 浏览器会拦截 HTTP 页面里的文件选择，
 *  属于浏览器层限制，页面脚本无法绕过，只能引导用户改用系统浏览器打开同一地址。 */
internal object PhoneServerPage {
    /** 页面里预留的占位符，命中微信时替换成提示条，否则替换为空串。 */
    const val PLACEHOLDER = "__WECHAT__"

    fun fill(page: String, userAgent: String?): String =
        page.replace(PLACEHOLDER, notice(userAgent))

    private fun notice(userAgent: String?): String =
        if(userAgent?.contains("micromessenger", ignoreCase = true) == true)
            """<p style="color:#ffd166;background:#2a2410;border:1px solid #6b5a20;border-radius:8px;padding:10px;font-size:13px;line-height:1.5">如无法上传，请点击右上角「⋯」→「在浏览器打开」，再用同一页面选择文件。</p>"""
        else ""
}
