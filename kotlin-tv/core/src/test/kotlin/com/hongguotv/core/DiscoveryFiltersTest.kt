package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFiltersTest {
    private val firstPage = """
        {"code":0,"data":{"has_more":true,"next_offset":24,"session_id":"session-1",
        "selector_rows":[
          {"type":"genre","items":[{"selector_item_id":"short_play","show_name":"真人剧"},{"selector_item_id":"comic_series","show_name":"漫剧"},{"selector_item_id":"ai_series","show_name":"AI剧"}]},
          {"type":"category_dim_theme","items":[{"selector_item_id":"urban","show_name":"都市"},{"selector_item_id":"magic","show_name":"魔法"}]},
          {"type":"sort","items":[{"selector_item_id":"online_time","show_name":"最新上架"}]},
          {"type":"unknown_dim","items":[{"selector_item_id":"x","show_name":"未知"}]}
        ],
        "video_data":[
          {"series_id":"123","title":"测试剧","cover":"http:\/\/cover","video_desc":"简介","episode_cnt":12,
           "category_schema":[{"name":"都市"},{"name":"系统"}]},
          {"series_id":"bad","title":"非法ID","episode_cnt":1}
        ]}}
    """.trimIndent()

    private val nextPage = """
        {"code":0,"data":{"has_more":false,"video_data":[
          {"series_id":"456","title":"第二页剧","cover":"","video_desc":"","episode_cnt":0}
        ]}}
    """.trimIndent()

    @Test fun firstPageParsesItemsGroupsAndCursor() {
        val page = DiscoveryParser.parse(firstPage, true)
        assertEquals(1, page.items.size)
        assertEquals("测试剧", page.items.single().title)
        assertEquals("全 12 集", page.items.single().badge)
        assertEquals("都市 · 系统", page.items.single().tags)
        assertEquals(listOf("genre", "category_dim_theme", "sort"), page.groups.map { it.type })
        assertEquals("类型", page.groups.first().label)
        assertEquals(3, page.groups.first().options.size)
        assertEquals(DiscoveryCursor(24, "session-1"), page.next)
    }

    @Test fun continuationPageWithoutSelectorRowsStillParses() {
        val page = DiscoveryParser.parse(nextPage, false)
        assertEquals("第二页剧", page.items.single().title)
        assertTrue(page.groups.isEmpty())
        assertNull(page.next)
    }

    @Test fun selectionRequestCarriesCursorAndConditionFlags() {
        val selection = DiscoverySelection().choose("genre", "comic_series").choose("sort", "online_time")
        val first = JSONObject(selection.request(null))
        assertEquals(0, first.getInt("offset"))
        assertEquals(24, first.getInt("limit"))
        assertTrue(first.getBoolean("need_selector_panel"))
        assertEquals("default", first.getString("req_type"))
        assertEquals("comic_series", first.getString("req_scene"))
        assertTrue(first.getJSONObject("select_items").getJSONArray("genre").getString(0) == "comic_series")

        val cursor = DiscoveryCursor(24, "session-1")
        val next = JSONObject(selection.request(cursor))
        assertEquals(24, next.getInt("offset"))
        assertFalse(next.getBoolean("need_selector_panel"))
        assertEquals("only_content", next.getString("req_type"))
        assertEquals("session-1", next.getString("session_id"))
    }

    @Test fun switchingGenreClearsOtherConditions() {
        val selection = DiscoverySelection().choose("genre", "short_play").choose("sort", "online_time")
        assertEquals(2, selection.values.size)
        val switched = selection.choose("genre", "ai_series")
        assertEquals(mapOf("genre" to "ai_series"), switched.values)
        assertEquals("ai_series", switched.values["genre"])
        // AI 剧同属漫剧口径，req_scene 同步切换
        assertEquals("ai_series", JSONObject(switched.request(null)).getString("req_scene"))
    }

    @Test fun choosingAllClearsOnlyThatDimension() {
        val selection = DiscoverySelection().choose("genre", "comic_series").choose("sort", "online_time")
        val cleared = selection.choose("sort", "")
        assertNull(cleared.values["sort"])
        assertEquals("comic_series", cleared.values["genre"])
    }

    @Test fun rejectsUnknownDimensionAndIllegalId() {
        assertThrows(IllegalArgumentException::class.java) { DiscoverySelection().choose("unknown", "x") }
        assertThrows(IllegalArgumentException::class.java) { DiscoverySelection().choose("genre", "有中文") }
    }

    @Test fun rejectsBusinessErrorAndMissingPanel() {
        assertThrows(java.io.IOException::class.java) {
            DiscoveryParser.parse("""{"code":1001,"message":"bad"}""", true)
        }
        assertThrows(java.io.IOException::class.java) {
            DiscoveryParser.parse("""{"code":0,"data":{"video_data":[],"selector_rows":[{"type":"sort","items":[]}]}}""", true)
        }
    }

    @Test fun brokenCursorIsRejected() {
        assertThrows(java.io.IOException::class.java) {
            DiscoveryParser.parse("""{"code":0,"data":{"has_more":true,"next_offset":0,"session_id":"","video_data":[]}}""", false)
        }
    }
}
