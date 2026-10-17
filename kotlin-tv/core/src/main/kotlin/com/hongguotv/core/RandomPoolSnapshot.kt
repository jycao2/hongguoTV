// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 随机荐剧候选池的本地持久化；只存剧集元数据，7 天有效。 */
object RandomPoolSnapshot {
    private const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_ITEMS = 300
    fun encode(items: List<Series>, now: Long): String = JSONObject()
        .put("savedAt", now)
        .put("items", JSONArray(items.take(MAX_ITEMS).map { it.copy(description = it.description.take(1500)).toJson() }))
        .toString()

    fun decode(value: String, now: Long): List<Series>? = runCatching {
        require(value.length <= 600_000)
        val o = JSONObject(value)
        require(now - o.getLong("savedAt") in 0..MAX_AGE)
        val rows = o.getJSONArray("items")
        (0 until rows.length()).map { Series.fromJson(rows.getJSONObject(it)) }
            .filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            .distinctBy { it.id }
    }.getOrNull()
}
