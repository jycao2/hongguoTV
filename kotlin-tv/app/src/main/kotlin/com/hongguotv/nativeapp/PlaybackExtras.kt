// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/**
 * 电视端本地播放增强设置：弹幕屏蔽词、按剧弹幕记忆、遥控器按键与不兼容集跳过。
 * 只保存在本机，不进入备份，换机后重新设置即可。
 */
class PlaybackExtras(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 弹幕屏蔽词：命中任意一个即不展示该条弹幕。 */
    var blockedWords: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, DEFAULT_BLOCKED)?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet(KEY_BLOCKED, value.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply() }

    fun blocks(text: String) = blockedWords.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

    /** 按剧记忆弹幕开关：全局开关仍是总闸，单剧可以关闭，默认跟随全局。 */
    fun danmakuEnabled(seriesId: String, global: Boolean) = global && prefs.getBoolean(KEY_DANMAKU + seriesId, true)

    fun setDanmakuEnabled(seriesId: String, on: Boolean) {
        prefs.edit().putBoolean(KEY_DANMAKU + seriesId, on).apply()
    }

    /** 平台只提供电视不可解码的清晰度时，自动跳到下一集（默认关闭，避免跳过用户想看的集数）。 */
    var autoSkipUnplayable: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_SKIP, value).apply() }

    /** 遥控器左右短按的快进步长（秒）。 */
    var seekStepSeconds: Int
        get() = prefs.getInt(KEY_SEEK_STEP, 10).takeIf { it in SEEK_STEPS } ?: 10
        set(value) { prefs.edit().putInt(KEY_SEEK_STEP, value).apply() }

    /** 长按右键快进时使用的倍速。 */
    var longPressSpeed: Float
        get() = prefs.getFloat(KEY_FAST_SPEED, 3f).takeIf { it in FAST_SPEEDS } ?: 3f
        set(value) { prefs.edit().putFloat(KEY_FAST_SPEED, value).apply() }

    /** 播放中按菜单键的行为：panel 播放设置 / episodes 选集 / danmaku 弹幕开关。 */
    var menuKeyAction: String
        get() = prefs.getString(KEY_MENU, MENU_PANEL)?.takeIf { it in listOf(MENU_PANEL, MENU_EPISODES, MENU_DANMAKU) } ?: MENU_PANEL
        set(value) { prefs.edit().putString(KEY_MENU, value).apply() }

    companion object {
        private const val PREFS = "playback-extras-v1"
        private const val KEY_BLOCKED = "blockedWords"
        private const val KEY_DANMAKU = "danmaku:"
        private const val KEY_AUTO_SKIP = "autoSkipUnplayable"
        private const val KEY_SEEK_STEP = "seekStepSeconds"
        private const val KEY_FAST_SPEED = "longPressSpeed"
        private const val KEY_MENU = "menuKeyAction"
        val SEEK_STEPS = listOf(10, 15, 30, 60)
        val FAST_SPEEDS = listOf(2f, 3f, 4f)
        const val MENU_PANEL = "panel"
        const val MENU_EPISODES = "episodes"
        const val MENU_DANMAKU = "danmaku"
        private val DEFAULT_BLOCKED = setOf("加群", "微信", "公众号", "QQ群")
    }
}
