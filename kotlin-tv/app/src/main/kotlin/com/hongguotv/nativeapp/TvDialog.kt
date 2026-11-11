// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 统一弹窗视觉层：深色玻璃卡片底、大圆角、细描边、胶囊按钮、 accent 焦点高亮。
 * 所有原生 AlertDialog 统一走这里，保证全应用弹窗观感一致。
 * 注意：构建方法只创建不显示，由调用方决定何时 show() 与焦点恢复。
 */
object TvDialog {

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    /** 弹窗正文滚动区的标记：遥控上下键靠它找到该滚哪一块（见 [dispatchRemoteKey]）。 */
    private const val TAG_BODY = "tvdialog:body"
    /** 确认框「确认」按钮的标记：语音「确认接收」直接点它，不去猜焦点落在哪个按钮上。 */
    private const val TAG_CONFIRM = "tvdialog:confirm"

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
     * 语音 / 遥控的「确认接收」：点最上层确认框的「确认」按钮。
     * 不看当前焦点，免得焦点停在「取消」时一句确认把接收给取消了；没有确认按钮就返回 false。
     */
    fun confirmTopmost(): Boolean {
        val decor = topmostDialog()?.window?.decorView ?: return false
        val ok = decor.findViewWithTag<View>(TAG_CONFIRM) ?: return false
        return ok.isEnabled && ok.performClick()
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
        // 正文超高的弹窗：上下键翻正文，翻到头再放焦点走。
        // 实体遥控那条路在 bindScrollKeys，这里管的是手机遥控。
        val body = decor.findViewWithTag<android.widget.ScrollView>(TAG_BODY)
        if(body != null && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)
            && (body.canScrollVertically(1) || body.canScrollVertically(-1))) {
            val page = (body.height * 2 / 3).coerceAtLeast(1)
            body.scrollBy(0, if(direction == View.FOCUS_DOWN) page else -page)
            return true
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

    /**
     * 正文滚动区。内容短时高度跟着内容走，和直接放 TextView 没区别；
     * 内容超过卡片上限时由 [styleWindow] 的 capPx 压高度，再靠方向键翻。
     */
    private fun scrollBody(activity: Activity, text: String): android.widget.ScrollView {
        val label = body(activity, text)
        return android.widget.ScrollView(activity).apply {
            // 滚动条常显：默认几秒就淡出，电视遥控器没有滚轮，看不见就等于没有
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            tag = TAG_BODY
            addView(label, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * 让遥控器的上下键直接翻正文，而不是先要求焦点落到滚动区上。
     *
     * 实体遥控器的按键走 Dialog.dispatchKeyEvent，压根不经过 [dispatchRemoteKey]，
     * 而正文区里没有可聚焦子项，ScrollView 自己永远等不到焦点——遥控按下去就是没反应。
     * 这里在弹窗层拦截：焦点停在「知道了」上也能滚，代价是这条弹窗里上下键不再用于移动焦点
     * （只有一个按钮，本来也没别的去处）。内容不超高时不拦截。
     */
    private fun bindScrollKeys(dialog: Dialog, scroll: android.widget.ScrollView) {
        if(!scroll.canScrollVertically(1) && !scroll.canScrollVertically(-1)) return
        dialog.setOnKeyListener { _, keyCode, event ->
            if(event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // 步长按键时再算：show 之后 scroll 才量出高度
            val page = (scroll.height * 2 / 3).coerceAtLeast(1)
            when(keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { scroll.scrollBy(0, -page); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.scrollBy(0, page); true }
                else -> false
            }
        }
    }

    /**
     * 胶囊按钮：默认 surface 底，焦点 accent 填充 + 深色文字。compact 用于按钮多的弹窗（单行放 6 个）。
     * [radiusDp] 可单独调小：compact 只有 34dp 高，24dp 圆角会被裁成半高，短按钮看起来就是圆形；
     * 快传弹窗把按钮拉宽后改用 10dp，做成圆角矩形而不是全胶囊。
     */
    fun capsuleButton(activity: Activity, text: String, compact: Boolean = false, radiusDp: Int = 24, onClick: () -> Unit): Button {
        val dp = { v: Int -> dp(activity, v) }
        val corner = dp(radiusDp).toFloat()
        return Button(activity).apply {
            this.text = text
            textSize = if(compact) 13f else 15f
            setTextColor(TvStyle.text)
            minHeight = dp(if(compact) 34 else 44)
            // 主题默认 minWidth≈88dp 会把「帮助/停用/取消」这类短字按钮撑大，
            // 六按钮单行直接溢出——compact 下清零，宽度交给内容
            if(compact) { minWidth = 0; minimumWidth = 0 }
            // 弹窗在触摸模式（IR 鼠标棒点击过屏幕）里创建时，requestFocus 只对
            // focusableInTouchMode 的 view 生效——不然 showExitConfirm 的默认焦点挂不上
            isFocusableInTouchMode = true
            setPadding(dp(if(compact) 12 else 20), 0, dp(if(compact) 12 else 20), 0)
            background = GradientDrawable().apply {
                setColor(TvStyle.surface)
                cornerRadius = corner
                setStroke(dp(1), TvStyle.outline)
            }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    setColor(if(focused) TvStyle.accent else TvStyle.surface)
                    cornerRadius = corner
                    setStroke(dp(1), if(focused) TvStyle.accent else TvStyle.outline)
                }
                setTextColor(if(focused) TvStyle.background else TvStyle.text)
                setTypeface(null, if(focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * 二维码弹窗的标准行：左侧二维码（默认 132dp 见方，比早先的 185dp 收小近三成，
     * 避免二维码压过文案成为视觉主体），右侧文案列**顶边与二维码顶边齐平**、高度随文案自适应。
     * 文案列用 wrap 而不是早先的固定 185dp 高：固定高 + 垂直居中时，多行文案会把二维码压得很小、
     * 少行文案又显得空；地址行改成整段换行后（见 [urlLabel]），列高天然接近 132dp，两端对齐更自然。
     * 文案列宽度约 200dp，说明建议 3-4 行、地址行交给 [urlLabel] 自动折行，避免整块高过二维码太多。
     */
    fun qrRow(activity: Activity, qr: View, vararg texts: TextView, qrDp: Int = 132): LinearLayout {
        val row = LinearLayout(activity).apply { gravity = Gravity.TOP }
        row.addView(qr, LinearLayout.LayoutParams(dp(activity, qrDp), dp(activity, qrDp)))
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(activity, 12), dp(activity, 2), 0, 0)
        }
        texts.forEachIndexed { i, t ->
            // 左右 padding 一并清零：label() 自带 8dp 左内边距、urlLabel 是 0，
            // 保留的话地址会比上面的说明整体左移 8dp（看上去「地址没和文案左对齐」）。
            // 内缩统一由文案列自己的左 padding 给，所有行从同一 x 起排。
            t.setPadding(0, 0, 0, 0)
            t.gravity = Gravity.START
            col.addView(t, LinearLayout.LayoutParams(-1, -2).apply { if(i > 0) topMargin = dp(activity, 6) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** 二维码旁的地址行：muted 小字，整段完整显示、按字符强制折行（不省略）。
     *  扫码失败时用户要靠这行手输地址，截断就失去意义；BREAK_STRATEGY_SIMPLE 让超长无空白串也能任意断行。
     *  常量取 Layout 里的同名值（=0）：LineBreaker 的版本要 API 29，这里全版本都要生效。 */
    @Suppress("WrongConstant")
    fun urlLabel(activity: Activity, url: String): TextView = TextView(activity).apply {
        text = url
        textSize = 10f
        setTextColor(TvStyle.muted)
        // setBreakStrategy / setHyphenationFrequency 要 API 23，低版本本身就没有这两项排版策略
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
    }

    /**
     * 生成二维码视图，白框（静区）固定只留码体宽度的 6%。
     *
     * ZXing 默认静区 4 个模块，再叠加「按整倍数缩放后居中补齐」的留白，360px 里每边约 48px 全是白，
     * 缩到 132dp 显示就是一圈很厚的白框，二维码本体反而被压小。这里先按默认参数编码，
     * 裁掉自带静区，再按固定 6% 补白重建像素图：白框厚度与二维码版本号、缩放取整都无关，
     * 1:1 像素映射不糊边，扫码区稳定占满 88%（6% 约 1.7 个模块，深色卡片底上仍能正常识别）。
     */
    fun qrView(activity: Activity, url: String, description: String = "二维码"): ImageView {
        val size = 420
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        var minX = size; var minY = size; var maxX = -1; var maxY = -1
        for(y in 0 until size) for(x in 0 until size) if(bits[x, y]) {
            if(x < minX) minX = x
            if(x > maxX) maxX = x
            if(y < minY) minY = y
            if(y > maxY) maxY = y
        }
        val codeW = maxX - minX + 1
        val codeH = maxY - minY + 1
        val side = maxOf(codeW, codeH)
        val pad = (side * 0.06f).toInt().coerceAtLeast(2)
        val out = side + pad * 2
        val pixels = IntArray(out * out) { i ->
            val dx = i % out - pad
            val dy = i / out - pad
            if(dx in 0 until codeW && dy in 0 until codeH && bits[minX + dx, minY + dy]) Color.BLACK else Color.WHITE
        }
        return ImageView(activity).apply {
            setImageBitmap(Bitmap.createBitmap(pixels, out, out, Bitmap.Config.ARGB_8888))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
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
        // 标题钉在卡片顶部，不进滚动区：特大/极大档选项行变高放不下时只滚选项，标题始终可见
        val heading = title(activity, title)
        val itemsHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
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
            itemsHost.addView(view, LinearLayout.LayoutParams(itemWidth.coerceIn(dp(activity, 120), dp(activity, 480)), -2).apply { bottomMargin = dp(activity, 8) })
        }
        val scroll = android.widget.ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(itemsHost, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 卡片高度上限：锚点弹窗压到「按钮顶部 → 屏幕底部」以内（小屏电视也放得下），普通弹窗约 60% 屏高。
        // 交给 styleWindow 在 show 前按真实宽度预先压缩——那里的高度才算得准（见 styleWindow 注释）
        val a = anchorView
        val cardCap = if(a != null && a.isAttachedToWindow) {
            val loc = IntArray(2)
            a.getLocationOnScreen(loc)
            activity.resources.displayMetrics.heightPixels - loc[1] - dp(activity, 24)
        } else (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(heading)
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
        styleWindow(activity, dialog, anchor = anchorView, capPx = cardCap) {
            items.getOrNull(if(focusIndex in labels.indices) focusIndex else 0)?.requestFocus()
        }
        return dialog
    }

    /**
     * 信息弹窗：标题 + 正文 + 单按钮。
     *
     * 正文包在滚动区里并给卡片设了高度上限：720p 这类小屏电视上，长说明（如遥控地址）
     * 原本会把按钮顶出屏幕，现在是正文自己滚，按钮始终可见。
     */
    fun info(activity: Activity, title: String, message: String): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        val scroll = scrollBody(activity, message)
        content.addView(scroll)
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val ok = capsuleButton(activity, "知道了") {}
        buttons.addView(ok, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        // 卡片总高封顶约六成屏高：内容多就让正文自己滚，标题和按钮永远在屏内
        val cap = (activity.resources.displayMetrics.heightPixels * 0.65f).toInt()
        styleWindow(activity, dialog, capPx = cap) {
            ok.requestFocus()
            bindScrollKeys(dialog, scroll)
        }
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
        val ok = capsuleButton(activity, "确认") { action() }.apply { tag = TAG_CONFIRM }
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
     *   注意：内容必须是 show 前就绪的（异步填充的列表弹窗请传 widthDp 固定宽度）；
     *   **内容里不能有水平 weight 横排**（如「二维码 + weight 文字列」）——UNSPECIFIED
     *   测量把 weight 子项量成 0 宽，文字被裁没、还按 0 宽逐字换行撑出虚高，这类弹窗必须传 widthDp
     *   （窗口宽度是 EXACTLY 时 weight 才正常分配剩余空间）。
     * - 传 widthDp：固定宽度，给表单、网格这类内容宽度和位置都要求稳定的弹窗。
     * - 传 capPx：卡片高度上限（内容过长时压第一个 ScrollView 的高度）。压缩在 show 前完成，
     *   首帧就是最终高度，不会先撑满再收缩。
     * - gravity/marginDp 供播放中的小型选择器用：居中会挡住画面，靠左 + 收窄后
     *   既不遮中间内容，也离触发它的底部按钮更近。
     * - anchor：悬浮定位到锚点按钮左侧（题材这类筛选器用，像从按钮边展开的下拉）；
     *   顶部与按钮顶部对齐，估高放不下时整体上移夹回屏幕内。
     *
     * 所有窗口参数（含 x/y）都必须在 show() 之前写进 attributes：Dialog 的 OnShowListener 是
     * show() 内部 Handler post 出来的，回调落地时窗口已经 addView，很可能已经过了一帧，
     * 在那里改位置就会看到「先出现在默认位置（居中/左上）再飞过去」。
     */
    fun styleWindow(activity: Activity, dialog: AlertDialog, widthDp: Int? = null, maxWidthDp: Int = 520,
                    gravity: Int = Gravity.CENTER, marginDp: Int = 0, anchor: View? = null,
                    capPx: Int? = null, onShown: (() -> Unit)? = null) {
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
        // 宽度定了再按 EXACTLY 宽量一次高：宽度不对，换行数就不对，估出来的高度会带偏 anchor 的 y
        var cardH = 0
        if(card != null) {
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            // 内容超过 capPx：show 前就把滚动区压到位，否则首帧超高、次帧收缩，位置也跟着跳
            if(capPx != null && card.measuredHeight > capPx) {
                shrinkScroll(card, card.measuredHeight - capPx)
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            }
            cardH = card.measuredHeight
        }
        val pad = dp(activity, 12)
        val anchorLoc = anchor?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let { a ->
            IntArray(2).also { a.getLocationOnScreen(it) }
        }
        val wantGravity = if(anchor != null) Gravity.TOP or Gravity.START else gravity
        // 右缘贴按钮左侧、顶部与按钮顶部对齐；估高超出屏幕底部时整体上移夹回
        fun place(loc: IntArray): Pair<Int, Int> {
            val estH = (cardH.takeIf { it > 0 } ?: (screenH / 3)) + dp(activity, 6)
            return (loc[0] - width - pad).coerceAtLeast(pad) to
                loc[1].coerceAtMost((screenH - estH - pad).coerceAtLeast(pad)).coerceAtLeast(pad)
        }
        dialog.window?.let { win ->
            val xy = anchorLoc?.let { place(it) } ?: (dp(activity, marginDp) to 0)
            // 非浮窗（windowIsFloating=false，见 styles.xml）不再自带变暗遮罩，显式补上，
            // 保持与浮窗弹窗一致的观感
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.6f)
            win.setBackgroundDrawableResource(android.R.color.transparent)
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.attributes = win.attributes.apply {
                this.width = width
                this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                this.gravity = wantGravity
                x = xy.first
                y = xy.second
            }
            // 锚点弹窗关掉窗口动画：Material 弹窗的进入动画带缩放，位置明明对也像「从别处飞过来」
            if(anchor != null) win.setWindowAnimations(0)
        }
        dialog.setOnShowListener {
            // 兜底：show 前锚点还没量出宽度（刚 attach 的极端情况）时补一次定位
            if(anchorLoc == null) anchor?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let { a ->
                val loc = IntArray(2).also { a.getLocationOnScreen(it) }
                val xy = place(loc)
                dialog.window?.let { win ->
                    win.attributes = win.attributes.apply { x = xy.first; y = xy.second }
                }
            }
            track(dialog)
            onShown?.invoke()
        }
    }

    /** 把第一个 ScrollView 的高度压掉 [delta]（内容超屏时用，show 前调用才能首帧到位）。 */
    private fun shrinkScroll(view: View, delta: Int): Boolean {
        if(view is android.widget.ScrollView && view.measuredHeight > 0) {
            val p = view.layoutParams
            p.height = (view.measuredHeight - delta).coerceAtLeast(view.measuredHeight / 4)
            view.layoutParams = p
            return true
        }
        if(view is ViewGroup) for(i in 0 until view.childCount) {
            if(shrinkScroll(view.getChildAt(i), delta)) return true
        }
        return false
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
