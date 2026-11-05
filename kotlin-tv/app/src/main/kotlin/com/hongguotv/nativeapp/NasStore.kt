// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.NasLibrary
import org.json.JSONArray
import org.json.JSONObject

/** NAS 连接配置（凭据仅本机保存，不随备份导出）。 */
data class NasConnection(
    val id: String,
    val name: String,
    val url: String,
    val user: String,
    /** 加密存储（Base64）：应用启动时能解即可，非安全边界。 */
    val pass: String,
    val mountPath: String,
    val addedAt: Long,
    val updatedAt: Long,
) {
    fun json() = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("user", user)
        .put("pass", pass).put("mountPath", mountPath)
        .put("addedAt", addedAt).put("updatedAt", updatedAt)
}

/** 本地库连接与扫描缓存持久化：凭据 + 目录树快照。 */
class NasStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun connections(): List<NasConnection> = try {
        val raw = prefs.getString(KEY_CONNS, null) ?: return emptyList()
        (0 until JSONArray(raw).length()).mapNotNull { i ->
            val o = JSONArray(raw).optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); if (id.isEmpty()) return@mapNotNull null
            NasConnection(id, o.optString("name"), o.optString("url"), o.optString("user"),
                o.optString("pass"), o.optString("mountPath"), o.optLong("addedAt"), o.optLong("updatedAt"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveConnections(list: List<NasConnection>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.json()) }
        prefs.edit().putString(KEY_CONNS, arr.toString()).apply()
    }

    fun upsert(c: NasConnection) {
        val list = connections().filter { it.id != c.id } + c
        saveConnections(list)
    }

    fun remove(id: String) {
        saveConnections(connections().filter { it.id != id })
        prefs.edit().remove(KEY_CACHE + id).remove(KEY_STATUS + id).apply()
    }

    /** 当前选择的连接 id（记忆上次）。 */
    var currentId: String?
        get() = prefs.getString(KEY_CURRENT, null)
        set(v) = prefs.edit().let { if (v == null) it.remove(KEY_CURRENT) else it.putString(KEY_CURRENT, v) }.apply()

    // ---------- 扫描缓存 ----------

    /** 上次扫描结果 JSON（NasLibrary.NasEntry 列表），可直接解析为离线浏览。 */
    fun cache(id: String): List<NasLibrary.NasEntry>? = try {
        val raw = prefs.getString(KEY_CACHE + id, null) ?: return null
        (0 until JSONArray(raw).length()).mapNotNull { i ->
            val o = JSONArray(raw).optJSONObject(i) ?: return@mapNotNull null
            NasLibrary.NasEntry(o.optString("p"), o.optBoolean("d"), o.optLong("s"))
        }
    } catch (_: Exception) { null }

    fun saveCache(id: String, entries: List<NasLibrary.NasEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("p", it.path).put("d", it.isDir).put("s", it.size)) }
        prefs.edit().putString(KEY_CACHE + id, arr.toString()).apply()
    }

    /** 上次扫描时间（毫秒）。 */
    fun lastScanAt(id: String): Long = prefs.getLong(KEY_SCANNED_AT + id, 0L)
    fun markScanned(id: String) { prefs.edit().putLong(KEY_SCANNED_AT + id, System.currentTimeMillis()).apply() }

    /** 上次扫描状态 ok/reason 与条目数，供 UI 顶部状态条。 */
    data class ScanStatus(val ok: Boolean, val total: Int, val reason: String?)

    fun lastStatus(id: String): ScanStatus? = try {
        val raw = prefs.getString(KEY_STATUS + id, null) ?: return null
        val o = JSONObject(raw)
        ScanStatus(o.optBoolean("ok"), o.optInt("total"), o.optString("reason").ifBlank { null })
    } catch (_: Exception) { null }

    fun saveStatus(id: String, s: ScanStatus) {
        prefs.edit().putString(KEY_STATUS + id,
            JSONObject().put("ok", s.ok).put("total", s.total).put("reason", s.reason.orEmpty()).toString()).apply()
    }

    /** 所有与该连接相关的本地数据一并清除。 */
    fun purge(id: String) {
        prefs.edit().remove(KEY_CACHE + id).remove(KEY_SCANNED_AT + id).remove(KEY_STATUS + id).apply()
    }

    companion object {
        private const val PREFS = "nas-v1"
        private const val KEY_CONNS = "connections"
        private const val KEY_CURRENT = "current"
        private const val KEY_CACHE = "cache:"
        private const val KEY_SCANNED_AT = "scanned:"
        private const val KEY_STATUS = "status:"

        /** 扫描时间阈值：超过 24 小时视为过期，建议重扫。 */
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
