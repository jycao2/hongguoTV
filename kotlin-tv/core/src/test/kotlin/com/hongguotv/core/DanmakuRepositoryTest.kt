package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DanmakuRepositoryTest {
    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    private fun windowJson(start: Long, next: Long, comments: List<Triple<String, Long, String>>, hasMore: Boolean = false, cursor: String = ""): String {
        val list = JSONArray()
        comments.forEach { (id, offset, text) ->
            list.put(JSONObject().put("comment", JSONObject()
                .put("comment_id", id)
                .put("common", JSONObject().put("content", JSONObject().put("text", text)))
                .put("expand", JSONObject().put("offset_time", offset))
                .put("stat", JSONObject().put("digg_count", 7))))
        }
        return JSONObject().put("code", 0).put("data", JSONObject()
            .put("data_list", list)
            .put("common_list_info", JSONObject().put("cursor", cursor).put("has_more", hasMore))
            .put("extra", JSONObject().put("next_query_danmaku_list_time", next))).toString()
    }

    private fun repository(respond: (Long, String) -> String): DanmakuRepository {
        now = 0L; sleeps.clear()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            val start = payload.getJSONObject("business_param").getLong("start_offset_time")
            assertEquals("601", request.header("comment-source"))
            assertEquals("1000", request.header("server-channel"))
            assertTrue(request.url.encodedPath.endsWith("/novel/commentapi/comment/list/999/v1/"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(start, payload.optString("cursor")).toResponseBody()).build()
        }.build()
        return DanmakuRepository(http, { now }, { sleeps += it }, { 0.0 })
    }

    @Test fun windowParsesAnchoredCommentsAndNextStart() {
        val repo = repository { start, _ ->
            assertEquals(0L, start)
            windowJson(start, 30_000, listOf(Triple("a", 1200, "前排"), Triple("b", 45_000, "后续")))
        }
        val window = repo.window("123", "999", 120_000, 0)
        assertEquals(30_000, window.nextStartMs)
        assertEquals(listOf(Danmaku("a", 1200, "前排", 7), Danmaku("b", 45_000, "后续", 7)), window.comments)
    }

    @Test fun emptyGatewayReplyDegradesToEmptyWindowInsteadOfCrashing() {
        val repo = repository { _, _ -> "" }
        val window = repo.window("123", "999", 120_000, 60_000)
        assertTrue(window.comments.isEmpty())
        assertEquals(90_000, window.nextStartMs)
    }

    @Test fun nonJsonRiskControlPageDegradesToEmptyWindow() {
        val repo = repository { _, _ -> "<html>blocked</html>" }
        assertTrue(repo.window("123", "999", 120_000, 0).comments.isEmpty())
    }

    @Test fun serverCooldownIsHonoredBeforeRetrying() {
        var calls = 0
        val repo = repository { start, _ ->
            calls++
            if (calls == 1) JSONObject().put("code", 8).put("message", """{"min_ban_time":3,"max_ban_time":5}""").toString()
            else windowJson(start, 30_000, listOf(Triple("a", 100, "来了")))
        }
        val window = repo.window("123", "999", 120_000, 0)
        assertEquals(2, calls)
        assertEquals(listOf(3_000L), sleeps.filter { it >= 1_000 })
        assertEquals(1, window.comments.size)
    }

    @Test fun persistentCooldownEventuallyFails() {
        val repo = repository { _, _ -> JSONObject().put("code", 8).put("message", """{"min_ban_time":2}""").toString() }
        assertThrows(IOException::class.java) { repo.window("123", "999", 120_000, 0) }
        assertEquals(listOf(2_000L, 2_000L), sleeps.filter { it >= 1_000 })
    }

    @Test fun plainErrorCodeWithoutCooldownFailsImmediately() {
        val repo = repository { _, _ -> JSONObject().put("code", 1001).put("message", "bad request").toString() }
        assertThrows(IOException::class.java) { repo.window("123", "999", 120_000, 0) }
    }

    @Test fun nextStartNeverMovesBackwards() {
        val repo = repository { start, _ -> windowJson(start, start, emptyList()) }
        assertEquals(30_000, repo.window("123", "999", 120_000, 0).nextStartMs)
    }

    @Test fun inWindowPaginationFollowsCursorAndDeduplicates() {
        val repo = repository { start, cursor ->
            if (cursor.isEmpty()) windowJson(start, 30_000, listOf(Triple("a", 100, "一"), Triple("b", 200, "二")), hasMore = true, cursor = "p2")
            else windowJson(start, 30_000, listOf(Triple("b", 200, "二"), Triple("c", 300, "三")))
        }
        val window = repo.window("123", "999", 120_000, 0)
        assertEquals(listOf("a", "b", "c"), window.comments.map { it.id })
        assertEquals(30_000, window.nextStartMs)
    }

    @Test fun consecutiveRequestsAreThrottled() {
        val repo = repository { start, _ -> windowJson(start, start + 30_000, emptyList()) }
        repo.window("123", "999", 120_000, 0)
        repo.window("123", "999", 120_000, 30_000)
        assertEquals(listOf(180L), sleeps)
    }

    @Test fun firstRequestAtRealEpochDoesNotSleep() {
        val repo = repository { start, _ -> windowJson(start, start + 30_000, emptyList()) }
        now = 1_792_016_000_000L // A realistic epoch-millis clock; guards the throttle underflow regression.
        repo.window("123", "999", 120_000, 0)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun cooldownMessageParsing() {
        assertEquals(3_000, DanmakuRepository.cooldownMs("""{"min_ban_time":3,"max_ban_time":5}"""))
        assertEquals(5_000, DanmakuRepository.cooldownMs("""{"max_ban_time":5}"""))
        assertEquals(0, DanmakuRepository.cooldownMs("not json"))
        assertEquals(0, DanmakuRepository.cooldownMs(""))
    }

    @Test fun dedupeSortsByOffsetAndPrefersIds() {
        val rows = listOf(
            Danmaku("b", 200, "二", 0), Danmaku("a", 100, "一", 0), Danmaku("b", 200, "二-改", 0),
            Danmaku("", 150, "匿名", 0), Danmaku("", 150, "匿名", 0),
        )
        val result = DanmakuRepository.dedupe(rows)
        assertEquals(listOf(Danmaku("a", 100, "一", 0), Danmaku("", 150, "匿名", 0), Danmaku("b", 200, "二-改", 0)), result)
    }
}

class DanmakuLoaderTest {
    private var now = 0L

    private fun windowJson(start: Long, next: Long, texts: List<String>): String {
        val list = JSONArray()
        texts.forEachIndexed { index, text ->
            list.put(JSONObject().put("comment", JSONObject()
                .put("comment_id", "$start-$index")
                .put("common", JSONObject().put("content", JSONObject().put("text", text)))
                .put("expand", JSONObject().put("offset_time", start + index * 1000))))
        }
        return JSONObject().put("code", 0).put("data", JSONObject()
            .put("data_list", list)
            .put("common_list_info", JSONObject().put("cursor", "").put("has_more", false))
            .put("extra", JSONObject().put("next_query_danmaku_list_time", next))).toString()
    }

    private fun loader(durationMs: Long, leadMs: Long = 60_000, respond: (Long) -> String): Pair<DanmakuLoader, MutableList<Long>> {
        now = 0L
        val requested = mutableListOf<Long>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            val start = JSONObject(buffer.readUtf8()).getJSONObject("business_param").getLong("start_offset_time")
            requested += start
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(start).toResponseBody()).build()
        }.build()
        val repo = DanmakuRepository(http, { now }, { }, { 0.0 })
        return DanmakuLoader(repo, "123", "999", durationMs, leadMs, { now }) to requested
    }

    @Test fun buffersWindowsAheadOfPlayback() {
        val (loader, requested) = loader(180_000) { start -> windowJson(start, start + 30_000, listOf("弹幕$start")) }
        loader.loadUpTo(0)
        assertEquals(listOf(0L, 30_000L, 60_000L), requested)
        assertEquals(3, loader.buffered)
        assertEquals(listOf("弹幕0"), loader.between(0, 1_000).map { it.text })
        assertEquals(listOf("弹幕30000"), loader.between(1_000, 31_000).map { it.text })
    }

    @Test fun forwardSeekSkipsUnwatchedWindows() {
        val (loader, requested) = loader(600_000) { start -> windowJson(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        requested.clear()
        loader.loadUpTo(300_000)
        assertEquals(listOf(300_000L, 330_000L, 360_000L), requested)
    }

    @Test fun stopsRequestingOnceEpisodeFullyBuffered() {
        val (loader, requested) = loader(60_000) { start -> windowJson(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        val count = requested.size
        loader.loadUpTo(50_000)
        assertEquals(count, requested.size)
    }

    @Test fun failuresBackOffAndEventuallyGiveUpSilently() {
        val (loader, requested) = loader(600_000) { throw IOException("offline") }
        loader.loadUpTo(0)
        assertEquals(1, requested.size)
        loader.loadUpTo(0)
        assertEquals(1, requested.size)
        now += 31_000
        loader.loadUpTo(0)
        assertEquals(2, requested.size)
        now += 31_000
        loader.loadUpTo(0)
        assertEquals(3, requested.size)
        now += 31_000
        loader.loadUpTo(0)
        assertEquals(3, requested.size)
    }

    @Test fun betweenFiltersByOffsetRange() {
        val (loader, _) = loader(120_000) { start -> windowJson(start, start + 30_000, listOf("a", "b", "c")) }
        loader.loadUpTo(0)
        assertEquals(listOf("a", "b"), loader.between(0, 2_000).map { it.text })
        assertTrue(loader.between(5_000, 5_000).isEmpty())
    }

    @Test fun unknownDurationWaitsForMetadata() {
        val (loader, requested) = loader(0) { start -> windowJson(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        assertTrue(requested.isEmpty())
        loader.durationMs = 120_000
        loader.loadUpTo(0)
        assertFalse(requested.isEmpty())
    }
}
