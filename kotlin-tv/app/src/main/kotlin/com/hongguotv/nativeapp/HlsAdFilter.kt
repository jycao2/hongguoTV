// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/**
 * 三方片源的 m3u8 插播广告过滤。
 *
 * 部分源站（如暴风）会在点播清单里插入独立的广告分片，URI 形如
 * `/video/adjump/time/xxx.ts`，前后被 #EXT-X-DISCONTINUITY 包夹。
 * 这类广告是完整的独立段，整段删除不会破坏正片的时间戳：
 * 播放器按剩余 #EXTINF 重新累加时长即可，所以可以在读取清单时直接剔除。
 *
 * 只处理 URI 含 m3u8 且内容确为 HLS 清单的请求，其余请求原样透传。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class HlsAdFilterDataSource private constructor(
    private val upstream: DataSource
) : DataSource {

    /** 过滤后的清单；为 null 表示当前请求走透传。 */
    private var body: ByteArray? = null
    private var pos = 0
    /**
     * 最近一次请求的 URI。不能直接返回 upstream.uri：上游还没 open 或已经 close 时它是 null，
     * 而 HLS 媒体源会在这些时机读 getUri()，拿到 null 会直接抛 NPE。
     */
    private var currentUri: android.net.Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        body = null
        pos = 0
        currentUri = dataSpec.uri
        // 带 position 的是范围请求（分片），不是清单：直接透传，避免返回从头开始的字节
        if(dataSpec.position != 0L || !looksLikePlaylist(dataSpec.uri.toString())) {
            return upstream.open(dataSpec)
        }

        val raw = readAll(dataSpec)
        val text = String(raw, Charsets.UTF_8)
        if(!text.startsWith("#EXTM3U")) {
            body = raw
            return raw.size.toLong()
        }
        // 过滤本身出任何问题都退回原始清单，宁可看广告也不能播不了
        val filtered = try { HlsAdFilter.filter(text) } catch(_: Throwable) { null }
        if(filtered != null) {
            // 电视端 logd 会吞 INFO，这里用 W 便于现场排查过滤是否命中
            android.util.Log.w(
                "HongguoTV",
                "HLS 广告过滤生效 url=${dataSpec.uri} segments=${countSegments(text)}->${countSegments(filtered)}"
            )
        }
        body = (filtered ?: text).toByteArray(Charsets.UTF_8)
        return body!!.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = body ?: return upstream.read(buffer, offset, length)
        if(length == 0) return 0
        if(pos >= bytes.size) return -1
        val count = minOf(length, bytes.size - pos)
        System.arraycopy(bytes, pos, buffer, offset, count)
        pos += count
        return count
    }

    override fun close() {
        body = null
        pos = 0
        upstream.close()
    }

    override fun getUri(): android.net.Uri? = currentUri

    private fun readAll(dataSpec: DataSpec): ByteArray {
        upstream.open(dataSpec)
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while(true) {
                val n = upstream.read(buf, 0, buf.size)
                if(n == -1) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            upstream.close()
        }
    }

    companion object {
        /** 包装上游工厂：每次 createDataSource 都生成独立的过滤通道。 */
        fun factory(upstream: DataSource.Factory): DataSource.Factory = object : DataSource.Factory {
            override fun createDataSource(): DataSource =
                HlsAdFilterDataSource(upstream.createDataSource())
        }

        private fun looksLikePlaylist(url: String) =
            (url.startsWith("http://") || url.startsWith("https://")) &&
                url.contains("m3u8", ignoreCase = true)

        /** 统计分片行数（非注释、非空行），仅用于日志。 */
        private fun countSegments(text: String) = text.lineSequence()
            .count { it.isNotBlank() && !it.trimStart().startsWith("#") }
    }
}

/** m3u8 文本层面的广告段剔除，独立成对象便于单元测试。 */
object HlsAdFilter {
    /**
     * 广告分片特征。当前只覆盖已实测确认的命名，宁可漏删也不误删正片：
     * 暴风源的广告分片路径固定为 /video/adjump/time/xxx.ts。
     */
    private val AD_SEGMENT = Regex("""(/adjump/|/guanggao/)""", RegexOption.IGNORE_CASE)

    /**
     * 删除清单中的广告分片及其 EXTINF，同时合并因此产生的相邻 DISCONTINUITY。
     * 没有可删内容时返回 null，调用方据此保留原始清单。
     */
    fun filter(text: String): String? {
        if(!text.startsWith("#EXTM3U")) return null
        val lines = text.split('\n')
        val out = ArrayList<String>(lines.size)
        var removed = 0
        var lastDiscontinuity = false
        var i = 0
        while(i < lines.size) {
            val rawLine = lines[i]
            val line = rawLine.trim()
            if(line.startsWith("#EXTINF")) {
                var j = i + 1
                while(j < lines.size && lines[j].isBlank()) j++
                val uri = lines.getOrNull(j)?.trim().orEmpty()
                if(uri.isNotEmpty() && !uri.startsWith("#") && AD_SEGMENT.containsMatchIn(uri)) {
                    removed++
                    i = j + 1
                    // 广告段前的 DISCONTINUITY 只为切换到广告才存在，广告删了它就多余
                    if(lastDiscontinuity && out.isNotEmpty()) {
                        out.removeAt(out.size - 1)
                        lastDiscontinuity = false
                    }
                    continue
                }
            }
            if(line == "#EXT-X-DISCONTINUITY") {
                // 整段广告删除后两侧的标记会挨在一起，只保留一个
                if(lastDiscontinuity) { i++; continue }
                lastDiscontinuity = true
            } else if(line.isNotEmpty()) {
                lastDiscontinuity = false
            }
            out.add(rawLine)
            i++
        }
        if(removed == 0) return null
        return out.joinToString("\n")
    }
}
