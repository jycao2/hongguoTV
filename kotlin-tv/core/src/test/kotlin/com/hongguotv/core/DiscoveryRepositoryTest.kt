// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiscoveryRepositoryTest {
    private fun drama(id: String, title: String, cnt: Int = 12, contentType: Int = 1, categoryType: String = "") =
        JSONObject().put("series_id", id).put("title", title).put("cover", "https://img/$id.jpg")
            .put("video_desc", "简介$id").put("episode_cnt", cnt).put("content_type", contentType)
            .put("video_category_type", categoryType).put("sub_title", "都市·全${cnt}集")

    /** tab/v 响应：tab_item → cell_data（一层直接 video_data；一层 video_detail.video_list）。 */
    private fun tabBody(vararg entries: JSONObject): String {
        val cells = org.json.JSONArray()
        for (e in entries) cells.put(JSONObject().put("cell_name", "小卡").put("video_data", org.json.JSONArray().put(e)))
        return JSONObject().put("code", 0).put("data", JSONObject()
            .put("session_id", "S-TAB")
            .put("cell_id_str", "111")
            .put("client_template", 7)
            .put("tab_item", org.json.JSONArray().put(JSONObject().put("tab_type", 16).put("cell_data", org.json.JSONArray().put(JSONObject().put("cell_name", "猜你喜欢").put("cell_data", cells)))))
        ).toString()
    }

    /** cell/change 响应（cell_view.cell_data[].video_data[]）。 */
    private fun cellBody(vararg entries: JSONObject, nextOffset: Int = 10, hasMore: Boolean = true, session: String = "S-CELL") =
        JSONObject().put("code", 0).put("data", JSONObject()
            .put("session_id", session).put("has_more", hasMore).put("next_offset", nextOffset)
            .put("cell_view", JSONObject().put("cell_data", org.json.JSONArray().put(JSONObject().put("video_data", org.json.JSONArray().put(entries[0])))))
        ).toString()

    private class ScriptedDispatcher(private val tab: String?, private val pages: MutableList<String>) : Dispatcher() {
        val rankRequests = mutableListOf<RecordedRequest>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            return when {
                path.endsWith("/bookmall/tab/v") && tab != null -> json(tab)
                path.endsWith("/cell/change/v1/") && request.requestUrl!!.queryParameter("cell_id") == "7470092475068071998" -> {
                    rankRequests += request
                    json(pages.removeFirstOrNull() ?: error("no more scripted pages"))
                }
                path.endsWith("/cell/change/v1/") -> json(pages.removeFirstOrNull() ?: error("no more scripted pages"))
                else -> MockResponse().setResponseCode(500)
            }
        }
        private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
    }

    private fun repo(server: MockWebServer, dispatcher: Dispatcher): DiscoveryRepository {
        server.dispatcher = dispatcher
        return DiscoveryRepository(OkHttpClient(), hosts = listOf(server.url("/").toString().trimEnd('/')))
    }

    @Test fun `tab first page parses dramas filters pugc and dedups`() {
        val dispatcher = ScriptedDispatcher(tabBody(
            drama("100", "《剧甲》第一季 | 超燃 12集"),
            drama("100", "重复的剧甲"),
            drama("200", "剧乙"),
            drama("300", "PUGC合集", contentType = 1001, categoryType = "pugc_album_video"),
        ), mutableListOf(cellBody(drama("400", "剧丙"))))
        val server = MockWebServer(); server.start()
        try {
            val r = repo(server, dispatcher)
            val page = r.nextPage()
            assertEquals(listOf("100", "200"), page.items.map { it.id })
            assertEquals("剧甲 第一季", page.items[0].title)
            assertTrue(page.hasMore)
            // 续页走 cell/change 并带 tab 游标
            val next = r.nextPage()
            assertEquals(listOf("400"), next.items.map { it.id })
        } finally { server.shutdown() }
    }

    @Test fun `tab failure falls back to ranklist paging`() {
        val dispatcher = ScriptedDispatcher(null, mutableListOf(
            cellBody(drama("747001", "榜一"), nextOffset = 10),
            cellBody(drama("747002", "榜二"), nextOffset = 20),
        ))
        val server = MockWebServer(); server.start()
        try {
            val r = repo(server, dispatcher)
            assertEquals(listOf("747001"), r.nextPage().items.map { it.id })
            assertEquals(listOf("747002"), r.nextPage().items.map { it.id })
            assertEquals(2, dispatcher.rankRequests.size)
            assertEquals("ranklist_hot_sc", dispatcher.rankRequests[0].requestUrl!!.queryParameter("selected_items"))
            assertEquals("2", dispatcher.rankRequests[1].requestUrl!!.queryParameter("unlimited_selector_change_type"))
        } finally { server.shutdown() }
    }

    @Test fun `all endpoints failing throws`() {
        val dispatcher = ScriptedDispatcher(null, mutableListOf())
        val server = MockWebServer(); server.start()
        try {
            val r = repo(server, dispatcher)
            runCatching { r.nextPage() }.onSuccess { fail("should throw") }
                .onFailure { assertTrue(it is IOException) }
        } finally { server.shutdown() }
    }

    @Test fun `reset clears cursor and seen`() {
        val dispatcher = ScriptedDispatcher(tabBody(drama("100", "剧甲")), mutableListOf(
            cellBody(drama("400", "剧丙")),
            cellBody(drama("100", "剧甲重播")),
        ))
        val server = MockWebServer(); server.start()
        try {
            val r = repo(server, dispatcher)
            r.nextPage()
            r.reset()
            assertEquals(listOf("100"), r.nextPage().items.map { it.id })
        } finally { server.shutdown() }
    }

    @Test fun `login flag and cookie follow the cookie provider`() {
        val dispatcher = ScriptedDispatcher(tabBody(drama("100", "剧甲")), mutableListOf())
        val server = MockWebServer(); server.start()
        try {
            val r = DiscoveryRepository(OkHttpClient(),
                loginCookie = { "sid=abc" },
                hosts = listOf(server.url("/").toString().trimEnd('/')))
            server.dispatcher = dispatcher
            r.nextPage()
            val taken = server.takeRequest()
            assertEquals("1", taken.requestUrl!!.queryParameter("user_is_login"))
            assertEquals("sid=abc", taken.getHeader("Cookie"))
        } finally { server.shutdown() }
    }
}
