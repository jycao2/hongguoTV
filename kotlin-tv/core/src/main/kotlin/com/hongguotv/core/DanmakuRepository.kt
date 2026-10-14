// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One official bullet comment, anchored to a playback offset within its episode. */
data class Danmaku(val id: String, val offsetMs: Long, val text: String, val diggCount: Int)

/**
 * Pulls the platform's official time-anchored comments (弹幕) for one episode, one 30-second
 * window per request. All requests are serialized and throttled because the endpoint is
 * gateway-policed; a risk-control reply carries a server-dictated cooldown that is honored
 * before retrying. Empty or censored replies degrade to an empty window instead of failing.
 */
class DanmakuRepository(
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val random: () -> Double = Math::random,
) {
    private val signer = Signer()
    // Negative means "no request yet": the first call must not sleep. Starting at 0 or
    // Long.MIN_VALUE breaks that — the former throttles a non-existent previous request,
    // the latter overflows the subtraction and sleeps effectively forever.
    private var lastRequestAt = -1L

    /** Comments of one 30-second window plus the server-suggested start of the next window. */
    data class Window(val comments: List<Danmaku>, val nextStartMs: Long)

    private data class Page(val comments: List<Danmaku>, val nextStartMs: Long, val cursor: String, val hasMore: Boolean)
    private class Cooldown(val ms: Long) : IOException()

    /** Fetches the window starting at [startMs], following in-window pagination (bounded). */
    @Synchronized
    fun window(seriesId: String, vid: String, durationMs: Long, startMs: Long): Window {
        val comments = mutableListOf<Danmaku>()
        var cursor = ""
        var nextStart = -1L
        var pages = 0
        while (true) {
            val page = fetchWithRetry(seriesId, vid, durationMs, startMs, cursor)
            comments += page.comments
            if (nextStart < 0) nextStart = page.nextStartMs
            cursor = page.cursor
            pages++
            if (!page.hasMore || cursor.isEmpty() || pages >= MAX_PAGES_PER_WINDOW) break
        }
        return Window(dedupe(comments), if (nextStart > startMs) nextStart else startMs + WINDOW_MS)
    }

    private fun fetchWithRetry(seriesId: String, vid: String, durationMs: Long, startMs: Long, cursor: String): Page {
        var attempt = 0
        while (true) {
            try {
                return parse(post(vid, body(seriesId, vid, durationMs, startMs, cursor)), startMs)
            } catch (cooldown: Cooldown) {
                if (attempt >= MAX_COOLDOWN_RETRIES) throw IOException("弹幕接口风控中，请稍后再试")
                attempt++
                sleep(cooldown.ms + (random() * 1000).toLong())
            }
        }
    }

    private fun body(seriesId: String, vid: String, durationMs: Long, startMs: Long, cursor: String): ByteArray {
        val business = JSONObject()
            .put("book_id", seriesId)
            .put("start_offset_time", startMs.coerceAtLeast(0))
            .put("playlet_item_duration", durationMs.coerceAtLeast(0))
            .put("need_danmaku_guide_type", JSONArray(listOf(1, 3, 4, 2)))
        return JSONObject()
            .put("comment_source", 601)
            .put("server_channel", 1000)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("comment_type", 20)
            .put("sort", 1)
            .put("business_param", business)
            .put("count", 90)
            .put("cursor", cursor)
            .put("aid", 8662)
            .put("compliance_status", 0)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun throttle() {
        val now = clock()
        if (lastRequestAt >= 0) {
            val wait = THROTTLE_MS + (random() * THROTTLE_JITTER_MS).toLong() - (now - lastRequestAt)
            if (wait > 0) sleep(wait)
        }
        lastRequestAt = clock()
    }

    private fun post(vid: String, body: ByteArray): String {
        throttle()
        VendorConstants.regenerate()
        val signed = signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/novel/commentapi/comment/list/$vid/v1/", VendorConstants.device, body)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .header("comment-source", "601")
            .header("server-channel", "1000")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post((signed.body ?: body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("弹幕请求失败（HTTP ${response.code}）")
            // The gateway silently drops intercepted requests with an HTTP 200 empty body;
            // an empty reply must surface as "no comments", never as a parse crash.
            val payload = response.body ?: return@execute ""
            if (payload.contentLength() > MAX_BYTES) throw IOException("弹幕响应过大")
            val bytes = payload.bytes()
            if (bytes.size > MAX_BYTES) throw IOException("弹幕响应过大")
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun parse(raw: String, startMs: Long): Page {
        if (raw.isBlank()) return Page(emptyList(), startMs + WINDOW_MS, "", false)
        val payload = try {
            JSONObject(raw)
        } catch (invalid: Exception) {
            // Risk-control interceptions sometimes answer plain text; treat them as a dead window.
            return Page(emptyList(), startMs + WINDOW_MS, "", false)
        }
        if (payload.optInt("code", 0) != 0) {
            val cooldown = cooldownMs(payload.optString("message", payload.optString("msg", "")))
            if (cooldown > 0) throw Cooldown(cooldown)
            throw IOException("弹幕接口暂不可用")
        }
        val data = payload.optJSONObject("data") ?: return Page(emptyList(), startMs + WINDOW_MS, "", false)
        val rows = data.optJSONArray("data_list") ?: JSONArray()
        val comments = rows.objects().mapNotNull { item ->
            val comment = item.optJSONObject("comment") ?: return@mapNotNull null
            val text = comment.optJSONObject("common")?.optJSONObject("content")?.optString("text", "")?.trim().orEmpty()
            val offset = comment.optJSONObject("expand")?.optLong("offset_time", -1) ?: -1
            if (text.isEmpty() || offset < 0) return@mapNotNull null
            Danmaku(
                comment.optString("comment_id", ""), offset.coerceAtLeast(0L), text,
                (comment.optJSONObject("stat")?.optLong("digg_count", 0) ?: 0).coerceAtLeast(0L).toInt(),
            )
        }
        val listInfo = data.optJSONObject("common_list_info")
        var nextStart = data.optJSONObject("extra")?.optLong("next_query_danmaku_list_time", -1) ?: -1
        if (nextStart <= startMs) nextStart = startMs + WINDOW_MS
        return Page(comments, nextStart, listInfo?.optString("cursor", "").orEmpty(), listInfo?.optBoolean("has_more", false) ?: false)
    }

    companion object {
        const val WINDOW_MS = 30_000L
        private const val MAX_PAGES_PER_WINDOW = 3
        private const val MAX_COOLDOWN_RETRIES = 2
        private const val THROTTLE_MS = 180L
        private const val THROTTLE_JITTER_MS = 240L
        private const val MAX_BYTES = 2 * 1024 * 1024

        /** The risk-control reply message embeds a JSON payload with ban seconds. */
        internal fun cooldownMs(message: String): Long {
            val detail = try {
                JSONObject(message)
            } catch (invalid: Exception) {
                return 0L
            }
            val min = detail.optLong("min_ban_time", 0)
            val max = detail.optLong("max_ban_time", 0)
            return (if (min > 0) min else max).coerceAtLeast(0L) * 1000
        }

        internal fun dedupe(rows: List<Danmaku>): List<Danmaku> {
            val seen = LinkedHashMap<String, Danmaku>()
            for (row in rows) seen[if (row.id.isNotEmpty()) "id:${row.id}" else "anonymous:${row.offsetMs}:${row.text}"] = row
            return seen.values.sortedWith(compareBy({ it.offsetMs }, { it.id }, { it.text }))
        }
    }
}
