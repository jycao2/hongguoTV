// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    enum class Theme(val label: String) { NIGHT("深夜影院"), PORCELAIN("暖玉白");
        companion object { fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: NIGHT }
    }
    var theme = Theme.NIGHT
        private set
    fun apply(raw: String?) { theme = Theme.of(raw) }
    fun toggle(): Theme { theme = if (theme == Theme.NIGHT) Theme.PORCELAIN else Theme.NIGHT; return theme }

    val background get() = if (theme == Theme.NIGHT) Color.rgb(11,15,21) else Color.rgb(245,241,234)
    val surface get() = if (theme == Theme.NIGHT) Color.rgb(23,30,40) else Color.rgb(255,255,255)
    val raised get() = if (theme == Theme.NIGHT) Color.rgb(32,42,54) else Color.rgb(236,229,216)
    val accent get() = if (theme == Theme.NIGHT) Color.rgb(255,137,100) else Color.rgb(196,83,46)
    val text get() = if (theme == Theme.NIGHT) Color.rgb(243,244,246) else Color.rgb(58,52,44)
    val muted get() = if (theme == Theme.NIGHT) Color.rgb(147,160,178) else Color.rgb(154,141,124)
    val outline get() = if (theme == Theme.NIGHT) Color.rgb(43,55,70) else Color.rgb(227,220,205)
    val selectedSurface get() = if (theme == Theme.NIGHT) Color.rgb(61,39,34) else Color.rgb(232,213,199)
    val badgeSurface get() = if (theme == Theme.NIGHT) Color.rgb(28,31,39) else Color.rgb(80,70,60)
    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) raised else surface,if(focused) accent else Color.TRANSPARENT)
}
