// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号 + 验证码登录（大陆与境外号码都收）。
 * * [send]：先匿名注册一台**新设备**（[DeviceRegister]），再用这台设备的身份发码。 设备注册仅是前置条件；短信登录换取会话尚未完成实机验收。
 * * [verify]：用**同一台设备**验码，平台在 `Set-Cookie` 里下发 `sessionid`。 换到的凭据带上这台设备的 `device_id` /
 *   `iid`（[AccountCredentials.device]）， 之后账号接口的签名都用它，会话与设备保持一致。
 *
 * 一个实例对应一次登录；重新发码会换一台新设备，之前的验证码随之作废。
 */
class PhoneLogin(private val http: OkHttpClient) {
    var deviceId = ""
    var installId = ""
    var mobile = ""
    var ticket = ""
    var type = LOGIN_CODE_TYPE
    private var expired = false
    val cookies = linkedMapOf<String, String>()
    /** Compatibility field; authentication responses are never retained here. */
    val trace = mutableListOf<String>()

    /** 平台拒绝时的可读原因，界面直接显示 [message]。 */
    class Failure(message: String, val errorCode: Int? = null, val canRetryCode: Boolean = false) :
        Exception(message)

    /** [maskedMobile] 是平台回显的打码号码（如 `+886******23`）。 */
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    fun send(mobile: String): Sent {
        val registered =
            CloudAccountClient(http).registerDevice() ?: throw Failure("设备注册失败，请检查网络后重试")
        deviceId = registered.deviceId
        installId = registered.installId
        cookies.clear()
        cookies.putAll(registered.cookies)
        expired = false
        this.mobile = mobile
        val reply =
            call(
                "/passport/mobile/send_code/v1/",
                mapOf("mobile" to mobile, "type" to type),
                form = false,
            )
        val data = reply.optJSONObject("data") ?: JSONObject()
        // 风控要求滑块验证：电视上没法做，也不去绕它。
        if (data.has("verify_center_decision_conf"))
            throw Failure("平台要求先完成滑块验证，当前电视登录流程不支持，尚未完成登录。")
        if (reply.optString("message") != "success") throw Failure(describe(data) ?: "验证码没发出去")
        ticket = data.optString("mobile_ticket")
        return Sent(data.optString("mobile").ifBlank { mobile }, data.optInt("retry_time", 60))
    }

    fun verify(code: String): AccountCredentials {
        if (expired) throw Failure("本次登录会话已失效，不能继续使用此会话提交验证码", 1)
        check(deviceId.isNotEmpty()) { "请先发送验证码" }
        val params =
            mapOf("mobile" to mobile, "code" to code, "type" to type, "mobile_ticket" to ticket)
        val replies = mutableListOf<JSONObject>()
        for ((path, form) in VERIFY_ATTEMPTS) {
            val reply = call(path, params, form)
            replies += reply
            if (cookies.containsKey("sessionid")) break
            val error = reply.optJSONObject("data")
            if (error != null && error.optInt("error_code", 0) != 0) {
                val errorCode = error.optInt("error_code")
                if (errorCode == 1) {
                    expired = true
                    cookies.clear()
                    ticket = ""
                    throw Failure(
                        "平台拒绝本次登录会话（错误码 1）。短信发送成功不代表登录会话有效，当前登录链路尚未接通；请勿反复获取或提交验证码。",
                        errorCode,
                    )
                }
                throw Failure(describe(error) ?: "验证码校验失败", errorCode, errorCode == 1102)
            }
        }
        if (!cookies.containsKey("sessionid")) {
            throw Failure(
                replies.firstNotNullOfOrNull { describe(it.optJSONObject("data") ?: JSONObject()) }
                    ?: "登录没有成功，平台没有下发登录态"
            )
        }
        val header = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header)
            .copy(
                deviceId = deviceId,
                installId = installId,
                device = mapOf("device_id" to deviceId, "iid" to installId),
            )
    }

    /** 带这台设备的身份签名后发一次 passport 请求；`Set-Cookie` 并进 [cookies]，返回 JSON（非 JSON 时包成 `raw`）。 */
    private fun call(path: String, params: Map<String, String>, form: Boolean): JSONObject {
        val device =
            LinkedHashMap(VendorConstants.device).apply {
                put("device_id", deviceId)
                put("iid", installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, "73932")
                put("version_name", "7.3.9.32")
            }
        val passport =
            mapOf("aid" to "8662", "app_name" to "novelread", "device_platform" to "android") +
                params.filterValues { it.isNotBlank() }
        val payload = if (form) Signer.encode(passport).toByteArray() else null
        val signed =
            Signer()
                .sign(PASSPORT + path, if (form) device else device + passport, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        if (cookies.isNotEmpty())
            request.header(
                "Cookie",
                cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
            )
        if (payload != null)
            request.post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        val reply =
            http.newCall(request.build()).execute().use { response ->
                response.headers("Set-Cookie").forEach { line ->
                    val pair = line.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
                val text = cloudResponse(response)
                runCatching { JSONObject(text) }
                    .getOrElse {
                        JSONObject().put("http", response.code).put("raw", text.take(300))
                    }
            }
        // Never retain authentication responses or verification codes in diagnostics.
        return reply
    }

    private fun describe(data: JSONObject): String? =
        data
            .optString("description")
            .takeIf { it.isNotBlank() }
            ?.let { "$it（错误码 ${data.optInt("error_code")}）" }

    companion object {
        private const val PASSPORT = "https://is.snssdk.com"
        /** login-lab 实测：24 = 登录验证码；1 / 3 是绑定 / 换绑手机号。 */
        const val LOGIN_CODE_TYPE = "24"
        /** `validate_code` 是要验证的主角；`sms_login` 系列是同一套 passport 上常见的直登端点，顺手一起试。 */
        private val VERIFY_ATTEMPTS =
            listOf(
                "/passport/mobile/validate_code/v1/" to false,
                "/passport/mobile/validate_code/v1/" to true,
                "/passport/mobile/sms_login/" to true,
                "/passport/mobile/sms_login_only/" to true,
            )

        /**
         * 区号 + 本地号码 → 平台认的手机号；不合格时抛 [IllegalArgumentException]，[Throwable.message] 可直接显示。
         * * 大陆（86）：1 开头 11 位，原样发（实测可用）；
         * * 境外：`+区号 号码`（区号后一个空格，去掉本地开头的 0），passport 的惯用写法； 长度按 E.164——区号 1–3 位，区号 + 号码合计不超过 15
         *   位，号码至少 4 位。
         *
         * 号码里的空格、横线等非数字字符一律忽略。
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

        /** 一整串的写法：`13800138000`（大陆），或 `+区号-号码` / `+区号 号码`（如 `+886-0912345678`）。 */
        fun parse(raw: String): String {
            val text = raw.trim()
            if (!text.startsWith("+")) return format("86", text)
            val cc = text.drop(1).takeWhile(Char::isDigit)
            val rest = text.drop(1 + cc.length)
            require(rest.firstOrNull() == '-' || rest.firstOrNull() == ' ') {
                "境外号码写成 +区号 号码，如 +886 0912345678"
            }
            return format(cc, rest)
        }
    }
}
