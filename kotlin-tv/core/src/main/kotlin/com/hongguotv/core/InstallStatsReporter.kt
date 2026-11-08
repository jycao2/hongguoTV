// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 匿名安装/活跃统计：端点常量与安装 ID 生成。数据仅含随机 ID 与版本号。 */
object InstallStats {
    const val ENDPOINT = "https://1303168554-kf2oz3rjo1.ap-guangzhou.tencentscf.com"

    /** 32 位无横线 hex UUID，满足服务端 `[A-Za-z0-9_-]{8,64}` 校验。 */
    fun newInstallId(): String = UUID.randomUUID().toString().replace("-", "")
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
    fun ping(installId: String, versionName: String, versionCode: Int): Boolean {
        if (!ID_RE.matches(installId) || !VER_RE.matches(versionName) || versionCode < 0) return false
        val url = try {
            endpoint.toHttpUrl().newBuilder()
                .addPathSegment("ping")
                .addQueryParameter("u", installId)
                .addQueryParameter("v", versionName)
                .addQueryParameter("c", versionCode.toString())
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
    }
}
