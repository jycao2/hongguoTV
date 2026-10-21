// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.BackupThirdFavorite
import com.hongguotv.core.BackupThirdHistory
import com.hongguotv.core.BackupThirdSource
import com.hongguotv.core.PlaybackSpeed
import org.json.JSONArray
import org.json.JSONObject

/** 三方片源观看记录：按「源接口 + 影片」去重，最近观看在前。标题与封面本地留存，源或影片失效时卡片仍可显示。 */
data class ThirdHistoryEntry(
    val sourceApi: String, val sourceName: String, val videoId: String, val title: String, val cover: String,
    val episodeIndex: Int, val episodeLabel: String, val positionMs: Long, val durationMs: Long,
    val updatedAt: Long, val completed: Boolean
) {
    fun json() = JSONObject().put("sourceApi", sourceApi).put("sourceName", sourceName).put("videoId", videoId)
        .put("title", title).put("cover", cover).put("episodeIndex", episodeIndex).put("episodeLabel", episodeLabel)
        .put("positionMs", positionMs).put("durationMs", durationMs).put("updatedAt", updatedAt).put("completed", completed)
}

/** 片源内收藏：独立存储并随主备份迁移；标题与封面本地留存，源失效时卡片仍可显示。 */
data class ThirdFavorite(
    val sourceApi: String, val sourceName: String, val videoId: String, val title: String, val cover: String,
    val episodeIndex: Int, val episodeLabel: String, val positionMs: Long, val updatedAt: Long
) {
    fun json() = JSONObject().put("sourceApi", sourceApi).put("sourceName", sourceName).put("videoId", videoId)
        .put("title", title).put("cover", cover).put("episodeIndex", episodeIndex).put("episodeLabel", episodeLabel)
        .put("positionMs", positionMs).put("updatedAt", updatedAt)
}

/** 探活结果缓存：TTL 由界面判断，落盘避免每次冷启动都全量探活。 */
data class ThirdProbeRecord(val ok: Boolean, val total: Int, val reason: String?, val at: Long)

/** 片源播放器的片头/片尾跳过设置（秒，0 表示关闭）。 */
data class ThirdSkipMark(val intro: Int, val outro: Int)

/** 三方片源独立持久化：源列表与播放进度存独立 SharedPreferences（third-sources-v1）。
 *  不进 Library、不进收藏/历史/继续观看；源配置与观看历史随主备份一起迁移，播放进度与搜索历史仍只在本机。 */
class ThirdStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 首次启动为空源：不预置任何片源，由用户自行添加或导入；此后完全以用户列表为准。 */
    fun ensureSources(): List<ThirdSource> {
        val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
        if(initialized) return sources()
        prefs.edit().putBoolean(KEY_INITIALIZED, true).putString(KEY_SOURCES, "[]").apply()
        return emptyList()
    }

    fun sources(): List<ThirdSource> = try {
        readSources(prefs.getString(KEY_SOURCES, null))
    } catch (_: Exception) { emptyList() }

    fun saveSources(list: List<ThirdSource>) { prefs.edit().putString(KEY_SOURCES, writeSources(list)).apply() }

    fun sourceById(id: String): ThirdSource? = sources().firstOrNull { it.id == id }

    /** 当前使用中的源（记忆上次选择）。 */
    fun currentId(): String? = prefs.getString(KEY_CURRENT, null)

    fun setCurrent(id: String?) {
        val editor = prefs.edit()
        if(id == null) editor.remove(KEY_CURRENT) else editor.putString(KEY_CURRENT, id)
        editor.apply()
    }

    /** 播放进度（键：源id:vod_id → 集号/位置/组号）。 */
    fun progress(sourceId: String, videoId: String): ThirdProgress? = try {
        prefs.getString(KEY_PROGRESS + key(sourceId, videoId), null)?.let { raw ->
            val json = JSONObject(raw)
            ThirdProgress(json.optInt("episode"), json.optLong("positionMs"), json.optInt("group"))
        }
    } catch (_: Exception) { null }

    fun saveProgress(sourceId: String, videoId: String, progress: ThirdProgress) {
        prefs.edit().putString(KEY_PROGRESS + key(sourceId, videoId),
            JSONObject().put("episode", progress.episode).put("positionMs", progress.positionMs).put("group", progress.group).toString()
        ).apply()
    }

    fun clearProgress(sourceId: String, videoId: String) { prefs.edit().remove(KEY_PROGRESS + key(sourceId, videoId)).apply() }

    /** 搜索历史：最近在前，最多 20 条；损坏或缺失时静默返回空。 */
    fun searches(): List<String> = try {
        val raw = prefs.getString(KEY_SEARCHES, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    fun rememberSearch(word: String) {
        val clean = word.trim()
        if(clean.isEmpty()) return
        val next = (listOf(clean) + searches().filterNot { it.equals(clean, ignoreCase = true) }).take(20)
        prefs.edit().putString(KEY_SEARCHES, JSONArray(next).toString()).apply()
    }

    fun clearSearches() { prefs.edit().remove(KEY_SEARCHES).apply() }

    /** 片源播放器的倍速偏好：仅本机记忆，默认 1×。 */
    var playbackSpeed: Float
        get() = PlaybackSpeed.normalize(prefs.getFloat(KEY_SPEED, 1f))
        set(value) { prefs.edit().putFloat(KEY_SPEED, PlaybackSpeed.normalize(value)).apply() }

    /** 片源播放器的画面比例偏好：0 适应 / 1 拉伸 / 2 裁剪，仅本机记忆。 */
    var resizeMode: Int
        get() = prefs.getInt(KEY_RESIZE, 0).coerceIn(0, 2)
        set(value) { prefs.edit().putInt(KEY_RESIZE, value.coerceIn(0, 2)).apply() }

    /** 片头/片尾跳过秒数：按「源接口 + 影片」记忆，仅本机，损坏时静默忽略。 */
    fun skipMark(key: String): ThirdSkipMark? = try {
        val raw = prefs.getString(KEY_SKIP, null) ?: return null
        JSONObject(raw).optJSONObject(key)?.let {
            ThirdSkipMark(it.optInt("intro", 0).coerceIn(0, MAX_SKIP_SECONDS), it.optInt("outro", 0).coerceIn(0, MAX_SKIP_SECONDS))
        }
    } catch (_: Exception) { null }

    fun setSkipMark(key: String, mark: ThirdSkipMark?) {
        runCatching {
            val next = LinkedHashMap<String, ThirdSkipMark>()
            (prefs.getString(KEY_SKIP, null)?.let { raw ->
                JSONObject(raw).let { obj ->
                    obj.keys().asSequence().forEach { id ->
                        obj.optJSONObject(id)?.let {
                            next[id] = ThirdSkipMark(it.optInt("intro", 0).coerceIn(0, MAX_SKIP_SECONDS),
                                it.optInt("outro", 0).coerceIn(0, MAX_SKIP_SECONDS))
                        }
                    }
                }
            })
            if(mark == null || (mark.intro <= 0 && mark.outro <= 0)) next.remove(key)
            else next[key] = ThirdSkipMark(mark.intro.coerceIn(0, MAX_SKIP_SECONDS), mark.outro.coerceIn(0, MAX_SKIP_SECONDS))
            while(next.size > MAX_SKIP_MARKS) next.remove(next.keys.first())
            val json = JSONObject()
            next.forEach { (id, value) -> json.put(id, JSONObject().put("intro", value.intro).put("outro", value.outro)) }
            prefs.edit().putString(KEY_SKIP, json.toString()).apply()
        }
    }

    /** 观看历史：最近在前，最多 100 条；损坏或缺失时静默返回空。 */
    fun history(): List<ThirdHistoryEntry> = try {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val api = item.optString("sourceApi"); val video = item.optString("videoId")
            if(api.isEmpty() || video.isEmpty()) null else ThirdHistoryEntry(api, item.optString("sourceName"), video,
                item.optString("title"), item.optString("cover"), item.optInt("episodeIndex"), item.optString("episodeLabel"),
                item.optLong("positionMs"), item.optLong("durationMs"), item.optLong("updatedAt"), item.optBoolean("completed"))
        }
    } catch (_: Exception) { emptyList() }

    /** 记录/更新一条观看历史：同一源同一影片只保留最新一条，超出上限淘汰最旧的。 */
    fun rememberHistory(entry: ThirdHistoryEntry) {
        val next = (listOf(entry) + history().filterNot { it.sourceApi == entry.sourceApi && it.videoId == entry.videoId })
            .take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, JSONArray(next.map { it.json() }).toString()).apply()
    }

    fun removeHistory(sourceApi: String, videoId: String) {
        val next = history().filterNot { it.sourceApi == sourceApi && it.videoId == videoId }
        prefs.edit().putString(KEY_HISTORY, JSONArray(next.map { it.json() }).toString()).apply()
    }

    fun clearHistory() { prefs.edit().remove(KEY_HISTORY).apply() }

    /** 片源内收藏：最近收藏在前，最多 100 条；损坏或缺失时静默返回空。 */
    fun favorites(): List<ThirdFavorite> = try {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val api = item.optString("sourceApi"); val video = item.optString("videoId")
            if(api.isEmpty() || video.isEmpty()) null else ThirdFavorite(api, item.optString("sourceName"), video,
                item.optString("title"), item.optString("cover"), item.optInt("episodeIndex"), item.optString("episodeLabel"),
                item.optLong("positionMs"), item.optLong("updatedAt"))
        }
    } catch (_: Exception) { emptyList() }

    fun isFavorite(sourceApi: String, videoId: String): Boolean =
        favorites().any { it.sourceApi == sourceApi && it.videoId == videoId }

    /** 收藏/取消收藏：返回操作后是否处于收藏状态。 */
    fun toggleFavorite(item: ThirdFavorite): Boolean {
        val list = favorites()
        val exists = list.any { it.sourceApi == item.sourceApi && it.videoId == item.videoId }
        val next = if(exists) list.filterNot { it.sourceApi == item.sourceApi && it.videoId == item.videoId }
            else (listOf(item) + list).take(MAX_FAVORITES)
        prefs.edit().putString(KEY_FAVORITES, JSONArray(next.map { it.json() }).toString()).apply()
        return !exists
    }

    fun removeFavorite(sourceApi: String, videoId: String) {
        val next = favorites().filterNot { it.sourceApi == sourceApi && it.videoId == videoId }
        prefs.edit().putString(KEY_FAVORITES, JSONArray(next.map { it.json() }).toString()).apply()
    }

    /** 备份用：收藏随主备份迁移。 */
    fun backupFavorites(): List<BackupThirdFavorite> = favorites().map {
        BackupThirdFavorite(it.sourceApi, it.sourceName, it.videoId, it.title, it.cover, it.episodeIndex,
            it.episodeLabel, it.positionMs, it.updatedAt)
    }

    /** 恢复收藏：同「源接口 + 影片」保留较新的一条，最多 100 条；任何异常都静默忽略。 */
    fun mergeFavorites(incoming: List<BackupThirdFavorite>) {
        runCatching {
            val merged = LinkedHashMap<String, ThirdFavorite>()
            (favorites() + incoming.map { it.toFavorite() }).forEach { item ->
                val key = item.sourceApi + "|" + item.videoId
                val prior = merged[key]
                if(prior == null || item.updatedAt > prior.updatedAt) merged[key] = item
            }
            val next = merged.values.sortedByDescending { it.updatedAt }.take(MAX_FAVORITES)
            prefs.edit().putString(KEY_FAVORITES, JSONArray(next.map { it.json() }).toString()).apply()
        }
    }

    /** 探活结果缓存（按接口地址），TTL 由调用方判断。 */
    fun probes(): Map<String, ThirdProbeRecord> = try {
        val raw = prefs.getString(KEY_PROBES, null) ?: return emptyMap()
        val json = JSONObject(raw)
        json.keys().asSequence().mapNotNull { api ->
            json.optJSONObject(api)?.let {
                api to ThirdProbeRecord(it.optBoolean("ok"), it.optInt("total"), it.optString("reason").ifBlank { null }, it.optLong("at"))
            }
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    fun saveProbe(api: String, record: ThirdProbeRecord) {
        runCatching {
            val next = LinkedHashMap(probes().filterKeys { it != api })
            next[api] = record
            while(next.size > 60) next.remove(next.keys.first())
            val json = JSONObject()
            next.forEach { (key, value) ->
                json.put(key, JSONObject().put("ok", value.ok).put("total", value.total)
                    .put("reason", value.reason.orEmpty()).put("at", value.at))
            }
            prefs.edit().putString(KEY_PROBES, json.toString()).apply()
        }
    }

    /** 聚合搜索偏好：默认全部源；用户切到「仅当前源」后本机记忆。 */
    var searchAllSources: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_ALL, true)
        set(value) { prefs.edit().putBoolean(KEY_SEARCH_ALL, value).apply() }

    /** 备份用：观看历史随主备份迁移。 */
    fun backupHistory(): List<BackupThirdHistory> = history().map {
        BackupThirdHistory(it.sourceApi, it.sourceName, it.videoId, it.title, it.cover, it.episodeIndex,
            it.episodeLabel, it.positionMs, it.durationMs, it.updatedAt, it.completed)
    }

    /** 恢复观看历史：同「源接口 + 影片」保留进度较新的一条，最多 100 条；任何异常都静默忽略。 */
    fun mergeHistory(incoming: List<BackupThirdHistory>) {
        runCatching {
            val merged = LinkedHashMap<String, ThirdHistoryEntry>()
            (history() + incoming.map { it.toEntry() }).forEach { entry ->
                val key = entry.sourceApi + "|" + entry.videoId
                val prior = merged[key]
                if(prior == null || entry.updatedAt > prior.updatedAt) merged[key] = entry
            }
            val next = merged.values.sortedByDescending { it.updatedAt }.take(MAX_HISTORY)
            prefs.edit().putString(KEY_HISTORY, JSONArray(next.map { it.json() }).toString()).apply()
        }
    }

    /** 备份/导出用：只保留名称与接口。 */
    fun backupSources(): List<BackupThirdSource> = sources().map { BackupThirdSource(it.name, it.api) }

    /** 恢复源配置：本地已有的接口一律保留（不覆盖），只追加缺失项；返回新增数量。 */
    fun mergeRestored(incoming: List<BackupThirdSource>): Int {
        val local = sources()
        val known = local.mapTo(HashSet()) { it.api }
        val room = (ThirdCms.MAX_SOURCES - local.size).coerceAtLeast(0)
        val added = incoming.asSequence()
            .filter { it.api.startsWith("http") && known.add(it.api) }
            .take(room)
            .map { ThirdSource("u" + it.api.hashCode().toUInt().toString(),
                it.name.ifBlank { it.api.removePrefix("https://").removePrefix("http://").substringBefore('/') }, it.api) }
            .toList()
        if(added.isEmpty()) return 0
        saveSources(local + added)
        // 恢复后视为已初始化，避免首次启动再覆盖用户配置
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        return added.size
    }

    private fun key(sourceId: String, videoId: String) = "$sourceId:$videoId"

    private fun readSources(raw: String?): List<ThirdSource> {
        raw ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id"); val api = item.optString("api")
            if(id.isEmpty() || api.isEmpty()) null
            else ThirdSource(id, item.optString("name"), api)
        }
    }

    private fun writeSources(list: List<ThirdSource>): String {
        val array = JSONArray()
        list.forEach { source ->
            array.put(JSONObject().put("id", source.id).put("name", source.name).put("api", source.api))
        }
        return array.toString()
    }

    companion object {
        private const val PREFS = "third-sources-v1"
        private const val KEY_SOURCES = "sources"
        private const val KEY_PROGRESS = "progress:"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_CURRENT = "current"
        private const val KEY_SEARCHES = "searches"
        private const val KEY_SPEED = "playbackSpeed"
        private const val KEY_RESIZE = "resizeMode"
        private const val KEY_SKIP = "skipMarks"
        private const val KEY_HISTORY = "watchHistory"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PROBES = "probeCache"
        private const val KEY_SEARCH_ALL = "searchAllSources"
        private const val MAX_HISTORY = 100
        private const val MAX_FAVORITES = 100
        private const val MAX_SKIP_SECONDS = 600
        private const val MAX_SKIP_MARKS = 200
    }
}

private fun BackupThirdHistory.toEntry() = ThirdHistoryEntry(sourceApi, sourceName, videoId, title, cover,
    episodeIndex, episodeLabel, positionMs, durationMs, updatedAt, completed)

private fun BackupThirdFavorite.toFavorite() = ThirdFavorite(sourceApi, sourceName, videoId, title, cover,
    episodeIndex, episodeLabel, positionMs, updatedAt)
