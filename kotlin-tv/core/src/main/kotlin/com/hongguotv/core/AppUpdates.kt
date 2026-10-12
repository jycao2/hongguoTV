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
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"
    companion object {
        const val REPOSITORY = "N3urda/hongguoTV-updates"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
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
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub's CDN redirects, never cleartext or arbitrary manifest-provided hosts.
            if (url.scheme != "https" || url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()
    fun latest(): AppUpdate {
        val call = http.newCall(request("${AppUpdate.RELEASES}/latest/download/update.json"))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
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
        http.newCall(request(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
