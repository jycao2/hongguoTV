// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
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

/** 三方片源独立持久化：源列表与播放进度存独立 SharedPreferences（third-sources-v1）。
 *  不进 Library、不进收藏/历史/继续观看；源配置与观看历史随主备份一起迁移，播放进度与搜索历史仍只在本机。 */
class ThirdStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 首次启动预置内置源（默认源：量子），此后完全以用户列表为准（删光即真实空态）。 */
    fun ensureSources(): List<ThirdSource> {
        val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
        if(initialized) return sources()
        prefs.edit().putBoolean(KEY_INITIALIZED, true).putString(KEY_SOURCES, writeSources(ThirdCms.BUILTIN_SOURCES)).apply()
        return ThirdCms.BUILTIN_SOURCES
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
                it.name.ifBlank { it.api.removePrefix("https://").removePrefix("http://").substringBefore('/') }, it.api, false) }
            .toList()
        if(added.isEmpty()) return 0
        saveSources(local + added)
        // 恢复后视为已初始化，避免首次启动再用内置源覆盖用户配置
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
            else ThirdSource(id, item.optString("name"), api, item.optBoolean("builtin"))
        }
    }

    private fun writeSources(list: List<ThirdSource>): String {
        val array = JSONArray()
        list.forEach { source ->
            array.put(JSONObject().put("id", source.id).put("name", source.name).put("api", source.api).put("builtin", source.builtin))
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
        private const val KEY_HISTORY = "watchHistory"
        private const val MAX_HISTORY = 100
    }
}

private fun BackupThirdHistory.toEntry() = ThirdHistoryEntry(sourceApi, sourceName, videoId, title, cover,
    episodeIndex, episodeLabel, positionMs, durationMs, updatedAt, completed)
