// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 统一弹窗视觉层：深色玻璃卡片底、大圆角、细描边、胶囊按钮、 accent 焦点高亮。
 * 所有原生 AlertDialog 统一走这里，保证全应用弹窗观感一致。
 * 注意：构建方法只创建不显示，由调用方决定何时 show() 与焦点恢复。
 */
object TvDialog {

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    /** 当前存活的弹窗，后进的在最上层。遥控要作用在它上面，见 [topmostDialog]。
     *  用 Dialog 而非 AlertDialog 登记：选集面板（EpisodePanel）和 NAS 播放器是独立 Dialog 窗口，同样要能被遥控。 */
    private val live = ArrayList<Dialog>()

    /**
     * [card] 最近产出的卡片，给 [styleWindow] 自然测宽用。
     * AlertDialog 在 show() 之前 decor 里还没有内容视图（installContent 发生在 show→onCreate），
     * 从窗口里捞 android.R.id.content 的 child 必然是 null——老代码的自然测宽因此永远落空，
     * 所有不传 widthDp 的弹窗都走了「半屏回退」宽度。这里在 card() 时登记、styleWindow 时消费。
     */
    private var pendingCard: View? = null

    /**
     * 屏幕上最上层的弹窗，没有就返回 null。
     *
     * Activity.currentFocus 只认 Activity 自己的窗口，弹窗一旦盖上来它就是 null，
     * 遥控那套「手动 focusSearch + performClick」会全部落空——真机上的表现正是
     * 「弹窗弹出来，遥控器就点不动了」。所以遥控命令要先问这里。
     */
    fun topmostDialog(): Dialog? {
        live.removeAll { !it.isShowing }
        return live.lastOrNull()
    }

    /**
     * 把遥控按键作用到弹窗上。
     *
     * 不用 dispatchKeyEvent 注入：弹窗是独立窗口，注入的按键会先经过 Dialog 的
     * callback，不确定能落到焦点搜索上，实测按下去毫无反应。这里直接对弹窗自己的
     * 视图树做和主界面一样的「焦点搜索 + 点击」，行为确定。
     */
    fun dispatchRemoteKey(dialog: Dialog, key: String): Boolean {
        val decor = dialog.window?.decorView ?: return false
        if(key == "back") { dialog.dismiss(); return true }
        val focus = decor.findFocus()
        if(key == "center") return focus?.performClick() == true
        val direction = when(key) {
            "up" -> View.FOCUS_UP
            "down" -> View.FOCUS_DOWN
            "left" -> View.FOCUS_LEFT
            "right" -> View.FOCUS_RIGHT
            else -> return false
        }
        val next = (focus ?: decor).focusSearch(direction) ?: decor.focusSearch(direction)
        return next != null && next.requestFocus()
    }

    /** 弹窗内容卡片：圆角 + 细描边 + 主题 surface 底色。 */
    fun card(activity: Activity, content: View): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(TvStyle.surface)
                cornerRadius = dp(activity, 20).toFloat()
                setStroke(dp(activity, 1), TvStyle.outline)
            }
            setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 20))
        }
        card.addView(content)
        pendingCard = card
        return card
    }

    /** 弹窗标题：20sp 加粗，底部留间距。 */
    fun title(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 20f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(TvStyle.text)
        setPadding(0, 0, 0, dp(activity, 14))
    }

    /** 弹窗正文/说明：15sp，muted 色，行高舒适。 */
    fun body(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(TvStyle.muted)
        setLineSpacing(0f, 1.25f)
        setPadding(0, 0, 0, dp(activity, 12))
    }

    /** 胶囊按钮：默认 surface 底，焦点 accent 填充 + 深色文字。 */
    fun capsuleButton(activity: Activity, text: String, onClick: () -> Unit): Button {
        val dp = { v: Int -> dp(activity, v) }
        return Button(activity).apply {
            this.text = text
            textSize = 15f
            setTextColor(TvStyle.text)
            minHeight = dp(44)
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                setColor(TvStyle.surface)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), TvStyle.outline)
            }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    setColor(if(focused) TvStyle.accent else TvStyle.surface)
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), if(focused) TvStyle.accent else TvStyle.outline)
                }
                setTextColor(if(focused) TvStyle.background else TvStyle.text)
                setTypeface(null, if(focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            setOnClickListener { onClick() }
        }
    }

    /** 列表选择弹窗：统一卡片 + 选项按钮列。
     *  样式与「全局默认清晰度」选择器完全一致：surface 底无边框、焦点 raised+accent 边框、
     *  当前项 selectedSurface 底（调用方在文案里带「· 当前」）；不设取消按钮，按返回键关闭。
     *  选项必须是可点击的按钮行而不是 ListView：遥控的确认键是对焦点 View 调 performClick()，
     *  ListView 的 performClick 不会触发 OnItemClickListener，方向键也挪不动列表项。
     *  focusIndex 兼作当前项标注与开窗焦点；没有「当前值」概念的弹窗（如搜索历史）不传即可。 */
    fun choose(
        activity: Activity,
        title: String,
        labels: List<String>,
        onChoice: (Int) -> Unit,
        extra: String? = null,
        onExtra: () -> Unit = {},
        focusIndex: Int = -1,
        anchorView: View? = null
    ): AlertDialog {
        val dialog = AlertDialog.Builder(activity).create()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        // 行宽按最长文案统一：窄弹窗 + 等宽选项行，文字左对齐（Button 默认居中）
        val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * activity.resources.displayMetrics.scaledDensity
        }
        val titleTp = android.text.TextPaint(tp).apply {
            textSize = 20f * activity.resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val itemWidth = maxOf(
            labels.maxOfOrNull { tp.measureText(it) } ?: 0f,
            titleTp.measureText(title)
        ).toInt() + dp(activity, 16) * 2
        val items = labels.mapIndexed { index, label ->
            TextView(activity).apply {
                text = label; textSize = 15f; setTextColor(TvStyle.text); includeFontPadding = false
                id = View.generateViewId()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
                minHeight = dp(activity, 46)
                isFocusable = true; isClickable = true
                // 与清晰度选择器同一套状态：焦点 raised+accent 边框，当前项 selectedSurface，平时 surface 无边框
                fun paint(focused: Boolean) {
                    background = TvStyle.shape(activity,
                        when { focused -> TvStyle.raised; index == focusIndex -> TvStyle.selectedSurface; else -> TvStyle.surface },
                        if(focused) TvStyle.accent else android.graphics.Color.TRANSPARENT)
                }
                paint(false)
                setOnFocusChangeListener { _,focused -> paint(focused) }
                setOnClickListener { dialog.dismiss(); onChoice(index) }
            }
        }
        items.forEachIndexed { index, view ->
            view.nextFocusUpId = items.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusDownId = items.getOrNull(index + 1)?.id ?: view.id
            // 下限只兜极端短的选项（如「是/否」），宽度主要跟最长文案走
            content.addView(view, LinearLayout.LayoutParams(itemWidth.coerceIn(dp(activity, 120), dp(activity, 480)), -2).apply { bottomMargin = dp(activity, 8) })
        }
        val scroll = android.widget.ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(content, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 锚点弹窗：高度压到「按钮顶部 → 屏幕底部」以内，小屏电视也放得下；普通弹窗维持 55% 屏高
        val a = anchorView
        if(a != null && a.isAttachedToWindow) {
            val loc = IntArray(2)
            a.getLocationOnScreen(loc)
            capHeight(activity, scroll, activity.resources.displayMetrics.heightPixels - loc[1] - dp(activity, 24))
        } else capHeight(activity, scroll)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll)
        // 不设取消按钮：返回键就能关。extra（如搜索历史的「清空历史」）是功能入口，保留
        if(extra != null) {
            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(activity, 14), 0, 0)
            }
            buttons.addView(capsuleButton(activity, extra) { onExtra() })
            root.addView(buttons)
        }
        dialog.setView(card(activity, root))
        styleWindow(activity, dialog, anchor = anchorView) {
            items.getOrNull(if(focusIndex in labels.indices) focusIndex else 0)?.requestFocus()
        }
        return dialog
    }

    /** 信息弹窗：标题 + 正文 + 单按钮。 */
    fun info(activity: Activity, title: String, message: String): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        content.addView(body(activity, message))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val ok = capsuleButton(activity, "知道了") {}
        buttons.addView(ok, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog) { ok.requestFocus() }
        ok.setOnClickListener { dialog.dismiss() }
        return dialog
    }

    /** 确认弹窗：标题 + 正文 + 取消/确认双按钮。 */
    fun confirm(activity: Activity, title: String, message: String, action: () -> Unit): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        content.addView(body(activity, message))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val cancel = capsuleButton(activity, "取消") {}
        val ok = capsuleButton(activity, "确认") { action() }
        buttons.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(activity, 12) })
        buttons.addView(ok, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog) { cancel.requestFocus() }
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener { dialog.dismiss(); action() }
        return dialog
    }

    /** 限制滚动容器最大高度，避免长列表把弹窗撑出屏幕（post 到 attach 后执行，未 show 前调用也安全）。 */
    fun capHeight(activity: Activity, view: View, fraction: Float = 0.55f) {
        view.post {
            val cap = (activity.resources.displayMetrics.heightPixels * fraction).toInt()
            if(view.height > cap) { val p = view.layoutParams; p.height = cap; view.layoutParams = p }
        }
    }

    /** 同上，但上限是绝对像素（锚点弹窗用：按钮顶部到屏幕底部的可用高度）。 */
    fun capHeight(activity: Activity, view: View, capPx: Int) {
        view.post {
            if(view.height > capPx) { val p = view.layoutParams; p.height = capPx; view.layoutParams = p }
        }
    }

    /**
     * 通用弹窗窗口样式：透明背景、屏幕居中、宽度收缩到内容自然宽度。
     * - 不传 widthDp：show 前对卡片做一次 UNSPECIFIED 自然测量，短内容（几个选项、一句确认）
     *   得到窄弹窗，不再一律撑半屏；下限 180dp 防过窄，上限 maxWidthDp 防长文撑爆。
     *   注意内容必须是 show 前就绪的（异步填充的列表弹窗请传 widthDp 固定宽度）。
     * - 传 widthDp：固定宽度，给表单、网格这类内容宽度和位置都要求稳定的弹窗。
     * gravity/marginDp 供播放中的小型选择器用：居中会挡住画面，靠左 + 收窄后
     * 既不遮中间内容，也离触发它的底部按钮更近。
     * anchor：悬浮定位到锚点按钮左侧（题材这类筛选器用，像从按钮边展开的下拉）；
     * 顶部与按钮顶部对齐，估高放不下时整体上移夹回屏幕内。
     */
    fun styleWindow(activity: Activity, dialog: AlertDialog, widthDp: Int? = null, maxWidthDp: Int = 520,
                    gravity: Int = Gravity.CENTER, marginDp: Int = 0, anchor: View? = null,
                    onShown: (() -> Unit)? = null) {
        val screen = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val cap = dp(activity, maxWidthDp).coerceAtMost((screen * 0.92f).toInt())
        // 无论走哪条路径都消费掉登记：过期卡片留到下一个弹窗会造成串宽
        val pending = pendingCard
        pendingCard = null
        // show 前 decor 没有内容视图（见 pendingCard 注释），优先用 card() 登记的卡片；
        // decor 路径只对已经 show 过的弹窗有意义，留着作双保险
        val card = pending?.takeIf { it.parent == null }
            ?: dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        val width = when {
            // 显式指定宽度是调用方算好的（内容宽+卡片内边距），只受屏幕约束；
            // 不能套 maxWidthDp：默认上限 520 会把要 620 的表单弹窗压窄，
            // 内容又是定宽的，直接顶破卡片右缘（NAS 编辑连接弹窗的溢出就是这么来的）。
            widthDp != null -> dp(activity, widthDp).coerceAtMost((screen * 0.92f).toInt())
            card == null -> (screen * 0.5f).toInt().coerceAtMost(cap)
            else -> {
                card.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                (card.measuredWidth + dp(activity, 6)).coerceAtMost(cap).coerceAtLeast(minOf(dp(activity, 180), cap))
            }
        }
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT) // show 前就定宽，避免先宽后窄闪一帧
        dialog.setOnShowListener {
            val wantGravity = if(anchor != null) Gravity.TOP or Gravity.START else gravity
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                attributes = attributes.apply {
                    this.gravity = wantGravity
                    if(marginDp > 0) x = dp(activity, marginDp)
                }
            }
            // 锚点定位在 onShow 里同步算好 x/y（首帧前生效；若放到 decor.post 会先落左上角再飞过去）：
            // 右缘贴按钮左侧、顶部与按钮顶部对齐，估高超出屏幕底部时整体上移夹回。
            anchor?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let { a ->
                val win = dialog.window ?: return@let
                val loc = IntArray(2)
                a.getLocationOnScreen(loc)
                val pad = dp(activity, 12)
                // 弹窗高度估算：卡片自然测高（widthDp 路径可能还没量过，补一次）
                if(card != null && card.measuredHeight == 0) card.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val estH = (card?.measuredHeight ?: 0) + dp(activity, 6)
                val lp = win.attributes
                lp.x = (loc[0] - width - pad).coerceAtLeast(pad)
                lp.y = loc[1].coerceAtMost((screenH - estH - pad).coerceAtLeast(pad)).coerceAtLeast(pad)
                win.attributes = lp
            }
            track(dialog)
            onShown?.invoke()
        }
    }

    /** 登记弹窗；窗口消失时自动摘掉，避免长期攥着已经关掉的 Activity。
     *  不走 styleWindow 的弹窗（全屏播放器、选集面板）必须显式调这里才进遥控范围。 */
    fun register(dialog: Dialog) = track(dialog)

    private fun track(dialog: Dialog) {
        if(live.any { it === dialog }) return
        live.add(dialog)
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { live.remove(dialog) }
        })
    }
}
