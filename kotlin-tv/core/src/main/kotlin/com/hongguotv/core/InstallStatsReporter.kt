// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 匿名安装/活跃统计：端点常量与安装 ID 生成。数据仅含随机 ID、版本号与设备去重键。 */
object InstallStats {
    const val ENDPOINT = "https://1303168554-kf2oz3rjo1.ap-guangzhou.tencentscf.com"

    /** 32 位无横线 hex UUID，满足服务端 `[A-Za-z0-9_-]{8,64}` 校验。 */
    fun newInstallId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * 设备级去重键：`ANDROID_ID` 的 SHA-256 前 16 位。
     *
     * `install_id` 存在 SharedPreferences 里，卸载重装就会换新的，统计会把同一台电视算成多台；
     * `ANDROID_ID` 在 Android 8+ 由「应用签名 + 用户 + 设备」共同决定，重装不变，适合做去重键。
     * 只报哈希不报原文，取不到时返回空串（服务端把空值当「未上报」，不影响入库）。
     */
    fun deviceKey(androidId: String?): String {
        val raw = androidId?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(16) { for (i in 0 until 8) append("%02x".format(digest[i])) }
    }
}

/** 极简 HTTP 传输抽象，便于单测注入；失败时允许抛异常，由 [InstallStatsReporter] 吞掉。 */
fun interface StatsTransport {
    fun get(url: String): Int
}

/** 默认 OkHttp 传输：短超时、无重试，适用于 fire-and-forget 场景。 */
class OkHttpStatsTransport(
    private val client: OkHttpClient = defaultClient(),
) : StatsTransport {
    override fun get(url: String): Int =
        client.newCall(Request.Builder().url(url).get().build()).execute().use { it.code }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 安装统计上报器。任何参数错误、网络异常都只返回 false，绝不向调用方抛出，
 * 保证埋点完全旁路启动/播放/更新流程。参数规则与 SCF 端 app.py 保持一致。
 */
class InstallStatsReporter(
    private val endpoint: String = InstallStats.ENDPOINT,
    private val transport: StatsTransport = OkHttpStatsTransport(),
) {
    /**
     * 启动上报。`deviceKey`（[InstallStats.deviceKey]）与 `sdkInt` 是 0.30.11 起新增的可选字段：
     * 传空/0 时不下发，服务端按「未上报」处理，因此老客户端行为不受影响。
     */
    fun ping(installId: String, versionName: String, versionCode: Int,
             deviceKey: String = "", sdkInt: Int = 0): Boolean {
        if (!ID_RE.matches(installId) || !VER_RE.matches(versionName) || versionCode < 0) return false
        val hasKey = deviceKey.isNotEmpty()
        val hasSdk = sdkInt > 0
        if (hasKey && !DEVICE_KEY_RE.matches(deviceKey)) return false
        if (hasSdk && sdkInt !in 21..100) return false
        val url = try {
            endpoint.toHttpUrl().newBuilder()
                .addPathSegment("ping")
                .addQueryParameter("u", installId)
                .addQueryParameter("v", versionName)
                .addQueryParameter("c", versionCode.toString())
                .apply {
                    if (hasKey) addQueryParameter("a", deviceKey)
                    if (hasSdk) addQueryParameter("s", sdkInt.toString())
                }
                .build()
                .toString()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            transport.get(url) in 200..299
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 更新失败上报：只带匿名 ID、当前版本、目标版本与失败阶段。
     * 阶段取值 parse / download / verify / install，用于判断某个版本是否在特定机型上装不上。
     */
    fun updateFailed(installId: String, from: String, to: String, stage: String): Boolean {
        if (!ID_RE.matches(installId) || !VER_RE.matches(from) || !VER_RE.matches(to) || !STAGE_RE.matches(stage)) return false
        val url = try {
            endpoint.toHttpUrl().newBuilder()
                .addPathSegment("update_fail")
                .addQueryParameter("u", installId)
                .addQueryParameter("f", from)
                .addQueryParameter("t", to)
                .addQueryParameter("s", stage)
                .build()
                .toString()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            transport.get(url) in 200..299
        } catch (_: Throwable) {
            false
        }
    }

    private companion object {
        val ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
        val VER_RE = Regex("^[0-9A-Za-z.+:-]{1,32}$")
        val STAGE_RE = Regex("^[a-z]{3,16}$")
        val DEVICE_KEY_RE = Regex("^[0-9a-f]{16}$")
    }
}
