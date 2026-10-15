// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * User-tunable bullet-comment (弹幕) rendering preferences. Values persist as plain ints in
 * backups and on disk, so every write must pass through these normalizers; both player UIs
 * share the same labels and rendering scales from here.
 */
object DanmakuPrefs {
    const val OPACITY_MIN = 21
    const val OPACITY_MAX = 100
    const val OPACITY_DEFAULT = 100
    const val FONT_DEFAULT = 2
    const val SPEED_DEFAULT = 2
    const val ROWS_MIN = 1
    const val ROWS_MAX = 10
    const val ROWS_DEFAULT = 3
    val FONT_LABELS = listOf("小", "较小", "适中", "大", "较大")
    val SPEED_LABELS = listOf("慢", "较慢", "适中", "快", "较快")

    fun opacity(value: Int) = if (value in OPACITY_MIN..OPACITY_MAX) value else OPACITY_DEFAULT
    fun font(value: Int) = value.coerceIn(0, FONT_LABELS.lastIndex)
    fun speed(value: Int) = value.coerceIn(0, SPEED_LABELS.lastIndex)
    fun rows(value: Int) = if (value in ROWS_MIN..ROWS_MAX) value else ROWS_DEFAULT
    fun fontLabel(value: Int) = FONT_LABELS[font(value)]
    fun speedLabel(value: Int) = SPEED_LABELS[speed(value)]

    /** Text size multiplier per font choice. */
    fun fontScale(value: Int) = when (font(value)) {
        0 -> 0.72f; 1 -> 0.85f; 2 -> 1f; 3 -> 1.18f; else -> 1.36f
    }

    /** Cross-screen time multiplier per speed choice; a larger factor drifts slower. */
    fun speedScale(value: Int) = when (speed(value)) {
        0 -> 1.5f; 1 -> 1.25f; 2 -> 1f; 3 -> 0.8f; else -> 0.65f
    }
}
