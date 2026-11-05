// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 本地库 WebDAV 协议层：PROPFIND 递归列目录 + Basic Auth。
 * 输出 [NasLibrary.NasEntry] 供 [NasLibrary.organize] 消费；播放地址经 [fileUrl] 拼接。
 */
class NasWebDav(
    baseUrl: String,
    private val user: String,
    private val pass: String,
    http: OkHttpClient? = null,
) {
    class NasException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val http = (http ?: OkHttpClient()).newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 规范化的根地址：协议+主机+端口+可选挂载路径，无尾斜杠，如 http://192.168.1.20:5005/video */
    private val root: String
    /** 挂载路径部分（含前导斜杠，无尾斜杠；根挂载为空串），用于从 href 中剥离前缀 */
    private val mountPath: String

    init {
        val trimmed = baseUrl.trim().trimEnd('/')
        val m = Regex("""^(https?://[^/]+)(/.*)?$""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: throw NasException("地址格式不正确，应类似 http://192.168.1.20:5005/目录")
        root = trimmed
        mountPath = m.groupValues.getOrNull(2).orEmpty()
    }

    private val authHeader: String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    /** 连通性测试：对根目录发 Depth:0 的 PROPFIND。 */
    fun testConnection() {
        propfind("", 0)
    }

    /** 递归列出全部条目（相对路径，以 "/" 分隔），供 NasLibrary.organize 使用。 */
    fun listAll(maxDepth: Int = NasLibrary.MAX_DEPTH): List<NasLibrary.NasEntry> {
        val out = mutableListOf<NasLibrary.NasEntry>()
        var frontier = listOf("")
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = mutableListOf<String>()
            for (dir in frontier) {
                for (e in propfind(dir, 1)) {
                    out += NasLibrary.NasEntry(e.path, e.isDir, e.size)
                    if (e.isDir) next += e.path
                }
            }
            frontier = next
            depth++
        }
        return out
    }

    /** 相对路径 -> 完整播放地址（凭据仍走 Authorization 头，不进 URL）。 */
    fun fileUrl(path: String): String = root + "/" + encodePath(path)

    fun authHeader(): String = authHeader

    // ---------------- 内部 ----------------

    private data class DavEntry(val path: String, val isDir: Boolean, val size: Long)

    private fun propfind(relPath: String, depth: Int): List<DavEntry> {
        val url = root + (if (relPath.isEmpty()) "/" else "/" + encodePath(relPath) + "/")
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <propfind xmlns="DAV:">
              <prop><displayname/><resourcetype/><getcontentlength/></prop>
            </propfind>
        """.trimIndent().toRequestBody(null)
        val req = Request.Builder().url(url).method("PROPFIND", body)
            .header("Depth", depth.toString())
            .header("Authorization", authHeader)
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    207 -> return parseMultistatus(resp.body!!.byteStream(), relPath)
                    401, 403 -> throw NasException("账号或密码错误（HTTP ${resp.code}）")
                    404 -> throw NasException("目录不存在，请检查地址与路径（HTTP 404）")
                    else -> throw NasException("服务器返回 HTTP ${resp.code}，请确认这是 WebDAV 地址")
                }
            }
        } catch (e: NasException) {
            throw e
        } catch (e: Exception) {
            throw NasException("无法连接 NAS：${e.message ?: "网络异常"}", e)
        }
    }

    /** 解析 207 multistatus；按 localName 匹配，兼容 D:/DAV:/无前缀等各种命名空间写法。 */
    private fun parseMultistatus(stream: java.io.InputStream, requestedPath: String): List<DavEntry> {
        val doc = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(stream)
        } catch (e: Exception) {
            throw NasException("服务器响应格式不正确", e)
        }
        val out = mutableListOf<DavEntry>()
        val responses = doc.getElementsByTagNameNS("*", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as? Element ?: continue
            val href = firstText(r, "href") ?: continue
            val rel = hrefToRelative(href) ?: continue
            if (rel.isEmpty()) continue // 集合自身
            val isDir = r.getElementsByTagNameNS("*", "collection").length > 0
            val size = firstText(r, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            out += DavEntry(rel.trimEnd('/'), isDir, size)
        }
        return out
    }

    /** href（可能是绝对 URL 或绝对路径，已编码）-> 相对挂载根的路径；不属于本挂载返回 null。 */
    private fun hrefToRelative(href: String): String? {
        var p = href.trim()
        // 绝对 URL 时去掉协议与主机
        Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE).find(p)?.let { p = p.removePrefix(it.value) }
        p = try {
            URLDecoder.decode(p, Charsets.UTF_8.name())
        } catch (_: Exception) {
            return null
        }
        if (mountPath.isNotEmpty()) {
            if (!p.startsWith(mountPath)) return null
            p = p.removePrefix(mountPath)
        }
        return p.trim('/')
    }

    private fun firstText(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent
    }

    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
}
