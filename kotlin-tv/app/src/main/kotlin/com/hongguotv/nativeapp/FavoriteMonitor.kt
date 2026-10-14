// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RequestScope
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Serial foreground checks; cancellation owns only calls made by this monitor. */
class FavoriteMonitor(private val library: Library, source: ContentRepository, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var destroyed = false
    private val http = source.http.newBuilder().callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val repository = ContentRepository(http)
    var running = false; private set
    var status = "进入收藏可手动检查更新"; private set
    var lastChangedId: String? = null; private set

    fun check(force: Boolean = false) {
        if (running || destroyed || !library.isLoaded) return
        val now = System.currentTimeMillis()
        val favorites = library.favorites()
        // Keep automatic checks under ~40 requests/hour no matter how large the library grows.
        val windowHours = favorites.size / 40 + 1
        val items = favorites.filter { force || library.favoriteUpdate(it.id)?.let { now - it.checkedAt !in 0 until windowHours * 3_600_000L } != false }
        lastChangedId = null
        if (items.isEmpty()) {
            status = if (favorites.isEmpty()) "收藏后可检查新集" else "最近 $windowHours 小时内已检查，可手动刷新"
            changed(); return
        }
        val ticket = ++epoch
        val owned = RequestScope()
        scope = owned
        running = true
        var completed = 0; var failures = 0; var consecutiveFailures = 0
        fun next() {
            if (ticket != epoch) return
            if (completed == items.size || consecutiveFailures >= 3) {
                running = false
                status = "已检查 $completed/${items.size} 部" + (if (failures > 0) " · $failures 部失败，稍后可重试" else " · 检查完成")
                changed(); return
            }
            val item = items[completed]
            val started = System.currentTimeMillis()
            status = "检查更新 ${completed + 1}/${items.size}…"
            changed()
            lastChangedId = null
            job = worker.submit {
                if (ticket != epoch) return@submit
                val result = runCatching { owned.run { repository.detail(item.id) } }
                main.post {
                    if (ticket != epoch) return@post
                    job = null
                    result.onSuccess { detail ->
                        library.observeFavorite(item.id, detail.episodes.size, started,
                            newSeason = detail.newerSeasons.lastOrNull())
                        lastChangedId = item.id
                        consecutiveFailures = 0
                    }.onFailure { failures++; consecutiveFailures++ }
                    completed++
                    // Pace serial requests so a large library does not burst against the site.
                    main.postDelayed({ next() }, 1_500L)
                }
            }
        }
        next()
    }
    fun stop() {
        epoch++
        if (running) status = "更新检查已暂停，返回目录后继续"
        running = false
        scope?.cancel(); scope = null
        job?.cancel(true); job = null
    }
    fun destroy() { destroyed = true; stop(); worker.shutdownNow() }
}
