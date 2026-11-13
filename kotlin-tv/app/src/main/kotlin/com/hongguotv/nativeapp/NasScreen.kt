// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.NasLibrary
import com.hongguotv.core.NasWebDav
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm

/** 本地库（NAS/WebDAV）浏览与播放。空态 → 添加连接 → 剧集列表 → 详情/起播。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NasScreen(private val activity: Activity, private val tvTools: TvTools) {
    private val store = NasStore(activity)
    private val main = Handler(Looper.getMainLooper())

    var topFocusId = View.NO_ID; private set
    private var currentContainer: LinearLayout? = null

    // ---------------- 入口 ----------------

    fun show(container: LinearLayout) {
        currentContainer = container
        container.removeAllViews()
        val hasConn = store.connections().isNotEmpty()
        if (!hasConn) {
            showEmpty(container)
            return
        }
        val conn = store.connections().firstOrNull { it.id == store.currentId }
            ?: store.connections().first()
        store.currentId = conn.id
        showBrowser(container, conn)
    }

    // ---------------- 空态 ----------------

    private fun showEmpty(container: LinearLayout) {
        container.removeAllViews()
        val body = bodyColumn(container)
        body.addView(spacer())
        val center = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = text("还没有连接本地库", 22f, TvStyle.text, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(12))
        }
        center.addView(title, LinearLayout.LayoutParams(-1, -2))
        val desc = label("接入同一局域网下的 NAS 即可直接播放影片。\n连接信息只保存在本机，卸载重装后需重新填写。", 13f, TvStyle.muted)
            .apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(28)); maxWidth = dp(560) }
        center.addView(desc)
        val addBtn = button("添加连接", true) { showAddDialog(container) }
        addBtn.minHeight = dp(42); addBtn.setPadding(dp(28), dp(8), dp(28), dp(8))
        center.addView(addBtn)
        body.addView(center, LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER })
        body.addView(spacer())
        topFocusId = addBtn.id
        main.post { addBtn.requestFocus() }
    }

    private fun spacer(): LinearLayout = LinearLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
    }

    // ---------------- 添加连接对话框 ----------------

    private fun showAddDialog(parent: LinearLayout, existing: NasConnection? = null) {
        val editing = existing != null
        // 内容撑满卡片：卡片内边距 28dp 就是全部边距，输入框/按钮与卡片边缘保持一致
        val content = bodyColumn(null)
        content.addView(label(if (editing) "编辑连接" else "添加连接", 18f, TvStyle.text, true)
            .apply { setPadding(0, 0, 0, dp(8)) })
        content.addView(label("填写 NAS 的 WebDAV 地址与账号，保存后自动扫描目录", 12f, TvStyle.muted)
            .apply { setPadding(0, 0, 0, dp(16)) })

        // 「可留空」说明放进输入框的 hint：占着列标签既宽又重复
        val nameInput = field("名称", existing?.name ?: "", "可留空 · 例如：家里的 NAS")
        val urlInput = field("地址", existing?.url ?: "", "http://192.168.1.20:5005")
        val pathInput = field("目录", existing?.mountPath ?: "", "可留空 · 例如：/video")
        val userInput = field("账号", existing?.user ?: "", "可留空 · WebDAV 账号")
        val passInput = field("密码", existing?.pass ?: "", "可留空 · WebDAV 密码").apply {
            (this.valueView as EditText).setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance())
        }

        val protoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        protoRow.addView(chip("WebDAV", true) {}.apply { isEnabled = false })
        protoRow.addView(chip("SMB · 敬请期待", false) {}.apply { isEnabled = false; alpha = 0.5f })
        content.addView(label("协议 · 目前仅支持 WebDAV", 12f, TvStyle.muted).apply { setPadding(0, 0, 0, dp(8)) })
        content.addView(protoRow)

        content.addView(nameInput.root); content.addView(urlInput.root); content.addView(pathInput.root)
        content.addView(userInput.root); content.addView(passInput.root)
        content.addView(label("账号密码仅存本机，不随本地备份导出。建议填写后自行加密 NAS 访问。", 11f, TvStyle.muted)
            .apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.START })

        val dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        // 窗口 560dp：卡片内宽 504dp，输入框约 380dp（原 438dp），比旧版收窄一截
        TvDialog.styleWindow(activity, dialog, widthDp = 560)

        // 自定义底部按钮
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        // 手机扫码填写：地址/账号用遥控器逐个输入很费劲，扫码后在手机上一次填完回填到本表单
        var phoneBtn: TextView? = null
        phoneBtn = button("手机填写", false) {
            tvTools.phoneForm(phoneBtn, PhoneForm(
                heading = if (editing) "编辑本地库连接" else "添加本地库连接",
                description = "填写 NAS 的 WebDAV 地址与账号，提交后自动回填到电视表单，确认无误后再保存。",
                fields = listOf(
                    PhoneField("name", "名称（可留空）", "例如：家里的 NAS", 40, required = false),
                    PhoneField("url", "地址", "http://192.168.1.20:5005", 200),
                    PhoneField("path", "目录（可留空）", "例如：/video", 200, required = false),
                    PhoneField("user", "账号（可留空）", "WebDAV 账号", 80, required = false),
                    PhoneField("pass", "密码（可留空）", "WebDAV 密码", 80, required = false, secret = true),
                ),
                doneHint = "请回到电视确认并保存连接。",
                invalidMessage = "请填写 WebDAV 地址"
            )) { values ->
                (nameInput.valueView as EditText).setText(values["name"].orEmpty())
                (urlInput.valueView as EditText).setText(values["url"].orEmpty())
                (pathInput.valueView as EditText).setText(values["path"].orEmpty())
                (userInput.valueView as EditText).setText(values["user"].orEmpty())
                (passInput.valueView as EditText).setText(values["pass"].orEmpty())
            }
        }
        actionRow.addView(phoneBtn)
        actionRow.addView(button("测试连接", false) {
            if (urlInput.value.trim().isBlank()) {
                Toast.makeText(activity, "请填写 WebDAV 地址", Toast.LENGTH_SHORT).show()
                return@button
            }
            val d = buildConn(nameInput, urlInput, pathInput, userInput, passInput, editing, existing)
            // 自定义视图的 AlertDialog 上 setTitle 不可见，用 Toast 反馈
            Toast.makeText(activity, "正在测试连接…", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = try {
                    NasWebDav(d.url, d.user, d.pass).testConnection(); "连接成功"
                } catch (e: Exception) { "失败：${e.message}" }
                main.post { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
            }.start()
        })
        actionRow.addView(button("取消", false) { dialog.dismiss() })
        actionRow.addView(button(if (editing) "保存" else "保存并扫描", true) {
            if (urlInput.value.trim().isBlank()) {
                Toast.makeText(activity, "请填写 WebDAV 地址", Toast.LENGTH_SHORT).show()
                return@button
            }
            val conn = buildConn(nameInput, urlInput, pathInput, userInput, passInput, editing, existing)
            store.upsert(conn); store.currentId = conn.id; dialog.dismiss()
            main.post { showBrowser(parent, conn) }
        })

        // 按钮放进内容卡片：show 之前 window decor 里拿不到 content 容器，会静默丢失
        actionRow.setPadding(0, dp(10), 0, 0)
        content.addView(actionRow)
        // 标题只保留卡片内那颗：自定义视图弹窗上 setTitle 会显示在卡片外面，造成双标题
        dialog.show()
    }

    /** 管理连接：列出全部连接，可进入编辑或删除（删除有二次确认）。 */
    private var manageDialog: AlertDialog? = null
    private fun showManageDialog(parent: LinearLayout) {
        val content = column()
        content.addView(TvDialog.title(activity, "管理连接"))
        content.addView(label("选择连接进行编辑，或将其从本机删除。", 12f, TvStyle.muted)
            .apply { setPadding(0, 0, 0, dp(12)) })
        val conns = store.connections()
        val listHost = column()
        conns.forEach { conn ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = TvStyle.shape(activity, TvStyle.surface, TvStyle.outline, 12)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(conn.name, 13.5f, TvStyle.text, true))
            info.addView(label(conn.url, 11.5f, TvStyle.muted))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("编辑", false) {
                manageDialog?.dismiss(); showAddDialog(parent, conn)
            }.apply { textSize = 12f; minHeight = dp(32); setPadding(dp(12), dp(5), dp(12), dp(5)) })
            val deleteBtn = button("删除", false) {}.apply {
                textSize = 12f; minHeight = dp(32); setPadding(dp(12), dp(5), dp(12), dp(5))
                (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8)
            }
            deleteBtn.setOnClickListener {
                // 二次确认走全局 TvDialog.confirm：和系统其他弹窗同一套风格
                tvTools.confirm("删除连接", "确定删除「${conn.name}」？本机保存的地址、账号与扫描缓存会一并清除。", deleteBtn) {
                    store.remove(conn.id)
                    if (store.currentId == conn.id) {
                        store.currentId = conns.firstOrNull { it.id != conn.id }?.id
                    }
                    manageDialog?.dismiss()
                    show(parent)
                }
            }
            row.addView(deleteBtn)
            listHost.addView(row)
        }
        if (conns.isEmpty()) listHost.addView(label("还没有连接。", 13f, TvStyle.muted))
        // 连接多时列表可滚，标题和说明保持可见
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(listHost, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        TvDialog.capHeight(activity, scroll)
        content.addView(scroll)
        content.addView(button("关闭", false) { manageDialog?.dismiss() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
        })
        val dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        manageDialog = dialog
        TvDialog.styleWindow(activity, dialog, widthDp = 560) {
            (listHost.getChildAt(0) as? ViewGroup)?.let { row ->
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i)
                    if (child.isFocusable) { child.requestFocus(); break }
                }
            }
        }
        dialog.show()
    }

    private fun buildConn(
        nameInput: FieldHost, urlInput: FieldHost, pathInput: FieldHost,
        userInput: FieldHost, passInput: FieldHost, editing: Boolean, existing: NasConnection?,
    ): NasConnection {
        val url = urlInput.value.trim().trimEnd('/')
        val mount = "/" + pathInput.value.trim().trim('/').ifBlank { "" }
        val fullUrl = if (mount.isNotBlank()) url + mount else url
        val id = existing?.id ?: "n" + url.hashCode().toUInt().toString(16)
        val now = System.currentTimeMillis()
        val dispName = nameInput.value.trim().ifBlank {
            val tail = url.substringAfterLast('/')
            if (tail.isBlank()) url.substringBefore(':').substringAfterLast('/')
            else tail
        }
        return NasConnection(id, dispName, fullUrl, userInput.value.trim(), passInput.value,
            mount, existing?.addedAt ?: now, now)
    }

    // ---------------- 浏览页 ----------------

    private fun showBrowser(container: LinearLayout, conn: NasConnection) {
        container.removeAllViews()
        val body = bodyColumn(container)
        body.addView(buildHeader(conn))
        val gridHost = FrameLayout(activity).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        body.addView(gridHost)
        topFocusId = View.NO_ID
        renderFromCacheOrScan(conn, gridHost)
    }

    private fun buildHeader(conn: NasConnection): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label("本地库", 22f, TvStyle.text, true))
        val chips = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        val status = store.lastStatus(conn.id)
        val cached = store.cache(conn.id)
        val okFlag = status?.ok == true || cached != null
        val dot = View(activity).apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 1000f; setColor(if (okFlag) TvStyle.gold else TvStyle.muted)
            }
            background = bg; layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) }
        }
        val nameRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(dot); nameRow.addView(label(conn.name, 12.5f, TvStyle.muted))
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = TvStyle.shape(activity, TvStyle.surface, TvStyle.outline, 14)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) }
            addView(nameRow)
        }
        chips.addView(card)
        val scanLabel = if (cached != null) "重新扫描" else "立即扫描"
        chips.addView(button(scanLabel, false) { triggerScan(conn) }.apply {
            textSize = 12.5f; minHeight = dp(34); setPadding(dp(14), dp(6), dp(14), dp(6))
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8)
        })
        chips.addView(button("管理连接", false) { showManageDialog(currentContainer!!) }.apply {
            textSize = 12.5f; minHeight = dp(34); setPadding(dp(14), dp(6), dp(14), dp(6))
        })
        row.addView(chips)
        return row
    }

    private fun renderFromCacheOrScan(conn: NasConnection, host: FrameLayout) {
        val cached = store.cache(conn.id)
        if (cached != null && cached.isNotEmpty()) {
            renderGrid(host, conn, cached)
            val age = System.currentTimeMillis() - store.lastScanAt(conn.id)
            if (age > NasStore.CACHE_TTL_MS) main.post { triggerScan(conn, silent = true) }
        } else {
            host.addView(label("正在连接 NAS 并扫描目录…", 14f, TvStyle.muted).apply {
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            })
            main.post {
                triggerScan(conn) {
                    main.post {
                        val entries = store.cache(conn.id)
                        // 扫描失败时 triggerScan 已弹原因，这里别用 !!（缓存为空会闪退），换成失败提示
                        if (entries != null) renderGrid(host, conn, entries)
                        else {
                            host.removeAllViews()
                            host.addView(label("扫描失败，请检查连接配置后点上方「重新扫描」。", 14f, TvStyle.muted).apply {
                                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                            })
                        }
                    }
                }
            }
        }
    }

    private fun triggerScan(conn: NasConnection, silent: Boolean = false, onDone: (() -> Unit)? = null) {
        Thread {
            val result = runCatching {
                val dav = NasWebDav(conn.url, conn.user, conn.pass)
                dav.testConnection()
                dav.listAll()
            }
            result.onSuccess { entries ->
                store.saveCache(conn.id, entries)
                store.markScanned(conn.id)
                val organized = NasLibrary.organize(entries)
                store.saveStatus(conn.id, NasStore.ScanStatus(true, organized.sumOf { it.episodeCount }, null))
            }.onFailure { e ->
                store.saveStatus(conn.id, NasStore.ScanStatus(false, 0, e.message))
                if (!silent) main.post { alert("扫描失败：${e.message}") }
            }
            main.post { onDone?.invoke() }
        }.start()
    }

    private fun renderGrid(host: FrameLayout, conn: NasConnection, entries: List<NasLibrary.NasEntry>) {
        host.removeAllViews()
        val series = NasLibrary.organize(entries)
        if (series.isEmpty()) {
            host.addView(label("未扫描到任何视频文件。请检查目录是否正确，或在 NAS 上开启 WebDAV。", 13f, TvStyle.muted).apply {
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            })
            return
        }
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val root = column().apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(label("${series.size} 个条目 · ${series.sumOf { it.episodeCount }} 集", 12f, TvStyle.muted)
            .apply { setPadding(dp(4), 0, dp(4), dp(12)) })

        val cols = 6
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; weightSum = cols.toFloat() }
        val colHosts = List(cols) { c ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = dp(14) }
            }
        }
        colHosts.forEach { grid.addView(it) }
        series.forEachIndexed { idx, item -> colHosts[idx % cols].addView(buildPoster(item, conn)) }
        root.addView(grid)
        scroll.addView(root)
        host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        topFocusId = colHosts.firstOrNull()?.getChildAt(0)?.id ?: View.NO_ID
        main.post { if (topFocusId != View.NO_ID) activity.findViewById<View>(topFocusId)?.requestFocus() }
    }

    private fun buildPoster(item: NasLibrary.NasSeries, conn: NasConnection): View {
        val card = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            background = TvStyle.shape(activity, TvStyle.surface, Color.TRANSPARENT, 12)
            setClickable(true); setFocusable(true)
            isFocusable = true
            setOnFocusChangeListener { v, focused ->
                v.background = TvStyle.shape(activity, if (focused) TvStyle.raised else TvStyle.surface,
                    if (focused) TvStyle.accent else Color.TRANSPARENT, 12)
            }
            setOnClickListener { v -> showDetail(item, conn) }
            setOnKeyListener { v, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
                    v.performClick(); true
                } else false
            }
        }
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(8)) }
        val poster = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(160))
            val colors = arrayOf(TvStyle.accent, TvStyle.gold, TvStyle.text, TvStyle.muted)
            val c = colors[((item.title.hashCode().let { if (it < 0) -it else it }) % colors.size)]
            background = TvStyle.shape(activity, c, Color.TRANSPARENT, 10)
        }
        val badge = TextView(activity).apply {
            text = when (item.kind) {
                NasLibrary.Kind.MOVIE -> "电影"
                NasLibrary.Kind.SERIES -> "${item.episodeCount} 集"
                NasLibrary.Kind.FILES -> "文件"
            }
            textSize = 11f; setTextColor(TvStyle.gold); setPadding(dp(8), dp(2), dp(8), dp(2))
            background = TvStyle.shape(activity, TvStyle.badgeSurface, TvStyle.outline, 6)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) }
        }
        poster.addView(badge); inner.addView(poster)
        val title = TextView(activity).apply {
            text = item.title; textSize = 13.5f; setTextColor(TvStyle.text)
            setPadding(0, dp(8), 0, 0)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false
        }
        // 标题/元信息必须进 inner 竖排容器：card 是 FrameLayout，直接 add 会全部叠在封面上
        inner.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        val firstFile = item.seasons.flatMap { it.episodes }.firstOrNull()?.file?.path ?: ""
        val meta = TextView(activity).apply {
            text = detectRes(firstFile); textSize = 11.5f; setTextColor(TvStyle.muted)
        }
        inner.addView(meta)
        card.addView(inner)
        return card
    }

    private fun detectRes(path: String): String {
        val p = path.lowercase()
        return when {
            Regex("""4320p|8k""").containsMatchIn(p) -> "4K"
            Regex("""2160p|4k|uhd""").containsMatchIn(p) -> "4K"
            Regex("""1080p|1080i|fhd""").containsMatchIn(p) -> "1080p"
            Regex("""720p""").containsMatchIn(p) -> "720p"
            else -> "本地"
        }
    }

    // ---------------- 详情 ----------------

    private fun showDetail(item: NasLibrary.NasSeries, conn: NasConnection) {
        val content = bodyColumn(null, widthLimit = dp(600))
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
        }
        val poster = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(108), dp(154))
            val colors = arrayOf(TvStyle.accent, TvStyle.gold, TvStyle.text, TvStyle.muted)
            val c = colors[((item.title.hashCode().let { if (it < 0) -it else it }) % colors.size)]
            background = TvStyle.shape(activity, c, Color.TRANSPARENT, 10)
        }
        poster.addView(text(item.title.take(1), 30f, Color.WHITE, true).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        })
        poster.addView(TextView(activity).apply {
            text = if (item.kind == NasLibrary.Kind.MOVIE) "电影" else "${item.episodeCount} 集"
            textSize = 10f; setTextColor(TvStyle.gold); setPadding(dp(6), dp(2), dp(6), dp(2))
            background = TvStyle.shape(activity, TvStyle.badgeSurface, TvStyle.outline, 6)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) }
        })
        val info = column()
        info.addView(label(item.title, 22f, TvStyle.text, true).apply { setPadding(dp(14), 0, 0, dp(10)) })
        val chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        chipRow.addView(chip("本地库 · ${conn.name}", false))
        chipRow.addView(chip(if (item.kind == NasLibrary.Kind.MOVIE) "电影" else "${item.episodeCount} 集", false))
        info.addView(chipRow)
        head.addView(poster); head.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        content.addView(head)
        content.addView(label("目录：${item.path}", 11.5f, TvStyle.muted).apply { setPadding(0, dp(10), 0, dp(18)) })

        // 动作按钮
        val acts = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        acts.addView(button("播放", true) {
            val ep = item.seasons.firstOrNull()?.episodes?.firstOrNull() ?: return@button
            playEpisode(item, ep, conn)
        })
        acts.addView(button("文件列表", false) {
            val text = item.seasons.flatMap { it.episodes }.joinToString("\n") { it.file.path }
            alert("文件列表（${item.seasons.sumOf { it.episodes.size }} 个）\n$text")
        })
        content.addView(acts.apply { setPadding(0, 0, 0, dp(12)) })

        for (s in item.seasons) {
            if (s.season == NasLibrary.SEASON_DATE) continue
            content.addView(label(s.label, 14f, TvStyle.text, true).apply { setPadding(0, dp(8), 0, dp(6)) })
            content.addView(buildEpisodeGrid(s, item, conn))
            if (s.missing.isNotEmpty()) {
                content.addView(label("缺失集：${s.missing.joinToString(", ")}", 11f, TvStyle.muted).apply { setPadding(0, dp(2), 0, dp(0)) })
            }
        }
        val dated = item.seasons.firstOrNull { it.season == NasLibrary.SEASON_DATE }
        if (dated != null) {
            content.addView(label("按日期", 14f, TvStyle.text, true).apply { setPadding(0, dp(12), 0, dp(6)) })
            content.addView(buildEpisodeGrid(dated, item, conn))
        }

        val dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        TvDialog.styleWindow(activity, dialog, widthDp = 660)
        dialog.show()
    }

    private fun buildEpisodeGrid(season: NasLibrary.NasSeason, item: NasLibrary.NasSeries, conn: NasConnection): View {
        val cols = 8
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; weightSum = cols.toFloat() }
        val colHosts = List(cols) { c ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = dp(8) }
            }
        }
        colHosts.forEach { grid.addView(it) }
        season.episodes.forEachIndexed { idx, ep -> colHosts[idx % cols].addView(buildEpisodeChip(ep, item, conn)) }
        return grid
    }

    private fun buildEpisodeChip(ep: NasLibrary.NasEpisode, item: NasLibrary.NasSeries, conn: NasConnection): View {
        val label = if (ep.episode != null) "第 ${ep.episode} 集" else ep.display.take(16)
        val tv = TextView(activity).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(8) }
            setTextColor(TvStyle.muted); setPadding(0, dp(6), 0, dp(6))
            background = TvStyle.shape(activity, TvStyle.surface, TvStyle.outline, 10)
            setClickable(true); setFocusable(true); isFocusable = true
            setOnFocusChangeListener { v, focused ->
                val t = v as TextView
                t.setTextColor(if (focused) TvStyle.text else TvStyle.muted)
                t.background = TvStyle.shape(activity,
                    if (focused) TvStyle.selectedSurface else TvStyle.surface,
                    if (focused) TvStyle.accent else TvStyle.outline, 10)
                if (focused) t.setTypeface(null, Typeface.BOLD) else t.setTypeface(null, Typeface.NORMAL)
            }
            setOnClickListener { playEpisode(item, ep, conn) }
        }
        return tv
    }

    // ---------------- 全屏播放 ----------------

    private fun playEpisode(item: NasLibrary.NasSeries, start: NasLibrary.NasEpisode, conn: NasConnection) {
        val playlist = item.seasons.flatMap { it.episodes }
        if (playlist.isEmpty()) return
        var index = playlist.indexOfFirst { it.file.path == start.file.path }.let { if (it < 0) 0 else it }

        val dav = NasWebDav(conn.url, conn.user, conn.pass)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to dav.authHeader()))
        val player = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(DefaultMediaSourceFactory(activity).setDataSourceFactory(dataSourceFactory))
            .build()

        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(activity).apply {
            useController = false; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        root.addView(playerView)

        val titleView = text("", 16f, Color.WHITE, true).apply {
            setPadding(dp(28), dp(16), dp(28), dp(16)); setBackgroundColor(0x66000000)
        }
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2))

        val ffView = text("3x ⏩", 42f, Color.WHITE, true).apply { visibility = View.GONE }
        root.addView(ffView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // 底部控制条：进度 + 按钮，4 秒无操作自动隐藏；整体靠左、尺寸收敛，避免顶出屏幕下沿
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        val curView = text("00:00", 12f, Color.WHITE)
        val durView = text("--:--", 12f, 0xFFB0B0B0.toInt())
        val seek = SeekBar(activity).apply {
            max = 1000; isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(12) }
        }
        bar.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(curView); addView(seek); addView(durView)
        })
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START; setPadding(0, dp(6), 0, 0)
        }
        bar.addView(btnRow)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        var loop = false; var ffActive = false; var controlsVisible = true
        var rightHold = false; var leftHold = false; var centerWake = false
        lateinit var playBtn: TextView
        lateinit var loopBtn: TextView
        // 隐藏用 alpha=0 而不是 GONE：GONE 会把焦点从按钮收走，重现时系统把焦点分给行首
        // "上一集"，用户按 OK 想暂停却切成上一集；alpha=0 时按钮仍在树中且保持焦点
        fun hideControls() {
            controlsVisible = false
            bar.animate().alpha(0f).setDuration(180).start()
            titleView.animate().alpha(0f).setDuration(180).start()
        }
        val hideRunnable = Runnable { hideControls() }
        fun showControls() {
            controlsVisible = true
            bar.animate().cancel(); titleView.animate().cancel()
            bar.alpha = 1f; titleView.alpha = 1f
            main.removeCallbacks(hideRunnable); main.postDelayed(hideRunnable, 4000)
            val focused = root.findFocus()
            val onButtons = (0 until btnRow.childCount).any { btnRow.getChildAt(it) === focused }
            if (!onButtons) main.postDelayed({ if (controlsVisible) playBtn.requestFocus() }, 120)
        }
        fun fmt(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
            else "%02d:%02d".format(s / 60, s % 60)
        }
        val progressTicker = object : Runnable {
            override fun run() {
                val d = player.duration
                if (d > 0) {
                    seek.progress = ((player.currentPosition * 1000) / d).toInt()
                    curView.text = fmt(player.currentPosition); durView.text = fmt(d)
                }
                main.postDelayed(this, 500)
            }
        }
        // 长按 3x 松手后要回到这里设定的倍速，而不是写死 1x
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 3f, 0.75f)
        val speedLabels = arrayOf("1.0x", "1.25x", "1.5x", "2.0x", "3.0x", "0.75x")
        var speedIdx = 0
        fun loadEpisode(i: Int) {
            index = i.coerceIn(0, playlist.lastIndex)
            val ep = playlist[index]
            titleView.text = "${item.title} · ${ep.display}"
            player.setMediaItem(MediaItem.fromUri(Uri.parse(dav.fileUrl(ep.file.path))))
            player.prepare(); player.playWhenReady = true
            // 切集后显式重设倍速：新一集视频编码变化时 ExoPlayer 重建解码器，
            // 部分设备不会继承 PlaybackParameters，导致画面回到 1x、音频维持原倍速。
            player.setPlaybackSpeed(speeds[speedIdx])
        }
        // 播放器专用小按钮：比全局 button() 紧凑，整行靠左排开
        fun pbtn(s: String, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = s; textSize = 12.5f; gravity = Gravity.CENTER; includeFontPadding = false
                setTextColor(TvStyle.text)
                background = TvStyle.shape(activity, TvStyle.surface, TvStyle.outline, 10)
                setPadding(dp(14), dp(6), dp(14), dp(6)); minHeight = dp(32)
                setClickable(true); isFocusable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = TvStyle.shape(activity, if (focused) TvStyle.raised else TvStyle.surface,
                        if (focused) TvStyle.accent else TvStyle.outline, 10)
                }
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            }
        playBtn = pbtn("暂停") { if (player.isPlaying) player.pause() else player.play() }
        btnRow.addView(playBtn)
        btnRow.addView(pbtn("上一集") { if (index > 0) loadEpisode(index - 1) })
        btnRow.addView(pbtn("下一集") { if (index < playlist.lastIndex) loadEpisode(index + 1) })
        btnRow.addView(pbtn("-15s") { player.seekTo((player.currentPosition - 15000).coerceAtLeast(0)) })
        btnRow.addView(pbtn("+15s") {
            player.seekTo((player.currentPosition + 15000).coerceAtMost(player.duration.coerceAtLeast(0)))
        })
        lateinit var speedBtn: TextView
        speedBtn = pbtn("倍速 1.0x") {
            speedIdx = (speedIdx + 1) % speeds.size
            player.setPlaybackSpeed(speeds[speedIdx])
            speedBtn.text = "倍速 " + speedLabels[speedIdx]
        }
        btnRow.addView(speedBtn)
        loopBtn = pbtn("循环：关") {
            loop = !loop
            player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            loopBtn.text = if (loop) "循环：开" else "循环：关"
        }
        btnRow.addView(loopBtn)
        // 适应=留黑边保比例；填满=裁掉多余部分铺满屏幕（对付宽银幕上下黑边）
        var zoom = false
        lateinit var zoomBtn: TextView
        zoomBtn = pbtn("比例：适应") {
            zoom = !zoom
            playerView.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
            zoomBtn.text = if (zoom) "比例：填满" else "比例：适应"
        }
        btnRow.addView(zoomBtn)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { playBtn.text = if (playing) "暂停" else "播放" }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !loop) {
                    if (index < playlist.lastIndex) loadEpisode(index + 1) else dialog.dismiss()
                }
            }
        })

        dialog.setOnDismissListener {
            main.removeCallbacks(hideRunnable); main.removeCallbacks(progressTicker)
            player.release(); playerView.player = null
        }
        dialog.setOnKeyListener { _, key, event ->
            // 显示期间任何按键都重置 4 秒自动隐藏计时，避免正在按钮行导航时控制条消失
            if (event.action == KeyEvent.ACTION_DOWN && controlsVisible && key != KeyEvent.KEYCODE_BACK) {
                main.removeCallbacks(hideRunnable); main.postDelayed(hideRunnable, 4000)
            }
            when (key) {
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_DOWN) dialog.dismiss(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 下键专职管工具栏：隐藏时唤出，显示时收回；不参与快进/导航
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (controlsVisible) { main.removeCallbacks(hideRunnable); hideControls() } else showControls()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 控制条显示时单击右键留给按钮行做焦点导航；隐藏时单击直接 +15s（不弹工具栏）；
                    // 长按始终 3x 倍速
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> when {
                            event.repeatCount > 0 -> {
                                if (rightHold && !ffActive) {
                                    ffActive = true; player.setPlaybackSpeed(3f); ffView.visibility = View.VISIBLE
                                }
                                true
                            }
                            controlsVisible -> false
                            else -> { rightHold = true; true }
                        }
                        KeyEvent.ACTION_UP -> {
                            if (ffActive) { ffActive = false; player.setPlaybackSpeed(speeds[speedIdx]); ffView.visibility = View.GONE }
                            else if (rightHold) player.seekTo((player.currentPosition + 15000).coerceAtMost(player.duration.coerceAtLeast(0)))
                            rightHold = false; true
                        }
                        else -> true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // 与右键同理：显示时导航，隐藏时单击 -15s
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> when {
                            event.repeatCount > 0 -> true
                            controlsVisible -> false
                            else -> { leftHold = true; true }
                        }
                        KeyEvent.ACTION_UP -> {
                            if (leftHold) player.seekTo((player.currentPosition - 15000).coerceAtLeast(0))
                            leftHold = false; true
                        }
                        else -> true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event.action == KeyEvent.ACTION_UP) { if (player.isPlaying) player.pause() else player.play() }
                    showControls(); true
                }
                else -> {
                    // 控制条隐藏时的第一次按键只唤醒界面（尤其 CENTER，不能顺带点了焦点按钮）
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (controlsVisible) false else { centerWake = true; showControls(); true }
                        KeyEvent.ACTION_UP -> if (centerWake) { centerWake = false; true } else false
                        else -> false
                    }
                }
            }
        }

        playerView.player = player
        loadEpisode(index)
        dialog.show()
        // 全屏播放器弹窗不走 styleWindow（自己管窗口样式），但要登记进遥控范围，
        // 否则手机遥控的确认/方向键对它全部落空
        TvDialog.register(dialog)
        dialog.window?.let { w ->
            w.setDimAmount(0f); w.decorView.setPadding(0, 0, 0, 0)
            // 清掉 AlertDialog 默认圆角阴影背景：它会露在播放器四周、透出底下详情页的
            // 模糊封面，看起来就像画面边缘叠了两层"重影"
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        showControls()
        main.post(progressTicker)
        // 初始焦点落在暂停键：CENTER 直接暂停，右键从它开始导航到下一集/循环；
        // 经 view.post 等 dialog 布局完成后再抢焦点，避免静默失败落到行首"上一集"
        playBtn.post { playBtn.requestFocus() }
    }

    // ---------------- 工具 ----------------

    private fun bodyColumn(container: LinearLayout?, widthLimit: Int? = null): LinearLayout {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.layoutParams = if (container == null) LinearLayout.LayoutParams(widthLimit ?: -1, LinearLayout.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = dp(40); rightMargin = dp(40); topMargin = dp(16)
            }
        container?.addView(col)
        return col
    }

    private fun column(): LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun text(s: String, size: Float, color: Int = TvStyle.text, bold: Boolean = false): TextView =
        TextView(activity).apply {
            text = s; textSize = size; setTextColor(color); includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun label(s: String, size: Float = 14f, color: Int = TvStyle.text, bold: Boolean = false): TextView =
        text(s, size, color, bold)

    private fun button(s: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = s; textSize = 13.5f; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(if (primary) Color.WHITE else TvStyle.text)
            background = TvStyle.shape(activity, if (primary) TvStyle.accent else TvStyle.surface,
                if (primary) TvStyle.accent else TvStyle.outline, 10)
            setPadding(dp(18), dp(9), dp(18), dp(9)); minHeight = dp(40)
            setClickable(true); setFocusable(true); isFocusable = true
            setOnFocusChangeListener { v, focused ->
                if (primary) {
                    // primary 不变
                } else {
                    (v as View).background = TvStyle.shape(activity, if (focused) TvStyle.raised else TvStyle.surface,
                        if (focused) TvStyle.accent else TvStyle.outline, 10)
                }
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        }

    private fun chip(s: String, selected: Boolean, onClick: () -> Unit = {}): TextView =
        TextView(activity).apply {
            text = s; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(if (selected) TvStyle.text else TvStyle.muted)
            background = TvStyle.shape(activity, if (selected) TvStyle.selectedSurface else TvStyle.surface,
                if (selected) TvStyle.accent else TvStyle.outline, 999)
            setPadding(dp(14), dp(6), dp(14), dp(6)); minHeight = dp(34)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            setClickable(true); setFocusable(true); isFocusable = true
            setOnFocusChangeListener { v, focused ->
                val t = v as TextView
                t.background = TvStyle.shape(activity,
                    when { selected -> TvStyle.selectedSurface; focused -> TvStyle.raised; else -> TvStyle.surface },
                    when { selected -> TvStyle.accent; focused -> TvStyle.accent; else -> TvStyle.outline }, 999)
                t.setTextColor(if (selected || focused) TvStyle.text else TvStyle.muted)
            }
            setOnClickListener { onClick() }
        }

    private fun alert(msg: String) {
        // 不能用原生 AlertDialog：没登记进 TvDialog，手机遥控按不动，观感也和全局不一致
        TvDialog.info(activity, "提示", msg).show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private class FieldHost(val root: LinearLayout, val valueView: View) {
        val value get() = (valueView as? EditText)?.text?.toString().orEmpty()
    }

    private fun field(label: String, initial: String, hint: String = ""): FieldHost {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
        }
        val labelView = TextView(activity).apply {
            text = label; textSize = 13f; setTextColor(TvStyle.muted)
            layoutParams = LinearLayout.LayoutParams(dp(112), -2).apply { rightMargin = dp(10) }
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val edit = EditText(activity).apply {
            setText(initial); textSize = 14f; setTextColor(TvStyle.text)
            setHint(hint); setHintTextColor(TvStyle.muted)
            background = TvStyle.shape(activity, Color.TRANSPARENT, TvStyle.outline, 8)
            setPadding(dp(14), dp(9), dp(14), dp(9)); minHeight = dp(40)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            includeFontPadding = false; setSingleLine(true)
        }
        row.addView(labelView); row.addView(edit)
        return FieldHost(row, edit)
    }
}
