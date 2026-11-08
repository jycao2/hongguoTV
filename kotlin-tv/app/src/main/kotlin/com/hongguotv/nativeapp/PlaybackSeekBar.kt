// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * 播放页进度条：显示进度，也接受鼠标 / 触摸拖动定位。
 *
 * 为什么不用系统 SeekBar：TV 主题下它的 thumb 又小又难瞄，轨道粗细也不受控。
 * 这里轨道只画 4dp，可点区域却有 28dp——鼠标落在轨道上下十来像素都能按住，
 * 按下即跳、横向拖动跟手。滚轮停在条上滚是 ±10 秒，滚在画面别处仍是换集，
 * 两种手势靠「指针有没有落在条上」天然分开。
 *
 * 拖动期间对 [progress] 的写入会被忽略：播放器每秒刷新一次进度，
 * 不挡住的话手指按住的位置会被播放位置拽回去，拖到一半就跳。
 */
class PlaybackSeekBar(context: Context) : View(context) {

    /** 进度上限，沿用旧 ProgressBar 的 0..max 口径（默认 1000）。 */
    var max: Int = 1000
        set(value) {
            field = value.coerceAtLeast(1)
            current = current.coerceIn(0, field)
            invalidate()
        }

    private var current = 0

    /** 当前进度（0..max）。拖动中写入无效，见类注释。 */
    var progress: Int
        get() = current
        set(value) {
            if(dragging) return
            current = value.coerceIn(0, max)
            invalidate()
        }

    /** 拖动回调：ratio 为 0..1，final 表示松手（调用方据此决定要不要真正 seek）。 */
    var onScrub: ((ratio: Float, final: Boolean) -> Unit)? = null

    /** 拖动时的实时回调（用来把「定位到 12:34」写到文字上）。 */
    var onScrubPreview: ((ratio: Float) -> Unit)? = null

    /** 滚轮步长：正数向后、负数向前，单位毫秒。 */
    var scrollStepMs = 10_000L

    /** 滚轮在进度条上滚动时回调（正=快进）。 */
    var onScrollStep: ((deltaMs: Long) -> Unit)? = null

    /** 正在被按住拖动。上层据此暂停每秒的进度刷新。 */
    var scrubbing: Boolean = false
        private set

    private var dragging = false
    private var hovered = false
    private val density = resources.displayMetrics.density
    private val trackHeight = 4f * density
    private val thumbRadius = 5f * density
    private val thumbHoverRadius = 7f * density
    private val thumbDragRadius = 8f * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TvStyle.surface }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TvStyle.accent }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TvStyle.accent }
    private val rect = RectF()

    init {
        // 可点击才会参与触摸命中；不抢遥控焦点，焦点仍在播放控制条上
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val w = width.toFloat()
        if(w <= 0f) return
        val radius = trackHeight / 2
        rect.set(0f, cy - radius, w, cy + radius)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val ratio = (current.toFloat() / max).coerceIn(0f, 1f)
        val fillRight = (w * ratio).coerceIn(radius, w)
        if(ratio > 0f) {
            rect.set(0f, cy - radius, fillRight, cy + radius)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        val r = when {
            dragging -> thumbDragRadius
            hovered -> thumbHoverRadius
            else -> thumbRadius
        }
        canvas.drawCircle(fillRight.coerceIn(r, (w - r).coerceAtLeast(r)), cy, r, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled || !isClickable) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; scrubbing = true
                // 进度条在 ScrollView 里：不 disallow 的话，稍微带点垂直分量的拖动会被
                // 外层当成滚动拦走，表现就是拖到一半突然不动
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event.x, false)
                return true
            }
            MotionEvent.ACTION_MOVE -> if(dragging) return applyTouch(event.x, false)
            MotionEvent.ACTION_UP -> {
                if(dragging) {
                    dragging = false
                    applyTouch(event.x, true)
                    scrubbing = false
                    performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if(dragging) { dragging = false; scrubbing = false; invalidate() }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float, final: Boolean): Boolean {
        val w = width.coerceAtLeast(1)
        val ratio = (x / w).coerceIn(0f, 1f)
        current = (ratio * max).toInt().coerceIn(0, max)
        invalidate()
        onScrubPreview?.invoke(ratio)
        onScrub?.invoke(ratio, final)
        return true
    }

    /** 鼠标滚轮：只在指针停在进度条上时生效，画面其它位置的滚轮仍归换集手势。 */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if(delta != 0f) { onScrollStep?.invoke(if(delta > 0) scrollStepMs else -scrollStepMs); return true }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> if(!hovered) { hovered = true; invalidate() }
            MotionEvent.ACTION_HOVER_EXIT -> if(hovered) { hovered = false; invalidate() }
        }
        return super.onHoverEvent(event)
    }
}
