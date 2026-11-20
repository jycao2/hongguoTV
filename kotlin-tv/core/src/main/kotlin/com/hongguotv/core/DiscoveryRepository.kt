// SPDX-License-Identifier: GPL-3.0-only
// 红果官方推荐流客户端：bookmall/tab/v（猜你喜欢首屏）+ bookmall/cell/change/v1（瀑布流续页），
// 兜底推荐榜 ranklist_hot_sc。参考 LanZhan-Harmony/PhoenixReadApi 与 2026-11-17 真机探针结论。
// 未登录（user_is_login=0）全功能可用；登录后带 Cookie 只提升个性化质量，不改变可用性。
// 设备身份由调用方提供并自行持久化（稳定 device_id/iid 是匿名个性化画像的载体，
// 不能随请求刷新，否则画像被反风控 regenerate() 打散）。
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class DiscoveryRepository(
    val http: OkHttpClient,
    /** 每次请求前取设备身份（登录后可换账号派生的 did/iid 提升个性化）。 */
    private val deviceProvider: () -> Map<String, String> = { VendorConstants.device },
    /** 登录 cookie（可空）；非空时请求带 Cookie 且 user_is_login=1。 */
    private val loginCookie: () -> String? = { null },
    /** API 主机列表；被网关拦截时自动换主机兜底（与 appSearch 同策略）。 */
    private val hosts: List<String> = listOf(
        "https://api5-normal-sinfonlinea.fqnovel.com",
        "https://reading.snssdk.com",
    ),
) {
    private companion object {
        const val MODE_TAB = 0       // 猜你喜欢：tab/v 首屏 + cell/change 续页
        const val MODE_RANK = 1      // 推荐榜：固定 cell_id（ranklist_hot_sc）翻页
        /** 短剧推荐 tab（官方 DiscoverChannel.Recommend 的 responseTab） */
        const val DEFAULT_TAB = 16
        const val RANK_CELL_ID = "7470092475068071998"
        const val RANK_BOARD = "ranklist_hot_sc"
        const val MAX_EMPTY_PAGES = 2
    }

    private var mode = MODE_TAB
    private var cellId = ""
    /** 续页必须带 show_type（官方从 tab 的流式容器 cell 上取），缺了服务端会给空页 */
    private var showType = ""
    private var tabType = 16
    private var template = 7
    private var sessionId = ""
    private var offset = 0
    private var emptyPages = 0
    private val seen = LinkedHashSet<String>()
    private val lock = Any()

    /** 最近一次成功拉取走的链路（TAB/RANK），供 App 层打日志排查「是不是退化成热门榜」。 */
    @Volatile
    var lastRoute: String = "TAB"
        private set

    /** 最近一次 TAB 链路失败的原因（空串=没失败），配合 lastRoute 排查。 */
    @Volatile
    var lastNote: String = ""
        private set

    /** 重置游标（切页/下拉刷新调用；seen 一并清空，允许重新出现历史内容）。 */
    fun reset() = synchronized(lock) {
        mode = MODE_TAB; cellId = ""; showType = ""; sessionId = ""
        tabType = DEFAULT_TAB; template = 7; offset = 0; emptyPages = 0
        seen.clear()
    }

    /**
     * 拉下一页推荐。链路：猜你喜欢（tab/v 首屏 → cell/change 续页）→ 推荐榜 → 抛异常
     * （调用方捕获后回退本地 home()）。串行调用（HomePage 的 worker 队列满足）。
     */
    fun nextPage(): CatalogPage = synchronized(lock) {
        var failure: Exception? = null
        if (mode == MODE_TAB) {
            try {
                val page = if (cellId.isEmpty()) tabFirstPage() else tabNextPage()
                lastRoute = "TAB"; lastNote = ""
                return@synchronized page
            } catch (e: Exception) { failure = e; lastNote = e.message.orEmpty() }
            mode = MODE_RANK; offset = 0; emptyPages = 0
        }
        try { return@synchronized rankPage().also { lastRoute = "RANK" } } catch (e: Exception) { failure = e }
        throw IOException("推荐接口暂不可用（${failure?.message ?: "未知错误"}）")
    }

    // ---- 猜你喜欢 ----

    private fun tabFirstPage(): CatalogPage {
        val device = deviceProvider()
        val values = device.toMutableMap().apply {
            put("tab_type", "-1"); put("bottom_tab_type", "7"); put("bottom_tab_type_list", "7,0,1,2,3,4")
            put("offset", "0"); put("client_req_type", "3"); put("user_is_login", loginFlag())
        }
        val data = fetch("/reading/bookapi/bookmall/tab/v", device, values)
        val items = collect(data)
        if (items.isEmpty()) throw IOException("猜你喜欢首屏无剧集")
        // 与官方口径一致：先按 tab_type=16（短剧推荐）挑 tab，再从它的 cell_data 里找「流式容器」
        // 取 cell_id + show_type；顶层 data 的 cell_id_str 常常是别的频道容器，拿它续页会接错流。
        val tabs = data.optJSONArray("tab_item")?.objects().orEmpty()
        val tab = tabs.firstOrNull { it.optInt("tab_type") == DEFAULT_TAB } ?: tabs.firstOrNull()
        sessionId = tab?.optString("session_id").orEmpty().ifEmpty { data.optString("session_id") }
        template = tab?.optInt("client_template", 0)?.takeIf { it > 0 } ?: data.optInt("client_template", 7)
        tabType = tab?.optInt("tab_type", DEFAULT_TAB)?.takeIf { it > 0 } ?: DEFAULT_TAB
        val continuation = tab?.let { findContinuation(it) }
        cellId = continuation?.first ?: data.optString("cell_id_str").ifEmpty { data.optString("cell_id") }
        showType = continuation?.second.orEmpty()
        offset = 0
        // 官方续页硬依赖 session_id；没有就换推荐榜续流
        if (cellId.isEmpty() || sessionId.isEmpty()) mode = MODE_RANK
        return CatalogPage(items, true)
    }

    /** tab 里找瀑布流容器（有嵌套子 cell 的那种），返回 cell_id 与 show_type。 */
    private fun findContinuation(tab: JSONObject): Pair<String, String>? {
        val cells = tab.optJSONArray("cell_data")?.objects().orEmpty()
        var fallback: Pair<String, String>? = null
        for (cell in cells) {
            val id = cell.optString("cell_id_str").ifEmpty { cell.optString("cell_id") }
            val show = cell.optInt("show_type", 0)
            if (id.isEmpty() || show == 0) continue
            val entry = id to show.toString()
            if ((cell.optJSONArray("cell_data")?.length() ?: 0) > 0) return entry
            if (fallback == null) fallback = entry
        }
        return fallback
    }

    private fun tabNextPage(): CatalogPage {
        val device = deviceProvider()
        val values = device.toMutableMap().apply {
            put("cell_id", cellId); put("offset", offset.toString()); put("limit", "20")
            put("change_type", "0"); put("tab_type", tabType.toString())
            if (showType.isNotEmpty()) put("show_type", showType)
            put("client_req_type", "2")
            if (sessionId.isNotEmpty()) put("session_id", sessionId)
            if (template > 0) put("client_template", template.toString())
            put("user_is_login", loginFlag())
        }
        val data = fetch("/reading/bookapi/bookmall/cell/change/v", device, values)
        val items = collect(data)
        advance(data.optInt("next_offset", offset), items.size)
        data.optString("session_id").takeIf { it.isNotEmpty() }?.let { sessionId = it }
        // 连续空页（都是看过的重复内容）就切推荐榜续流，避免原地打转
        if (items.isEmpty() && ++emptyPages >= MAX_EMPTY_PAGES) { mode = MODE_RANK; offset = 0; emptyPages = 0 }
        return CatalogPage(items, data.optBoolean("has_more", true))
    }

    // ---- 推荐榜（ranklist_hot_sc，个性化排序流） ----

    private fun rankPage(): CatalogPage {
        val device = deviceProvider()
        val values = device.toMutableMap().apply {
            put("cell_id", RANK_CELL_ID); put("tab_type", "26")
            put("selected_items", RANK_BOARD); put("offset", offset.toString()); put("limit", "0")
            put("client_req_type", "2"); put("client_template", "2")
            put("unlimited_selector_change_type", if (offset > 0) "2" else "1")
            put("user_is_login", loginFlag())
        }
        val data = fetch("/reading/bookapi/bookmall/cell/change/v1/", device, values)
        val items = collect(data)
        advance(data.optInt("next_offset", offset + items.size), items.size)
        data.optString("session_id").takeIf { it.isNotEmpty() }?.let { sessionId = it }
        return CatalogPage(items, data.optBoolean("has_more", true))
    }

    // ---- 解析 ----

    /** 递归收集 video_data / video_detail.video_list 条目（响应结构层层嵌套，扁平遍历最稳）。 */
    private fun collect(node: JSONObject?): List<Series> {
        if (node == null) return emptyList()
        val rows = mutableListOf<JSONObject>()
        walk(node, rows)
        val fresh = mutableListOf<Series>()
        for (row in rows) {
            val series = drama(row) ?: continue
            if (seen.add(series.id)) fresh += series
        }
        return fresh
    }

    private fun walk(node: Any?, sink: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("video_data")?.objects()?.forEach { sink += it }
                node.optJSONObject("video_detail")?.optJSONArray("video_list")?.objects()?.forEach { sink += it }
                for (key in node.keys()) walk(node.opt(key), sink)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), sink)
        }
    }

    /** video_data 条目 → Series；PUGC 合集（官网无详情页、播放不了）与书城条目过滤掉。 */
    private fun drama(v: JSONObject): Series? {
        val id = v.optString("series_id").trim()
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        if (v.optString("video_category_type").startsWith("pugc")) return null
        val cnt = v.optInt("episode_cnt", 0)
        val contentType = v.optInt("content_type", 1)
        if (contentType != 1 && cnt <= 0) return null
        val rawTitle = v.optString("series_name").ifEmpty { v.optString("title") }.trim()
        if (rawTitle.isBlank()) return null
        val badge = if (cnt > 0) "全 $cnt 集" else v.optString("rec_text")
        return Series(
            id, seriesTitle(cleanTitle(rawTitle)),
            cleanUrl(v.optString("cover").ifEmpty { v.optString("cover_url") }),
            v.optString("video_desc"), badge, v.optString("sub_title"),
        )
    }

    /** tab/v 的条目标题常是「《剧名》第一季 | 片段名 12集」，削掉竖线后的片段信息并去书名号。 */
    private fun cleanTitle(raw: String): String {
        val title = raw.substringBefore('|').substringBefore('｜')
            .replace("《", "").replace("》", " ").trim()
        return title
    }

    /** 官方 next_offset 停滞时按本页增量推进，防止游标原地打转。 */
    private fun advance(returned: Int, gained: Int) {
        offset = if (returned > offset) returned else offset + gained.coerceAtLeast(1)
    }

    private fun loginFlag() = if (loginCookie().isNullOrBlank()) "0" else "1"

    // ---- 传输 ----

    private fun fetch(path: String, device: Map<String, String>, values: Map<String, String>): JSONObject {
        var failure: Exception? = null
        for (host in hosts) {
            try {
                val signed = Signer(device = device).sign("$host$path", values)
                val builder = Request.Builder().url(signed.url)
                signed.headers.forEach { (k, v) -> builder.header(k, v) }
                loginCookie()?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
                http.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw IOException("响应为空")
                    val payload = JSONObject(body)
                    if (payload.optInt("code", -1) != 0) throw IOException(payload.optString("message", "接口返回异常"))
                    return payload.optJSONObject("data") ?: throw IOException("响应缺少 data")
                }
            } catch (e: Exception) { failure = e }
        }
        throw failure ?: IOException("推荐接口请求失败")
    }
}
