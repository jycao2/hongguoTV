// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
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

    /** 普通列表适配器：文字颜色跟随主题，深浅卡片底上均可读（系统布局在暖玉白主题下不可读）。 */
    fun listAdapter(activity: Activity, labels: List<String>): android.widget.ArrayAdapter<String> {
        return object : android.widget.ArrayAdapter<String>(activity, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(activity).apply {
                    textSize = 16f
                    setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                }
                view.text = getItem(position)
                view.setTextColor(TvStyle.text)
                return view
            }
        }
    }

    /** 勾选列表适配器：单选/多选弹窗通用，文字与勾选标记颜色跟随主题。 */
    fun choiceAdapter(activity: Activity, labels: List<String>, multiple: Boolean = false): android.widget.ArrayAdapter<String> {
        val attr = if(multiple) android.R.attr.listChoiceIndicatorMultiple else android.R.attr.listChoiceIndicatorSingle
        val styled = activity.obtainStyledAttributes(intArrayOf(attr))
        val checkMark = styled.getDrawable(0)
        styled.recycle()
        return object : android.widget.ArrayAdapter<String>(activity, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? android.widget.CheckedTextView) ?: android.widget.CheckedTextView(activity).apply {
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                    checkMarkDrawable = checkMark
                    checkMarkTintList = android.content.res.ColorStateList.valueOf(TvStyle.accent)
                }
                view.text = getItem(position)
                view.setTextColor(TvStyle.text)
                return view
            }
        }
    }

    /** 列表选择弹窗：统一卡片 + 列表 + 底部按钮。 */
    fun choose(
        activity: Activity,
        title: String,
        labels: List<String>,
        onChoice: (Int) -> Unit,
        extra: String? = null,
        onExtra: () -> Unit = {}
    ): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        val list = android.widget.ListView(activity).apply {
            adapter = listAdapter(activity, labels)
        }
        capHeight(activity, list)
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        if(extra != null) buttons.addView(capsuleButton(activity, extra) { onExtra() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(activity, 12) })
        val cancel = capsuleButton(activity, "取消") {}
        buttons.addView(cancel, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog) { list.requestFocus() }
        cancel.setOnClickListener { dialog.dismiss() }
        list.setOnItemClickListener { _, _, index, _ -> dialog.dismiss(); onChoice(index) }
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

    /** 通用弹窗窗口样式：透明背景、居中、限制最大宽度；onShown 合并进同一个 OnShowListener（AlertDialog 仅支持一个）。 */
    fun styleWindow(activity: Activity, dialog: AlertDialog, widthFraction: Float = 0.55f, maxWidthDp: Int = 620, onShown: (() -> Unit)? = null) {
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val width = (activity.resources.displayMetrics.widthPixels * widthFraction).toInt().coerceAtMost(dp(activity, maxWidthDp))
                setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
            onShown?.invoke()
        }
    }
}
