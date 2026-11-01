// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.ReceivedFile
import com.hongguotv.core.TransferKind
import com.hongguotv.core.TransferRules
import java.util.Calendar
import java.util.Locale

/** 快传文件列表：短按确认执行主操作，右移到「删除」可单条删除。 */
class QuickFilesAdapter(
    private val activity: Activity,
    private val onOpen: (ReceivedFile) -> Unit,
    private val onDelete: (ReceivedFile) -> Unit,
) : RecyclerView.Adapter<QuickFilesAdapter.Holder>() {
    private val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
    private val items = mutableListOf<ReceivedFile>()

    fun submit(next: List<ReceivedFile>) {
        items.clear(); items.addAll(next); notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val tag = TextView(activity).apply {
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(TvStyle.muted)
            background = rounded(TvStyle.surface, 0, 6)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(22))
        }
        val name = TextView(activity).apply {
            textSize = 14f; setTextColor(TvStyle.text); maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        val meta = TextView(activity).apply {
            textSize = 12f; setTextColor(TvStyle.muted); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(10) }
        }
        val delete = TextView(activity).apply {
            id = View.generateViewId()
            text = "删除"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(TvStyle.muted)
            isFocusable = true; isClickable = true
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.TRANSPARENT, 0, 999)
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if(focused) TvStyle.raised else Color.TRANSPARENT, if(focused) TvStyle.accent else 0, 999)
                (view as TextView).setTextColor(if(focused) TvStyle.accent else TvStyle.muted)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(10) }
        }
        val row = LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(12), 0, dp(10), 0)
            background = rounded(TvStyle.background, 0, 12)
            addView(tag); addView(name); addView(meta); addView(delete)
        }
        row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
            .apply { bottomMargin = dp(4) }
        row.setOnFocusChangeListener { _, focused ->
            row.background = rounded(if(focused) TvStyle.raised else TvStyle.background, if(focused) TvStyle.accent else 0, 12)
            name.isSelected = focused
        }
        return Holder(row, tag, name, meta, delete)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, "${TransferRules.humanBytes(item.file.length())} · ${timeLabel(item.receivedAt)}")
        holder.row.setOnClickListener { onOpen(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    private fun rounded(color: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if(stroke != 0) setStroke(dp(1), stroke)
    }

    private fun timeLabel(millis: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val clock = String.format(Locale.ROOT, "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
        val now = Calendar.getInstance()
        if(then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return "今天 $clock"
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if(then.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) return "昨天 $clock"
        return String.format(Locale.ROOT, "%02d-%02d %s", then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH), clock)
    }

    class Holder(
        val row: LinearLayout,
        private val tag: TextView,
        private val name: TextView,
        private val meta: TextView,
        val delete: TextView,
    ) : RecyclerView.ViewHolder(row) {
        fun bind(item: ReceivedFile, metaText: String) {
            tag.text = when(item.kind) {
                TransferKind.APK -> "APK"
                TransferKind.VIDEO -> "视频"
                TransferKind.IMAGE -> "图片"
                TransferKind.OTHER -> "其它"
            }
            name.text = item.name
            name.isSelected = row.isFocused
            meta.text = metaText
            row.nextFocusRightId = delete.id
            delete.nextFocusLeftId = row.id
        }
    }
}
