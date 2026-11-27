// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 远端协议档案的拉取。
 *
 * 网络规矩与更新包完全一致（[AppUpdate]）：只走 HTTPS、只认白名单里的域、响应有大小上限，
 * 并且**复用同一套中国大陆可达的镜像链**。这一点与原方案不同：公开 GitHub 的
 * `raw.githubusercontent.com` 在家庭宽带上经常直接不可达，只用它等于把功能做废。
 */
class PlaybackProfileRepository(
    private val path: String = PlaybackProfile.REMOTE_PATH,
) : Closeable {
    data class Document(val raw: String, val profile: PlaybackProfile)

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val target = chain.request().url
            if (target.scheme != "https" || target.host !in ALLOWED_HOSTS) {
                throw IOException("协议档案下载地址不受支持：${target.host}")
            }
            chain.proceed(chain.request())
        }
        .build()

    private val candidates: List<String> =
        AppUpdate.MIRRORS.map { it("https://raw.githubusercontent.com/$path") }

    /** 依次试原始地址与镜像，第一个成功且验签通过的胜出；全失败就抛异常，调用方继续用当前档案。 */
    fun fetch(): Document = fetchFrom(candidates) { fetchOnce(it) }

    /**
     * 候选地址的尝试顺序（可测）：原始地址 → 镜像 1 → 镜像 2 → 镜像 3。
     * 任何一步失败都继续试下一个，绝不因为某个镜像挂了就把整次刷新判死。
     */
    internal fun fetchFrom(
        candidates: List<String>,
        fetchOne: (String) -> Document,
    ): Document {
        var last: Exception? = null
        for (url in candidates) {
            try {
                return fetchOne(url)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("协议档案拉取失败：${last?.message}", last)
    }

    private fun fetchOnce(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HongguoTV-Profile")
            .header("Cache-Control", "no-cache")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            val stream = body.byteStream()
            val buffer = ByteArray(4096)
            val out = ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size() + read > PlaybackProfile.MAX_BYTES * 2) throw IOException("协议档案过大")
                out.write(buffer, 0, read)
            }
            val raw = out.toByteArray().toString(Charsets.UTF_8)
            Document(raw, PlaybackProfileEnvelope.decode(raw))
        }
    }

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    private companion object {
        val ALLOWED_HOSTS: Set<String> =
            AppUpdate.ALLOWED_HOSTS + setOf("raw.githubusercontent.com")
    }
}
