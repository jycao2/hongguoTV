// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** Main-thread-owned library. JSON is parsed once, and getters never parse or sort. */
class LibraryIndex(data: BackupData, updates: Map<String, FavoriteUpdate> = emptyMap(),
                   cloudUpdates: Map<String, FavoriteUpdate> = emptyMap(), cloudNewIds: Set<String> = emptySet()) {
    private var favoriteRows = data.favorites.distinctBy { it.id }.take(500)
    private var favoriteIds = favoriteRows.map { it.id }.toSet()
    private var laterRows = data.later.distinctBy { it.id }.take(100)
    private var laterIds = laterRows.map { it.id }.toSet()
    private var historyRows = data.history.sortedByDescending { it.updatedAt }.distinctBy { it.series.id }.take(200)
    private val progressById = historyRows.associateBy { it.series.id }.toMutableMap()
    private var watchedIds = data.watched.toSet()
    private var hiddenIds = data.hidden.take(500).toSet()
    private var searchRows = data.searches.take(20)
    private var updateRows = updates.filterKeys { it in favoriteIds }
    private var updateCount = updateRows.values.count { it.added > 0 }
    /** 云端收藏的更新基线：与红果书架对账用的本机状态，独立于本机收藏、不进备份。 */
    private var cloudUpdateRows = cloudUpdates.entries.take(500).associate { it.key to it.value }.toMutableMap()
    /** 云端书架里上次检查后新出现的条目，展示「新收藏」角标，点进详情后移除。 */
    private var cloudNewIds = cloudNewIds.take(500).toMutableSet()
    private var groupById = data.favoriteGroups.filterKeys { it in favoriteIds }
    var settings = data.settings

    fun favorites() = favoriteRows
    fun favorite(id: String) = id in favoriteIds
    fun later() = laterRows
    fun queued(id: String) = id in laterIds
    fun history() = historyRows
    fun progress(id: String) = progressById[id]
    fun watched(id: String) = id in watchedIds
    fun watched() = watchedIds
    fun hidden() = hiddenIds
    fun searches() = searchRows
    fun updates() = updateRows
    fun favoriteUpdate(id: String) = updateRows[id]
    fun updatedFavorites() = updateCount
    fun cloudUpdate(id: String) = cloudUpdateRows[id]
    fun cloudUpdates() = cloudUpdateRows.toMap()
    fun cloudBaselineCount() = cloudUpdateRows.size
    fun updatedCloudFavorites() = cloudUpdateRows.values.count { it.hasUpdate }
    fun isCloudNew(id: String) = id in cloudNewIds
    fun cloudNewCount() = cloudNewIds.size
    fun cloudNewIds() = cloudNewIds.toSet()
    fun groups() = favoriteRows.mapNotNull { groupById[it.id] }.distinct()
    fun groupOf(id: String) = groupById[id]
    fun groupsSnapshot() = groupById.toMap()
    fun setGroup(id: String, group: String?): Boolean {
        if (!favorite(id)) return false
        val name = group?.trim()?.filterNot { it.isISOControl() }?.take(12).orEmpty()
        if (name.isEmpty()) {
            if (id !in groupById) return false
            groupById = groupById - id
            return true
        }
        if (groupById[id] == name) return false
        if (name !in groupById.values && groupById.values.toSet().size >= 20) return false
        groupById = groupById + (id to name)
        return true
    }

    fun toggle(series: Series): Boolean {
        val added = !favorite(series.id)
        favoriteRows = (if (added) listOf(series) + favoriteRows else favoriteRows.filterNot { it.id == series.id }).take(500)
        favoriteIds = favoriteRows.map { it.id }.toSet()
        updateRows = updateRows.filterKeys { it in favoriteIds }
        updateCount = updateRows.values.count { it.added > 0 }
        if (!added) groupById = groupById - series.id
        return added
    }
    fun toggleLater(series: Series): Boolean {
        val added = !queued(series.id)
        laterRows = (if (added) listOf(series) + laterRows else laterRows.filterNot { it.id == series.id }).take(100)
        laterIds = laterRows.map { it.id }.toSet()
        return added
    }
    /** A paused player's identical checkpoint does not refresh its timestamp or trigger a write. */
    fun save(progress: BackupProgress): Boolean {
        val previous = progressById[progress.series.id]
        if (previous != null && previous.copy(updatedAt = progress.updatedAt) == progress) return false
        historyRows = (listOf(progress) + historyRows.filterNot { it.series.id == progress.series.id }).take(200).sortedByDescending { it.updatedAt }
        progressById.clear()
        historyRows.forEach { progressById[it.series.id] = it }
        return true
    }
    fun setWatched(id: String, value: Boolean): Boolean {
        if (watched(id) == value) return false
        watchedIds = if (value) watchedIds + id else watchedIds - id
        return true
    }
    fun hide(id: String) { hiddenIds = (hiddenIds + id).toList().takeLast(500).toSet() }
    fun unhideAll() { hiddenIds = emptySet() }
    fun rememberSearch(query: String, type: ContentType) { searchRows = SearchHistory.remember(searchRows, query, type) }
    fun clearSearches() { searchRows = emptyList() }
    fun removeHistory(id: String) {
        historyRows = historyRows.filterNot { it.series.id == id }
        progressById.remove(id)
        watchedIds = watchedIds - id
    }
    fun clearHistory() { historyRows = emptyList(); progressById.clear(); watchedIds = emptySet() }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean, newSeason: Series? = null): Boolean {
        if (!favorite(id)) return false
        val previous = updateRows[id]
        var next = previous?.observe(count, now, acknowledge) ?: FavoriteUpdate.first(count, now)
        if (!acknowledge) next = next.observeNewSeason(newSeason?.id, newSeason?.title, now)
        if (previous == next) return false
        updateRows = updateRows + (id to next)
        updateCount += (if (next.hasUpdate) 1 else 0) - (if (previous?.hasUpdate == true) 1 else 0)
        return true
    }

    /**
     * 云端收藏基线：count 来自云端书架自带的集数。
     * 首次记录（无基线）默认静默；调用方明确 markNew（此前已有基线、本次书架新出现的条目）才记「新收藏」；
     * 集数变小（疑似换季）不直接计入加更，由调用方补查详情后经 newSeason 传入。
     */
    fun observeCloudFavorite(id: String, count: Int, now: Long, markNew: Boolean = false, newSeason: Series? = null): Boolean {
        if (count <= 0) return false
        val previous = cloudUpdateRows[id]
        if (previous == null) {
            cloudUpdateRows[id] = FavoriteUpdate.first(count, now)
            if (markNew) cloudNewIds += id
            return true
        }
        // 集数回退可能是换季重置：保留旧基线，只接受新季标记或显式确认
        if (count < previous.total) {
            val next = previous.observeNewSeason(newSeason?.id, newSeason?.title, now)
            if (next == previous) return false
            cloudUpdateRows[id] = next
            return true
        }
        var next = previous.observe(count, now)
        next = next.observeNewSeason(newSeason?.id, newSeason?.title, now)
        if (next == previous) return false
        cloudUpdateRows[id] = next
        return true
    }

    /** 用户点进云端收藏详情：以当前云端集数重置基线，同时清掉「新收藏」与更新角标。无功能变化返回 false。 */
    fun acknowledgeCloudFavorite(id: String, count: Int, now: Long): Boolean {
        val hadNew = cloudNewIds.remove(id)
        val current = cloudUpdateRows[id] ?: return hadNew
        val next = when {
            count > 0 && count >= current.total -> current.observe(count, now, acknowledge = true)
            else -> current.observe(current.total, now, acknowledge = true)
        }
        if (next == current) return hadNew
        // checkedAt 变化不算功能变化：重复确认不产生写盘，只有基线/角标真正变了才记录
        val functional = next.total != current.total || next.added != current.added || next.hasNewSeason != current.hasNewSeason
        if (!functional) return hadNew
        cloudUpdateRows[id] = next
        return true
    }

    /** 书架里已不存在的收藏（用户在手机端取消了）连带清掉基线与新标记。 */
    fun pruneCloudFavorites(keepIds: Set<String>): Boolean {
        val stale = cloudUpdateRows.keys - keepIds
        val before = cloudNewIds.size
        cloudNewIds.retainAll(keepIds)
        if (stale.isNotEmpty()) cloudUpdateRows.keys.removeAll(stale)
        return stale.isNotEmpty() || cloudNewIds.size != before
    }
    fun snapshot(thirdSources: List<BackupThirdSource> = emptyList(),
                 thirdHistory: List<BackupThirdHistory> = emptyList(),
                 thirdFavorites: List<BackupThirdFavorite> = emptyList()) = BackupData(favoriteRows, historyRows, watchedIds, searchRows, settings, laterRows, hiddenIds, groupById, thirdSources, thirdHistory, thirdFavorites)
}

/** Keeps the native-library-v1 disk keys and rich local metadata compatible with older APKs. */
object LibraryDisk {
    fun decode(values: Map<String, *>): LibraryIndex {
        fun rows(key: String) = runCatching { JSONArray(values[key] as? String ?: "[]") }.getOrDefault(JSONArray())
        fun series(key: String): List<Series> {
            val data = rows(key)
            return (0 until data.length()).mapNotNull { runCatching { Series.fromJson(data.getJSONObject(it)) }.getOrNull() }
        }
        fun ids(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val history = rows("progress").let { rows ->
            (0 until rows.length()).mapNotNull { i -> runCatching {
                val o = rows.getJSONObject(i)
                BackupProgress(Series.fromJson(o.getJSONObject("series")), o.getString("episodeId"), o.getInt("episodeIndex"),
                    o.getLong("position"), o.getLong("duration"), o.optBoolean("completed"), o.getLong("updatedAt"))
            }.getOrNull() }
        }
        val rawUpdates = runCatching { JSONObject(values["favoriteUpdates"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val updates = rawUpdates.keys().asSequence().mapNotNull { id -> FavoriteUpdate.decode(rawUpdates.optJSONObject(id))?.let { id to it } }.toMap()
        // 云端收藏上新基线与书架新条目标记：只在本机与红果书架之间对账，不进备份
        val rawCloud = runCatching { JSONObject(values["cloudUpdates"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val cloudUpdates = rawCloud.keys().asSequence().mapNotNull { id -> FavoriteUpdate.decode(rawCloud.optJSONObject(id))?.let { id to it } }.toMap()
        val cloudNewIds = (values["cloudNewIds"] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        // 导航模块显隐单独存放在 navHidden.<key>，随备份一起迁移；本地库默认隐藏（显式置 false 才显示）
        val navHidden = LibraryBackup.NAV_KEYS.filter { key ->
            if(key=="nas") values["navHidden.$key"] as? Boolean != false else values["navHidden.$key"] as? Boolean == true
        }.toSet()
        val settings = BackupSettings(PlaybackQuality.normalize((values["quality"] as? Number)?.toInt() ?: 1080),
            PlaybackSpeed.normalize((values["playbackSpeed"] as? Number)?.toFloat() ?: 1f), values["autoNext"] as? Boolean ?: true,
            ContentType.fromStored(values["contentType"] as? String), VideoFrameMode.fromStored(values["frameMode"] as? String), values["autoSeason"] as? Boolean ?: true,
            values["danmaku"] as? Boolean ?: true,
            DanmakuPrefs.opacity((values["danmakuOpacity"] as? Number)?.toInt() ?: DanmakuPrefs.OPACITY_DEFAULT),
            DanmakuPrefs.font((values["danmakuFont"] as? Number)?.toInt() ?: DanmakuPrefs.FONT_DEFAULT),
            DanmakuPrefs.speed((values["danmakuSpeed"] as? Number)?.toInt() ?: DanmakuPrefs.SPEED_DEFAULT),
            DanmakuPrefs.rows((values["danmakuRows"] as? Number)?.toInt() ?: DanmakuPrefs.ROWS_DEFAULT),
            values["showClock"] as? Boolean ?: true,
            values["sourceHidden"] as? Boolean ?: true, navHidden)
        val groupMap = runCatching { JSONObject(values["favoriteGroups"] as? String ?: "{}") }.getOrDefault(JSONObject())
            .let { o -> o.keys().asSequence().mapNotNull { k -> (o.opt(k) as? String)?.let { k to it } }.toMap() }
        return LibraryIndex(BackupData(series("favorites"), history, ids("watched"),
            SearchHistory.decode(values["searches"] as? String ?: "[]"), settings, series("later"), ids("hidden"), groupMap), updates, cloudUpdates, cloudNewIds)
    }
    fun series(rows: List<Series>) = JSONArray(rows.map { it.toJson() }).toString()
    fun history(rows: List<BackupProgress>) = JSONArray(rows.map { p ->
        JSONObject().put("series", p.series.toJson()).put("episodeId", p.episodeId).put("episodeIndex", p.episodeIndex)
            .put("position", p.position).put("duration", p.duration).put("completed", p.completed).put("updatedAt", p.updatedAt)
    }).toString()
    fun updates(rows: Map<String, FavoriteUpdate>) = JSONObject().also { result -> rows.forEach { (id, update) -> result.put(id, update.json()) } }.toString()
    fun groups(rows: Map<String, String>) = JSONObject().also { result -> rows.forEach { (id, name) -> result.put(id, name) } }.toString()
}
