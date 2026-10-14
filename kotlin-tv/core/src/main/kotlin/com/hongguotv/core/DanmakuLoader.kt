// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Buffers official comments for one episode ahead of the playback position. Call [loadUpTo]
 * from a background thread as playback advances (it is cheap and re-entrant); read visible
 * comments with [between] from the UI thread. Failures back off silently — comments are a
 * decoration and must never interrupt playback.
 */
class DanmakuLoader(
    private val repository: DanmakuRepository,
    private val seriesId: String,
    private val vid: String,
    @Volatile var durationMs: Long,
    private val leadMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val comments = ArrayList<Danmaku>()
    private val seen = HashSet<String>()
    private val loading = AtomicBoolean(false)
    private var frontier = 0L
    private var exhausted = false
    private var failures = 0
    private var retryAfter = 0L

    /** Fetch windows until buffered past [positionMs] + lead. No-op while another load runs. */
    fun loadUpTo(positionMs: Long) {
        if (durationMs <= 0 || !loading.compareAndSet(false, true)) return
        try {
            while (true) {
                val start = synchronized(this) {
                    when {
                        exhausted || clock() < retryAfter -> return
                        frontier >= durationMs -> { exhausted = true; return }
                        positionMs > frontier -> {
                            // A forward seek skips windows that will never be watched.
                            frontier = positionMs / DanmakuRepository.WINDOW_MS * DanmakuRepository.WINDOW_MS
                            if (frontier > positionMs + leadMs) return
                            frontier
                        }
                        frontier > positionMs + leadMs -> return
                        else -> frontier
                    }
                }
                val window = try {
                    repository.window(seriesId, vid, durationMs, start)
                } catch (failed: Exception) {
                    synchronized(this) {
                        failures++
                        retryAfter = clock() + RETRY_BACKOFF_MS
                        if (failures >= MAX_FAILURES) exhausted = true
                    }
                    return
                }
                synchronized(this) {
                    failures = 0
                    for (comment in window.comments) {
                        val key = if (comment.id.isNotEmpty()) "id:${comment.id}" else "anonymous:${comment.offsetMs}:${comment.text}"
                        if (seen.add(key)) comments.add(comment)
                    }
                    // Windows normally arrive in order; a forward seek can interleave offsets.
                    if (comments.size > 1 && comments[comments.size - 2].offsetMs > comments[comments.size - 1].offsetMs) {
                        comments.sortBy { it.offsetMs }
                    }
                    frontier = if (window.nextStartMs > start) window.nextStartMs else start + DanmakuRepository.WINDOW_MS
                    if (frontier >= durationMs) exhausted = true
                }
            }
        } finally {
            loading.set(false)
        }
    }

    /** Comments anchored in [fromMs, [toMs]); the list is sorted by offset. UI-thread safe. */
    fun between(fromMs: Long, toMs: Long): List<Danmaku> = synchronized(this) {
        if (fromMs >= toMs || comments.isEmpty()) return@synchronized emptyList()
        comments.filter { it.offsetMs >= fromMs && it.offsetMs < toMs }
    }

    /** Total distinct comments buffered so far; mainly for diagnostics and tests. */
    val buffered: Int get() = synchronized(this) { comments.size }

    companion object {
        private const val MAX_FAILURES = 3
        private const val RETRY_BACKOFF_MS = 30_000L
    }
}
