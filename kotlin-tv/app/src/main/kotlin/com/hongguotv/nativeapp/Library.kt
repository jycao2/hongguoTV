// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
        /** 普通进度变更的备份节流窗口：播放中每 5 秒就有一次进度落盘，没必要跟着整包重写。 */
        const val PROGRESS_BACKUP_INTERVAL_MS = 5 * 60_000L
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val localPrefs by lazy { app.getSharedPreferences("native-local-backup-v1", Context.MODE_PRIVATE) }
    private val localBackupStorage = LocalBackupStorage(app)
    private val backupWorker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "hongguotv-local-backup") }
    private val backupLock = Any()
    private var backupTask: ScheduledFuture<*>? = null
    /** 已排队的备份计划时间点（epoch ms），用于复用更早的计划，避免任务被反复推迟。 */
    private var backupDueAt = 0L
    @Volatile private var backupDirty = false
    /** 上次成功落盘时间：两次备份之间至少间隔 1 分钟，避免播放中每 5 秒重写整包。 */
    @Volatile private var lastBackupAt = 0L
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                // 0.19.7 片源入口默认隐藏：升级后首次加载放开一次，之后尊重用户在设置里的手动选择
                if(!values.containsKey("sourceEntryShownV2")) {
                    loaded.settings = loaded.settings.copy(sourceHidden = false)
                    prefs.edit().putBoolean("sourceEntryShownV2", true).putBoolean("sourceHidden", false).apply()
                }
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                Triple(loaded, cached, stamps)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, cached, stamps) ->
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps)
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })); scheduleLocalBackup() }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "favoriteGroups" -> state.groupsSnapshot().let { rows -> { LibraryDisk.groups(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
        // 只有播放进度是高频变更：其余收藏/历史操作/设置都尽快落盘
        scheduleLocalBackup(urgent = keys.any { it != "progress" })
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun updates() = state().updates()
    fun groups() = state().groups()
    fun groupOf(id: String) = state().groupOf(id)
    fun setGroup(id: String, group: String?): Boolean = state().setGroup(id, group).also { if (it) persist("favoriteGroups") }
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        val season = if (it.hasNewSeason) "《${it.newSeasonTitle ?: "新季"}》已上线" else ""
        val episodes = if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
        listOf(season, episodes).filter { it.isNotEmpty() }.joinToString(" · ")
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false, newSeason: Series? = null) {
        if (state().observeFavorite(id, count, now, acknowledge, newSeason)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var autoSeason: Boolean
        get() = state().settings.autoSeason
        set(value) { if (value != autoSeason) { state().settings = state().settings.copy(autoSeason = value); put("autoSeason", value) } }
    var danmaku: Boolean
        get() = state().settings.danmaku
        set(value) { if (value != danmaku) { state().settings = state().settings.copy(danmaku = value); put("danmaku", value) } }
    var danmakuOpacity: Int
        get() = state().settings.danmakuOpacity
        set(value) { val next = DanmakuPrefs.opacity(value); if (next != danmakuOpacity) { state().settings = state().settings.copy(danmakuOpacity = next); put("danmakuOpacity", next) } }
    var danmakuFont: Int
        get() = state().settings.danmakuFont
        set(value) { val next = DanmakuPrefs.font(value); if (next != danmakuFont) { state().settings = state().settings.copy(danmakuFont = next); put("danmakuFont", next) } }
    var danmakuSpeed: Int
        get() = state().settings.danmakuSpeed
        set(value) { val next = DanmakuPrefs.speed(value); if (next != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = next); put("danmakuSpeed", next) } }
    var danmakuRows: Int
        get() = state().settings.danmakuRows
        set(value) { val next = DanmakuPrefs.rows(value); if (next != danmakuRows) { state().settings = state().settings.copy(danmakuRows = next); put("danmakuRows", next) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    var showClock: Boolean
        get() = state().settings.showClock
        set(value) { if (value != showClock) { state().settings = state().settings.copy(showClock = value); put("showClock", value) } }
    var theme: String
        get() = prefs.getString("theme", null) ?: "NIGHT"
        set(value) { prefs.edit().putString("theme", value).apply() }
    /** 导航栏「片源」入口是否隐藏：默认隐藏，用户可在设置中开启；随备份一起恢复。 */
    var sourceHidden: Boolean
        get() = state().settings.sourceHidden
        set(value) { if (value != sourceHidden) { state().settings = state().settings.copy(sourceHidden = value); put("sourceHidden", value) } }
    // Per-series intro/outro skips stay on this device, outside the backup, like the theme.
    private var marksCache: LinkedHashMap<String, SkipMark>? = null
    private fun marks(): LinkedHashMap<String, SkipMark> =
        marksCache ?: SkipMarks.decode(prefs.getString("skipMarks", null)).also { marksCache = it }
    fun skipMark(id: String): SkipMark? = marks()[id]
    fun setSkipMark(id: String, mark: SkipMark?) {
        val next = SkipMarks.set(marks(), id, mark)
        marksCache = next
        prefs.edit().putString("skipMarks", SkipMarks.encode(next)).apply()
    }
    fun snapshot() = state().snapshot(
        // 三方源/观看历史/收藏来自独立存储，任何异常都退化为空列表，不能让备份整体失败
        runCatching { thirdSourcesProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList()),
        runCatching { thirdHistoryProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList()),
        runCatching { thirdFavoritesProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList()))

    /** 由 MainActivity 注入：三方片源配置、观看历史与收藏随主备份一起写入与恢复。 */
    var thirdSourcesProvider: (() -> List<BackupThirdSource>)? = null
    var thirdSourcesRestore: ((List<BackupThirdSource>) -> Unit)? = null
    var thirdHistoryProvider: (() -> List<BackupThirdHistory>)? = null
    var thirdHistoryRestore: ((List<BackupThirdHistory>) -> Unit)? = null
    var thirdFavoritesProvider: (() -> List<BackupThirdFavorite>)? = null
    var thirdFavoritesRestore: ((List<BackupThirdFavorite>) -> Unit)? = null

    /** 三方片源变化后尽快落盘，避免只有片源变更时备份长期停留在旧内容。 */
    fun requestBackup() = scheduleLocalBackup()

    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates", "favoriteGroups")
        // 三方源、观看历史与收藏各自合并：任一侧异常都只跳过该段，不影响其余记录恢复
        if(incoming.thirdSources.isNotEmpty()) runCatching { thirdSourcesRestore?.invoke(incoming.thirdSources) }
        if(incoming.thirdHistory.isNotEmpty()) runCatching { thirdHistoryRestore?.invoke(incoming.thirdHistory) }
        if(incoming.thirdFavorites.isNotEmpty()) runCatching { thirdFavoritesRestore?.invoke(incoming.thirdFavorites) }
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext }, "contentType" to { merged.settings.type.storedValue }, "frameMode" to { merged.settings.frame.name },
            "autoSeason" to { merged.settings.autoSeason }, "danmaku" to { merged.settings.danmaku },
            "danmakuOpacity" to { merged.settings.danmakuOpacity }, "danmakuFont" to { merged.settings.danmakuFont },
            "danmakuSpeed" to { merged.settings.danmakuSpeed }, "danmakuRows" to { merged.settings.danmakuRows }, "showClock" to { merged.settings.showClock },
            "sourceHidden" to { merged.settings.sourceHidden }))
        scheduleLocalBackup()
        flush()
    }
    // ---- Local rotating backup ----
    var localBackupEnabled: Boolean
        get() = localPrefs.getBoolean("enabled", true)
        set(value) { localPrefs.edit().putBoolean("enabled", value).apply() }
    var localRestoreChecked: Boolean
        get() = localPrefs.getBoolean("restoreChecked", false)
        set(value) { localPrefs.edit().putBoolean("restoreChecked", value).apply() }
    var localPermissionAsked: Boolean
        get() = localPrefs.getBoolean("permissionAsked", false)
        set(value) { localPrefs.edit().putBoolean("permissionAsked", value).apply() }
    fun localBackup(): LocalBackupStorage = localBackupStorage
    /** True when the library holds no user data, so a backup may be restored without overwriting anything. */
    fun isEmptyForRestore(): Boolean {
        val data = runCatching { snapshot() }.getOrNull() ?: return false
        return data.favorites.isEmpty() && data.later.isEmpty() && data.history.isEmpty() && data.searches.isEmpty() &&
            data.favoriteGroups.isEmpty() && data.watched.isEmpty() && data.hidden.isEmpty()
    }
    fun readLocalBackup(): BackupData? = localBackupStorage.load()
    fun hasLocalBackup(): Boolean = localBackupStorage.store()?.hasBackup() == true
    fun applyLocalBackup(data: BackupData) = restore(data, true)
    /** Immediate backup for the settings entry; the callback runs on the main thread. */
    fun backupNow(onDone: (Boolean) -> Unit) {
        val data = runCatching { snapshot() }.getOrNull()
        if (data == null) { main.post { onDone(false) }; return }
        backupWorker.execute {
            val ok = localBackupStorage.store()?.save(data) == true
            if (ok) { backupDirty = false; lastBackupAt = System.currentTimeMillis() }
            main.post { onDone(ok) }
        }
    }
    /** First run after an upgrade may have data but no backup yet; empty libraries are never written. */
    fun maybeBackupOnStartup() {
        if (!localBackupEnabled || index == null || isEmptyForRestore()) return
        val store = localBackupStorage.store() ?: return
        // 升级后清理旧版临时名残留（每次保存都会新插一行的孤儿条目），避免它们继续占用目录
        store.cleanup()
        if (store.hasBackup()) return
        val data = runCatching { snapshot() }.getOrNull() ?: return
        backupWorker.execute { if (store.save(data)) lastBackupAt = System.currentTimeMillis() }
    }
    private fun scheduleLocalBackup(urgent: Boolean = true) {
        if (!localBackupEnabled || index == null) return
        backupDirty = true
        synchronized(backupLock) {
            val now = System.currentTimeMillis()
            // 变更先防抖 5 秒；播放进度这类高频变更再叠一个 5 分钟最小间隔，避免整包反复 fsync。
            // 收藏、设置、片源等低频变更走 urgent，只防抖不受最小间隔限制。
            val due = if(urgent) now + 5_000L else maxOf(now + 5_000L, lastBackupAt + PROGRESS_BACKUP_INTERVAL_MS)
            // 已有更早的计划就复用：否则播放中每 5 秒都会把任务往后推，可能一直轮不到写盘
            val pending = backupTask
            if (pending != null && !pending.isDone && backupDueAt in 1..due) return
            pending?.cancel(false)
            backupDueAt = due
            backupTask = backupWorker.schedule({
                synchronized(backupLock) { backupTask = null; backupDueAt = 0L }
                // 真正写盘时再取快照，保证是最新进度
                runCatching { snapshot() }.getOrNull()?.let { writeLocalBackup(it) }
            }, (due - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
    }
    private fun writeLocalBackup(data: BackupData) {
        if (!localBackupEnabled) return
        val ok = localBackupStorage.store()?.save(data) == true
        if (ok) { backupDirty = false; lastBackupAt = System.currentTimeMillis() }
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() {
        writes.flush(); homeWrites.flush()
        if (backupDirty && index != null) {
            val data = runCatching { snapshot() }.getOrNull()
            if (data != null) {
                // 放弃排队中的延时备份，改为立刻写一次，避免 5 秒后又重复写同一份数据
                synchronized(backupLock) { backupTask?.cancel(false); backupTask = null; backupDueAt = 0L }
                backupWorker.execute { writeLocalBackup(data) }
            }
        }
    }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        if (backupDirty && index != null) {
            val data = runCatching { snapshot() }.getOrNull()
            if (data != null) {
                synchronized(backupLock) { backupTask?.cancel(false); backupTask = null; backupDueAt = 0L }
                backupWorker.execute { writeLocalBackup(data) }
            }
        }
        backupWorker.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
