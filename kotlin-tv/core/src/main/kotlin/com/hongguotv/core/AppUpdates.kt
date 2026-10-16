// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    companion object {
        const val REPOSITORY = "jycao2/hongguoTV"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        /**
         * Public GitHub is often slow or unreachable from mainland China home networks.
         * These mirror CDNs act as transparent proxies and are generally accessible there,
         * so we fall through them in order until one responds. Each entry is a function
         * that takes the original GitHub URL and returns the proxied form; the raw URL
         * is always tried first.
         */
        val MIRRORS: List<(String) -> String> = listOf(
            { it },                                        // raw GitHub first
            { "https://gh-proxy.com/$it" },                // fastgit style proxy
            { "https://ghfast.top/$it" },                  // fastgit alternate
            { "https://gh-proxy.net/$it" },                // another mirror
        )
        /** All hosts we ever send HTTPS traffic to — the interceptor whitelist. */
        val ALLOWED_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "gh-proxy.com", "ghfast.top", "gh-proxy.net",
        )
        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == "com.hongguotv.nativeapp") { "更新信息不匹配" }
            val apk = o.getJSONObject("apk")
            val update = AppUpdate(o.getLong("versionCode"), o.getString("versionName"), o.getInt("minSdk"),
                o.getString("tag"), apk.getString("name"), apk.getLong("size"), apk.getString("sha256").lowercase(), o.optString("notes").take(1500))
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 26..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]{0,80}")))
            require(update.fileName.matches(Regex("hongguotv-kotlin-[0-9][0-9A-Za-z.+-]{0,80}-android8\\.apk")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }
    }
    /** Original GitHub URL for this APK; [AppUpdateRepository] will wrap it with mirrors. */
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) {
                // File.renameTo refuses to replace an existing target on Windows.
                try { java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                catch(e: Exception) { throw IOException("无法保存安装包", e) }
            }
        } finally { partial.delete() }
    }
}

class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub CDN redirects + CN-friendly mirror proxies; never cleartext or arbitrary hosts.
            if (url.scheme != "https" || url.host !in AppUpdate.ALLOWED_HOSTS)
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()

    /** Try each mirror in order; return the result from the first that works. */
    private fun <T> tryMirrors(base: String, maxPerMirrorSec: Long, block: (okHttpResponse: okhttp3.Response, url: String) -> T): T {
        val failures = ArrayList<String>()
        for (wrap in AppUpdate.MIRRORS) {
            val url = wrap(base)
            val call = http.newCall(request(url))
            call.timeout().timeout(maxPerMirrorSec, TimeUnit.SECONDS)
            var nextMirror = false
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        failures += "HTTP${response.code}@${hostOf(url)}"
                        nextMirror = true
                        return@use
                    }
                    return block(response, url)
                }
            } catch (e: IOException) {
                failures += "${e.message?.take(50) ?: "?"}@${hostOf(url)}"
            }
            if (nextMirror) continue
        }
        throw IOException("更新检查失败：${failures.joinToString("; ")}")
    }

    private fun hostOf(url: String) = url.removePrefix("https://").substringBefore("/")

    fun latest(): AppUpdate {
        val base = "${AppUpdate.RELEASES}/latest/download/update.json"
        return tryMirrors(base, 12) { response, _ ->
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 65536) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: throw IOException("更新信息为空")
            try { AppUpdate.parse(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
        }
    }

    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        val base = update.downloadUrl
        val errors = ArrayList<String>()
        for (wrap in AppUpdate.MIRRORS) {
            val url = wrap(base)
            var response: okhttp3.Response? = null
            try {
                response = http.newCall(request(url)).execute()
                if (!response.isSuccessful) { errors += "HTTP${response.code}@${hostOf(url)}"; continue }
                val body = response.body
                if (body == null) { errors += "空响应@${hostOf(url)}"; continue }
                if (body.contentLength() >= 0 && body.contentLength() != update.size) { errors += "长度不匹配@${hostOf(url)}"; continue }
                body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
                return
            } catch (e: IOException) {
                errors += "${e.message?.take(50) ?: "?"}@${hostOf(url)}"
                file.delete()
            } finally {
                response?.close()
            }
        }
        throw IOException("安装包下载失败：${errors.joinToString("; ")}")
    }

    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
