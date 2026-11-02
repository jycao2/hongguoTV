// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** One official website login session; its CSRF cookies survive send/verify/poll. */
class WebLogin(http: OkHttpClient) {
    private val cookies = mutableListOf<Cookie>()
    private val client =
        http
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    @Volatile private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
    }

    private fun execute(request: Request): Response {
        val matching =
            cookies.filter { it.matches(request.url) && it.expiresAt > System.currentTimeMillis() }
        val scoped =
            request
                .newBuilder()
                .apply {
                    if (matching.isNotEmpty())
                        header("Cookie", matching.joinToString("; ") { "${it.name}=${it.value}" })
                }
                .build()
        val call = client.newCall(scoped)
        activeCall = call
        val response =
            try {
                call.execute()
            } finally {
                if (activeCall === call) activeCall = null
            }
        for (cookie in Cookie.parseAll(request.url, response.headers)) {
            cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
        }
        return response
    }

    private var token = ""
    private var mobile = ""
    private var sent = false

    data class Qr(val image: ByteArray, val expiresIn: Int)

    private fun call(
        path: String,
        data: Map<String, String>? = null,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url = (BASE + path).toHttpUrl().newBuilder()
        (mapOf(
                "aid" to "513755",
                "passport_jssdk_version" to "3.4.10",
                "passport_jssdk_type" to "normal",
                "account_sdk_source" to "web",
                "is_from_ttaccountsdk" to "1",
                "language" to "zh",
            ) + params)
            .forEach { (k, v) -> url.addQueryParameter(k, v) }
        val request =
            Request.Builder()
                .url(url.build())
                .header("User-Agent", UA)
                .header("Referer", "$BASE/")
                .header("Origin", BASE)
                .header("Accept", "application/json, text/javascript")
        cookies
            .firstOrNull { it.name == "passport_csrf_token" && it.matches(url.build()) }
            ?.let { request.header("x-tt-passport-csrf-token", it.value) }
        if (data != null)
            request.post(FormBody.Builder().apply { data.forEach { (k, v) -> add(k, v) } }.build())
        return execute(request.build()).use { JSONObject(cloudResponse(it)) }
    }

    private fun checked(reply: JSONObject): JSONObject {
        val data = reply.optJSONObject("data") ?: throw IOException("官网登录返回格式异常")
        if (reply.optString("message") != "success") {
            val code = data.optInt("error_code", -1)
            val message =
                if (data.has("verify_center_decision_conf")) "红果要求完成滑块验证，请改用扫码登录"
                else data.optString("description").ifBlank { "官网登录失败" }
            throw PhoneLogin.Failure("$message（错误码 $code）", code, code == 1102)
        }
        return data
    }

    fun qr(): Qr {
        val data = checked(call("/passport/web/get_qrcode/", params = mapOf("next" to "$BASE/")))
        token = data.getString("token")
        val encoded = data.getString("qrcode").substringAfter("base64,")
        val expiry = data.optLong("expire_time", 180)
        val seconds =
            if (expiry > 1_000_000_000)
                (expiry - System.currentTimeMillis() / 1000)
                    .coerceAtLeast(0)
                    .coerceAtMost(600)
                    .toInt()
            else expiry.coerceIn(0, 600).toInt()
        return Qr(decode64(encoded), seconds)
    }

    /** Returns false while awaiting the user's scan/confirmation. */
    fun poll(): Boolean {
        check(token.isNotBlank()) { "请先获取二维码" }
        val reply =
            call(
                "/passport/web/check_qrconnect/",
                params = mapOf("next" to "$BASE/", "token" to token),
            )
        val data = checked(reply)
        val status = data.optString("status")
        if (status in listOf("5", "expired")) throw IOException("二维码已过期，请重新获取")
        if (status in listOf("3", "confirmed")) {
            val redirect = data.optString("redirect_url")
            if (redirect.isNotBlank()) finishRedirect(redirect)
        }
        return hasSession()
    }

    /**
     * Complete only the official site's confirmation redirect, preserving domain-scoped cookies.
     */
    private fun finishRedirect(value: String) {
        var url = BASE.toHttpUrl().resolve(value) ?: throw IOException("登录确认地址无效")
        repeat(5) {
            if (url.scheme != "https" || url.host != "hongguoduanju.com")
                throw IOException("登录确认跳转到不支持的站点")
            val next =
                execute(
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", UA)
                            .header("Referer", "$BASE/")
                            .build()
                    )
                    .use { response ->
                        if (response.isRedirect)
                            response.header("Location")?.let { url.resolve(it) }
                        else {
                            if (!response.isSuccessful)
                                throw IOException("登录确认失败（HTTP ${response.code}）")
                            null
                        }
                    }
            if (next == null) return
            url = next
        }
        throw IOException("登录确认跳转次数过多")
    }

    fun send(number: String): PhoneLogin.Sent {
        if (cookies.none { it.name == "passport_csrf_token" }) qr()
        mobile = if (number.startsWith("+")) number else "+86 $number"
        val data =
            checked(
                call(
                    "/passport/web/send_code/",
                    mapOf(
                        "mobile" to mix(mobile),
                        "type" to mix("24"),
                        "mix_mode" to "1",
                        "fixed_mix_mode" to "1",
                    ),
                )
            )
        sent = true
        return PhoneLogin.Sent(
            data.optString("mobile").ifBlank { "你的手机" },
            data.optInt("retry_time", 60),
        )
    }

    fun verify(code: String): AccountCredentials {
        check(sent) { "请先发送验证码" }
        checked(
            call(
                "/passport/web/sms_login/",
                mapOf(
                    "mobile" to mix(mobile),
                    "code" to mix(code),
                    "login_only" to "true",
                    "mix_mode" to "1",
                    "fixed_mix_mode" to "1",
                ),
            )
        )
        return credentials()
    }

    private fun hasSession() =
        cookies.any {
            it.name in listOf("sessionid", "sessionid_ss") &&
                it.matches(BASE.toHttpUrl()) &&
                it.expiresAt > System.currentTimeMillis()
        }

    fun credentials(): AccountCredentials {
        check(hasSession()) { "官网尚未下发登录态，请先在手机确认登录" }
        checked(call("/passport/account/info/v2/"))
        return AccountCredentials(
            cookies
                .filter {
                    it.matches(BASE.toHttpUrl()) && it.expiresAt > System.currentTimeMillis()
                }
                .joinToString("; ") { "${it.name}=${it.value}" }
        )
    }

    companion object {
        const val BASE = "https://hongguoduanju.com"
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

        internal fun mix(value: String) =
            value.toByteArray(Charsets.UTF_8).joinToString("") {
                ((it.toInt() and 255) xor 5).toString(16)
            }
    }
}
