// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    enum class Theme(val label: String) { NIGHT("深夜影院"), PORCELAIN("暖玉白");
        companion object { fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: NIGHT }
    }
    var theme = Theme.NIGHT
        private set
    fun apply(raw: String?) { theme = Theme.of(raw) }
    fun toggle(): Theme { theme = if (theme == Theme.NIGHT) Theme.PORCELAIN else Theme.NIGHT; return theme }

    val background get() = if (theme == Theme.NIGHT) Color.rgb(11,15,21) else Color.rgb(226,217,200)
    val surface get() = if (theme == Theme.NIGHT) Color.rgb(23,30,40) else Color.rgb(255,255,255)
    val raised get() = if (theme == Theme.NIGHT) Color.rgb(32,42,54) else Color.rgb(243,236,224)
    val accent get() = if (theme == Theme.NIGHT) Color.rgb(255,137,100) else Color.rgb(196,83,46)
    /** 辅助强调色：剧集元信息与播放状态小标，不与焦点橙冲突。 */
    val gold get() = if (theme == Theme.NIGHT) Color.rgb(255,196,107) else Color.rgb(166,104,28)
    val text get() = if (theme == Theme.NIGHT) Color.rgb(243,244,246) else Color.rgb(58,52,44)
    val muted get() = if (theme == Theme.NIGHT) Color.rgb(147,160,178) else Color.rgb(139,125,107)
    val outline get() = if (theme == Theme.NIGHT) Color.rgb(43,55,70) else Color.rgb(206,195,176)
    val selectedSurface get() = if (theme == Theme.NIGHT) Color.rgb(61,39,34) else Color.rgb(224,206,188)
    val badgeSurface get() = if (theme == Theme.NIGHT) Color.rgb(28,31,39) else Color.rgb(80,70,60)
    /** 危险操作色：不可逆删除/关闭保护。见 docs/UI_STYLE_GUIDE.md §2。 */
    val danger get() = if (theme == Theme.NIGHT) Color.rgb(232,86,86) else Color.rgb(196,60,60)
    /** 状态反馈绿：只用于状态点（接口自检等），不做大面积填充。 */
    val success get() = Color.rgb(76,175,80)
    const val POSTER_ASPECT=.7f

    // ---- 圆角刻度（docs/UI_STYLE_GUIDE.md §4）----
    object Radius { const val S=8; const val M=12; const val L=16; const val XL=20; const val PILL=-1 }
    // ---- 间距刻度：4/8/12/16/20/24/28，代码里直接写这些值，不再自创 ----

    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=Radius.M)=GradientDrawable().apply {
        // PILL 用超大圆角，绘制时系统会自动钳到短边的一半（半高胶囊）
        setColor(color); cornerRadius=(if(radius==Radius.PILL) 500 else radius)*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) raised else surface,if(focused) accent else Color.TRANSPARENT)

    /**
     * 全系统唯一的焦点视觉（规范 §5）：焦点=raised 底+accent 2dp 描边；选中=selectedSurface 底+accent；
     * 默认=baseColor（不给则用 surface）。用法：
     *   val paint=TvStyle.focusPainter(view); paint(false);
     *   view.setOnFocusChangeListener { _,f -> paint(f) }
     */
    fun focusPainter(view: View,selected: Boolean=false,baseColor: Int=-1,strokeFocusedOnly: Boolean=false): (Boolean)->Unit {
        val context=view.context
        val base=if(baseColor>=0) baseColor else surface
        fun paint(focused: Boolean) {
            view.background=shape(context,when { focused -> raised; selected -> selectedSurface; else -> base },
                if(focused||selected) accent else Color.TRANSPARENT)
        }
        return ::paint
    }
}
