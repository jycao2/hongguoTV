// SPDX-License-Identifier: GPL-3.0-only
// Ported from tsukiyo-zero/hongguoTV PhoneLogin.kt with fixes from OOIUa/hongguo-android
// AppPhoneLogin (both GPL-3.0-only): security.snssdk.com host, the official App's fixed
// device identity, device_id/iid kept out of the query, no cookie replay, sms_login first.
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号 + 验证码登录：走官方 App 的 passport 短信通道（网页发码通道已被滑块挡死，不绕）。
 *
 * * [send]：用官方 App 的固定设备身份发码；同一号码在平台冷却期内不允许重发。
 * * [verify]：同一验证码按顺序尝试 passport 的几个登录端点，第一个明确结果即停；
 *   平台在 `Set-Cookie` 下发 `sessionid` 即成功。换到的会话绑定这台设备，
 *   之后账号接口的 Cookie 与签名都用它。
 *
 * 一个实例对应一次登录会话；重新发码会重置整个会话。
 */
class PhoneLogin(private val http: OkHttpClient) {
    private var mobile = ""
    private var ticket = ""
    private var sent = false
    private var nextSmsAt = 0L
    private val cookies = linkedMapOf<String, String>()
    private val submittedCodes = mutableSetOf<String>()

    /** 平台拒绝时的可读原因，界面直接显示 [message]。[needsWebsite] 表示只有官方页面能完成的验证（如滑块）。 */
    class Failure(
        message: String,
        val errorCode: Int? = null,
        val canRetryCode: Boolean = false,
        val needsWebsite: Boolean = false,
    ) : Exception(message)

    /** [maskedMobile] 是回显用的打码号码（如 `138****5678`）。 */
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    fun send(raw: String): Sent {
        check(System.currentTimeMillis() >= nextSmsAt) { "请等待短信冷却后再试" }
        mobile = parse(raw)
        sent = false
        ticket = ""
        cookies.clear()
        submittedCodes.clear()
        val reply =
            call(
                "/passport/mobile/send_code/v1/",
                mapOf("mobile" to mobile, "type" to LOGIN_CODE_TYPE),
                form = false,
            )
        val data = reply.optJSONObject("data") ?: JSONObject()
        if (needsWebsite(data)) throw Failure("平台要求滑块等额外验证，电视上完成不了；请改用扫码登录", needsWebsite = true)
        if (reply.optString("message") != "success") throw failure(data)
        ticket = data.optString("mobile_ticket")
        val retry = data.optInt("retry_time", 60).coerceIn(1, 3600)
        nextSmsAt = System.currentTimeMillis() + retry * 1000L
        sent = true
        val digits = mobile.substringAfter(' ')
        val masked = if (digits.length >= 7) digits.take(3) + "****" + digits.takeLast(4) else "你的手机"
        return Sent(masked, retry)
    }

    fun verify(code: String): AccountCredentials {
        check(sent) { "请先获取验证码" }
        require(code.matches(Regex("[0-9]{4,8}"))) { "请输入 4—8 位验证码" }
        // 同一验证码最多提交一次：响应丢失也不重放，平台把重复提交当攻击。
        if (!submittedCodes.add(code)) throw Failure("这个验证码已提交过，请输入新的验证码", canRetryCode = true)
        sent = false
        var sawExpired = false
        for ((path, form) in VERIFY_ATTEMPTS) {
            val reply =
                call(
                    path,
                    mapOf("mobile" to mobile, "code" to code, "type" to LOGIN_CODE_TYPE, "mobile_ticket" to ticket),
                    form,
                )
            if (cookies.containsKey("sessionid") || cookies.containsKey("sessionid_ss")) return credentials()
            val data = reply.optJSONObject("data") ?: continue
            // 每次尝试后平台可能下发新 ticket，后面的端点用最新的。
            data.optString("mobile_ticket").takeIf { it.isNotBlank() }?.let { ticket = it }
            when (data.optInt("error_code", 0)) {
                // 0 / 1：这个端点不吃未登录会话，换下一个端点再试。
                0 -> continue
                1 -> { sawExpired = true; continue }
                else -> {
                    val f = failure(data)
                    sent = f.canRetryCode
                    throw f
                }
            }
        }
        throw Failure(
            if (sawExpired) "登录会话已失效，请重新获取验证码" else "登录没有成功，平台没有下发登录态",
            if (sawExpired) 1 else null,
        )
    }

    private fun credentials() = AccountCredentials(
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
        DEVICE_ID,
        INSTALL_ID,
        mapOf("device_id" to DEVICE_ID, "iid" to INSTALL_ID),
    )

    /**
     * 带官方 App 设备身份签名发一次 passport 请求；`Set-Cookie` 只收不发
     * （把发码阶段的 cookie 回放到登录端点会被判异常会话）。
     */
    private fun call(path: String, params: Map<String, String>, form: Boolean): JSONObject {
        val device =
            LinkedHashMap(VendorConstants.device).apply {
                put("device_id", DEVICE_ID)
                put("iid", INSTALL_ID)
                for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) put(key, "73932")
                put("version_name", "7.3.9.32")
            }
        val passport = mapOf("aid" to "8662", "app_name" to "novelread", "device_platform" to "android") + params.filterValues { it.isNotBlank() }
        val payload = if (form) Signer.encode(passport).toByteArray() else null
        // 官方 App 把设备身份放在签名里而不是 query；query 里带 device_id/iid 会被判异常设备。
        val query = device.filterKeys { it != "device_id" && it != "iid" }
        val signed = Signer(device = device).sign(PASSPORT + path, if (form) query else query + passport, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        if (payload != null) request.post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        return http.newCall(request.build()).execute().use { response ->
            response.headers("Set-Cookie").forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            runCatching { JSONObject(cloudResponse(response)) }
                .getOrElse { JSONObject().put("http", response.code) }
        }
    }

    private fun needsWebsite(data: JSONObject) =
        data.has("verify_center_decision_conf") || data.optString("captcha").isNotBlank() || data.optInt("error_code", 0) == 1105

    private fun failure(data: JSONObject): Failure {
        val code = data.optInt("error_code", -1)
        val website = needsWebsite(data)
        val message = when {
            website -> "平台要求滑块等额外验证，电视上完成不了；请改用扫码登录"
            code == 1102 || code == 1202 -> "验证码有误，请检查后重新输入"
            code == 1103 || code == 1104 -> "验证码已失效，请重新获取后再试"
            code == 1106 || code == 1107 -> "请求过于频繁，请稍后再试"
            code == 1203 -> "错误次数过多或验证码已过期，请重新获取验证码"
            else -> data.optString("description").ifBlank { "手机号登录失败，请稍后重试" }
        }
        return Failure("$message（错误码 $code）", code, !website && (code == 1102 || code == 1202), website)
    }

    companion object {
        /** 官方 App 的 passport 域名；`is.snssdk.com` 对短信登录回 2046。 */
        private const val PASSPORT = "https://security.snssdk.com"

        /**
         * 官方 App 短信登录用的固定设备身份（公开抓包样本）。
         * 平台要求发码与验码的设备就是这个；新注册的设备会被判 2046。
         */
        private const val DEVICE_ID = "1905892595378490"
        private const val INSTALL_ID = "1905892595382586"

        /** 实测 24 = 登录验证码；1 / 3 是绑定 / 换绑手机号。 */
        private const val LOGIN_CODE_TYPE = "24"

        /** 端点顺序即尝试顺序：`sms_login` 系列吃未登录会话；`validate_code` 是登录后校验，放最后兜底。 */
        private val VERIFY_ATTEMPTS = listOf(
            "/passport/mobile/sms_login/" to true,
            "/passport/mobile/sms_login_only/" to true,
            "/passport/mobile/sms_login_continue/" to true,
            "/passport/mobile/validate_code/v1/" to false,
            "/passport/mobile/validate_code/v1/" to true,
        )

        /**
         * 区号 + 号码 → 平台认的手机号；不合格抛 [IllegalArgumentException]，[Throwable.message] 可直接显示。
         *
         * * 大陆（86）：1 开头 11 位，原样发；
         * * 境外：`+区号 号码`（区号后一个空格，去掉本地开头的 0），长度按 E.164。
         */
        fun format(countryCode: String, number: String): String {
            val cc = countryCode.filter(Char::isDigit)
            val digits = number.filter(Char::isDigit).removePrefix("0")
            if (cc == "86") {
                require(digits.matches(Regex("1\\d{10}"))) { "大陆手机号是 1 开头的 11 位数字" }
                return digits
            }
            require(cc.matches(Regex("[1-9]\\d{0,2}"))) { "区号是 1–3 位数字" }
            require(digits.length >= 4 && cc.length + digits.length <= 15) { "号码长度不对：区号加号码最多 15 位" }
            return "+$cc $digits"
        }

        /** 一整串的写法：`13800138000`（大陆），或 `+区号 号码` / `+区号-号码`（如 `+886 0912345678`）。 */
        fun parse(raw: String): String {
            val text = raw.trim()
            if (!text.startsWith("+")) return format("86", text)
            val cc = text.drop(1).takeWhile(Char::isDigit)
            val rest = text.drop(1 + cc.length)
            require(rest.firstOrNull() == '-' || rest.firstOrNull() == ' ') { "境外号码写成 +区号 号码，如 +886 0912345678" }
            return format(cc, rest)
        }
    }
}
