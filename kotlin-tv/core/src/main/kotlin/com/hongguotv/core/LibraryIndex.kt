// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** Main-thread-owned library. JSON is parsed once, and getters never parse or sort. */
class LibraryIndex(data: BackupData, updates: Map<String, FavoriteUpdate> = emptyMap()) {
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

    fun toggle(series: Series): Boolean {
        val added = !favorite(series.id)
        favoriteRows = (if (added) listOf(series) + favoriteRows else favoriteRows.filterNot { it.id == series.id }).take(500)
        favoriteIds = favoriteRows.map { it.id }.toSet()
        updateRows = updateRows.filterKeys { it in favoriteIds }
        updateCount = updateRows.values.count { it.added > 0 }
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
    fun snapshot() = BackupData(favoriteRows, historyRows, watchedIds, searchRows, settings, laterRows, hiddenIds)
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
        val settings = BackupSettings(PlaybackQuality.normalize((values["quality"] as? Number)?.toInt() ?: 1080),
            PlaybackSpeed.normalize((values["playbackSpeed"] as? Number)?.toFloat() ?: 1f), values["autoNext"] as? Boolean ?: true,
            ContentType.fromStored(values["contentType"] as? String), VideoFrameMode.fromStored(values["frameMode"] as? String))
        return LibraryIndex(BackupData(series("favorites"), history, ids("watched"),
            SearchHistory.decode(values["searches"] as? String ?: "[]"), settings, series("later"), ids("hidden")), updates)
    }
    fun series(rows: List<Series>) = JSONArray(rows.map { it.toJson() }).toString()
    fun history(rows: List<BackupProgress>) = JSONArray(rows.map { p ->
        JSONObject().put("series", p.series.toJson()).put("episodeId", p.episodeId).put("episodeIndex", p.episodeIndex)
            .put("position", p.position).put("duration", p.duration).put("completed", p.completed).put("updatedAt", p.updatedAt)
    }).toString()
    fun updates(rows: Map<String, FavoriteUpdate>) = JSONObject().also { result -> rows.forEach { (id, update) -> result.put(id, update.json()) } }.toString()
}
