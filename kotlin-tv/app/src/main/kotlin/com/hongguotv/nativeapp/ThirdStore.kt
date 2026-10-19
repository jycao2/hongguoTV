// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 三方片源独立持久化：源列表与播放进度存独立 SharedPreferences（third-sources-v1）。
 *  不进 Library、不进收藏/历史/继续观看、不参与备份格式；旧数据缺失字段静默兜底。 */
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
    }
}
