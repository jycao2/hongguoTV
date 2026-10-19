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
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.ThirdSourceConfig
import java.util.concurrent.Executors

/** 三方片源页：自成体系的「店中店」——浏览/详情/播放全部独立，与红果内容体系零交集。
 *  生命周期由 MainActivity 委托：show/onBack/onKey/pause/stop/release。 */
class ThirdSourceScreen(private val activity: Activity, private val rebuild: () -> Unit, private val backupRequest: () -> Unit = {}) {
    private enum class Mode { BROWSE, SEARCH, SOURCES, DETAIL, PLAY }
    private enum class Dot { RUNNING, OK, DEAD }

    private companion object {
        /** 三方片源免责声明：空态与源管理页常驻展示。 */
        const val SOURCE_DISCLAIMER = "内置第三方 CMS 接口仅为技术交流与学习用途，应用不提供、不存储、不传播任何视频内容，内容合法性由对应源站负责。"
    }

    private class ProbeState(val dot: Dot, val total: Int, val reason: String? = null)

    private val main = Handler(Looper.getMainLooper())
    private val muted get() = TvStyle.muted
    private val store = ThirdStore(activity)
    private val client = ThirdCmsClient()
    private val artwork = ArtworkLoader(activity)
    private val tvTools = TvTools(activity)
    private val executor = Executors.newFixedThreadPool(3)
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
    private var categoriesCached = false
    private var categoryId: String? = null
    private var page = 1
    private var hasMore = false
    private var videos: MutableList<ThirdVideo> = mutableListOf()
    private var loading = false
    private var loadingMore = false
    // 本次浏览会话是否已发起过列表请求：空结果分类若再自动重发会形成「请求→空→rebuild→再请求」
    // 死循环，界面持续重建吞掉按键（表现为遥控器卡死）
    private var catalogRequested = false
    private var loadError: String? = null
    private var searchWord: String? = null
    private var searchDetails: List<ThirdDetail> = emptyList()
    private val searchIndex = HashMap<String, ThirdDetail>()
    // 搜索页（独立 Mode.SEARCH）：输入、联想、分页状态与浏览态结果分离
    private var searchInput = ""
    private var suggestions: List<String> = emptyList()
    private var suggestSeq = 0
    private var searchLoading = false
    private var searchLoadingMore = false
    private var searchRequested = false
    private var searchPage = 1
    private var searchHasMore = false
    private var searchTotal = 0
    private val suggestDebounce = Runnable { runSuggest() }
    // 搜索筛选（客户端筛选，CMS 搜索接口不支持多维条件）
    private var filterType: String? = null
    private var filterYear: String? = null
    private var filterArea: String? = null
    private var probeSummary: TextView? = null
    private var gridStatus: TextView? = null
    private var gridAdapter: GridAdapter? = null
    private var gridRecycler: RecyclerView? = null
    private var sourceButtons: List<View> = emptyList()

    // ---- 详情态 ----
    private var detail: ThirdDetail? = null
    private var detailGroup = 0
    private var synopsisExpanded = false

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
            mode == Mode.SEARCH -> renderSearch(container)
            mode == Mode.SOURCES -> renderSources(container)
            else -> { mode = Mode.BROWSE; loading = false; loadingMore = false; renderBrowse(container) }
        }
        // 重建时系统可能在首帧布局阶段清掉焦点（片库页同样用 post 兜底）：下一帧再补一次，
        // 否则遥控器会出现「按了没反应」的失焦状态
        val focusId = topFocusId
        if(focusId != View.NO_ID) container.post {
            if(container.isAttachedToWindow) container.findViewById<View>(focusId)?.requestFocus()
        }
    }

    /** 返回：播放→详情→浏览（搜索页直接回浏览）；浏览态交还 MainActivity（导航焦点/退出确认）。 */
    fun onBack(): Boolean = when(mode) {
        Mode.PLAY -> { saveProgressNow(); mode = Mode.DETAIL; releasePlayback(); rebuild(); true }
        Mode.DETAIL -> { mode = Mode.BROWSE; rebuild(); true }
        Mode.SEARCH -> { mode = Mode.BROWSE; rebuild(); true }
        Mode.SOURCES -> { sourceBatchMode = false; sourceSelected.clear(); mode = Mode.BROWSE; rebuild(); true }
        // 搜索结果页返回搜索页，方便改关键词；普通浏览交还 MainActivity
        Mode.BROWSE -> if(searchWord != null) {
            suggestions = emptyList(); suggestSeq++; mode = Mode.SEARCH; rebuild(); scheduleSuggest(); true
        } else false
    }

    /** 播放页按键接管；浏览态只接管网格上下翻行（RecyclerView 在固定高度容器里不会为焦点自动滚动）。 */
    fun onKey(event: KeyEvent): Boolean {
        if(event.action != KeyEvent.ACTION_DOWN) return false
        if(mode == Mode.BROWSE && browseGridKey(event.keyCode)) return true
        if(mode != Mode.PLAY || player == null) return false
        val key = event.keyCode
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

        // 源胶囊条 + 管理（搜索态隐藏，把高度让给结果网格）
        var capsuleViews: List<View> = emptyList()
        var manage: TextView? = null
        if(searchWord == null) {
            val sourceRow = row()
            val capsuleScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false }
            val capsules = row(); capsuleDots.clear()
            capsuleViews = sources.mapIndexed { index, source -> sourceCapsule(source, index) }
            capsuleViews.forEach { view -> capsules.addView(view, lp(-2, -2).apply { rightMargin = dp(8) }) }
            sourceButtons = capsuleViews
            capsuleScroll.addView(capsules)
            sourceRow.addView(capsuleScroll, lp(0, -2).apply { weight = 1f })
            manage = addButton(sourceRow, "管理") { openSources() }
            body.addView(sourceRow)

            probeSummary = text(probeSummaryText(), 12f, muted).apply { setPadding(0, dp(5), 0, dp(5)) }
            body.addView(probeSummary)
        } else sourceButtons = emptyList()

        // 搜索行
        val searchRow = row()
        val search = addButton(searchRow, "搜索片源") { openSearch() }
        var clearRef: TextView? = null
        if(searchWord != null) clearRef = addButton(searchRow, "重新搜索") { openSearch(clear = true) }
        body.addView(searchRow)

        // 分类条（搜索态隐藏，改为筛选条）
        var categoryButtons: List<TextView> = emptyList()
        var filterButtons: List<View> = emptyList()
        if(searchWord != null) {
            // 搜索态：结果统计 + 类型/年份/地区筛选
            val filteredCount = filteredSearchDetails().size
            val status = when {
                searchLoading -> "正在搜索「$searchWord」…"
                searchDetails.isEmpty() -> "搜索「$searchWord」 · 暂无结果"
                searchTotal > searchDetails.size -> "搜索「$searchWord」 · $filteredCount/${searchDetails.size} 条（共 $searchTotal 条）"
                else -> "搜索「$searchWord」 · $filteredCount/${searchDetails.size} 条"
            }
            body.addView(text(status, 13f).apply { setPadding(0, dp(6), 0, dp(2)) })
            val filterRow = row().apply { setPadding(0, 0, 0, dp(4)) }
            val typeOptions = searchDetails.mapNotNull { it.typeName }.distinct().sorted()
            val yearOptions = searchDetails.mapNotNull { it.year }.distinct().sortedDescending()
            val areaOptions = searchDetails.mapNotNull { it.area }.distinct().sorted()
            val filterCapsules = mutableListOf<View>()
            fun filterCapsule(label: String, current: String?, options: List<String>, onPick: (String?) -> Unit): TextView =
                text(label + (current?.let { "：$it" } ?: ""), 13f, if(current != null) TvStyle.accent else TvStyle.text).apply {
                    setPadding(dp(10), dp(6), dp(10), dp(6)); focusStyle(this, selected = current != null)
                    setOnClickListener { showFilterPicker(label, listOf<String?>(null) + options, current, onPick) }
                }
            if(typeOptions.size > 1) filterCapsules += filterCapsule("类型", filterType, typeOptions) { filterType = it; rebuild() }
            if(yearOptions.size > 1) filterCapsules += filterCapsule("年份", filterYear, yearOptions) { filterYear = it; rebuild() }
            if(areaOptions.size > 1) filterCapsules += filterCapsule("地区", filterArea, areaOptions) { filterArea = it; rebuild() }
            if(filterType != null || filterYear != null || filterArea != null) {
                filterCapsules += text("清除筛选", 13f, muted).apply {
                    setPadding(dp(10), dp(6), dp(10), dp(6)); focusStyle(this)
                    setOnClickListener { filterType = null; filterYear = null; filterArea = null; rebuild() }
                }
            }
            filterCapsules.forEach { filterRow.addView(it, lp(-2, -2).apply { rightMargin = dp(8) }) }
            if(filterCapsules.isNotEmpty()) { body.addView(filterRow); filterButtons = filterCapsules }
        } else {
            val categoryScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false }
            val categoryRow = row().apply { setPadding(0, dp(2), 0, dp(2)) }
            val options = listOf(ThirdCategory("", "全部")) + categories
            categoryButtons = options.map { option ->
                val id = option.id.ifEmpty { null }
                addButton(categoryRow, option.name, categoryId == id) {
                    categoryId = id; videos.clear(); loadError = null; page = 1; hasMore = false
                    catalogRequested = false; rebuild()
                }
            }
            categoryScroll.addView(categoryRow)
            body.addView(categoryScroll)
        }

        // 结果区
        if(searchWord == null && loadError != null && videos.isEmpty()) {
            body.addView(text("暂时无法加载「${currentSource()?.name.orEmpty()}」：$loadError", 15f, muted).apply { setPadding(0, dp(24), 0, dp(10)) })
            addButton(body, "重试") { loadError = null; catalogRequested = false; rebuild() }.requestFocus()
            container.addView(body, lp(-1, 0).apply { weight = 1f })
            topFocusId = body.getChildAt(body.childCount - 1).id
            return
        }
        val display: List<ThirdVideo> = currentDisplay()
        // 自动补拉放在空态文案之前：请求会同步置 loading，文案能显示「正在加载…」而不是误报「暂无内容」
        if(videos.isEmpty() && searchWord == null && loadError == null && !loading && !catalogRequested) requestCatalog(reset = true)
        // 搜索同理：失败后不自动重发（searchRequested 守卫），避免「请求→失败→重建→再请求」死循环
        if(searchWord != null && searchDetails.isEmpty() && loadError == null && !searchLoading && !searchRequested) requestSearch(reset = true)
        if(display.isEmpty()) {
            message(body, when {
                searchWord != null && searchDetails.isNotEmpty() -> "没有符合筛选条件的结果，调整筛选或清除筛选试试"
                searchWord != null && searchLoading -> "正在搜索「$searchWord」…"
                searchWord != null && loadError != null -> "搜索失败：$loadError，换个关键词或稍后重试"
                searchWord != null -> "未找到与「$searchWord」相关的内容，换个关键词试试"
                loading -> "正在加载「${currentSource()?.name.orEmpty()}」…"
                loadError == null -> "该分类暂无内容"
                else -> ""
            })
        }
        if(display.isNotEmpty()) mountGrid(body)
        gridStatus = text(gridStatusText(), 12f, muted).apply { setPadding(0, dp(4), 0, 0) }
        body.addView(gridStatus)

        container.addView(body, lp(-1, 0).apply { weight = 1f })
        // 焦点归属：搜索结果→首张卡片；选中的分类→该分类；空态→「全部」；其余→当前使用的源胶囊
        // （点源 tab 切换后不要把焦点甩回第一个胶囊）
        val emptyBrowse = display.isEmpty() && !loading && searchWord == null && loadError == null
        val selectedCategory = if(searchWord == null && categoryId != null) {
            val index = categories.indexOfFirst { it.id == categoryId }
            if(index >= 0) categoryButtons.getOrNull(index + 1) else null
        } else null
        val currentCapsule = capsuleViews.getOrNull(sources.indexOfFirst { it.id == currentId })
        topFocusId = when {
            selectedCategory != null -> selectedCategory.id
            searchWord != null && gridRecycler != null -> gridRecycler!!.id
            searchWord != null -> search.id
            emptyBrowse && categoryButtons.isNotEmpty() -> categoryButtons[0].id
            currentCapsule != null -> currentCapsule.id
            else -> capsuleViews.firstOrNull()?.id ?: search.id
        }
        // 焦点接线：源胶囊/管理 ↓ → 搜索行 ↓ → 分类（搜索态为筛选项）↓ → 网格，逐层下移不被跳过
        gridRecycler?.id?.let { gridId ->
            val secondRow = categoryButtons.firstOrNull()?.id ?: filterButtons.firstOrNull()?.id ?: gridId
            capsuleViews.forEach { it.nextFocusDownId = search.id }
            manage?.nextFocusDownId = search.id
            search.nextFocusDownId = secondRow
            clearRef?.nextFocusDownId = secondRow
            categoryButtons.forEach { it.nextFocusDownId = gridId; it.nextFocusUpId = search.id }
            filterButtons.forEach { it.nextFocusDownId = gridId; it.nextFocusUpId = search.id }
        }
        search.nextFocusUpId = currentCapsule?.id ?: manage?.id ?: View.NO_ID
        search.nextFocusLeftId = manage?.id ?: View.NO_ID
        manage?.let { button ->
            button.nextFocusRightId = clearRef?.id ?: search.id
            clearRef?.nextFocusLeftId = button.id
        }
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
        val body = column().apply { setPadding(0, dp(24), 0, 0) }
        message(body, "暂无片源信息，可手动添加或恢复内置源")
        body.addView(text("若重装后没有自动恢复：可能是本机备份文件不存在或未授予存储权限，可在「设置 → 本地备份与恢复」中查看。", 13f, muted)
            .apply { setPadding(0, dp(2), 0, dp(10)) })
        val actions = row().apply { setPadding(0, dp(4), 0, 0) }
        val add = addButton(actions, "＋ 添加源") { showAddSourceDialog() }
        val restore = addButton(actions, "恢复内置源") { restoreBuiltinSources() }
        restore.nextFocusLeftId = add.id
        add.nextFocusRightId = restore.id
        body.addView(actions)
        body.addView(text(SOURCE_DISCLAIMER, 12f, muted).apply { setPadding(0, dp(20), 0, 0) })
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        topFocusId = add.id; add.requestFocus()
    }

    private fun mountGrid(body: LinearLayout) {
        val cols = browseCols()
        val adapter = GridAdapter(cols) { currentDisplay() }
        gridAdapter = adapter
        val grid = GridLayoutManager(activity, cols)
        val recycler = RecyclerView(activity).apply {
            id = View.generateViewId(); setLayoutManager(grid); setAdapter(adapter); itemAnimator = null
            isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true
            setOnFocusChangeListener { _, focused -> if(focused) {
                val first = grid.findFirstVisibleItemPosition().coerceAtLeast(0)
                post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() }
            } }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // 滚到底自动续页：焦点导航不会触发 bind 的临界判断时由这里兜底
                    if(!recyclerView.canScrollVertically(1)) maybeLoadMore()
                }
            })
        }
        gridRecycler = recycler
        body.addView(recycler, lp(-1, 0).apply { weight = 1f })
        sourceButtons.lastOrNull()?.nextFocusDownId = recycler.id
    }

    /** 当前结果集：浏览态用列表缓存，搜索态用筛选后的搜索结果。adapter 每次取最新，翻页追加不必重建控件。 */
    private fun currentDisplay(): List<ThirdVideo> =
        if(searchWord != null) filteredSearchDetails().map { it.video } else videos

    private inner class GridAdapter(private val cols: Int, private val source: () -> List<ThirdVideo>) : RecyclerView.Adapter<GridAdapter.Holder>() {
        private val contentPx = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
        private val cellPx = (contentPx - dp(12) * (cols - 1)) / cols
        private val posterPx = (cellPx / TvStyle.POSTER_ASPECT).toInt()
        private val items: List<ThirdVideo> get() = source()

        fun submit(appendFrom: Int) {
            if(appendFrom < 0) notifyDataSetChanged() else notifyItemRangeInserted(appendFrom, source().size - appendFrom)
        }

        inner class Holder(val card: LinearLayout, val image: ImageView, val name: TextView, val badge: TextView) : RecyclerView.ViewHolder(card)

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val card = column().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
            val imageFrame = FrameLayout(activity)
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TvStyle.badgeSurface) }
            imageFrame.addView(image, FrameLayout.LayoutParams(-1, posterPx))
            val badge = text("", 12f, Color.WHITE).apply { setPadding(dp(6), dp(3), dp(6), dp(3)); background = rounded(TvStyle.badgeSurface, radius = 6) }
            imageFrame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
            val name = text("", 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(6), dp(2), 0) }
            card.addView(imageFrame); card.addView(name)
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(12); rightMargin = dp(4) }
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
            // 保留 focusStyle 的绘制监听，追加名称高亮；焦点走到末排即预取下一页
            val paint = holder.card.onFocusChangeListener
            holder.card.setOnFocusChangeListener { v, focused ->
                paint?.onFocusChange(v, focused)
                holder.name.setTextColor(if(focused) TvStyle.accent else TvStyle.text)
                if(focused && position >= items.size - 3) maybeLoadMore()
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
        if(loadError != null || currentSource() == null) return
        if(searchWord != null) { requestSearch(reset = false); return }
        if(loadingMore || !hasMore) return
        requestCatalog(reset = false)
    }

    /** 网格上下翻行：目标行不在视口内时先滚动再聚焦；已到底则拉起下一页。 */
    private fun browseGridKey(key: Int): Boolean {
        val recycler = gridRecycler ?: return false
        // 有弹窗时焦点属于弹窗窗口，不能被网格按键逻辑吞掉
        val current = activity.currentFocus ?: return false
        if(current.windowToken !== recycler.windowToken) return false
        val focused = recycler.findFocus() ?: return false
        val grid = recycler.layoutManager as? GridLayoutManager ?: return false
        val position = recycler.getChildAdapterPosition(focused)
        if(position == RecyclerView.NO_POSITION) return false
        val span = grid.spanCount.coerceAtLeast(1)
        when(key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = position + span
                if(next >= (gridAdapter?.itemCount ?: 0)) { maybeLoadMore(); return true }
                // 目标行贴到视口底部：视口只有一行时即 0（整行替换），两行时等效下滚一行
                scrollAndFocus(recycler, grid, next, (recycler.height - focused.height).coerceAtLeast(0))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val prev = position - span
                if(prev < 0) return false
                scrollAndFocus(recycler, grid, prev, 0)
                return true
            }
        }
        return false
    }

    private fun scrollAndFocus(recycler: RecyclerView, grid: GridLayoutManager, position: Int, offset: Int) {
        grid.scrollToPositionWithOffset(position, offset)
        // postOnAnimation 在下一帧布局之后执行；普通 post 可能早于这次重新布局，导致目标卡片还没创建
        recycler.postOnAnimation {
            val target = grid.findViewByPosition(position)
            if(target != null) target.requestFocus()
            else recycler.postOnAnimation { grid.findViewByPosition(position)?.requestFocus() }
        }
    }

    private fun requestCatalog(reset: Boolean) {
        val source = currentSource() ?: return
        if(loading || loadingMore) return
        val gen = generation
        val requestCategory = categoryId
        val requestPage = if(reset) 1 else page + 1
        if(reset) {
            // 新 reset 请求发起前取消所有在途 catalog 请求：旧请求已无人消费（generation 会丢弃其回调），
            // 不取消会占满线程池让新请求排队，界面长时间空态（体感卡死）
            client.cancelCatalog()
            loading = true
            catalogRequested = true
        } else loadingMore = true
        executor.execute {
            // 分类与列表串行请求、统一一次回调：分类中途 rebuild 会让 generation 失效，
            // 导致 list 回调被丢弃、loading 死锁（点分类后界面卡死）
            val fetched = if(reset && !categoriesCached && requestCategory == null) client.categories(source.api) else null
            val result = client.list(source.api, requestCategory, requestPage)
            main.post {
                if(gen != generation) return@post
                if(fetched != null && fetched.isNotEmpty()) { categories = fetched; categoriesCached = true }
                if(reset) {
                    loading = false
                    if(result != null) {
                        // 分类兜底：仅首页响应含分类时更新缓存，后续页沿用
                        if(!categoriesCached && result.categories.isNotEmpty()) {
                            categories = result.categories; categoriesCached = true
                        }
                        videos = result.videos.toMutableList()
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

    /** 搜索分页请求（ac=detail&wd=）：reset 取第 1 页并替换结果，否则追加下一页。 */
    private fun requestSearch(reset: Boolean) {
        val source = currentSource() ?: return
        val word = searchWord ?: return
        if(reset) {
            if(searchLoading) return
            searchLoading = true; searchRequested = true
        } else {
            if(searchLoadingMore || !searchHasMore) return
            searchLoadingMore = true
        }
        val gen = generation
        val requestPage = if(reset) 1 else searchPage + 1
        executor.execute {
            val result = client.searchPage(source.api, word, requestPage)
            main.post {
                if(gen != generation) return@post
                if(reset) {
                    searchLoading = false
                    val pageData = result.page
                    if(pageData != null) {
                        searchDetails = pageData.details
                        searchIndex.clear(); searchIndex.putAll(pageData.details.associateBy { it.video.id })
                        searchHasMore = pageData.hasMore; searchTotal = pageData.total; searchPage = 1; loadError = null
                    } else {
                        searchDetails = emptyList(); searchHasMore = false; searchTotal = 0
                        loadError = result.error ?: "连接超时或接口无响应"
                    }
                    rebuild()
                } else {
                    searchLoadingMore = false
                    val pageData = result.page
                    if(pageData != null) {
                        val known = searchDetails.mapTo(HashSet()) { it.video.id }
                        val fresh = pageData.details.filter { it.video.id !in known }
                        val insertAt = searchDetails.size
                        searchDetails = searchDetails + fresh
                        searchIndex.putAll(fresh.associateBy { it.video.id })
                        searchHasMore = pageData.hasMore; searchTotal = pageData.total; searchPage = requestPage
                        gridAdapter?.submit(insertAt)
                        gridStatus?.text = gridStatusText()
                    } else gridStatus?.text = "加载更多失败，继续下滑可重试"
                }
            }
        }
    }

    private fun gridStatusText(): String = when {
        searchWord != null -> when {
            searchLoadingMore -> "正在加载更多…"
            searchHasMore -> "继续下滑加载更多"
            searchDetails.isEmpty() -> ""
            searchTotal > searchDetails.size -> "已加载 ${searchDetails.size} / $searchTotal 条"
            else -> "已加载全部 ${searchDetails.size} 条"
        }
        loadingMore -> "正在加载更多…"
        hasMore -> "继续下滑加载更多"
        videos.isEmpty() -> ""
        else -> "已加载全部 ${videos.size} 条"
    }

    /** 应用类型/年份/地区筛选后的搜索结果。 */
    private fun filteredSearchDetails(): List<ThirdDetail> = searchDetails.filter { item ->
        (filterType == null || item.typeName == filterType) &&
        (filterYear == null || item.year == filterYear) &&
        (filterArea == null || item.area == filterArea)
    }

    /** 筛选选项弹窗：第一项为「全部」。 */
    private fun showFilterPicker(title: String, options: List<String?>, current: String?, onPick: (String?) -> Unit) {
        val labels = options.map { it ?: "全部" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle(title)
            .setItems(labels) { dialog, which ->
                onPick(options[which]); dialog.dismiss()
            }.show()
    }

    // ---- 源切换与探活 ----

    private fun currentSource(): ThirdSource? = sources.firstOrNull { it.id == currentId }

    private fun switchSource(source: ThirdSource) {
        if(source.id == currentId) return
        currentId = source.id; store.setCurrent(source.id)
        categoryId = null; searchWord = null; searchDetails = emptyList(); searchIndex.clear()
        filterType = null; filterYear = null; filterArea = null
        videos.clear(); loadError = null; page = 1; hasMore = false
        categories = emptyList(); categoriesCached = false; catalogRequested = false
        resetSearchState()
        scheduleProbes(); rebuild()
    }

    /** 换源/删源后清空搜索页与分页状态，避免旧源结果残留。 */
    private fun resetSearchState() {
        main.removeCallbacks(suggestDebounce)
        searchInput = ""; suggestions = emptyList(); suggestSeq++
        searchRequested = false; searchLoading = false; searchLoadingMore = false
        searchPage = 1; searchHasMore = false; searchTotal = 0
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
                        probes[source.id] = if(result.ok) ProbeState(Dot.OK, result.total)
                            else ProbeState(Dot.DEAD, 0, result.reason)
                        capsuleDots[source.id]?.let { dot -> dot.background = rounded(dotColor(source.id), radius = 3) }
                        // 源管理页里的状态文字就地刷新，避免整页重建导致焦点跳动
                        sourceStatusViews[source.id]?.let { view ->
                            val (label, color) = probeLabel(source.id)
                            view.text = label; view.setTextColor(color)
                        }
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

    // ================= 源管理（独立页面）/ 添加 / 导入 =================

    private var sourceBatchMode = false
    private val sourceSelected = mutableSetOf<String>()
    private val sourceStatusViews = HashMap<String, TextView>()
    private val sourceCheckViews = HashMap<String, TextView>()
    private var batchActionButton: TextView? = null
    private var batchSelectAllButton: TextView? = null

    private fun openSources() {
        sourceBatchMode = false; sourceSelected.clear()
        mode = Mode.SOURCES
        rebuild()
    }

    /** 探活状态文案与颜色：与列表里的圆点一致。 */
    private fun probeLabel(id: String): Pair<String, Int> {
        val state = probes[id] ?: return "待探活" to muted
        return when(state.dot) {
            Dot.OK -> "可连接 · ${formatCount(state.total)}" to Color.rgb(76, 175, 80)
            Dot.DEAD -> (state.reason ?: "不可用") to muted
            Dot.RUNNING -> "探活中…" to Color.rgb(255, 180, 80)
        }
    }

    private fun renderSources(container: LinearLayout) {
        detachPlayerViews()
        val body = column().apply { setPadding(0, dp(2), 0, 0) }

        // 顶栏：返回 + 标题 + 批量选择
        val head = row()
        addButton(head, "‹ 返回") { onBack() }
        head.addView(text("源管理", 20f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL
        }, lp(0, dp(46)).apply { weight = 1f })
        addButton(head, if(sourceBatchMode) "退出批量" else "批量删除") {
            sourceBatchMode = !sourceBatchMode
            sourceSelected.clear()
            rebuild()
        }
        body.addView(head, lp(-1, -2).apply { bottomMargin = dp(8) })

        // 操作行
        val actions = row()
        val add = addButton(actions, "＋ 添加源") { showAddSourceDialog() }
        val importTvbox = addButton(actions, "＋ 导入配置") { showImportDialog() }
        var restoreRef: TextView? = null
        if(ThirdCms.restoreCandidates(sources).isNotEmpty()) restoreRef = addButton(actions, "恢复内置源") { restoreBuiltinSources() }
        val transfer = addButton(actions, "导入/导出配置（扫码）") { showSourceTransfer() }
        body.addView(actions, lp(-1, -2).apply { bottomMargin = dp(6) })

        // 源列表：名称 / 主机 / 探活状态 / 使用中；批量模式下点击勾选
        capsuleDots.clear(); sourceStatusViews.clear(); sourceCheckViews.clear()
        batchActionButton = null; batchSelectAllButton = null
        val listScroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val list = column()
        listScroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        val rows = mutableListOf<View>()
        sources.forEach { source ->
            val isCurrent = source.id == currentId
            val selected = source.id in sourceSelected
            val line = row().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(if(selected) TvStyle.selectedSurface else TvStyle.surface)
            }
            if(sourceBatchMode) {
                val check = text(if(selected) "✓" else "○", 16f, if(selected) TvStyle.accent else muted)
                sourceCheckViews[source.id] = check
                line.addView(check, lp(-2, -2).apply { rightMargin = dp(10) })
            }
            val dot = View(activity).apply {
                background = rounded(dotColor(source.id), radius = 3)
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(8) }
            }
            capsuleDots[source.id] = dot
            line.addView(dot)
            val nameColumn = column()
            nameColumn.addView(text(source.name + if(source.builtin) "  [内置]" else "", 15f, if(isCurrent) TvStyle.accent else TvStyle.text))
            nameColumn.addView(text(hostOf(source.api), 12f, muted))
            line.addView(nameColumn, lp(0, -2).apply { weight = 1f })
            val (label, color) = probeLabel(source.id)
            val status = text(label, 12f, color)
            sourceStatusViews[source.id] = status
            line.addView(status, lp(-2, -2).apply { rightMargin = dp(8) })
            if(isCurrent) line.addView(text("使用中", 12f, TvStyle.accent))
            focusStyle(line, selected = isCurrent || selected)
            line.setOnClickListener {
                if(sourceBatchMode) {
                    // 就地更新勾选，不重建页面，避免焦点丢失
                    if(!sourceSelected.remove(source.id)) sourceSelected += source.id
                    refreshBatchUi()
                } else switchSource(source)
            }
            list.addView(line, lp(-1, -2).apply { bottomMargin = dp(6) })
            rows += line
        }
        if(sources.isEmpty()) message(list, "暂无片源信息，可手动添加或恢复内置源")
        body.addView(listScroll, lp(-1, 0).apply { weight = 1f })

        // 底部：全选 / 批量删除 / 操作提示
        var selectAllRef: TextView? = null
        var removeRef: TextView? = null
        if(sourceBatchMode) {
            val bar = row().apply { setPadding(0, dp(6), 0, 0) }
            selectAllRef = addButton(bar, if(sourceSelected.size == sources.size) "取消全选" else "全选") {
                if(sourceSelected.size == sources.size) sourceSelected.clear()
                else sourceSelected.addAll(sources.map { it.id })
                refreshBatchUi()
            }
            removeRef = addButton(bar, "删除所选（${sourceSelected.size}）") { confirmBatchDelete() }
            removeRef?.let { it.isEnabled = sourceSelected.isNotEmpty() }
            batchSelectAllButton = selectAllRef
            batchActionButton = removeRef
            body.addView(bar)
            body.addView(text("点击条目勾选 · 「全选」一键勾选全部 · 删除前二次确认", 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        } else {
            body.addView(text("点击源条目切换使用 · 顶部「批量删除」可多选 · 上限 ${ThirdCms.MAX_SOURCES} 个", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
        }
        body.addView(text(SOURCE_DISCLAIMER, 12f, muted).apply { setPadding(0, dp(8), 0, dp(2)) })

        container.addView(body, lp(-1, 0).apply { weight = 1f })

        // 焦点：默认第一条源；上下串联，操作行与列表互通
        topFocusId = rows.firstOrNull()?.id ?: add.id
        rows.forEachIndexed { index, view ->
            view.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: add.id
            view.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: View.NO_ID
        }
        // 批量操作条：列表末尾 ↓ 进入，全选 ↔ 删除所选
        if(selectAllRef != null || removeRef != null) {
            rows.lastOrNull()?.let { last -> last.nextFocusDownId = selectAllRef?.id ?: removeRef?.id ?: View.NO_ID }
            selectAllRef?.let { all -> all.nextFocusRightId = removeRef?.id ?: View.NO_ID }
            removeRef?.let { remove ->
                remove.nextFocusLeftId = selectAllRef?.id ?: View.NO_ID
                remove.nextFocusUpId = rows.lastOrNull()?.id ?: View.NO_ID
            }
        }
        // 操作行左右串联：添加源 → 导入配置 →（恢复内置源）→ 导入/导出配置
        add.nextFocusRightId = importTvbox.id
        importTvbox.nextFocusLeftId = add.id
        val afterImport = restoreRef ?: transfer
        importTvbox.nextFocusRightId = afterImport.id
        afterImport.nextFocusLeftId = importTvbox.id
        restoreRef?.let { restore ->
            restore.nextFocusRightId = transfer.id
            transfer.nextFocusLeftId = restore.id
        }
        transfer.nextFocusRightId = View.NO_ID
        transfer.nextFocusDownId = rows.firstOrNull()?.id ?: View.NO_ID
    }

    /** 批量模式就地刷新：勾选标记、按钮文字与可用状态，不重建页面（避免焦点丢失）。 */
    private fun refreshBatchUi() {
        val count = sourceSelected.size
        sourceCheckViews.forEach { (id, check) ->
            val on = id in sourceSelected
            check.text = if(on) "✓" else "○"
            check.setTextColor(if(on) TvStyle.accent else muted)
        }
        batchActionButton?.let { button ->
            button.text = "删除所选（$count）"
            button.isEnabled = count > 0
        }
        batchSelectAllButton?.text = if(sources.isNotEmpty() && count == sources.size) "取消全选" else "全选"
    }

    /** 批量删除：选中项二次确认后移除，当前源被删则顺延到第一个剩余源。 */
    private fun confirmBatchDelete() {
        val targets = sources.filter { it.id in sourceSelected }
        if(targets.isEmpty()) return
        tvTools.confirm("批量删除源", "确定删除选中的 ${targets.size} 个源吗？删除后可通过「恢复内置源」或扫码导入找回。", null) {
            val remaining = sources.filterNot { it.id in sourceSelected }
            store.saveSources(remaining.take(ThirdCms.MAX_SOURCES))
            if(remaining.none { it.id == currentId }) store.setCurrent(remaining.firstOrNull()?.id)
            targets.forEach { probes.remove(it.id) }
            sourceSelected.clear(); sourceBatchMode = false
            categoryId = null; searchWord = null; searchDetails = emptyList(); searchIndex.clear()
            videos.clear(); loadError = null; page = 1; hasMore = false; categories = emptyList()
            categoriesCached = false; catalogRequested = false
            Toast.makeText(activity, "已删除 ${targets.size} 个源", Toast.LENGTH_SHORT).show()
            backupRequest(); rebuild()
        }
    }

    /** 打开手机扫码页：下载当前源配置，或上传配置文件（只追加、不覆盖已有源）。 */
    private fun showSourceTransfer() {
        val config = ThirdSourceConfig.encode(store.backupSources())
        tvTools.sourceTransfer(config, null) { incoming ->
            val added = store.mergeRestored(incoming)
            probes.clear()
            if(added > 0) {
                backupRequest(); rebuild()
                Toast.makeText(activity, "已追加 $added 个源（已有源保持不变）", Toast.LENGTH_LONG).show()
            } else Toast.makeText(activity, "没有需要新增的源（均已存在或已达上限）", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreBuiltinSources() {
        val missing = ThirdCms.restoreCandidates(sources)
        if(missing.isEmpty()) { Toast.makeText(activity, "内置源均已存在", Toast.LENGTH_SHORT).show(); return }
        val room = ThirdCms.MAX_SOURCES - sources.size
        if(room <= 0) { Toast.makeText(activity, "源数量已达上限（${ThirdCms.MAX_SOURCES}）", Toast.LENGTH_SHORT).show(); return }
        val restored = missing.take(room)
        // 恢复内置源属于批量写入，二次确认并同时展示免责声明
        tvTools.confirm("恢复内置源",
            "将补充 ${restored.size} 个内置源，已有源不会被覆盖。\n\n$SOURCE_DISCLAIMER", null) {
            val existApis = sources.mapTo(HashSet()) { it.api }
            val fresh = restored.filterNot { it.api in existApis }.take((ThirdCms.MAX_SOURCES - sources.size).coerceAtLeast(0))
            if(fresh.isEmpty()) { Toast.makeText(activity, "内置源均已存在", Toast.LENGTH_SHORT).show(); return@confirm }
            store.saveSources(sources + fresh)
            Toast.makeText(activity, "已恢复 ${fresh.size} 个内置源", Toast.LENGTH_SHORT).show()
            backupRequest(); rebuild()
        }
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
                            categoriesCached = false; catalogRequested = false
                            dialog.dismiss()
                            Toast.makeText(activity, "已添加「${source.name}」", Toast.LENGTH_SHORT).show()
                            backupRequest(); rebuild()
                        }
                    }
                }
            }
        }
        scan.setOnClickListener {
            // 添加源需要名称与接口地址两个值，手机页对应两个输入框
            tvTools.phoneForm(save, PhoneForm(
                heading = "添加三方片源",
                description = "填写源名称（可留空，留空自动用域名）与 CMS 接口地址，提交后自动回填到电视。",
                fields = listOf(
                    PhoneField("name", "源名称（可留空）", "例如：量子资源", 20, required = false),
                    PhoneField("api", "接口地址", "https://…/api.php/provide/vod", 300)
                ),
                doneHint = "请回到电视继续添加或保存。",
                invalidMessage = "请填写以 http 开头的接口地址"
            )) { values ->
                val api = values["api"].orEmpty()
                val name = values["name"].orEmpty()
                if(api.startsWith("http")) {
                    apiEdit.setText(api)
                    if(name.isNotEmpty()) nameEdit.setText(name)
                    nameEdit.requestFocus()
                } else errorText.text = "请输入以 http 开头的接口地址"
            }
        }
        dialog.show()
        // 不自动聚焦输入框：否则弹窗一出现就弹软键盘，想用「扫码填写」还得多按一次返回
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        save.requestFocus()
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
            // 导入配置只需一个配置链接，手机页文案与字段都要对应导入场景而不是搜索剧名
            tvTools.phoneForm(parse, PhoneForm(
                heading = "导入 TVBox 配置",
                description = "在手机浏览器里粘贴 TVBox 配置链接（明文 JSON），提交后电视会自动解析并列出可导入的 CMS 源。",
                fields = listOf(PhoneField("url", "TVBox 配置链接", "http://…/tv", 300)),
                doneHint = "请回到电视查看解析结果。",
                invalidMessage = "请填写以 http 开头的配置链接"
            )) { values ->
                val value = values["url"].orEmpty()
                if(value.startsWith("http")) { urlEdit.setText(value); runParse(value) }
                else errorText.text = "请输入以 http 开头的配置链接"
            }
        }
        dialog.show()
        // 同上：默认聚焦「解析」，不弹软键盘，方便直接走扫码填写
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        parse.requestFocus()
    }

    private fun showImportChecklist(rawCandidates: List<ThirdTvboxSite>, skipped: Int) {
        // 同一接口只出现一次；本地已有的源标为「已存在」，不参与探活与勾选
        val candidates = rawCandidates.distinctBy { it.api }
        val existingApis = sources.mapTo(HashSet()) { it.api }
        val existing = BooleanArray(candidates.size) { candidates[it].api in existingApis }
        // 默认全不勾：探活通过的源自动勾上，探活失败的保持不勾；用户手动点过的行不再被结果覆盖
        val checked = BooleanArray(candidates.size) { false }
        val touched = BooleanArray(candidates.size) { false }
        val checkViews = arrayOfNulls<TextView>(candidates.size)
        val statusViews = arrayOfNulls<TextView>(candidates.size)
        val content = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        fun paintCheck(index: Int) {
            val view = checkViews[index] ?: return
            view.text = if(checked[index]) "✓" else "○"
            view.setTextColor(if(checked[index]) TvStyle.accent else muted)
        }
        var refreshAction: () -> Unit = {}
        val rows = candidates.mapIndexed { index, site ->
            val line = row().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
            val check = text("○", 16f, muted)
            checkViews[index] = check
            val nameColumn = column()
            nameColumn.addView(text(site.name, 15f))
            nameColumn.addView(text(hostOf(site.api), 12f, muted))
            val status = text(if(existing[index]) "已存在" else "探活中…", 12f, muted)
            line.addView(check, lp(-2, -2).apply { rightMargin = dp(8) }); line.addView(nameColumn, lp(0, -2).apply { weight = 1f }); line.addView(status)
            focusStyle(line)
            line.setOnClickListener {
                if(existing[index]) return@setOnClickListener
                touched[index] = true
                checked[index] = !checked[index]
                paintCheck(index); refreshAction()
            }
            content.addView(line, lp(-1, -2).apply { bottomMargin = dp(6) })
            statusViews[index] = status
            line
        }
        content.addView(text("已自动跳过 $skipped 个不支持源 · 仅默认勾选可连接的源，已存在的源不会重复导入", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
        val dialog = AlertDialog.Builder(activity).setTitle("选择要导入的源（${candidates.size}）")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setPositiveButton("导入所选") { _,_ ->
                val selected = candidates.filterIndexed { index, _ -> checked[index] }
                importSources(selected)
            }.create()
        fun refreshActionImpl() {
            val count = checked.count { it }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
                button.text = "导入所选（$count）"
                button.isEnabled = count > 0
            }
        }
        refreshAction = { refreshActionImpl() }
        dialog.setOnShowListener { refreshAction() }
        dialog.show()
        refreshAction()
        rows.firstOrNull()?.requestFocus()
        // 逐个探活（单线程执行器天然串行），实时回填状态并自动勾选可连接的源；已存在的源跳过
        candidates.forEachIndexed { index, site ->
            if(existing[index]) return@forEachIndexed
            val gen = generation
            executor.execute {
                val probe = client.probe(site.api)
                main.post {
                    if(gen != generation) return@post
                    statusViews[index]?.let { view ->
                        view.text = if(probe.ok) "可连接 · ${formatCount(probe.total)}" else (probe.reason ?: "不可用")
                        view.setTextColor(if(probe.ok) Color.rgb(76, 175, 80) else TvStyle.muted)
                    }
                    if(!touched[index]) {
                        checked[index] = probe.ok
                        paintCheck(index); refreshAction()
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
        if(imported > 0) backupRequest()
        rebuild()
    }

    // ================= 搜索页（独立全屏：键盘 + 联想 + 历史 + 手机扫码） =================

    private var searchInputView: TextView? = null
    private var suggestHost: LinearLayout? = null
    private var suggestKeyDownTargets: List<View> = emptyList()
    private var searchKeyViews: List<View> = emptyList()

    /** 打开搜索页；clear=true 时同时清空上次结果（「重新搜索」入口）。 */
    private fun openSearch(clear: Boolean = false) {
        main.removeCallbacks(suggestDebounce)
        searchInput = if(clear) "" else searchWord.orEmpty()
        if(clear) {
            searchWord = null; searchDetails = emptyList(); searchIndex.clear()
            searchRequested = false; searchPage = 1; searchHasMore = false; searchTotal = 0
            filterType = null; filterYear = null; filterArea = null; loadError = null
        }
        suggestions = emptyList(); suggestSeq++
        mode = Mode.SEARCH
        rebuild()
        if(searchInput.isNotBlank()) scheduleSuggest()
    }

    private fun renderSearch(container: LinearLayout) {
        detachPlayerViews()
        val body = column()

        // 顶栏：返回 + 输入回显 + 手机扫码
        val head = row()
        addButton(head, "‹ 返回") { onBack() }
        val display = text(searchInput.ifEmpty { "输入片名或关键词" }, 19f, if(searchInput.isEmpty()) muted else TvStyle.text).apply {
            setPadding(dp(14), 0, dp(14), 0); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        searchInputView = display
        head.addView(display, lp(0, dp(46)).apply { weight = 1f; leftMargin = dp(6) })
        var phone: TextView? = null
        phone = addButton(head, "手机扫码输入") { phoneSearch(phone) }
        body.addView(head, lp(-1, -2).apply { bottomMargin = dp(6) })

        // 左中右三栏：历史 / 键盘 / 联想，每栏独立滚动，矮屏也不会互相挤掉
        val columns = row().apply { gravity = Gravity.TOP }

        // 左：搜索历史（最多 8 条，其余走「全部历史」）
        val history = store.searches()
        val historyViews = mutableListOf<View>()
        val historyCol = column()
        historyCol.addView(sectionTitle("搜索历史"))
        if(history.isEmpty()) historyCol.addView(text("暂无历史", 13f, muted).apply { setPadding(dp(10), dp(6), 0, 0) })
        history.take(8).forEach { word ->
            val chip = text(word, 14f).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                focusStyle(this); setOnClickListener { submitSearch(word) }
            }
            historyViews += chip
            historyCol.addView(chip, lp(-1, -2).apply { bottomMargin = dp(4) })
        }
        if(history.isNotEmpty()) {
            if(history.size > 8) historyViews += addButton(historyCol, "全部历史") { showAllHistory(history) }
            historyViews += addButton(historyCol, "清空历史") {
                tvTools.confirm("清空搜索历史", "清除本机保存的三方片源搜索词？", historyCol) { store.clearSearches(); rebuild() }
            }
        }
        columns.addView(columnScroll(historyCol), lp(0, -1).apply { weight = 0.24f })

        // 中：屏幕键盘（6 列，上下按列对齐）
        val keyViews = mutableListOf<TextView>()
        fun key(label: String, onClick: () -> Unit): TextView = text(label, 15f).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); focusStyle(this); setOnClickListener { onClick() }
        }.also { keyViews += it }
        val keyboardCol = column()
        listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789").forEach { letters ->
            val line = row().apply { setPadding(0, 0, 0, dp(4)) }
            letters.forEach { ch ->
                val letter = ch.toString().lowercase()
                line.addView(key(letter) { typeChar(letter) }, lp(0, -2).apply { weight = 1f; rightMargin = dp(4) })
            }
            keyboardCol.addView(line)
        }
        val lastRow = row().apply { setPadding(0, 0, 0, dp(4)) }
        val del = key("删除") { if(searchInput.isNotEmpty()) { searchInput = searchInput.dropLast(1); refreshSearchInput(); scheduleSuggest() } }
        val clear = key("清空") { searchInput = ""; refreshSearchInput(); suggestions = emptyList(); suggestSeq++; renderSuggestions() }
        val go = key("搜索") { submitSearch(searchInput) }
        lastRow.addView(del, lp(0, -2).apply { weight = 2f; rightMargin = dp(4) })
        lastRow.addView(clear, lp(0, -2).apply { weight = 2f; rightMargin = dp(4) })
        lastRow.addView(go, lp(0, -2).apply { weight = 2f })
        keyboardCol.addView(lastRow)
        columns.addView(columnScroll(keyboardCol), lp(0, -1).apply { weight = 0.5f; leftMargin = dp(12); rightMargin = dp(12) })
        suggestKeyDownTargets = listOf(del, clear, go)

        // 右：联想词
        val host = column()
        val suggestCol = column().apply { addView(sectionTitle("联想")); addView(host) }
        suggestHost = host
        columns.addView(columnScroll(suggestCol), lp(0, -1).apply { weight = 0.26f })

        body.addView(columns, lp(-1, 0).apply { weight = 1f })
        container.addView(body, lp(-1, 0).apply { weight = 1f })

        // 焦点：默认键盘首键；上下按 6 列对齐，左列 ↔ 历史、右列 ↔ 联想
        searchKeyViews = keyViews
        topFocusId = keyViews.firstOrNull()?.id ?: View.NO_ID
        keyViews.forEachIndexed { index, view ->
            view.nextFocusUpId = keyViews.getOrNull(index - 6)?.id ?: View.NO_ID
            view.nextFocusDownId = keyViews.getOrNull(index + 6)?.id ?: View.NO_ID
            if(index < 36 && index % 6 == 0) {
                view.nextFocusLeftId = historyViews.getOrNull(index / 6)?.id ?: historyViews.lastOrNull()?.id ?: View.NO_ID
            }
        }
        historyViews.forEachIndexed { index, view ->
            view.nextFocusRightId = keyViews.getOrNull(index * 6)?.id ?: keyViews.firstOrNull()?.id ?: View.NO_ID
            view.nextFocusUpId = historyViews.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusDownId = historyViews.getOrNull(index + 1)?.id ?: View.NO_ID
        }
        renderSuggestions()
    }

    /** 纵向可滚容器：三栏内容超出屏幕时各自滚动，不挤压其它栏。 */
    private fun columnScroll(content: LinearLayout): ScrollView =
        ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }

    /** 只重建联想区：输入过程中不重建整页，避免焦点从键盘跳走。 */
    private fun renderSuggestions() {
        val host = suggestHost ?: return
        host.removeAllViews()
        val views = mutableListOf<View>()
        if(suggestions.isEmpty()) {
            host.addView(text(if(searchInput.isBlank()) "输入关键词后显示联想词" else "没有联想词，可直接按「搜索」", 13f, muted)
                .apply { setPadding(dp(10), dp(6), 0, dp(8)) })
        } else {
            suggestions.forEach { name ->
                val item = text(name, 15f).apply {
                    setPadding(dp(12), dp(8), dp(12), dp(8)); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    focusStyle(this); setOnClickListener { submitSearch(name) }
                }
                views += item
                host.addView(item, lp(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
        // 跨栏接线：键盘最右列 ↔ 联想同排，功能键 ↓ 进第一条联想
        searchKeyViews.forEachIndexed { index, keyView ->
            if(index < 36 && index % 6 == 5) {
                keyView.nextFocusRightId = views.getOrNull(index / 6)?.id ?: views.firstOrNull()?.id ?: View.NO_ID
            }
        }
        views.forEachIndexed { index, view ->
            view.nextFocusLeftId = searchKeyViews.getOrNull((index % 6) * 6 + 5)?.id ?: View.NO_ID
            view.nextFocusUpId = views.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusDownId = views.getOrNull(index + 1)?.id ?: View.NO_ID
        }
        suggestKeyDownTargets.forEach { it.nextFocusDownId = views.firstOrNull()?.id ?: View.NO_ID }
    }

    private fun typeChar(ch: String) {
        if(searchInput.length >= 40) return
        searchInput += ch
        refreshSearchInput(); scheduleSuggest()
    }

    private fun refreshSearchInput() {
        val view = searchInputView ?: return
        view.text = searchInput.ifEmpty { "输入片名或关键词" }
        view.setTextColor(if(searchInput.isEmpty()) muted else TvStyle.text)
    }

    private fun scheduleSuggest() {
        main.removeCallbacks(suggestDebounce)
        main.postDelayed(suggestDebounce, 300)
    }

    /** 联想请求：300ms 防抖 + 序号守卫（只接受最新输入的结果），接口不支持或失败时静默为空。 */
    private fun runSuggest() {
        val source = currentSource() ?: return
        val word = searchInput.trim()
        val seq = ++suggestSeq
        if(word.isEmpty()) { suggestions = emptyList(); renderSuggestions(); return }
        executor.execute {
            val list = client.suggest(source.api, word)
            main.post {
                if(seq != suggestSeq) return@post
                suggestions = list
                renderSuggestions()
            }
        }
    }

    /** 提交搜索：切回浏览态展示结果并写入搜索历史。 */
    private fun submitSearch(word: String) {
        val clean = word.trim()
        if(clean.isEmpty() || currentSource() == null) return
        main.removeCallbacks(suggestDebounce)
        searchWord = clean; searchInput = clean
        store.rememberSearch(clean)
        searchDetails = emptyList(); searchIndex.clear()
        searchPage = 1; searchHasMore = false; searchTotal = 0
        searchRequested = false; searchLoading = false; searchLoadingMore = false
        filterType = null; filterYear = null; filterArea = null; loadError = null
        categoryId = null
        mode = Mode.BROWSE
        rebuild()
    }

    private fun phoneSearch(anchor: View?) {
        tvTools.phoneForm(anchor, PhoneForm(
            heading = "搜索片源",
            description = "输入片名或关键词，提交后电视会立即按当前片源搜索。",
            fields = listOf(PhoneField("query", "片名或关键词", "例如：爱情", 40)),
            doneHint = "请回到电视查看搜索结果。",
            invalidMessage = "请输入 1—40 个字符的关键词"
        )) { values ->
            val word = values["query"].orEmpty()
            if(word.isNotBlank()) submitSearch(word)
        }
    }

    private fun showAllHistory(history: List<String>) {
        AlertDialog.Builder(activity).setTitle("搜索历史（${history.size}）")
            .setItems(history.toTypedArray()) { dialog, which -> dialog.dismiss(); submitSearch(history[which]) }
            .setNegativeButton("取消", null).show()
    }

    private fun sectionTitle(label: String) = text(label, 13f, muted).apply { setPadding(0, dp(6), 0, dp(4)) }

    // ================= 详情态 =================

    private fun openDetail(data: ThirdDetail) {
        detail = data
        synopsisExpanded = false
        val progress = store.progress(currentId.orEmpty(), data.video.id)
        detailGroup = progress?.group?.takeIf { data.groups.getOrNull(it) != null } ?: ThirdCms.defaultGroupIndex(data.groups)
        playGroup = detailGroup; playEpisode = progress?.episode ?: 0
        mode = Mode.DETAIL
        rebuild()
    }

    private fun renderDetail(container: LinearLayout) {
        val data = detail ?: run { mode = Mode.BROWSE; renderBrowse(container); return }
        val body = column()

        // 顶栏：返回按钮与片名同行、垂直居中对齐
        val head = row()
        val back = addButton(head, "‹ 返回") { onBack() }
        head.addView(text(data.video.name, 20f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), 0, 0, 0)
        }, lp(0, -2).apply { weight = 1f; gravity = Gravity.CENTER_VERTICAL })
        body.addView(head, lp(-1, -2).apply { bottomMargin = dp(8) })

        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val content = column()
        scroll.addView(content)

        // 信息区：海报（小尺寸）+ 右侧文案顶部对齐
        val infoRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val poster = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TvStyle.badgeSurface) }
        artwork.load(poster, data.video.cover)
        infoRow.addView(poster, lp(dp(150), (dp(150) / TvStyle.POSTER_ASPECT).toInt()))

        val right = column()
        val titleRow = row()
        titleRow.addView(text(data.video.name, 20f).apply { setTypeface(null, android.graphics.Typeface.BOLD) }, lp(-2, -2))
        if(data.video.remarks.isNotBlank()) titleRow.addView(text(data.video.remarks, 11f, Color.WHITE).apply {
            setPadding(dp(6), dp(3), dp(6), dp(3)); background = rounded(TvStyle.badgeSurface, radius = 6)
        }, lp(-2, -2).apply { leftMargin = dp(8) })
        right.addView(titleRow)
        val episodeCount = data.groups.getOrNull(detailGroup)?.second?.size ?: 0
        right.addView(text(listOfNotNull(data.typeName, data.year, data.area, "共 $episodeCount 集").joinToString(" · "), 13f, muted).apply { setPadding(0, dp(6), 0, dp(6)) })
        right.addView(text("来源：${currentSource()?.name.orEmpty()}", 12f, muted).apply { setPadding(0, 0, 0, dp(6)) })
        // 简介：默认 3 行折叠，文案多时提供展开/收起
        val fullSynopsis = data.synopsis ?: "暂无简介"
        val synopsis = text(fullSynopsis, 13f, muted).apply {
            maxLines = if(synopsisExpanded) Int.MAX_VALUE else 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        right.addView(synopsis)
        var toggle: TextView? = null
        toggle = text(if(synopsisExpanded) "收起 ▲" else "展开 ▼", 12f, TvStyle.accent).apply {
            setPadding(0, dp(4), 0, 0); visibility = View.GONE
            focusStyle(this)
            setOnClickListener {
                synopsisExpanded = !synopsisExpanded
                synopsis.maxLines = if(synopsisExpanded) Int.MAX_VALUE else 3
                this.text = if(synopsisExpanded) "收起 ▲" else "展开 ▼"
            }
        }
        // 仅当文案折叠后被截断时才显示展开按钮
        synopsis.post {
            toggle.visibility = if(synopsis.lineCount > 3 || synopsisExpanded) View.VISIBLE else View.GONE
        }
        right.addView(toggle)
        infoRow.addView(right, lp(0, -2).apply { weight = 1f; leftMargin = dp(14) })
        content.addView(infoRow, lp(-1, -2).apply { bottomMargin = dp(10) })

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

        body.addView(scroll, lp(-1, 0).apply { weight = 1f })
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        topFocusId = mainFocus?.id ?: back.id
        mainFocus?.nextFocusUpId = back.id
        mainFocus?.requestFocus()
    }

    private fun renderDetailRefresh() {
        if(mode == Mode.DETAIL) rebuild()
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
        widthDp() >= 1100 -> 7; widthDp() >= 900 -> 6; widthDp() >= 700 -> 5; else -> 4
    }
    private fun episodeCols(): Int = when {
        widthDp() >= 1100 -> 7; widthDp() >= 900 -> 6; widthDp() >= 700 -> 5; else -> 4
    }
}
