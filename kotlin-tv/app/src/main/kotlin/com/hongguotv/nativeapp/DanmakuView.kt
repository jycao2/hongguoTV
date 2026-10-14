// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuLoader

/**
 * Lightweight rolling-comment overlay. The playback position is the clock: a comment enters
 * from the right edge when playback crosses its anchor and drifts left at a fixed speed, so
 * pausing, seeking and playback-speed changes need no extra handling. Comments are assigned
 * to the first track whose previous comment has fully entered; when every track is busy the
 * comment is dropped, matching common danmaku behavior.
 */
class DanmakuView(context: Context, textSizeSp: Float = 20f, private val crossMs: Long = 9_000L) : View(context) {
    private data class Active(val danmaku: Danmaku, val width: Float, val track: Int)

    private val active = ArrayList<Active>()
    var loader: DanmakuLoader? = null
        private set
    private var positionProvider: () -> Long = { 0L }
    private var needMore: (Long) -> Unit = {}
    private var lastPumpAt = 0L
    private var lastEmit = -1L
    private var running = false
    private var dirty = false
    private var pxPerMs = 0f
    private var tracks = 1
    private var trackHeight = 0f
    private val gapPx = 24 * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
        setShadowLayer(textSize / 6f, 0f, 0f, Color.BLACK)
    }

    init {
        isFocusable = false
        visibility = GONE
    }

    /**
     * [position] reports playback milliseconds; [needMore] is raised on the UI thread about once
     * a second while playback advances — the host should schedule [DanmakuLoader.loadUpTo] off-thread.
     */
    fun bind(loader: DanmakuLoader, position: () -> Long, needMore: (Long) -> Unit = {}) {
        unbind()
        this.loader = loader
        positionProvider = position
        this.needMore = needMore
        lastEmit = position().coerceAtLeast(0)
        lastPumpAt = 0L
        running = true
        visibility = VISIBLE
        postOnAnimation(frame)
    }

    fun unbind() {
        running = false
        loader = null
        needMore = {}
        active.clear()
        lastEmit = -1L
        visibility = GONE
    }

    /** Playback jumped; comments on screen belong to the old position. */
    fun seekTo(positionMs: Long) {
        active.clear()
        lastEmit = positionMs.coerceAtLeast(0)
        dirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pxPerMs = if (crossMs > 0) w.toFloat() / crossMs else 0f
        trackHeight = paint.textSize * 1.5f
        tracks = (h / trackHeight).toInt().coerceAtLeast(1)
        active.clear()
        lastEmit = -1L
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            postOnAnimation(this)
        }
    }

    private fun x(item: Active, position: Long) = width - (position - item.danmaku.offsetMs) * pxPerMs

    private fun step() {
        val loader = loader ?: return
        if (width == 0) return
        val position = positionProvider().coerceAtLeast(0)
        if (lastEmit < 0) lastEmit = position
        if (position < lastEmit - SEEK_TOLERANCE_MS || position - lastEmit > SEEK_FORWARD_MS) {
            active.clear()
            lastEmit = position
            dirty = true
        }
        if (position > lastEmit) {
            for (comment in loader.between(lastEmit, position)) emit(comment, position)
            lastEmit = position
            dirty = true
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (position > 0 && now - lastPumpAt > 1_000) {
            lastPumpAt = now
            needMore(position)
        }
        val widthF = width.toFloat()
        if (active.removeAll { x(it, position) + it.width < 0 }) dirty = true
        if (dirty) {
            dirty = false
            invalidate()
        }
    }

    private fun emit(comment: Danmaku, position: Long) {
        if (pxPerMs <= 0f) return
        val textWidth = paint.measureText(comment.text)
        val widthF = width.toFloat()
        for (track in 0 until tracks) {
            val last = active.lastOrNull { it.track == track }
            if (last == null || x(last, position) + last.width < widthF - gapPx) {
                active.add(Active(comment, textWidth, track))
                return
            }
        }
        // Every track is busy; drop the comment rather than overlapping.
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (active.isEmpty()) return
        val position = positionProvider().coerceAtLeast(0)
        val widthF = width.toFloat()
        for (item in active) {
            val x = x(item, position)
            if (x > widthF || x + item.width < 0) continue
            canvas.drawText(item.danmaku.text, x, item.track * trackHeight + paint.textSize, paint)
        }
    }

    companion object {
        private const val SEEK_TOLERANCE_MS = 250L
        private const val SEEK_FORWARD_MS = 1_000L
    }
}
