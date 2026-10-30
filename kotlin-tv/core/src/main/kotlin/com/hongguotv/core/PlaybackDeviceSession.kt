// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/**
 * 匿名播放身份：注册一次后在整个进程内保持同一身份，所有 Repository 共用。
 * 平台按「注册过的 device_id + install_id」放行播放接口，未注册设备的播放请求只会拿到空响应。
 */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // 注册请求不带账号 Cookie；取消由调用方的工作域负责。
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            DeviceRegister.parse(readRegisterBody(response), response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(val values: Map<String, String>, val cookie: String)

    private val cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        val validId = Regex("[1-9][0-9]{0,29}")
        if (!validId.matches(device.deviceId) || !validId.matches(device.installId))
            throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
                VendorConstants.device +
                    mapOf(
                        "device_id" to device.deviceId,
                        "iid" to device.installId,
                        "cdid" to cdid,
                    ),
                device.cookies
                    .filterKeys {
                        it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                    }
                    .entries
                    .joinToString("; ") { (name, value) -> "$name=$value" },
            )
            .also { ready = it }
    }
    /** 丢弃进程内身份并重新注册一台匿名设备；播放接口持续返回空体时用它自愈。 */
    @Synchronized
    internal fun renew(http: OkHttpClient): Profile {
        ready = null
        return profile(http)
    }
}

/** 注册响应的有界读取；取消由调用方的工作域负责。 */
private fun readRegisterBody(response: okhttp3.Response): String {
    if (!response.isSuccessful) throw IOException("播放设备注册失败（HTTP ${response.code}）")
    val body = response.body ?: throw IOException("播放设备注册响应为空")
    val limit = 2L * 1024 * 1024
    if (body.contentLength() > limit) throw IOException("播放设备注册响应过大")
    val input = body.byteStream()
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    val bytes = out.toByteArray()
    if (bytes.size > limit) throw IOException("播放设备注册响应过大")
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) throw IOException("播放设备注册返回空响应")
    return text
}
