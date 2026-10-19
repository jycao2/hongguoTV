// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.BackupThirdSource
import org.json.JSONArray
import org.json.JSONObject

/** 三方片源独立持久化：源列表与播放进度存独立 SharedPreferences（third-sources-v1）。
 *  不进 Library、不进收藏/历史/继续观看；源配置随主备份一起迁移，播放进度与搜索历史仍只在本机。 */
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
    }
}
