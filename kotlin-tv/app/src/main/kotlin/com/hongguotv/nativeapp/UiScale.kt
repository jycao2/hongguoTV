package com.hongguotv.nativeapp

import android.content.Context
import android.content.res.Configuration

/**
 * 界面整体缩放：把 density 等比放大，文字、间距、控件一起变大，布局相对关系不变。
 *
 * 动机（repo issue #14）：电视观看距离远，同一份 sp 字号在不同电视上观感差异极大——
 * 各厂商 density 并不统一（720p≈160、1080p≈240、4K≥320），且「离得远」本身是物理变量，
 * 应用猜不准，给用户一档自己调的旋钮最可靠。
 *
 * 实现：attachBaseContext 用 [wrap] 包一层调过 densityDpi 的 Context，dp/sp 全部等比生效，
 * 像素宽高不变（网格列数、占屏比例关系保持）。只有 MainActivity 一个 Activity，
 * 全部 UI 组件（TvTools/NasScreen/TvDialog/EpisodePanel 等）都从 activity context 构建，
 * 一处覆盖即全局生效；改档位后 recreate() 重建即生效（与「系统风格」切换同款交互）。
 */
object UiScale {
    /** 档位循环用；不做自由滑杆——遥控器很难操作滑杆，档位循环与现有设置风格一致。 */
    val LEVELS = listOf("标准" to 1.0f, "大" to 1.15f, "特大" to 1.3f)
    private const val PREFS = "native-library-v1"
    private const val KEY = "uiScale"

    fun index(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY, 0).coerceIn(0, LEVELS.size - 1)

    fun factor(context: Context): Float = LEVELS[index(context)].second

    fun label(context: Context): String = LEVELS[index(context)].first

    /** 切到下一档并落盘，返回新档位下标。调用方随后 recreate() 让新密度生效。 */
    fun cycle(context: Context): Int {
        val next = (index(context) + 1) % LEVELS.size
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, next).apply()
        return next
    }

    /** 包一层带缩放密度的 Context；标准档原样返回，零开销。 */
    fun wrap(base: Context): Context {
        val f = factor(base)
        if(f == 1f) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = (cfg.densityDpi * f).toInt()
        return base.createConfigurationContext(cfg)
    }
}
