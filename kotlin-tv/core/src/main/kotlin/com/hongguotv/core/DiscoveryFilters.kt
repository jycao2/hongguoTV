// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class DiscoveryFilter(val id: String, val label: String)
data class DiscoveryGroup(val type: String, val label: String, val options: List<DiscoveryFilter>)
data class DiscoveryCursor(val offset: Int, val session: String)
data class FilteredDiscoveryPage(
    val items: List<Series>,
    val groups: List<DiscoveryGroup> = emptyList(),
    val next: DiscoveryCursor? = null,
)

/**
 * 高级搜索的一组服务端条件。选项 id 全部来自响应，不硬编码词表；
 * 切换类型会作废其他维度，因为各类型的选项集合本身不同。
 */
data class DiscoverySelection(val values: Map<String, String> = emptyMap()) {
    fun choose(type: String, id: String): DiscoverySelection {
        require(type in LABELS)
        require(id.isEmpty() || id.matches(FILTER_ID))
        val next = if (type == "genre") emptyMap() else values
        return DiscoverySelection(if (id.isEmpty()) next - type else next + (type to id))
    }

    /** 第 1 页带 need_selector_panel，续页只取内容并沿用游标里的会话。 */
    fun request(cursor: DiscoveryCursor?): String {
        val selected = JSONObject()
        LABELS.keys.forEach { key ->
            selected.put(key, JSONArray().apply { values[key]?.let { put(it) } })
        }
        return JSONObject()
            .put("offset", cursor?.offset ?: 0)
            .put("limit", PAGE_SIZE)
            .put("need_selector_panel", cursor == null)
            .put("select_items", selected)
            .put("session_id", cursor?.session.orEmpty())
            .put("req_type", if (cursor == null) "default" else "only_content")
            .put("client_req_type", 3)
            .put("filter_ids", "")
            .put("req_scene", values["genre"]?.takeIf { it == "comic_series" || it == "ai_series" } ?: "default")
            .toString()
    }

    companion object {
        const val PAGE_SIZE = 24
        private val FILTER_ID = Regex("[a-zA-Z0-9_-]{1,80}")
        /** 与服务端 selector_rows.type 一一对应；展示顺序由响应决定。 */
        val LABELS = linkedMapOf(
            "genre" to "类型",
            "category_dim_theme" to "主题",
            "category_dim_role" to "设定",
            "category_dim_epoch" to "背景",
            "sort" to "推荐",
            "gender" to "受众",
            "online_time" to "时间",
            "creation_status" to "状态",
        )
    }
}

internal object DiscoveryParser {
    private val SERIES_ID = Regex("[0-9]{1,30}")
    private val OPTION_ID = Regex("[a-zA-Z0-9_-]{1,80}")

    fun parse(raw: String, firstPage: Boolean): FilteredDiscoveryPage {
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IOException("分类数据格式异常") }
        if (root.optInt("code", -1) != 0) throw IOException("分类服务暂不可用")
        val data = root.optJSONObject("data") ?: throw IOException("分类数据缺失")
        val rows = data.optJSONArray("video_data") ?: throw IOException("分类剧集列表缺失")
        val items = rows.objects().mapNotNull { video ->
            val id = video.str("series_id")
            val title = video.str("title")
            if (!id.matches(SERIES_ID) || title.isBlank()) null
            else {
                val tags = runCatching {
                    JSONArray(video.str("category_schema")).objects()
                        .map { it.str("name") }.filter { it.isNotBlank() }.take(5).joinToString(" · ")
                }.getOrDefault("")
                Series(
                    id,
                    title,
                    cleanUrl(video.str("cover")),
                    video.str("video_desc"),
                    video.optInt("episode_cnt", 0).takeIf { it > 0 }?.let { "全 $it 集" }.orEmpty(),
                    tags,
                )
            }
        }.distinctBy { it.id }
        if (rows.length() > 0 && items.isEmpty()) throw IOException("分类剧集格式异常")

        val groups = data.optJSONArray("selector_rows")?.objects().orEmpty().mapNotNull { row ->
            val type = row.str("type")
            val label = DiscoverySelection.LABELS[type] ?: return@mapNotNull null
            val options = row.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
                val id = item.str("selector_item_id")
                val name = item.str("show_name")
                if (id.matches(OPTION_ID) && name.isNotBlank() && name.length <= 40) DiscoveryFilter(id, name) else null
            }.distinctBy { it.id }.take(128)
            if (options.isEmpty()) null else DiscoveryGroup(type, label, options)
        }.distinctBy { it.type }
        if (firstPage && groups.none { it.type == "genre" }) throw IOException("分类筛选条件缺失")

        val next = if (!data.optBoolean("has_more", false)) null else {
            val offset = data.optInt("next_offset", -1)
            val session = data.str("session_id")
            if (offset <= 0 || session.isBlank() || session.length > 4096) throw IOException("分类续页信息异常，请刷新重试")
            DiscoveryCursor(offset, session)
        }
        return FilteredDiscoveryPage(items, groups, next)
    }
}
