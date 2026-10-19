// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** 三方片源页：自成体系的「店中店」——浏览/详情/播放全部独立，与红果内容体系零交集。
 *  生命周期由 MainActivity 委托：show/onBack/onKey/pause/stop/release。 */
class ThirdSourceScreen(private val activity: Activity, private val rebuild: () -> Unit) {
    private enum class Mode { BROWSE, DETAIL, PLAY }
    private enum class Dot { RUNNING, OK, DEAD }

    private class ProbeState(val dot: Dot, val total: Int)

    private val main = Handler(Looper.getMainLooper())
    private val muted get() = TvStyle.muted
    private val store = ThirdStore(activity)
    private val client = ThirdCmsClient()
    private val artwork = ArtworkLoader(activity)
    private val tvTools = TvTools(activity)
    private val executor = Executors.newFixedThreadPool(2)
    private val probeExecutor = Executors.newFixedThreadPool(2)
    private var generation = 0

    private var mode = Mode.BROWSE
    var topFocusId = View.NO_ID
        private set

    // ---- 浏览态 ----
    private var sources = emptyList<ThirdSource>()
    private var currentId: String? = null
    private val probes = HashMap<String, ProbeState>()
    private val capsuleDots = HashMap<String, View>()
    private var categories: List<ThirdCategory> = emptyList()
    private var categoryId: String? = null
    private var page = 1
    private var hasMore = false
    private var videos: MutableList<ThirdVideo> = mutableListOf()
    private var loading = false
    private var loadingMore = false
    private var loadError: String? = null
    private var searchWord: String? = null
    private var searchDetails: List<ThirdDetail> = emptyList()
    private val searchIndex = HashMap<String, ThirdDetail>()
    private var probeSummary: TextView? = null
    private var gridStatus: TextView? = null
    private var gridAdapter: GridAdapter? = null
    private var gridRecycler: RecyclerView? = null
    private var sourceButtons: List<View> = emptyList()

    // ---- 详情态 ----
    private var detail: ThirdDetail? = null
    private var detailGroup = 0
    private var recommendations: List<ThirdVideo> = emptyList()
    private var recommendRow: LinearLayout? = null

    // ---- 播放态 ----
    private var player: ExoPlayer? = null
    private var playerHost: FrameLayout? = null
    private var playLoading: TextView? = null
    private var playErrorText: TextView? = null
    private var overlay: View? = null
    private var progressFill: View? = null
    private var playTime: TextView? = null
    private var overlayVisible = false
    private var playGroup = 0
    private var playEpisode = 0
    private var pendingStartMs = 0L
    private var pendingAutoplay = true
    private var hasPendingPlay = false
    private var lastPositionMs = -1L
    private val hideOverlayRun = Runnable { hideOverlay() }
    private val saveRun = Runnable { saveProgressNow(); scheduleSave() }
    private val uiTick = Runnable { updatePlayUi() }
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if(state == Player.STATE_READY) { playLoading?.visibility = View.GONE; updatePlayUi() }
            if(state == Player.STATE_ENDED) showOverlay()
        }
        override fun onPlayerError(error: PlaybackException) {
            playErrorText?.text = "该集播放失败，可换一集或切换线路"; showOverlay()
        }
    }

    // ================= 生命周期 =================

    fun show(container: LinearLayout) {
        generation++; artwork.cancelPage()
        sources = store.ensureSources()
        currentId = store.currentId()?.takeIf { id -> sources.any { it.id == id } }
            ?: sources.firstOrNull()?.also { store.setCurrent(it.id) }?.id
        scheduleProbes()
        when {
            mode == Mode.PLAY && detail != null && currentSource() != null -> {
                if(hasPendingPlay) { attachPlayer(pendingStartMs, pendingAutoplay); hasPendingPlay = false }
                else { attachPlayer(lastPositionMs.coerceAtLeast(0), false); lastPositionMs = -1L }
            }
            mode == Mode.DETAIL && detail != null -> renderDetail(container)
            else -> { mode = Mode.BROWSE; loading = false; loadingMore = false; renderBrowse(container) }
        }
    }

    /** 返回：播放→详情→浏览；浏览态交还 MainActivity（导航焦点/退出确认）。 */
    fun onBack(): Boolean = when(mode) {
        Mode.PLAY -> { saveProgressNow(); mode = Mode.DETAIL; releasePlayback(); rebuild(); true }
        Mode.DETAIL -> { mode = Mode.BROWSE; rebuild(); true }
        Mode.BROWSE -> false
    }

    /** 播放页按键接管；其他模式交还系统。 */
    fun onKey(event: KeyEvent): Boolean {
        if(mode != Mode.PLAY || player == null) return false
        val key = event.keyCode
        if(event.action != KeyEvent.ACTION_DOWN) return false
        val focusInOverlay = overlayButtons.any { it.hasFocus() }
        if(overlayVisible && focusInOverlay) return false   // 控制层内走原生焦点导航
        return when(key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if(overlayVisible) { togglePlay(); true } else { showOverlay(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if(overlayVisible) false else { seek(-10_000L); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if(overlayVisible) false else { seek(10_000L); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                if(!overlayVisible) { showOverlay(); true } else false
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { jumpEpisode(1); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { jumpEpisode(-1); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { saveProgressNow(); mode = Mode.DETAIL; releasePlayback(); rebuild(); true }
            else -> false
        }
    }

    fun pause() { player?.pause(); saveProgressNow() }

    fun stop() {
        saveProgressNow()
        if(player != null) {
            lastPositionMs = player?.currentPosition ?: -1L
            releasePlayback(); mode = Mode.PLAY
        }
    }

    fun release() {
        saveProgressNow()
        releasePlayback(); artwork.close(); tvTools.destroy(); client.shutdown(); executor.shutdownNow(); probeExecutor.shutdownNow()
    }

    // ================= 浏览态 =================

    private fun renderBrowse(container: LinearLayout) {
        detachPlayerViews()
        if(sources.isEmpty()) { renderEmptySources(container); return }
        val body = column().apply { setPadding(0, dp(4), 0, 0) }

        // 源胶囊条 + 管理
        val sourceRow = row()
        val capsuleScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false }
        val capsules = row(); capsuleDots.clear()
        val capsuleViews = sources.mapIndexed { index, source -> sourceCapsule(source, index) }
        capsuleViews.forEach { view -> capsules.addView(view, lp(-2, -2).apply { rightMargin = dp(8) }) }
        sourceButtons = capsuleViews
        capsuleScroll.addView(capsules)
        sourceRow.addView(capsuleScroll, lp(0, -2).apply { weight = 1f })
        val manage = addButton(sourceRow, "管理") { showSourceManager() }
        body.addView(sourceRow)

        probeSummary = text(probeSummaryText(), 12f, muted).apply { setPadding(0, dp(5), 0, dp(5)) }
        body.addView(probeSummary)

        // 搜索行
        val searchRow = row()
        val search = addButton(searchRow, "搜索片源") { showSearchDialog() }
        var clearRef: TextView? = null
        if(searchWord != null) clearRef = addButton(searchRow, "清除搜索") { clearSearch() }
        body.addView(searchRow)

        // 分类条（搜索态隐藏）
        var categoryButtons: List<TextView> = emptyList()
        if(searchWord != null) {
            body.addView(text("搜索「$searchWord」 · ${searchDetails.size} 条结果", 13f).apply { setPadding(0, dp(6), 0, dp(6)) })
        } else {
            val categoryScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false }
            val categoryRow = row().apply { setPadding(0, dp(2), 0, dp(2)) }
            val options = listOf(ThirdCategory("", "全部")) + categories
            categoryButtons = options.map { option ->
                val id = option.id.ifEmpty { null }
                addButton(categoryRow, option.name, categoryId == id) {
                    categoryId = id; videos.clear(); loadError = null; page = 1; hasMore = false; rebuild()
                }
            }
            categoryScroll.addView(categoryRow)
            body.addView(categoryScroll)
        }

        // 结果区
        if(searchWord == null && loadError != null && videos.isEmpty()) {
            body.addView(text("暂时无法加载「${currentSource()?.name.orEmpty()}」：$loadError", 15f, muted).apply { setPadding(0, dp(24), 0, dp(10)) })
            addButton(body, "重试") { loadError = null; rebuild() }.requestFocus()
            container.addView(body, lp(-1, 0).apply { weight = 1f })
            topFocusId = body.getChildAt(body.childCount - 1).id
            return
        }
        val display: List<ThirdVideo> = if(searchWord != null) searchDetails.map { it.video } else videos
        if(display.isEmpty()) {
            message(body, when {
                searchWord != null -> "未找到与「$searchWord」相关的内容，换个关键词试试"
                loading -> "正在加载「${currentSource()?.name.orEmpty()}」…"
                loadError == null -> "该分类暂无内容"
                else -> ""
            })
        }
        if(display.isNotEmpty()) mountGrid(body, display)
        gridStatus = text(gridStatusText(), 12f, muted).apply { setPadding(0, dp(4), 0, 0) }
        body.addView(gridStatus)

        container.addView(body, lp(-1, 0).apply { weight = 1f })
        topFocusId = capsuleViews.firstOrNull()?.id ?: search.id
        if(videos.isEmpty() && searchWord == null && loadError == null && !loading) requestCatalog(reset = true)
        // 焦点接线：胶囊/搜索/分类 ↓ → 网格；胶囊 → 管理 → 搜索 左右互达
        gridRecycler?.id?.let { gridId ->
            capsuleViews.forEach { it.nextFocusDownId = gridId }
            search.nextFocusDownId = gridId
            clearRef?.nextFocusDownId = gridId
            categoryButtons.forEach { it.nextFocusDownId = gridId }
        }
        search.nextFocusUpId = manage.id
        manage.nextFocusRightId = search.id
        search.nextFocusLeftId = manage.id
        if(clearRef != null) { manage.nextFocusRightId = clearRef.id; clearRef.nextFocusLeftId = manage.id }
    }

    private fun sourceCapsule(source: ThirdSource, index: Int): LinearLayout {
        val isCurrent = source.id == currentId
        val capsule = row().apply { setPadding(dp(12), dp(9), dp(12), dp(9)) }
        val dot = View(activity).apply {
            background = rounded(dotColor(source.id), radius = 3)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) }
        }
        capsuleDots[source.id] = dot
        capsule.addView(dot)
        capsule.addView(text(source.name, 14f, if(isCurrent) TvStyle.accent else TvStyle.text))
        focusStyle(capsule, selected = isCurrent)
        capsule.setOnClickListener { switchSource(source) }
        return capsule
    }

    private fun renderEmptySources(container: LinearLayout) {
        val body = column().apply { setPadding(0, dp(30), 0, 0) }
        message(body, "已删除全部片源，可添加或恢复内置源")
        val actions = row().apply { setPadding(0, dp(4), 0, 0) }
        val add = addButton(actions, "＋ 添加源") { showAddSourceDialog() }
        val restore = addButton(actions, "恢复内置源") { restoreBuiltinSources() }
        restore.nextFocusLeftId = add.id
        add.nextFocusRightId = restore.id
        body.addView(actions)
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        topFocusId = add.id; add.requestFocus()
    }

    private fun mountGrid(body: LinearLayout, display: List<ThirdVideo>) {
        val cols = browseCols()
        val adapter = GridAdapter(display, cols)
        gridAdapter = adapter
        val grid = GridLayoutManager(activity, cols)
        val recycler = RecyclerView(activity).apply {
            id = View.generateViewId(); setLayoutManager(grid); setAdapter(adapter); itemAnimator = null
            isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true
            setOnFocusChangeListener { _, focused -> if(focused) {
                val first = grid.findFirstVisibleItemPosition().coerceAtLeast(0)
                post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() }
            } }
        }
        gridRecycler = recycler
        body.addView(recycler, lp(-1, 0).apply { weight = 1f })
        sourceButtons.lastOrNull()?.nextFocusDownId = recycler.id
    }

    private inner class GridAdapter(private val items: List<ThirdVideo>, private val cols: Int) : RecyclerView.Adapter<GridAdapter.Holder>() {
        private val contentPx = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt()
        private val cellPx = (contentPx - dp(8) * (cols - 1)) / cols
        private val posterPx = (cellPx / TvStyle.POSTER_ASPECT).toInt()

        fun submit(appendFrom: Int) {
            if(appendFrom < 0) notifyDataSetChanged() else notifyItemRangeInserted(appendFrom, items.size - appendFrom)
        }

        inner class Holder(val card: LinearLayout, val image: ImageView, val name: TextView, val badge: TextView) : RecyclerView.ViewHolder(card)

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val card = column().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
            val imageFrame = FrameLayout(activity)
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TvStyle.badgeSurface) }
            imageFrame.addView(image, FrameLayout.LayoutParams(-1, posterPx))
            val badge = text("", 10f, Color.WHITE).apply { setPadding(dp(5), dp(2), dp(5), dp(2)); background = rounded(TvStyle.badgeSurface, radius = 6) }
            imageFrame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
            val name = text("", 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(5), dp(2), 0) }
            card.addView(imageFrame); card.addView(name)
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            return Holder(card, image, name, badge)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.badge.visibility = if(item.remarks.isBlank()) View.GONE else View.VISIBLE
            holder.badge.text = item.remarks
            artwork.load(holder.image, item.cover, ArtworkLoader.PRIORITY_VISIBLE)
            holder.card.id = View.generateViewId()
            focusStyle(holder.card)
            // 保留 focusStyle 的绘制监听，追加名称高亮
            val paint = holder.card.onFocusChangeListener
            holder.card.setOnFocusChangeListener { v, focused ->
                paint?.onFocusChange(v, focused)
                holder.name.setTextColor(if(focused) TvStyle.accent else TvStyle.text)
            }
            holder.card.setOnClickListener { onVideoClick(item) }
            if(position >= items.size - 12) maybeLoadMore()
        }
    }

    private fun onVideoClick(video: ThirdVideo) {
        val source = currentSource() ?: return
        if(searchWord != null) {
            searchIndex[video.id]?.let { openDetail(it); return }
        }
        val gen = generation
        executor.execute {
            val result = client.detail(source.api, video.id)
            main.post {
                if(gen != generation || result == null) {
                    if(gen == generation) Toast.makeText(activity, "获取详情失败，请重试", Toast.LENGTH_SHORT).show()
                    return@post
                }
                openDetail(result)
            }
        }
    }

    private fun maybeLoadMore() {
        if(loadingMore || !hasMore || searchWord != null || loadError != null || currentSource() == null) return
        requestCatalog(reset = false)
    }

    private fun requestCatalog(reset: Boolean) {
        val source = currentSource() ?: return
        if(loading || loadingMore) return
        val gen = generation
        val requestCategory = categoryId
        val requestPage = if(reset) 1 else page + 1
        if(reset) loading = true else loadingMore = true
        executor.execute {
            val result = client.list(source.api, requestCategory, requestPage)
            main.post {
                if(gen != generation) return@post
                if(reset) {
                    loading = false
                    if(result != null) {
                        categories = result.categories; videos = result.videos.toMutableList()
                        hasMore = result.hasMore && result.videos.isNotEmpty(); loadError = null; page = 1
                    } else { loadError = "连接超时或接口无响应"; videos = mutableListOf(); hasMore = false }
                    rebuild()
                } else {
                    loadingMore = false
                    if(result != null) {
                        val known = videos.mapTo(HashSet()) { it.id }
                        val fresh = result.videos.filter { it.id !in known }
                        val insertAt = videos.size
                        videos.addAll(fresh); hasMore = result.hasMore && result.videos.isNotEmpty(); page = requestPage
                        gridAdapter?.submit(insertAt)
                        gridStatus?.text = gridStatusText()
                    } else gridStatus?.text = "加载更多失败，继续下滑可重试"
                }
            }
        }
    }

    private fun gridStatusText(): String = when {
        searchWord != null -> ""
        loadingMore -> "正在加载更多…"
        hasMore -> "继续下滑加载更多"
        videos.isEmpty() -> ""
        else -> "已加载全部 ${videos.size} 条"
    }

    private fun clearSearch() { searchWord = null; searchDetails = emptyList(); searchIndex.clear(); rebuild() }

    // ---- 源切换与探活 ----

    private fun currentSource(): ThirdSource? = sources.firstOrNull { it.id == currentId }

    private fun switchSource(source: ThirdSource) {
        if(source.id == currentId) return
        currentId = source.id; store.setCurrent(source.id)
        categoryId = null; searchWord = null; searchDetails = emptyList(); searchIndex.clear()
        videos.clear(); loadError = null; page = 1; hasMore = false; categories = emptyList()
        scheduleProbes(); rebuild()
    }

    private fun scheduleProbes() {
        sources.forEach { source ->
            if(probes[source.id] == null) {
                probes[source.id] = ProbeState(Dot.RUNNING, 0)
                val gen = generation
                probeExecutor.execute {
                    val result = client.probe(source.api)
                    main.post {
                        if(gen != generation) return@post
                        probes[source.id] = if(result.ok) ProbeState(Dot.OK, result.total) else ProbeState(Dot.DEAD, 0)
                        capsuleDots[source.id]?.let { dot -> dot.background = rounded(dotColor(source.id), radius = 3) }
                        probeSummary?.text = probeSummaryText()
                    }
                }
            }
        }
    }

    private fun dotColor(id: String): Int = when(probes[id]?.dot) {
        Dot.OK -> Color.rgb(76, 175, 80)
        Dot.DEAD -> Color.rgb(120, 128, 140)
        else -> Color.rgb(255, 180, 80)   // 探活中（橙）
    }

    private fun probeSummaryText(): String {
        if(sources.isEmpty()) return ""
        val ready = sources.count { probes[it.id]?.dot == Dot.OK }
        val probing = sources.count { probes[it.id]?.dot == Dot.RUNNING }
        return buildString {
            append("探活 $ready/${sources.size} 就绪")
            if(probing > 0) append(" · 其余后台继续")
        }
    }

    // ================= 源管理 / 添加 / 导入 =================

    private fun showSourceManager() {
        val content = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        sources.forEachIndexed { index, source ->
            val isCurrent = source.id == currentId
            val line = row().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
            val nameColumn = column()
            nameColumn.addView(text("${source.name}${if(source.builtin) "  [内置]" else ""}", 15f, if(isCurrent) TvStyle.accent else TvStyle.text))
            nameColumn.addView(text(hostOf(source.api), 12f, muted))
            val count = probes[source.id]?.let { if(it.dot == Dot.OK) formatCount(it.total) else null }
            line.addView(nameColumn, lp(0, -2).apply { weight = 1f })
            line.addView(text(count ?: "", 12f, muted), lp(-2, -2).apply { rightMargin = dp(8) })
            if(isCurrent) line.addView(text("使用中", 12f, TvStyle.accent))
            focusStyle(line, selected = isCurrent)
            line.setOnClickListener { switchSource(source) }
            content.addView(line, lp(-1, -2).apply { bottomMargin = dp(6) })
        }

        val actions = row().apply { setPadding(0, dp(8), 0, 0) }
        val add = addButton(actions, "＋ 添加源") { manager?.dismiss(); showAddSourceDialog() }
        val import = addButton(actions, "＋ 导入配置") { manager?.dismiss(); showImportDialog() }
        var restoreRef: TextView? = null
        if(ThirdCms.restoreCandidates(sources).isNotEmpty()) {
            restoreRef = addButton(actions, "恢复内置源") { manager?.dismiss(); restoreBuiltinSources() }
        }
        content.addView(actions)
        content.addView(text("点击源条目切换使用 · 删除需二次确认 · 上限 ${ThirdCms.MAX_SOURCES} 个", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })

        val dialog = AlertDialog.Builder(activity).setTitle("源管理")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("删除当前源") { _,_ -> confirmDeleteCurrent() }
            .setPositiveButton("完成", null).create()
        manager = dialog
        dialog.show()
        add.requestFocus()
        add.nextFocusRightId = import.id
        import.nextFocusLeftId = add.id
        restoreRef?.let { import.nextFocusRightId = it.id; it.nextFocusLeftId = import.id }
    }

    private var manager: AlertDialog? = null

    private fun confirmDeleteCurrent() {
        val source = currentSource() ?: return
        tvTools.confirm("删除片源", "确定删除「${source.name}」吗？删除后可重新添加或恢复内置源。", null) {
            val remaining = sources.filterNot { it.id == source.id }
            store.saveSources(remaining.take(ThirdCms.MAX_SOURCES))
            store.setCurrent(remaining.firstOrNull()?.id)
            probes.remove(source.id)
            categoryId = null; searchWord = null; searchDetails = emptyList(); searchIndex.clear()
            videos.clear(); loadError = null; page = 1; hasMore = false; categories = emptyList()
            Toast.makeText(activity, "已删除「${source.name}」", Toast.LENGTH_SHORT).show()
            rebuild()
        }
    }

    private fun restoreBuiltinSources() {
        val missing = ThirdCms.restoreCandidates(sources)
        if(missing.isEmpty()) { Toast.makeText(activity, "内置源均已存在", Toast.LENGTH_SHORT).show(); return }
        val room = ThirdCms.MAX_SOURCES - sources.size
        if(room <= 0) { Toast.makeText(activity, "源数量已达上限（${ThirdCms.MAX_SOURCES}）", Toast.LENGTH_SHORT).show(); return }
        val restored = missing.take(room)
        store.saveSources(sources + restored)
        Toast.makeText(activity, "已恢复 ${restored.size} 个内置源", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    private fun showAddSourceDialog() {
        val content = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val nameEdit = editText("源名称（如：量子资源）")
        val apiEdit = editText("接口地址，如 https://…/api.php/provide/vod")
        val errorText = text("", 13f, TvStyle.accent).apply { setPadding(0, dp(6), 0, 0) }
        content.addView(text("名称", 13f, muted)); content.addView(nameEdit)
        content.addView(text("接口地址", 13f, muted).apply { setPadding(0, dp(8), 0, 0) }); content.addView(apiEdit)
        content.addView(errorText)
        content.addView(text("可用电视输入法/USB 键盘输入，或点「扫码填写」用手机输入。", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
        val actions = row().apply { setPadding(0, dp(10), 0, 0) }
        val save = addButton(actions, "保存并探活") { }
        val scan = addButton(actions, "扫码填写") { }
        content.addView(actions)

        val dialog = AlertDialog.Builder(activity).setTitle("添加片源").setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("取消", null).create()
        save.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val api = apiEdit.text.toString().trim()
            when {
                api.isEmpty() -> errorText.text = "请填写接口地址"
                !api.startsWith("http") -> errorText.text = "接口地址需以 http(s):// 开头"
                else -> {
                    errorText.text = "正在探活…"; save.isFocusable = false; save.isEnabled = false
                    val gen = generation
                    executor.execute {
                        val probe = client.probe(api)
                        main.post {
                            if(gen != generation) return@post
                            if(!probe.ok) { errorText.text = "探活失败：${probe.reason}"; save.isFocusable = true; save.isEnabled = true; return@post }
                            if(sources.size >= ThirdCms.MAX_SOURCES) { errorText.text = "源数量已达上限（${ThirdCms.MAX_SOURCES}）"; return@post }
                            if(sources.any { it.api == api }) { errorText.text = "该源已存在"; return@post }
                            val id = "u" + api.hashCode().toUInt().toString()
                            val source = ThirdSource(id, name.ifEmpty { hostOf(api) }, api, false)
                            store.saveSources(sources + source); store.setCurrent(source.id)
                            probes[source.id] = ProbeState(Dot.OK, probe.total)
                            categoryId = null; searchWord = null; searchDetails = emptyList(); searchIndex.clear()
                            videos.clear(); loadError = null; page = 1; hasMore = false; categories = emptyList()
                            dialog.dismiss()
                            Toast.makeText(activity, "已添加「${source.name}」", Toast.LENGTH_SHORT).show()
                            rebuild()
                        }
                    }
                }
            }
        }
        scan.setOnClickListener {
            tvTools.phoneInput(save) { submitted ->
                val value = submitted.trim()
                if(value.startsWith("http")) {
                    apiEdit.setText(value)
                    if(nameEdit.text.isBlank()) nameEdit.setText(hostOf(value))
                    nameEdit.requestFocus()
                } else errorText.text = "请输入以 http 开头的接口地址"
            }
        }
        dialog.show()
        nameEdit.requestFocus()
    }

    private fun showImportDialog() {
        val content = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val urlEdit = editText("TVBox 配置链接（明文 JSON）")
        val errorText = text("", 13f, TvStyle.accent).apply { setPadding(0, dp(6), 0, 0) }
        content.addView(urlEdit); content.addView(errorText)
        content.addView(text("自动跳过 spider jar（type=0）与加密 app 源（type=3/4）。", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
        val actions = row().apply { setPadding(0, dp(10), 0, 0) }
        val parse = addButton(actions, "解析") { }
        val scan = addButton(actions, "扫码填写") { }
        content.addView(actions)

        val dialog = AlertDialog.Builder(activity).setTitle("一键导入 TVBox 配置").setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("取消", null).create()
        fun runParse(rawUrl: String) {
            val url = rawUrl.trim()
            if(!url.startsWith("http")) { errorText.text = "请填写以 http 开头的配置链接"; return }
            errorText.text = "正在获取配置…"; parse.isFocusable = false; parse.isEnabled = false
            val gen = generation
            executor.execute {
                val result = client.importTvbox(url)
                main.post {
                    if(gen != generation) return@post
                    parse.isFocusable = true; parse.isEnabled = true
                    val (parsed, error) = result
                    if(parsed == null) { errorText.text = error ?: "无法获取配置"; return@post }
                    if(parsed.candidates.isEmpty()) { errorText.text = "没有可导入的 CMS 源（已跳过 ${parsed.skipped} 个不支持源）"; return@post }
                    dialog.dismiss()
                    showImportChecklist(parsed.candidates, parsed.skipped)
                }
            }
        }
        parse.setOnClickListener { runParse(urlEdit.text.toString()) }
        scan.setOnClickListener {
            tvTools.phoneInput(parse) { submitted ->
                val value = submitted.trim()
                if(value.startsWith("http")) { urlEdit.setText(value); runParse(value) }
                else errorText.text = "请输入以 http 开头的配置链接"
            }
        }
        dialog.show()
        urlEdit.requestFocus()
    }

    private fun showImportChecklist(candidates: List<ThirdTvboxSite>, skipped: Int) {
        val checked = BooleanArray(candidates.size) { true }
        val statusViews = arrayOfNulls<TextView>(candidates.size)
        val content = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val rows = candidates.mapIndexed { index, site ->
            val line = row().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
            val check = text(if(checked[index]) "✓" else "○", 16f, if(checked[index]) TvStyle.accent else muted)
            val nameColumn = column()
            nameColumn.addView(text(site.name, 15f))
            nameColumn.addView(text(hostOf(site.api), 12f, muted))
            val status = text("探活中…", 12f, muted)
            line.addView(check, lp(-2, -2).apply { rightMargin = dp(8) }); line.addView(nameColumn, lp(0, -2).apply { weight = 1f }); line.addView(status)
            focusStyle(line)
            line.setOnClickListener {
                checked[index] = !checked[index]
                check.text = if(checked[index]) "✓" else "○"
                check.setTextColor(if(checked[index]) TvStyle.accent else muted)
            }
            content.addView(line, lp(-1, -2).apply { bottomMargin = dp(6) })
            statusViews[index] = status
            line
        }
        content.addView(text("已自动跳过 $skipped 个不支持源", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
        val dialog = AlertDialog.Builder(activity).setTitle("选择要导入的源（${candidates.size}）")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setPositiveButton("导入所选") { _,_ ->
                val selected = candidates.filterIndexed { index, _ -> checked[index] }
                importSources(selected)
            }.create()
        dialog.show()
        rows.firstOrNull()?.requestFocus()
        // 逐个探活（单线程执行器天然串行），实时回填状态
        candidates.forEachIndexed { index, site ->
            val gen = generation
            executor.execute {
                val probe = client.probe(site.api)
                main.post {
                    if(gen != generation) return@post
                    statusViews[index]?.let { view ->
                        view.text = if(probe.ok) "可连接 · ${formatCount(probe.total)}" else "超时"
                        view.setTextColor(if(probe.ok) Color.rgb(76, 175, 80) else TvStyle.muted)
                    }
                }
            }
        }
    }

    private fun importSources(selected: List<ThirdTvboxSite>) {
        val existingApis = sources.mapTo(HashSet()) { it.api }
        var room = ThirdCms.MAX_SOURCES - sources.size
        var imported = 0; var duplicated = 0
        val added = mutableListOf<ThirdSource>()
        selected.forEach { site ->
            when {
                existingApis.contains(site.api) -> duplicated++
                room <= 0 -> {}
                else -> {
                    val id = "u" + site.api.hashCode().toUInt().toString()
                    added += ThirdSource(id, site.name, site.api, false)
                    existingApis += site.api; room--; imported++
                }
            }
        }
        if(added.isNotEmpty()) store.saveSources(sources + added)
        val message = buildString {
            append("成功导入 $imported 个源")
            if(duplicated > 0) append("，跳过重复 $duplicated 个")
            if(imported < selected.size) append("，超出上限跳过 ${selected.size - imported - duplicated} 个")
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        rebuild()
    }

    // ================= 搜索 =================

    private var searchDialog: AlertDialog? = null
    private var searchDraft = ""
    private val searchDebounce = Runnable { if(searchDraft.isNotBlank()) runSearchNow(searchDraft) }

    private fun showSearchDialog() {
        searchDraft = searchWord.orEmpty()
        val content = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val display = text(searchDraft.ifEmpty { "—" }, 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(8)) }
        content.addView(display)
        val keyViews = mutableListOf<TextView>()
        var firstKey: TextView? = null
        fun key(label: String, span: Int = 1, onClick: () -> Unit): TextView {
            val view = text(label, 14f).apply {
                gravity = Gravity.CENTER; minHeight = dp(34); focusStyle(this)
                setOnClickListener { onClick() }
            }
            keyViews += view
            if(firstKey == null) firstKey = view
            return view
        }
        val keyboard = column()
        listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789").forEach { keys ->
            val line = row().apply { setPadding(0, 0, 0, dp(4)) }
            keys.forEach { ch ->
                val view = key(ch.toString()) { searchDraft += ch; display.text = searchDraft; scheduleLiveSearch() }
                line.addView(view, lp(0, -2).apply { weight = 1f; rightMargin = dp(4) })
            }
            keyboard.addView(line)
        }
        val lastLine = row().apply { setPadding(0, 0, 0, dp(2)) }
        val del = key("删除", 2) { if(searchDraft.isNotEmpty()) { searchDraft = searchDraft.dropLast(1); display.text = searchDraft.ifEmpty { "—" }; scheduleLiveSearch() } }
        lastLine.addView(del, lp(0, -2).apply { weight = 2f; rightMargin = dp(4) })
        val clear = key("清空", 2) { searchDraft = ""; display.text = "—"; scheduleLiveSearch() }
        lastLine.addView(clear, lp(0, -2).apply { weight = 2f; rightMargin = dp(4) })
        val go = key("搜索", 2) { if(searchDraft.isNotBlank()) { searchDialog?.dismiss(); runSearchNow(searchDraft) } }
        lastLine.addView(go, lp(0, -2).apply { weight = 2f })
        content.addView(keyboard)
        val actions = row().apply { setPadding(0, dp(8), 0, 0) }
        val phone = addButton(actions, "手机扫码输入") { tvTools.phoneInput(go) { submitted ->
            val value = submitted.trim()
            if(value.isNotEmpty()) { searchDialog?.dismiss(); runSearchNow(value) }
        } }
        content.addView(actions)

        val dialog = AlertDialog.Builder(activity).setTitle("搜索片源")
            .setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("关闭", null).create()
        searchDialog = dialog
        dialog.show()
        firstKey?.requestFocus()
        // 键盘行内上下互访（6 列对齐）
        keyViews.forEachIndexed { index, view -> view.nextFocusUpId = keyViews.getOrNull(index - 6)?.id ?: View.NO_ID }
    }

    private fun scheduleLiveSearch() {
        main.removeCallbacks(searchDebounce)
        main.postDelayed(searchDebounce, 400)
    }

    private fun runSearchNow(word: String) {
        val source = currentSource() ?: return
        searchWord = word; searchDraft = word
        val gen = generation
        executor.execute {
            val details = client.search(source.api, word)
            main.post {
                if(gen != generation) return@post
                searchDetails = details
                searchIndex.clear(); searchIndex.putAll(details.associateBy { it.video.id })
                categoryId = null
                if(searchDialog?.isShowing == true) searchDialog?.dismiss()
                rebuild()
            }
        }
    }

    // ================= 详情态 =================

    private fun openDetail(data: ThirdDetail) {
        detail = data
        val progress = store.progress(currentId.orEmpty(), data.video.id)
        detailGroup = progress?.group?.takeIf { data.groups.getOrNull(it) != null } ?: ThirdCms.defaultGroupIndex(data.groups)
        playGroup = detailGroup; playEpisode = progress?.episode ?: 0
        recommendations = emptyList()
        mode = Mode.DETAIL
        requestRecommendations()
        rebuild()
    }

    private fun requestRecommendations() {
        val source = currentSource() ?: return
        val gen = generation
        val exclude = detail?.video?.id
        executor.execute {
            val result = client.list(source.api, categoryId, 1)
            main.post {
                if(gen != generation) return@post
                recommendations = result?.videos?.filter { it.id != exclude }?.take(5).orEmpty()
                mountRecommendations()
            }
        }
    }

    private fun renderDetail(container: LinearLayout) {
        val data = detail ?: run { mode = Mode.BROWSE; renderBrowse(container); return }
        recommendRow = null
        val body = column()
        val head = row()
        val back = addButton(head, "‹ 返回") { onBack() }
        head.addView(text(data.video.name, 21f).apply { setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(10), 0, 0, 0) }, lp(0, dp(40)).apply { weight = 1f; gravity = Gravity.CENTER_VERTICAL })
        body.addView(head)

        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val content = column().apply { setPadding(0, dp(6), 0, 0) }
        scroll.addView(content)

        val infoRow = row().apply { setPadding(0, 0, 0, dp(10)) }
        val posterColumn = column()
        val poster = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TvStyle.badgeSurface) }
        artwork.load(poster, data.video.cover)
        posterColumn.addView(poster, lp(dp(220), (dp(220) / TvStyle.POSTER_ASPECT).toInt()))
        posterColumn.addView(text("来源：${currentSource()?.name.orEmpty()}", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        infoRow.addView(posterColumn)

        val right = column()
        val titleRow = row()
        titleRow.addView(text(data.video.name, 22f).apply { setTypeface(null, android.graphics.Typeface.BOLD) }, lp(-2, -2))
        if(data.video.remarks.isNotBlank()) titleRow.addView(text(data.video.remarks, 11f, Color.WHITE).apply {
            setPadding(dp(6), dp(3), dp(6), dp(3)); background = rounded(TvStyle.badgeSurface, radius = 6)
        }, lp(-2, -2).apply { leftMargin = dp(8) })
        right.addView(titleRow)
        val episodeCount = data.groups.getOrNull(detailGroup)?.second?.size ?: 0
        right.addView(text(listOfNotNull(data.typeName, data.year, "共 $episodeCount 集").joinToString(" · "), 13f, muted).apply { setPadding(0, dp(6), 0, dp(6)) })
        right.addView(text(data.synopsis ?: "暂无简介", 13f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        content.addView(infoRow)
        infoRow.addView(right, lp(0, -2).apply { weight = 1f; leftMargin = dp(16) })

        // 主按钮：有进度默认继续播放并持焦点
        val progress = store.progress(currentId.orEmpty(), data.video.id)
        val mainActions = row().apply { setPadding(0, 0, 0, dp(10)) }
        var mainFocus: TextView? = null
        if(progress != null && data.groups.getOrNull(progress.group)?.second?.getOrNull(progress.episode) != null) {
            mainFocus = addButton(mainActions, "继续播放 第${progress.episode + 1}集") {
                playEpisode(progress.group, progress.episode, progress.positionMs)
            }
        }
        val start = addButton(mainActions, if(progress != null) "从头再看" else "从第01集开始") { playEpisode(detailGroup, 0) }
        if(mainFocus == null) mainFocus = start
        content.addView(mainActions)

        // 播放组 tabs
        if(data.groups.size > 1) {
            val tabRow = row().apply { setPadding(0, 0, 0, dp(6)) }
            var firstTab: TextView? = null
            data.groups.forEachIndexed { index, (name, _) ->
                val tab = addButton(tabRow, name, index == playGroup) {
                    playGroup = index; playEpisode = 0; renderDetailRefresh()
                }
                if(firstTab == null) firstTab = tab
            }
            content.addView(tabRow)
            firstTab?.let { tab ->
                mainFocus?.nextFocusDownId = tab.id
                tab.nextFocusUpId = mainFocus?.id ?: View.NO_ID
            }
        }

        // 分集网格
        val episodes = data.groups.getOrNull(playGroup)?.second.orEmpty()
        if(episodes.isEmpty()) {
            content.addView(text("该线路暂无可用剧集", 14f, muted).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            val cols = episodeCols()
            val episodeAdapter = EpisodeAdapter(episodes)
            val grid = GridLayoutManager(activity, cols)
            val recycler = RecyclerView(activity).apply {
                id = View.generateViewId(); layoutManager = grid; adapter = episodeAdapter; itemAnimator = null
                isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true
                setOnFocusChangeListener { _, focused -> if(focused) {
                    val first = grid.findFirstVisibleItemPosition().coerceAtLeast(0)
                    post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() }
                } }
            }
            content.addView(recycler, lp(-1, -2))
            mainFocus?.nextFocusDownId = recycler.id
        }

        // 同分类推荐
        content.addView(text("同分类推荐", 15f).apply { setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, dp(12), 0, dp(6)) })
        val recommend = row()
        recommendRow = recommend
        recommend.addView(text(if(recommendations.isEmpty()) "加载中…" else "", 12f, muted))
        if(recommendations.isNotEmpty()) mountRecommendations(recommend)
        content.addView(recommend)

        body.addView(scroll, lp(-1, 0).apply { weight = 1f })
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        topFocusId = mainFocus?.id ?: back.id
        mainFocus?.nextFocusUpId = back.id
        mainFocus?.requestFocus()
    }

    private fun renderDetailRefresh() {
        if(mode == Mode.DETAIL) rebuild()
    }

    private fun mountRecommendations(into: LinearLayout? = recommendRow) {
        val target = into ?: return
        if(recommendations.isEmpty()) { target.addView(text("暂无推荐", 12f, muted)); return }
        target.removeAllViews()
        recommendations.forEach { item ->
            val card = column().apply { setPadding(dp(4), 0, dp(4), 0) }
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TvStyle.badgeSurface) }
            card.addView(image, lp(dp(170), dp(96)))
            card.addView(text(item.name, 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(4), dp(2), 0) })
            focusStyle(card)
            card.setOnClickListener { onVideoClick(item) }
            target.addView(card, lp(-2, -2).apply { rightMargin = dp(8) })
            artwork.load(image, item.cover, ArtworkLoader.PRIORITY_VISIBLE)
        }
    }

    private inner class EpisodeAdapter(private val episodes: List<ThirdEpisode>) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(val view: TextView) : RecyclerView.ViewHolder(view)
        private val progressEpisode = store.progress(currentId.orEmpty(), detail?.video?.id.orEmpty())

        override fun getItemCount() = episodes.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = text("", 13f).apply {
                gravity = Gravity.CENTER; minHeight = dp(34)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6); rightMargin = dp(6) }
            }
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = episodes[position]
            val watched = progressEpisode?.episode == position
            holder.view.text = episode.label
            focusStyle(holder.view, selected = watched)
            if(watched) holder.view.setTextColor(TvStyle.accent)
            holder.view.setOnClickListener { playEpisode(playGroup, position) }
        }
    }

    // ================= 播放态 =================

    private fun playEpisode(group: Int, episode: Int, startMs: Long = 0L) {
        if(detail?.groups?.getOrNull(group)?.second?.getOrNull(episode) == null) return
        saveProgressNow()
        mode = Mode.PLAY; playGroup = group; playEpisode = episode
        pendingStartMs = startMs; pendingAutoplay = true; hasPendingPlay = true
        rebuild()
    }

    private fun attachPlayer(startMs: Long, autoplay: Boolean) {
        detachPlayerViews()
        val data = detail ?: return
        val group = data.groups.getOrNull(playGroup) ?: return
        val episode = group.second.getOrNull(playEpisode) ?: return
        val host = FrameLayout(activity).apply { isFocusable = false }
        val playerView = PlayerView(activity).apply { useController = false }
        host.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val loading = text("正在加载…", 15f, muted).apply { gravity = Gravity.CENTER }
        val errorView = text("", 14f, TvStyle.accent).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        val center = column().apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        center.addView(loading); center.addView(errorView)
        host.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playLoading = loading; playErrorText = errorView

        val instance = ExoPlayer.Builder(activity).build()
        instance.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        playerView.player = instance
        instance.addListener(playerListener)
        instance.setMediaItem(MediaItem.fromUri(episode.url), startMs.coerceAtLeast(0L))
        instance.prepare()
        instance.playWhenReady = autoplay

        player = instance; playerHost = host
        buildOverlay(host, data, group.first)
        (activity.findViewById(android.R.id.content) as FrameLayout).addView(host, FrameLayout.LayoutParams(-1, -1))
        scheduleSave()
    }

    private fun buildOverlay(host: FrameLayout, data: ThirdDetail, groupName: String) {
        // 顶部信息条
        val topBar = row().apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0)); setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        topBar.addView(text("${data.video.name} · ${data.groups.getOrNull(playGroup)?.second?.getOrNull(playEpisode)?.label.orEmpty()}", 15f), lp(0, -2).apply { weight = 1f })
        topBar.addView(text("${currentSource()?.name.orEmpty()} · $groupName", 12f, muted))
        topBar.visibility = View.GONE
        host.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // 底部控制层：上一集 / 下一集 / 返回详情 + 进度条 + 时间
        val bottom = column().apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(dp(14), dp(10), dp(14), dp(12)) }
        val track = FrameLayout(activity).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) }
        val fill = View(activity).apply { setBackgroundColor(TvStyle.accent) }
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        bottom.addView(track, lp(-1, dp(5)))
        val controls = row().apply { setPadding(0, dp(8), 0, 0) }
        overlayButtons.clear()
        val prev = addButton(controls, "上一集") { jumpEpisode(-1) }
        val next = addButton(controls, "下一集") { jumpEpisode(1) }
        val back = addButton(controls, "返回详情") { onBack() }
        val time = text("", 13f)
        controls.addView(time, lp(0, -2).apply { weight = 1f; gravity = Gravity.END; leftMargin = dp(10) })
        bottom.addView(controls)
        bottom.visibility = View.GONE
        host.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        overlay = bottom; overlayTopView = topBar; progressFill = fill; progressTrackView = track; playTime = time
        prev.nextFocusRightId = next.id; next.nextFocusLeftId = prev.id; next.nextFocusRightId = back.id; back.nextFocusLeftId = next.id
    }

    private val overlayButtons = mutableListOf<TextView>()
    private var overlayTopView: View? = null
    private var progressTrackView: View? = null

    private fun showOverlay() {
        overlayVisible = true
        overlayTopView?.visibility = View.VISIBLE
        overlay?.visibility = View.VISIBLE
        updatePlayUi()
        main.removeCallbacks(hideOverlayRun)
        main.postDelayed(hideOverlayRun, 10_000L)
        val focusTarget = overlayButtons.firstOrNull { it.text == "上一集" && playEpisode > 0 } ?: overlayButtons.firstOrNull()
        focusTarget?.requestFocus()
    }

    private fun hideOverlay() {
        overlayVisible = false
        overlayTopView?.visibility = View.GONE
        overlay?.visibility = View.GONE
        main.removeCallbacks(hideOverlayRun)
    }

    private fun togglePlay() {
        val instance = player ?: return
        if(instance.isPlaying) instance.pause() else instance.play()
        if(overlayVisible) { main.removeCallbacks(hideOverlayRun); main.postDelayed(hideOverlayRun, 10_000L) }
    }

    private fun seek(deltaMs: Long) {
        val instance = player ?: return
        val target = (instance.currentPosition + deltaMs).coerceIn(0L, (instance.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        instance.seekTo(target)
        if(!overlayVisible) showOverlay() else { main.removeCallbacks(hideOverlayRun); main.postDelayed(hideOverlayRun, 10_000L) }
    }

    private fun jumpEpisode(direction: Int) {
        val episodes = detail?.groups?.getOrNull(playGroup)?.second ?: return
        val next = playEpisode + direction
        if(next < 0) { Toast.makeText(activity, "已是第一集", Toast.LENGTH_SHORT).show(); return }
        if(next >= episodes.size) { Toast.makeText(activity, "已是最后一集", Toast.LENGTH_SHORT).show(); return }
        playEpisode(playGroup, next)
    }

    private fun updatePlayUi() {
        val instance = player ?: return
        val duration = instance.duration.takeIf { it > 0 } ?: 0L
        val position = instance.currentPosition.coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        playTime?.text = "${formatTime(position)} / ${if(duration > 0) formatTime(duration) else "直播中"}"
        val track = progressTrackView
        val fill = progressFill
        if(track != null && fill != null && track.width > 0) {
            val ratio = if(duration > 0) position.toDouble() / duration else 0.0
            fill.layoutParams = FrameLayout.LayoutParams((track.width * ratio).toInt().coerceAtLeast(1), -1)
            fill.requestLayout()
        }
        if(overlayVisible) { main.removeCallbacks(uiTick); main.postDelayed(uiTick, 500) }
    }

    private fun saveProgressNow() {
        val instance = player ?: return
        val data = detail ?: return
        val id = currentId ?: return
        val position = instance.currentPosition
        if(position > 0) store.saveProgress(id, data.video.id, ThirdProgress(playEpisode, position, playGroup))
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRun)
        main.postDelayed(saveRun, 5_000L)
    }

    private fun releasePlayback() {
        main.removeCallbacks(saveRun); main.removeCallbacks(hideOverlayRun); main.removeCallbacks(uiTick)
        overlayVisible = false; overlayButtons.clear()
        overlay = null; overlayTopView = null; progressFill = null; progressTrackView = null; playTime = null
        playLoading = null; playErrorText = null
        playerHost?.let { host -> (host.parent as? FrameLayout)?.removeView(host) }
        playerHost = null
        player?.let { instance -> instance.removeListener(playerListener); instance.release() }
        player = null
    }

    private fun detachPlayerViews() {
        playerHost?.let { host -> (host.parent as? FrameLayout)?.removeView(host) }
    }

    // ================= 通用小件 =================

    private fun dp(value: Number) = (value.toFloat() * activity.resources.displayMetrics.density).toInt()
    private fun widthDp() = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
    private fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun rounded(color: Int, border: Int = Color.TRANSPARENT, radius: Int = 10) =
        TvStyle.shape(activity, color, border, radius)
    private fun text(value: String, size: Float = 16f, color: Int = TvStyle.text) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View, selected: Boolean = false) {
        view.id = View.generateViewId(); view.isFocusable = true; view.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            view.background = rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else TvStyle.surface,
                if(focused) TvStyle.accent else Color.TRANSPARENT)
        }
        paint(false)
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }
    private fun addButton(parent: LinearLayout, label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 15f).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10)); minHeight = dp(42)
            focusStyle(this, selected); setOnClickListener { onClick() }
        }.also { view ->
            parent.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
    private fun message(parent: LinearLayout, label: String) {
        parent.addView(text(label, 16f, muted).apply { setPadding(0, dp(28), 0, dp(12)) })
    }
    private fun editText(hint: String) = EditText(activity).apply {
        this.hint = hint; textSize = 15f; setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted)
        isSingleLine = true; maxLines = 1
        background = rounded(TvStyle.raised, TvStyle.outline)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnFocusChangeListener { _, focused ->
            background = rounded(if(focused) TvStyle.raised else TvStyle.surface, if(focused) TvStyle.accent else TvStyle.outline)
            if(focused) {
                val manager = activity.getSystemService(InputMethodManager::class.java)
                manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun hostOf(api: String): String = try {
        java.net.URI(api).host ?: api.removePrefix("https://").removePrefix("http://").substringBefore('/')
    } catch (_: Exception) { api.substringBefore('/') }

    private fun formatCount(total: Int): String = when {
        total >= 10000 -> String.format(java.util.Locale.CHINA, "%.1f万", total / 10000.0)
        total > 0 -> total.toString()
        else -> "可连接"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if(hours > 0) String.format(java.util.Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.CHINA, "%02d:%02d", minutes, seconds)
    }

    private fun browseCols(): Int = when {
        widthDp() >= 1100 -> 6; widthDp() >= 900 -> 5; widthDp() >= 700 -> 4; else -> 3
    }
    private fun episodeCols(): Int = when {
        widthDp() >= 1100 -> 7; widthDp() >= 900 -> 6; widthDp() >= 700 -> 5; else -> 4
    }
}
