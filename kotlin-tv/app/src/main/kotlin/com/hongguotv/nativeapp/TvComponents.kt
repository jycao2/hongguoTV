// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.hongguotv.core.L10n
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 全系统组件库（docs/UI_STYLE_GUIDE.md §6）：
 * - [SettingRow] 左右布局行项：名称在左，值 / 开关 / 提示在右。设置页与后续列表页共用。
 * - [SettingOptionPanel] 右栏选项面板：当前项的可选项列表（可操作），开关/动作行显示状态卡片/提示。
 * - [TvSwitch] 开关：轨道+thumb 自绘，关=outline 轨，开=accent 轨，焦点 accent 描边。
 * 弹窗按钮维持 TvDialog.capsuleButton 原版样式（用户 2026-11-26 拍板回退）。
 * 所有视觉细节取自 TvStyle 令牌，禁止在这里写死颜色。
 */

/** 左右布局行项：左=名称（15sp），右=值（muted，右对齐）/开关。整行可聚焦可点击。 */
class SettingRow(context: Context) : LinearLayout(context) {
    val titleView = TextView(context).apply {
        textSize = 15f; setTextColor(TvStyle.text); includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }
    val valueView = TextView(context).apply {
        textSize = 14f; setTextColor(TvStyle.muted); includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val switchView = TvSwitch(context)

    /** 未翻译的名称原文：页面重建后按它恢复焦点，不受界面语言切换影响（2026-11-27） */
    var sourceTitle: String = ""
        private set
    var title: String
        get() = titleView.text.toString()
        // 设置行文案统一翻译（2026-11-26）：getter 返回译文原文（界面所见），setter 防重复翻译。
        // 走 UiL10n.text 而不是 L10n.tr：设置行标题常带「N 秒 / N 集」这类数字模板，要能按正则改写。
        set(v) { sourceTitle = v; titleView.text = UiL10n.text(v) }
    var value: String
        get() = valueView.text.toString()
        set(v) { valueView.text = UiL10n.text(v); valueView.visibility = if(v.isEmpty()) View.GONE else View.VISIBLE; onValueChanged?.invoke() }
    var checked: Boolean
        get() = switchView.checked
        set(v) { switchView.checked = v; onValueChanged?.invoke() }
    var onToggle: ((Boolean) -> Unit)? = null
    var onValueChanged: (() -> Unit)? = null

    /** 选项面板（规范 §6.2）素材：说明文案与操作提示，由设置页注入，供 [SettingOptionPanel] 回显 */
    var desc = ""
    var hint = ""
    /** 焦点回调：内部绘制之外的外部钩子（设置页用它刷新右侧焦点面板） */
    var onFocus: ((Boolean) -> Unit)? = null

    /** 值位显示开关而不是文字 */
    var toggleMode = false
        private set

    private var dangerRow = false
    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = (context.resources.displayMetrics.density).toInt()
        setPadding(d*16, d*12, d*16, d*12)
        minimumHeight = d*48
        // 显式 id：设置页焦点链（nextFocusUp/Down/Left/Right）按 id 串联，NO_ID 会让遥控右键掉进默认搜索
        id = View.generateViewId()
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        // 行背景恢复改版前的样式：常态=页面底色 bg、无边框（用户 2026-11-25）；聚焦仍是 raised+accent 边
        background = TvStyle.shape(context, TvStyle.background, radius = TvStyle.Radius.M)
        // 与整行同焦：开关独立聚焦无意义，遥控一路确认就是整行
        switchView.isFocusable = false; switchView.isClickable = false
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(valueView, LayoutParams(-2, LayoutParams.WRAP_CONTENT).apply { marginStart = d*12 })
        // 注意：visibility 必须写在 switchView 上——LayoutParams 没有 visibility 属性，
        // 写进 apply 会被 Kotlin 解析成外层 SettingRow 自己的 visibility，整行创建即 GONE。
        switchView.visibility = View.GONE
        addView(switchView, LayoutParams(-2, -2).apply { marginStart = d*12 })
        setOnFocusChangeListener { _, focused ->
            background = TvStyle.shape(context,
                if(focused) TvStyle.raised else TvStyle.background,
                if(focused) TvStyle.accent else Color.TRANSPARENT,
                TvStyle.Radius.M)
            if(focused) titleView.setTextColor(TvStyle.text)
            if(dangerRow) titleView.setTextColor(if(focused) TvStyle.danger else TvStyle.danger)
            onFocus?.invoke(focused)
        }
    }

    /** 切成开关行：值位隐藏，开关显示并接管点击语义（整行点击=拨开关并回调） */
    fun asToggle(initial: Boolean, onChange: (Boolean) -> Unit): SettingRow {
        toggleMode = true
        checked = initial
        onToggle = onChange
        valueView.visibility = View.GONE
        switchView.visibility = View.VISIBLE
        setOnClickListener {
            checked = !checked
            onToggle?.invoke(checked)
        }
        return this
    }

    /** 危险行：名称 danger 色 */
    fun asDanger(): SettingRow {
        dangerRow = true
        titleView.setTextColor(TvStyle.danger)
        return this
    }

    companion object {
        /** 兼容「名称 · 值」整串标签：拆给左右两侧。分隔符为两个空格+·+两个空格（全项目既有约定）。 */
        fun setValueLabel(row: View?, label: String) {
            val r = row as? SettingRow
            if(r != null) {
                val i = label.indexOf("  ·  ")
                if(i >= 0) {
                    r.title = label.substring(0, i)
                    r.value = label.substring(i + 5)
                } else {
                    // 无分隔符：整串给值位，名称保持不变（HUD 按钮等场景不走这里）
                    r.value = label
                }
            } else (row as? TextView)?.text = L10n.tr(label)
        }
    }
}

/**
 * 右栏选项面板（规范 §6.2）：三栏布局的第三栏，操作对象是「中间列表当前焦点项」。
 * - 该项有可选项 → 选项列表（可聚焦可上下浏览、确认键生效，取代弹窗选择器），选中行右侧出对勾徽章；
 * - 开关行 → 「当前状态」卡片（静态 TvSwitch，仅回显状态，切换仍在中间列表按确认键）；
 * - 动作行 / 左栏分类 → 说明 + 操作提示，不放假图标。
 * 结构：标题固定顶部，说明+选项在中间滚动区（超高内部滚动），操作提示固定底部。
 * 面板自身不可聚焦，只有选项行可聚焦；焦点链：中间行 →(右)→ 选项 →(左)→ 回中间行。
 */
class SettingOptionPanel(context: Context) : LinearLayout(context) {
    private val d = (context.resources.displayMetrics.density).toInt()
    private val titleView = TextView(context).apply {
        textSize = 17f; setTextColor(TvStyle.text)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); includeFontPadding = false
    }
    private val descView = TextView(context).apply {
        textSize = 13f; setTextColor(TvStyle.muted); includeFontPadding = false
        setLineSpacing(0f, 1.35f)
    }
    private val hintView = TextView(context).apply {
        textSize = 12f; setTextColor(TvStyle.muted); includeFontPadding = false
    }
    private val optionHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** 中间滚动区：说明 + 选项列表放这里，内容超高时内部滚动；fillViewport 让内容不足时撑满以居中占位图标 */
    private val scrollArea = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; clipToPadding = false; isFillViewport = true
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** 空内容占位图标：占满中间区剩余高度，图形在其中水平垂直居中；有选项时收缩为 0 */
    private val iconCenter = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; isFocusable = false
        visibility = View.GONE
    }
    /** 提示区上方的细分隔线：把「内容」和「操作提示」两个区块切开 */
    private val divider = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(TvStyle.outline); cornerRadius = 0.5f*d
        }
    }
    private var optionViews: List<LinearLayout> = emptyList()
    private var selectedIndex = -1
    private var checkedSet: Set<Int> = emptySet()
    private var isMultiSelect = false
    var hasOptions = false
        private set

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(d*20, d*20, d*20, d*20)
        background = TvStyle.shape(context, TvStyle.surface, radius = TvStyle.Radius.L)
        // 容器不可聚焦：遥控焦点只允许落在选项行上
        isFocusable = false; isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 结构：标题固定在顶，中间（说明+选项）占满剩余高度滚动，操作提示固定在底
        addView(titleView, LayoutParams(-1, -2))
        addView(scrollArea, LayoutParams(-1, 0, 1f).apply { topMargin = d*10 })
        addView(divider, LayoutParams(-1, d/2).apply { topMargin = d*14 })
        addView(hintView, LayoutParams(-1, -2).apply { topMargin = d*10 })
        // 宽度必须 MATCH_PARENT：wrap_content 容器里的 match_parent 子项会被收窄成内容宽
        scrollArea.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        content.addView(descView, LinearLayout.LayoutParams(-1, -2))
        content.addView(optionHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d*16 })
        // 占位图标区：weight 吃掉剩余高度，配合 fillViewport 在内容不足时垂直居中；列表长时自动收为 0
        content.addView(iconCenter, LinearLayout.LayoutParams(-1, 0, 1f))
        val topSpacer = View(context)
        val bottomSpacer = View(context)
        iconCenter.addView(topSpacer, LinearLayout.LayoutParams(-1, 0, 1f))
        iconCenter.addView(makeEmptyIcon(), LinearLayout.LayoutParams(220*d, 170*d).apply { gravity = Gravity.CENTER })
        iconCenter.addView(bottomSpacer, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /**
     * 「空白内容」图标：Feather 风格的打开空文件夹线条图标（24×24 viewBox 矢量路径缩放绘制），
     * outline 色 2.5dp 圆头描边——「这里没有内容」的通用视觉语言。
     */
    private fun makeEmptyIcon(): View = object : View(context) {
        private val folderPath = androidx.core.graphics.PathParser.createPathFromPathData(
            "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2z")
        private val matrix = android.graphics.Matrix()
        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val density = resources.displayMetrics.density
            // 图标占视图的 ~78%，居中
            val size = minOf(w, h)*0.78f
            val scale = size/24f
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate((w-size)/2f, (h-size)/2f)
            val drawn = android.graphics.Path()
            folderPath.transform(matrix, drawn)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                // 白色线条（跟随主题正文色，深夜主题下即纯白观感），加粗到 3dp
                color = TvStyle.text; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f*density; strokeJoin = android.graphics.Paint.Join.ROUND
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            canvas.drawPath(drawn, paint)
        }
    }.apply { isFocusable = false }

    private fun reset(title: String, desc: String) {
        titleView.text = L10n.tr(title)
        descView.text = L10n.tr(desc)
        descView.visibility = if(desc.isEmpty()) View.GONE else View.VISIBLE
        optionHost.removeAllViews()
        optionHost.visibility = View.GONE
        iconCenter.visibility = View.GONE
        optionViews = emptyList()
        selectedIndex = -1
        hasOptions = false
        hintView.text = ""
        divider.visibility = View.GONE
        scrollArea.scrollTo(0, 0)
    }

    /** 提示区：分隔线 + 一行小字（reset 后调用一次即可） */
    private fun setHint(text: String) {
        hintView.text = L10n.tr(text)
        divider.visibility = View.VISIBLE
    }

    /**
     * 绑定一个带选项的设置项。[selected] 为当前选中下标（-1=无选中）；
     * [multi]=复选形态：标记从绿色圆点换成纯色方框，配合 [checked] 集合显示勾选态。
     * [onPickIndex] 由页面提供（写偏好 + 刷新中间行值），选中标记由 [repaintOptions] 刷新。
     * [returnRowId]：选项按左键回到中间行的目标行 id。
     */
    fun bindOptions(title: String, desc: String, labels: List<String>, selected: Int,
                    returnRowId: Int, multi: Boolean = false, checked: Set<Int> = emptySet(),
                    onPickIndex: (Int) -> Unit) {
        reset(title, desc)
        hasOptions = true
        selectedIndex = selected
        checkedSet = checked
        isMultiSelect = multi
        optionHost.visibility = View.VISIBLE
        val views = mutableListOf<LinearLayout>()
        labels.forEachIndexed { i, label ->
            // 选中标记：单选=绿色实心圆点；复选=纯色方框。常驻占位避免文字宽度跳动
            val marker = View(context).apply {
                background = GradientDrawable().apply {
                    if(multi) { setColor(TvStyle.success); cornerRadius = 4*d.toFloat() }
                    else { setColor(TvStyle.success); shape = GradientDrawable.OVAL }
                }
            }
            val size = if(multi) 16 else 10
            val tv = TextView(context).apply {
                textSize = 14f; includeFontPadding = false
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                text = L10n.tr(label)
            }
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                id = View.generateViewId()
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setPadding(d*14, d*13, d*12, d*13)
                minimumHeight = d*44
                addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(marker, LinearLayout.LayoutParams(size*d, size*d).apply { marginStart = d*10 })
                // 焦点在选项间移动/进出面板都要重画（焦点底色与选中标记互不覆盖）
                setOnFocusChangeListener { _, _ -> repaintOptions() }
                setOnClickListener { onPickIndex(i) }
            }
            views += rowView
            optionHost.addView(rowView, LayoutParams(-1, -2).apply { bottomMargin = d*8 })
        }
        optionViews = views
        repaintOptions()
        // 选项内部焦点链：上下在本列内移动，左右回到中间行
        views.forEachIndexed { index, view ->
            view.nextFocusUpId = views.getOrNull(index-1)?.id ?: view.id
            view.nextFocusDownId = views.getOrNull(index+1)?.id ?: view.id
            view.nextFocusLeftId = returnRowId
        }
        setHint("上下选择 · 确认键生效 · 左键返回")
    }

    /**
     * 重画选项行：每行都有边框——常态 surface+outline 细边、muted 字；
     * 选中=raised 底+accent 边、accent 粗体字+绿色标记；焦点=accent 实底+白色粗体字。
     */
    fun repaintOptions(selected: Int = selectedIndex) {
        selectedIndex = selected
        optionViews.forEachIndexed { i, view ->
            val isSel = if(isMultiSelect) i in checkedSet else i == selected
            val isFocused = view.hasFocus()
            val (bg, border, strokeDp) = when {
                isFocused -> Triple(TvStyle.accent, TvStyle.accent, 0)
                isSel -> Triple(TvStyle.raised, TvStyle.accent, 1)
                else -> Triple(TvStyle.surface, TvStyle.outline, 1)
            }
            view.background = TvStyle.shape(context, bg, border, TvStyle.Radius.S).apply {
                if(strokeDp > 0) setStroke(d*strokeDp, border)
            }
            val tv = view.getChildAt(0) as TextView
            val marker = view.getChildAt(1) as View
            marker.visibility = if(isSel) View.VISIBLE else View.INVISIBLE
            // 焦点行底色是 accent，标记翻成白色才能看清
            (marker.background as? GradientDrawable)?.setColor(if(isFocused) Color.WHITE else TvStyle.success)
            tv.setTextColor(when {
                isFocused -> Color.WHITE
                isSel -> TvStyle.accent
                else -> TvStyle.muted
            })
            tv.typeface = Typeface.create("sans-serif", if(isSel || isFocused) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** 焦点落点：当前选中项优先，其次第一项。 */
    fun requestLandingFocus(): Boolean {
        val target = optionViews.getOrNull(selectedIndex) ?: optionViews.firstOrNull() ?: return false
        return target.requestFocus()
    }

    /**
     * 操作型选项执行后把焦点放回同一行的新视图。
     * 绑定回调里常会重建面板（如登录态变化刷新 labels），旧焦点视图被 removeAllViews 摘掉，
     * 不显式回填就会失焦；这里按执行前的下标找回重建后的对应项。
     */
    fun focusOption(index: Int): Boolean = optionViews.getOrNull(index)?.requestFocus() ?: false

    /** 复选行：勾选集变化后重画（不重建视图，焦点保持） */
    fun setChecked(checked: Set<Int>) {
        checkedSet = checked
        repaintOptions()
    }

    /**
     * 占位绑定：无选项的行（开关 / 动作 / 分类）。[toggleState] 非 null 时显示「当前状态」卡片
     * （左标签右开关，与中栏 SettingRow 同构），否则画一个滑杆装饰图标占住中部空间。
     */
    fun bindPlaceholder(title: String, desc: String, hintText: String, toggleState: Boolean?) {
        reset(title, desc)
        optionHost.visibility = View.VISIBLE
        if(toggleState != null) {
            // 状态卡片：surface 底 + outline 细描边，开关右对齐——呼应中栏开关行
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false; isClickable = false
                setPadding(d*14, d*13, d*14, d*13)
                minimumHeight = d*48
                background = TvStyle.shape(context, TvStyle.surface, TvStyle.outline, TvStyle.Radius.M)
                val label = TextView(context).apply {
                    textSize = 14f; setTextColor(TvStyle.muted); includeFontPadding = false
                    text = L10n.tr("当前状态")
                }
                addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val sw = TvSwitch(context).apply {
                    checked = toggleState; isFocusable = false; isClickable = false
                }
                addView(sw, LayoutParams(-2, -2))
            }
            optionHost.addView(card, LayoutParams(-1, -2))
        } else {
            // 空内容占位图标：垂直居中占满中间区
            iconCenter.visibility = View.VISIBLE
        }
        setHint(hintText)
    }

    /** 左栏分类回显：分类名 + 副标题。 */
    fun bindCategory(name: String, subtitle: String) =
        bindPlaceholder(name, subtitle, "按确认键进入分类", null)
}

/** 开关：40×22 轨 + 16 thumb。状态由 [checked] 驱动，翻动即重绘。 */
class TvSwitch(context: Context) : View(context) {
    var checked = false
        set(v) { field = v; invalidate() }
    var onToggle: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    init { contentDescription = "开关" }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val trackH = 22*d; val trackW = 40*d
        val left = (w - trackW)/2; val top = (h - trackH)/2
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // 轨道
        paint.color = if(checked) TvStyle.accent else TvStyle.outline
        canvas.drawRoundRect(left, top, left+trackW, top+trackH, trackH/2, trackH/2, paint)
        // thumb
        val t = 16*d
        val cx = if(checked) left+trackW-t/2-2*d else left+t/2+2*d
        val cy = top+trackH/2
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, t/2, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((40*d).toInt(), (26*d).toInt())
}

/**
 * 自动翻译的文本控件（2026-11-27）。
 *
 * 播放页状态行这类文案全是运行时拼出来的：失败原因、跳过计数、限流倒计时、定位时间。
 * 出口散在几十处赋值里，逐个包 `L10n.tr` 必然漏，而且以后新加的文案还会继续漏。
 * 这里在控件层兜一次：每次 setText 都过 [UiL10n.text]（整串 → 分段 → 数字模板），
 * 查不到就原样显示，所以对用户生成的内容（剧名等）也不会误改——只用于纯 UI 文案控件。
 */
class LocalizedTextView(context: Context) : TextView(context) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text?.let { UiL10n.text(it.toString()) }, type)
    }
}
