// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.ThirdSourceConfig
import java.util.concurrent.Executors

/** 三方片源页：自成体系的「店中店」——浏览/详情/播放全部独立，与红果内容体系零交集。
 *  生命周期由 MainActivity 委托：show/onBack/onKey/pause/stop/release。 */
// 播放器用到 media3 未稳定 API（PlayerView/AspectRatioFrameLayout/SeekParameters），整类显式接受
@androidx.annotation.OptIn(UnstableApi::class)
class ThirdSourceScreen(private val activity: Activity, private val rebuild: () -> Unit, private val backupRequest: () -> Unit = {}) {
    private enum class Mode { BROWSE, SEARCH, SOURCES, DETAIL, PLAY, HISTORY, FAVORITE }
    private enum class Dot { RUNNING, OK, DEAD }

    private companion object {
        /** 三方片源免责声明：空态与源管理页常驻展示。 */
        const val SOURCE_DISCLAIMER = "第三方 CMS 接口由用户自行添加，仅为技术交流与学习用途，应用不提供、不存储、不传播任何视频内容，内容合法性由对应源站负责。"
        /** 聚合搜索单次最多参与的源数：当前源优先，独立线程池并发，避免被慢源拖成多轮。 */
        const val AGGREGATE_LIMIT = 6
        /** 探活缓存有效期：30 分钟内的结果直接复用，冷启动不再全量请求。 */
        const val PROBE_TTL_MS = 30 * 60_000L
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
    /** 聚合搜索独立并发：同轮所有源同时发出，慢源不会挤占浏览/详情请求的线程。 */
    private val searchExecutor = Executors.newFixedThreadPool(AGGREGATE_LIMIT)
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
    // ---- 观看历史 ----
    private var historyEntries: List<ThirdHistoryEntry> = emptyList()
    private var historyVideos: List<ThirdVideo> = emptyList()
    private var lastHistoryKey = ""
    private var lastHistoryAt = 0L
    /** 观看历史触发的备份节流：最多 5 分钟一次，避免播放中反复整包写盘。 */
    private var lastHistoryBackupAt = 0L
    /** 详情页返回目标：历史/收藏/浏览，避免从二级页面返回时落到浏览页。 */
    private var detailReturnMode = Mode.BROWSE
    /** 浏览态网格里最近聚焦的卡片（显示用视频 id）：进详情/播放返回时把焦点恢复到这张卡片上。 */
    private var browseFocusKey: String? = null
    // 本次浏览会话是否已发起过列表请求：空结果分类若再自动重发会形成「请求→空→rebuild→再请求」
    // 死循环，界面持续重建吞掉按键（表现为遥控器卡死）
    private var catalogRequested = false
    private var loadError: String? = null
    private var searchWord: String? = null
    private var searchDetails: List<ThirdDetail> = emptyList()
    private val searchIndex = HashMap<String, ThirdDetail>()
    // 聚合搜索：命中保留来源源；网格用「源id|影片id」复合键，点击时反查回命中详情
    private var searchHits: List<ThirdSearchHit> = emptyList()
    private val searchHitByVideoId = HashMap<String, ThirdSearchHit>()
    private var searchAggregating = false
    private var searchAggregate = false
    private var searchSourcesDone = 0
    private var searchSourceTotal = 0
    private val searchFailures = HashMap<String, String>()
    private var searchSeq = 0
    private var searchAllOverride = false
    private var searchStatusView: TextView? = null
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
    /** 详情来自哪个源：聚合搜索结果/收藏/换源后不再等于当前浏览源。 */
    private var detailSource: ThirdSource? = null
    private var detailGroup = 0
    private var synopsisExpanded = false

    // ---- 收藏态 ----
    private var favoriteEntries: List<ThirdFavorite> = emptyList()
    private var favoriteVideos: List<ThirdVideo> = emptyList()

    // ---- 播放态 ----
    private var player: ExoPlayer? = null
    private var playerHost: FrameLayout? = null
    private var playerView: PlayerView? = null
    private var playLoading: TextView? = null
    private var playErrorText: TextView? = null
    private var overlay: View? = null
    private var progressFill: View? = null
    private var progressThumb: View? = null
    private var progressBubble: TextView? = null
    private var seekBarView: View? = null
    private var playTime: TextView? = null
    private var overlayVisible = false
    // 平滑快进快退：按住左右键时虚拟位置按节奏推进并实时渲染关键帧预览，松开才精确定位
    private var scrubbing = false
    /** ExoPlayer 最近一次带宽估计（bps）：缓冲提示用它显示实时网速。 */
    private var lastBandwidthBps = 0L
    private var scrubDirection = 0
    private var scrubPositionMs = 0L
    private var scrubStartAt = 0L
    private var playGroup = 0
    private var playEpisode = 0
    private var pendingStartMs = 0L
    private var pendingAutoplay = true
    private var hasPendingPlay = false
    /** 当前集已自动尝试过的线路，避免播放失败时反复重建播放器。 */
    private val autoFallbackGroups = mutableSetOf<Int>()
    private var lastPositionMs = -1L
    private val hideOverlayRun = Runnable { hideOverlay() }
    private val saveRun = Runnable { saveProgressNow(); scheduleSave() }
    private val uiTick = Runnable { updatePlayUi() }
    /** 平滑快进快退节奏器：步长随按住时长爬坡（起步 5 秒、每秒加 10 秒、封顶 60 秒）。 */
    private val scrubTick = object : Runnable {
        override fun run() {
            val instance = player
            if(!scrubbing || instance == null) return
            val heldMs = android.os.SystemClock.uptimeMillis() - scrubStartAt
            val step = (5_000L + heldMs / 1_000L * 10_000L).coerceAtMost(60_000L)
            val duration = instance.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            scrubPositionMs = (scrubPositionMs + scrubDirection * step).coerceIn(0L, duration)
            instance.seekTo(scrubPositionMs)
            updatePlayUi()
            main.postDelayed(this, 180L)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // 缓冲中给出「正在缓冲 + 实时网速」提示，避免慢网卡顿时画面静止却没有任何反馈
            if(state == Player.STATE_BUFFERING && mode == Mode.PLAY && !scrubbing) showBuffering()
            if(state == Player.STATE_READY) { playLoading?.visibility = View.GONE; updatePlayUi() }
            if(state == Player.STATE_ENDED) { keepScreenOn(false); showOverlay() }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { keepScreenOn(isPlaying) }
        override fun onPlayerError(error: PlaybackException) {
            // 线路失效时先自动换下一条线路，全部试过才提示手动换集
            val data = detail
            val next = data?.groups?.indices?.firstOrNull { index ->
                index != playGroup && index !in autoFallbackGroups && data.groups[index].second.isNotEmpty()
            }
            if(mode == Mode.PLAY && next != null) {
                autoFallbackGroups += playGroup
                val position = player?.currentPosition ?: 0L
                pendingStartMs = position.coerceAtLeast(0L)
                pendingAutoplay = true
                hasPendingPlay = true
                playGroup = next
                playEpisode = playEpisode.coerceAtMost((data?.groups?.getOrNull(next)?.second?.size ?: 1) - 1)
                Toast.makeText(activity, "播放失败，已自动切换到「${data?.groups?.getOrNull(next)?.first.orEmpty()}」", Toast.LENGTH_SHORT).show()
                // 不在错误回调里直接重建：先让回调返回，下一帧再释放旧的播放器实例
                main.post { rebuild() }
            } else {
                playErrorText?.text = "该集播放失败，可换一集或切换线路"; showOverlay()
            }
        }
    }

    /** 播放期间保持屏幕常亮，避免电视空闲十几分钟后弹出系统屏保；暂停/结束/退出即释放。 */
    private fun keepScreenOn(on: Boolean) {
        if(on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 缓冲提示文案：已知带宽时附上实时网速，让用户直观判断是源慢还是本地网络问题。 */
    private fun bufferText(): String {
        if(lastBandwidthBps <= 0) return "正在缓冲…"
        val kbs = lastBandwidthBps / 8 / 1024
        val speed = if(kbs >= 1024) String.format("%.1f MB/s", kbs / 1024f) else "$kbs KB/s"
        return "正在缓冲 · $speed"
    }

    private fun showBuffering() {
        playLoading?.let { it.text = bufferText(); it.visibility = View.VISIBLE }
    }

    // ================= 生命周期 =================

    fun show(container: LinearLayout) {
        generation++; artwork.cancelPage()
        sources = store.ensureSources()
        currentId = store.currentId()?.takeIf { id -> sources.any { it.id == id } }
            ?: sources.firstOrNull()?.also { store.setCurrent(it.id) }?.id
        scheduleProbes()
        when {
            mode == Mode.PLAY && detail != null && detailSource != null -> {
                if(hasPendingPlay) { attachPlayer(pendingStartMs, pendingAutoplay); hasPendingPlay = false }
                else { attachPlayer(lastPositionMs.coerceAtLeast(0), false); lastPositionMs = -1L }
            }
            mode == Mode.DETAIL && detail != null -> renderDetail(container)
            mode == Mode.SEARCH -> renderSearch(container)
            mode == Mode.SOURCES -> renderSources(container)
            mode == Mode.HISTORY -> renderHistory(container)
            mode == Mode.FAVORITE -> renderFavorites(container)
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
        Mode.DETAIL -> {
            val back = detailReturnMode
            detailReturnMode = Mode.BROWSE; mode = back; rebuild(); true
        }
        Mode.SEARCH -> { mode = Mode.BROWSE; rebuild(); true }
        Mode.SOURCES -> { sourceBatchMode = false; sourceSelected.clear(); mode = Mode.BROWSE; rebuild(); true }
        Mode.HISTORY -> { mode = Mode.BROWSE; rebuild(); true }
        Mode.FAVORITE -> { mode = Mode.BROWSE; rebuild(); true }
        // 搜索结果页返回搜索页，方便改关键词；普通浏览交还 MainActivity
        Mode.BROWSE -> if(searchWord != null) {
            suggestions = emptyList(); suggestSeq++; mode = Mode.SEARCH; rebuild(); scheduleSuggest(); true
        } else false
    }

    /** 播放页按键接管；浏览态只接管网格上下翻行（RecyclerView 在固定高度容器里不会为焦点自动滚动）。 */
    fun onKey(event: KeyEvent): Boolean {
        if(event.action == KeyEvent.ACTION_DOWN && (mode == Mode.BROWSE || mode == Mode.HISTORY || mode == Mode.FAVORITE) && browseGridKey(event.keyCode)) return true
        // 历史/收藏页：菜单键删除当前聚焦的记录（遥控器长按行为不一致，这里用显式按键）
        if(event.action == KeyEvent.ACTION_DOWN && mode == Mode.HISTORY && event.keyCode == KeyEvent.KEYCODE_MENU) { deleteFocusedHistory(); return true }
        if(event.action == KeyEvent.ACTION_DOWN && mode == Mode.FAVORITE && event.keyCode == KeyEvent.KEYCODE_MENU) { deleteFocusedFavorite(); return true }
        if(mode != Mode.PLAY || player == null) return false
        val key = event.keyCode
        // 平滑快进快退：覆盖层未展开或焦点在进度条上时，按住左右键持续预览，松开落点
        if(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val direction = if(key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            if(event.action == KeyEvent.ACTION_UP) {
                if(scrubbing) { endScrub(); return true }
                return false
            }
            if(event.action != KeyEvent.ACTION_DOWN) return false
            val onSeekBar = activity.currentFocus?.tag == "seekbar"
            if(!overlayVisible || onSeekBar) {
                if(event.repeatCount == 0) startScrub(direction)
                return true
            }
            if(scrubbing) endScrub()
            return false   // 覆盖层按钮焦点上：左右键走原生焦点导航
        }
        if(event.action != KeyEvent.ACTION_DOWN) return false
        if(scrubbing) endScrub()
        val focusInOverlay = overlayButtons.any { it.hasFocus() } || activity.currentFocus?.tag == "seekbar"
        if(overlayVisible && focusInOverlay) {
            return false   // 控制层内其余按键走原生焦点导航
        }
        return when(key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if(overlayVisible) { togglePlay(); true } else { showOverlay(); true }
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

    fun pause() { player?.pause(); saveProgressNow(); keepScreenOn(false) }

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
        var historyRef: TextView? = null
        var favoriteRef: TextView? = null
        if(searchWord == null) {
            val sourceRow = row()
            val capsuleScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false }
            val capsules = row(); capsuleDots.clear()
            capsuleViews = sources.mapIndexed { index, source -> sourceCapsule(source, index) }
            capsuleViews.forEach { view -> capsules.addView(view, lp(-2, -2).apply { rightMargin = dp(8) }) }
            sourceButtons = capsuleViews
            capsuleScroll.addView(capsules)
            sourceRow.addView(capsuleScroll, lp(0, -2).apply { weight = 1f })
            historyRef = addButton(sourceRow, "观看历史") { openHistory() }
            favoriteRef = addButton(sourceRow, "收藏") { openFavorites() }
            manage = addButton(sourceRow, "管理") { openSources() }
            body.addView(sourceRow)

            probeSummary = text(probeSummaryText(), 12f, muted).apply { setPadding(0, dp(5), 0, dp(5)) }
            body.addView(probeSummary)
        } else sourceButtons = emptyList()

        // 分类 + 搜索：浏览态合为一行（分类居左、搜索居右上），搜索态只保留「重新搜索」
        var categoryButtons: List<TextView> = emptyList()
        var filterButtons: List<View> = emptyList()
        var clearRef: TextView? = null
        val searchRow = row()
        val search: TextView
        if(searchWord == null) {
            // 分类入口：分类多时单行横滑要按很多次右键，改为弹窗网格一屏直选
            val options = listOf(ThirdCategory("", "全部")) + categories
            val currentName = options.firstOrNull { it.id.ifEmpty { null } == categoryId }?.name ?: "全部"
            val categoryChip = text("分类：$currentName ▾", 14f, if(categoryId != null) TvStyle.accent else TvStyle.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                focusStyle(this, selected = categoryId != null)
                setOnClickListener { showCategoryPicker() }
            }
            categoryButtons = listOf(categoryChip)
            searchRow.addView(categoryChip, lp(-2, -2))
            // 撑开左右两端：空白 View 若用 WRAP_CONTENT 会在 AT_MOST 下占满整行高度，这里固定 1dp
            searchRow.addView(View(activity), lp(0, dp(1)).apply { weight = 1f })
            search = text("搜索", 14f, TvStyle.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(16), dp(8))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
                compoundDrawablePadding = dp(6)
                setCompoundDrawableTintList(ColorStateList.valueOf(TvStyle.accent))
                focusStyle(this)
                setOnClickListener { openSearch() }
            }
            searchRow.addView(search, lp(-2, -2))
            body.addView(searchRow, lp(-1, -2))
        } else {
            search = addButton(searchRow, "重新搜索") { openSearch(clear = true) }
            clearRef = search
            body.addView(searchRow, lp(-1, -2))
            // 搜索态：结果统计 + 搜索范围 + 类型/年份/地区筛选
            searchStatusView = text(searchSummaryText(), 13f).apply { setPadding(0, dp(6), 0, dp(2)) }
            body.addView(searchStatusView)
            val filterRow = row().apply { setPadding(0, 0, 0, dp(4)) }
            val typeOptions = searchDetails.mapNotNull { it.typeName }.distinct().sorted()
            val yearOptions = searchDetails.mapNotNull { it.year }.distinct().sortedDescending()
            val areaOptions = searchDetails.mapNotNull { it.area }.distinct().sorted()
            val filterCapsules = mutableListOf<View>()
            // 搜索范围：聚合全部源 / 仅当前源（选择记忆在本机）
            filterCapsules += text(if(store.searchAllSources) "范围：全部源" else "范围：仅当前源", 13f,
                if(store.searchAllSources) TvStyle.accent else TvStyle.text).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6)); focusStyle(this, selected = store.searchAllSources)
                setOnClickListener {
                    store.searchAllSources = !store.searchAllSources
                    searchAllOverride = false
                    searchDetails = emptyList(); searchHits = emptyList(); searchHitByVideoId.clear()
                    searchLoading = false; searchRequested = false; searchAggregating = false; loadError = null
                    rebuild()
                }
            }
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
                searchWord != null && searchAggregating -> "正在聚合搜索「$searchWord」…（${searchSourcesDone}/${searchSourceTotal} 个源已返回）"
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
        // 焦点归属：从详情返回→上次聚焦的网格卡片；搜索结果→首张卡片；选中的分类→该分类；空态→「全部」；
        // 其余→当前使用的源胶囊（点源 tab 切换后不要把焦点甩回第一个胶囊）
        val restorePosition = browseFocusKey?.let { key -> display.indexOfFirst { it.id == key } } ?: -1
        val emptyBrowse = display.isEmpty() && !loading && searchWord == null && loadError == null
        val selectedCategory = if(searchWord == null && categoryId != null) categoryButtons.firstOrNull() else null
        val currentCapsule = capsuleViews.getOrNull(sources.indexOfFirst { it.id == currentId })
        topFocusId = when {
            restorePosition >= 0 && gridRecycler != null -> gridRecycler!!.id
            selectedCategory != null -> selectedCategory.id
            searchWord != null && gridRecycler != null -> gridRecycler!!.id
            searchWord != null -> search.id
            emptyBrowse && categoryButtons.isNotEmpty() -> categoryButtons[0].id
            currentCapsule != null -> currentCapsule.id
            else -> capsuleViews.firstOrNull()?.id ?: search.id
        }
        // 网格是 RecyclerView，卡片要等布局绑定后才存在：先滚动到目标位置，下一帧再把焦点落到那张卡片
        if(restorePosition >= 0) gridRecycler?.let { recycler ->
            recycler.post {
                (recycler.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(restorePosition, 0)
                recycler.post { recycler.findViewHolderForAdapterPosition(restorePosition)?.itemView?.requestFocus() }
            }
        }
        // 焦点接线：源胶囊 ↓ → 分类（搜索态为重新搜索）；管理 ↓ → 右上角搜索；分类/搜索 ↓ → 网格
        val rowAbove = currentCapsule?.id ?: historyRef?.id ?: favoriteRef?.id ?: manage?.id ?: View.NO_ID
        gridRecycler?.id?.let { gridId ->
            val browseRow = categoryButtons.firstOrNull()?.id
            val secondRow = browseRow ?: filterButtons.firstOrNull()?.id ?: gridId
            capsuleViews.forEach { it.nextFocusDownId = browseRow ?: search.id }
            historyRef?.nextFocusDownId = browseRow ?: search.id
            favoriteRef?.nextFocusDownId = browseRow ?: search.id
            manage?.nextFocusDownId = search.id
            search.nextFocusDownId = if(browseRow != null) gridId else secondRow
            clearRef?.nextFocusDownId = secondRow
            categoryButtons.forEach { it.nextFocusDownId = gridId }
            filterButtons.forEach { it.nextFocusDownId = gridId; it.nextFocusUpId = search.id }
        }
        categoryButtons.forEach { chip ->
            chip.nextFocusUpId = rowAbove
            chip.nextFocusRightId = search.id
        }
        // 搜索入口在右上角，↑ 优先回到正上方的「管理」
        search.nextFocusUpId = manage?.id ?: rowAbove
        search.nextFocusLeftId = categoryButtons.firstOrNull()?.id ?: manage?.id ?: favoriteRef?.id ?: historyRef?.id ?: View.NO_ID
        historyRef?.let { button ->
            button.nextFocusRightId = favoriteRef?.id ?: manage?.id ?: View.NO_ID
        }
        favoriteRef?.let { button ->
            button.nextFocusLeftId = historyRef?.id ?: View.NO_ID
            button.nextFocusRightId = manage?.id ?: View.NO_ID
        }
        manage?.let { button ->
            button.nextFocusLeftId = favoriteRef?.id ?: historyRef?.id ?: View.NO_ID
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
        message(body, "暂无片源信息，请添加源或导入配置")
        body.addView(text("若重装后没有自动恢复：可能是本机备份文件不存在或未授予存储权限，可在「设置 → 本地备份与恢复」中查看。", 13f, muted)
            .apply { setPadding(0, dp(2), 0, dp(10)) })
        // 空态同样给全入口：添加 / 导入配置 / 扫码导入导出
        val actions = row().apply { setPadding(0, dp(4), 0, 0) }
        val add = addButton(actions, "＋ 添加源") { showAddSourceDialog() }
        val importTvbox = addButton(actions, "＋ 导入配置") { showImportDialog() }
        val transfer = addButton(actions, "导入/导出配置（扫码）") { showSourceTransfer() }
        add.nextFocusRightId = importTvbox.id
        importTvbox.nextFocusLeftId = add.id; importTvbox.nextFocusRightId = transfer.id
        transfer.nextFocusLeftId = importTvbox.id
        body.addView(actions)
        body.addView(text(SOURCE_DISCLAIMER, 12f, muted).apply { setPadding(0, dp(20), 0, 0) })
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        topFocusId = add.id; add.requestFocus()
    }

    private fun mountGrid(body: LinearLayout, onOpen: ((ThirdVideo) -> Unit)? = null) {
        val cols = browseCols()
        val adapter = GridAdapter(cols, { currentDisplay() }, onOpen)
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

    /** 当前结果集：浏览态用列表缓存，搜索态用筛选后的聚合结果。adapter 每次取最新，追加不必重建控件。 */
    private fun currentDisplay(): List<ThirdVideo> = when {
        mode == Mode.HISTORY -> historyVideos
        mode == Mode.FAVORITE -> favoriteVideos
        searchWord != null -> filteredSearchHits().map { hit ->
            // 聚合结果用「源id|影片id」复合键，角标带源名；同源单搜时角标保持原备注
            val name = hit.source.name
            val remarks = if(searchAggregate) listOf(name, hit.detail.video.remarks).filter { it.isNotBlank() }.joinToString(" · ")
                else hit.detail.video.remarks
            hit.detail.video.copy(id = compositeVideoId(hit.source, hit.detail.video.id), remarks = remarks)
        }
        else -> videos
    }

    private fun compositeVideoId(source: ThirdSource, videoId: String) = source.id + "|" + videoId

    /** 应用筛选后的聚合命中（筛选条件与详情列表保持一致）。 */
    private fun filteredSearchHits(): List<ThirdSearchHit> = searchHits.filter { hit ->
        (filterType == null || hit.detail.typeName == filterType) &&
        (filterYear == null || hit.detail.year == filterYear) &&
        (filterArea == null || hit.detail.area == filterArea)
    }

    private inner class GridAdapter(private val cols: Int, private val source: () -> List<ThirdVideo>,
                                    private val onOpen: ((ThirdVideo) -> Unit)? = null) : RecyclerView.Adapter<GridAdapter.Holder>() {
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
                if(focused && mode == Mode.BROWSE) browseFocusKey = item.id
                if(focused && position >= items.size - 3) maybeLoadMore()
            }
            holder.card.setOnClickListener { onOpen?.invoke(item) ?: onVideoClick(item) }
            if(position >= items.size - 12) maybeLoadMore()
        }
    }

    private fun onVideoClick(video: ThirdVideo) {
        detailReturnMode = Mode.BROWSE
        if(searchWord != null) {
            searchHitByVideoId[video.id]?.let { openDetail(it.detail, it.source); return }
            searchIndex[video.id]?.let { openDetail(it); return }
        }
        val source = currentSource() ?: return
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

    // ================= 观看历史 =================

    private fun openHistory() { mode = Mode.HISTORY; rebuild() }

    private fun historyKey(entry: ThirdHistoryEntry) = entry.sourceApi + "|" + entry.videoId

    private fun renderHistory(container: LinearLayout) {
        detachPlayerViews()
        historyEntries = store.history()
        val aliveApis = sources.mapTo(HashSet()) { it.api }
        historyVideos = historyEntries.map { entry ->
            val badge = when {
                entry.sourceApi !in aliveApis -> "源已移除"
                entry.completed -> "已看完"
                else -> entry.episodeLabel.ifBlank { "第 ${entry.episodeIndex + 1} 集" } +
                    if(entry.positionMs > 0) " · " + formatTime(entry.positionMs) else ""
            }
            ThirdVideo(historyKey(entry), entry.title.ifBlank { "未命名影片" }, entry.cover, badge)
        }
        val body = column().apply { setPadding(0, dp(4), 0, 0) }
        val head = row()
        val back = addButton(head, "‹ 返回") { onBack() }
        head.addView(text("观看历史  ·  ${historyEntries.size} 条", 20f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(8), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL
        }, lp(0, dp(46)).apply { weight = 1f })
        if(historyEntries.isNotEmpty()) addButton(head, "清空历史") { confirmClearHistory() }
        body.addView(head, lp(-1, -2).apply { bottomMargin = dp(6) })
        if(historyEntries.isEmpty()) {
            message(body, "暂无观看记录，去片源里挑一部吧")
            body.addView(text("看过的影片会出现在这里，点开即可续播。", 12f, muted))
        } else {
            body.addView(text("按菜单键删除当前记录 · 源被删除可重新添加，影片失效可按片名重搜", 12f, muted).apply {
                setPadding(0, 0, 0, dp(4))
            })
        }
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        if(historyEntries.isEmpty()) { topFocusId = back.id; back.requestFocus(); return }
        mountGrid(body) { video -> historyEntries.firstOrNull { historyKey(it) == video.id }?.let { openHistoryEntry(it) } }
        topFocusId = gridRecycler?.id ?: back.id
    }

    /** 历史卡片点击：源被删可重新添加；影片失效可在本源按片名重搜。 */
    private fun openHistoryEntry(entry: ThirdHistoryEntry) {
        val source = sources.firstOrNull { it.api == entry.sourceApi }
        if(source == null) {
            tvTools.choose("源已移除", listOf("重新添加此源", "删除这条记录"), null, { choice ->
                if(choice == 0) readdHistorySource(entry) else deleteHistory(entry)
            })
            return
        }
        if(source.id != currentId) {
            currentId = source.id; store.setCurrent(source.id)
            resetBrowseState()
        }
        // 已看完的记录不再从片尾续播
        if(entry.completed) store.clearProgress(source.id, entry.videoId)
        val gen = generation
        executor.execute {
            val result = client.detail(source.api, entry.videoId)
            main.post {
                if(gen != generation) return@post
                if(result != null) { detailReturnMode = Mode.HISTORY; openDetail(result, source) } else showHistoryMissing(entry, source)
            }
        }
    }

    private fun showHistoryMissing(entry: ThirdHistoryEntry, source: ThirdSource) {
        val dead = probes[source.id]?.dot == Dot.DEAD
        val title = if(dead) "源暂时连不上" else "影片可能已下架"
        val message = if(dead) "「${source.name}」当前无法连接，可在全部片源中按片名重新搜索。"
            else "在「${source.name}」找不到这部影片，可能已下架或更换了编号；可在全部片源中按片名重新搜索。"
        tvTools.choose("$title\n$message", listOf("在全部片源搜索", "删除这条记录"), null, { choice ->
            if(choice == 0) searchHistoryTitle(entry) else deleteHistory(entry)
        })
    }

    /** 历史失效兜底：用全部源重新聚合搜索，避免用户还要手动画源。 */
    private fun searchHistoryTitle(entry: ThirdHistoryEntry) {
        val title = entry.title.trim()
        if(title.isEmpty() || currentSource() == null) return
        submitSearch(title, allSources = true)
    }

    private fun readdHistorySource(entry: ThirdHistoryEntry) = readdSource(entry.sourceApi, entry.sourceName)

    /** 历史/收藏引用的源被删除后，按原接口重新加回源列表。 */
    private fun readdSource(api: String, name: String) {
        if(sources.size >= ThirdCms.MAX_SOURCES) {
            Toast.makeText(activity, "源数量已达上限（${ThirdCms.MAX_SOURCES}）", Toast.LENGTH_SHORT).show(); return
        }
        if(sources.any { it.api == api }) { rebuild(); return }
        val source = ThirdSource("u" + api.hashCode().toUInt().toString(), name.ifBlank { hostOf(api) }, api)
        store.saveSources(sources + source)
        backupRequest()
        Toast.makeText(activity, "已重新添加「${source.name}」", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    private fun deleteHistory(entry: ThirdHistoryEntry) {
        store.removeHistory(entry.sourceApi, entry.videoId)
        sources.firstOrNull { it.api == entry.sourceApi }?.let { store.clearProgress(it.id, entry.videoId) }
        backupRequest()
        Toast.makeText(activity, "已删除这条记录", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    private fun deleteFocusedHistory() {
        val recycler = gridRecycler ?: return
        val focused = recycler.findFocus() ?: return
        val position = recycler.getChildAdapterPosition(focused)
        val entry = historyEntries.getOrNull(position) ?: return
        tvTools.confirm("删除观看记录", "从观看历史中移除《${entry.title}》？播放进度也会一起清除。", focused) {
            deleteHistory(entry)
        }
    }

    private fun confirmClearHistory() {
        tvTools.confirm("清空观看历史", "将删除全部 ${historyEntries.size} 条观看记录，不影响片源配置。", null) {
            store.clearHistory()
            backupRequest()
            Toast.makeText(activity, "观看历史已清空", Toast.LENGTH_SHORT).show()
            rebuild()
        }
    }

    // ================= 片源内收藏 =================

    private fun openFavorites() { mode = Mode.FAVORITE; rebuild() }

    private fun favoriteKey(entry: ThirdFavorite) = entry.sourceApi + "|" + entry.videoId

    private fun renderFavorites(container: LinearLayout) {
        detachPlayerViews()
        favoriteEntries = store.favorites()
        val aliveApis = sources.mapTo(HashSet()) { it.api }
        favoriteVideos = favoriteEntries.map { entry ->
            val badge = if(entry.sourceApi !in aliveApis) "源已移除"
            else listOf(entry.sourceName.ifBlank { hostOf(entry.sourceApi) },
                entry.episodeLabel.ifBlank { "第 ${entry.episodeIndex + 1} 集" }).filter { it.isNotBlank() }.joinToString(" · ")
            ThirdVideo(favoriteKey(entry), entry.title.ifBlank { "未命名影片" }, entry.cover, badge)
        }
        val body = column().apply { setPadding(0, dp(4), 0, 0) }
        val head = row()
        val back = addButton(head, "‹ 返回") { onBack() }
        head.addView(text("我的收藏  ·  ${favoriteEntries.size} 条", 20f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(8), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL
        }, lp(0, dp(46)).apply { weight = 1f })
        body.addView(head, lp(-1, -2).apply { bottomMargin = dp(6) })
        if(favoriteEntries.isEmpty()) {
            message(body, "还没有收藏，去片源详情页点「收藏」吧")
            body.addView(text("收藏只记录影片与来源片源，随主备份一起迁移。", 12f, muted))
        } else {
            body.addView(text("按菜单键取消收藏 · 源被删除可重新添加，影片失效可按片名重搜", 12f, muted).apply {
                setPadding(0, 0, 0, dp(4))
            })
        }
        container.addView(body, lp(-1, 0).apply { weight = 1f })
        if(favoriteEntries.isEmpty()) { topFocusId = back.id; back.requestFocus(); return }
        mountGrid(body) { video -> favoriteEntries.firstOrNull { favoriteKey(it) == video.id }?.let { openFavoriteEntry(it) } }
        topFocusId = gridRecycler?.id ?: back.id
    }

    /** 收藏卡片点击：源被删可重新添加，影片失效可按片名在全部源重搜。 */
    private fun openFavoriteEntry(entry: ThirdFavorite) {
        val source = sources.firstOrNull { it.api == entry.sourceApi }
        if(source == null) {
            tvTools.choose("源已移除", listOf("重新添加此源", "取消收藏"), null, { choice ->
                if(choice == 0) readdSource(entry.sourceApi, entry.sourceName) else deleteFavorite(entry)
            })
            return
        }
        val gen = generation
        executor.execute {
            val result = client.detail(source.api, entry.videoId)
            main.post {
                if(gen != generation) return@post
                if(result != null) { detailReturnMode = Mode.FAVORITE; openDetail(result, source) }
                else tvTools.choose("影片可能已下架", listOf("在全部片源搜索", "取消收藏"), null, { choice ->
                    if(choice == 0) submitSearch(entry.title, allSources = true) else deleteFavorite(entry)
                })
            }
        }
    }

    private fun deleteFavorite(entry: ThirdFavorite) {
        store.removeFavorite(entry.sourceApi, entry.videoId)
        backupRequest()
        Toast.makeText(activity, "已取消收藏", Toast.LENGTH_SHORT).show()
        if(mode == Mode.FAVORITE) rebuild() else renderDetailRefresh()
    }

    private fun deleteFocusedFavorite() {
        val recycler = gridRecycler ?: return
        val focused = recycler.findFocus() ?: return
        val position = recycler.getChildAdapterPosition(focused)
        val entry = favoriteEntries.getOrNull(position) ?: return
        tvTools.confirm("取消收藏", "将《${entry.title}》从收藏中移除？", focused) { deleteFavorite(entry) }
    }

    private fun maybeLoadMore() {
        if(mode == Mode.HISTORY || mode == Mode.FAVORITE) return
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

    /** 搜索请求（ac=detail&wd=）：reset 并发聚合各源第 1 页；「仅当前源」时支持向下翻页。 */
    private fun requestSearch(reset: Boolean) {
        val current = currentSource() ?: return
        val word = searchWord ?: return
        if(!reset) { requestSearchMore(current, word); return }
        if(searchLoading) return
        // 新搜索先取消上一轮在途请求，避免旧源结果串入新关键字
        client.cancelSearch()
        val seq = ++searchSeq
        searchLoading = true; searchRequested = true; searchLoadingMore = false
        searchDetails = emptyList(); searchHits = emptyList(); searchHitByVideoId.clear(); searchIndex.clear()
        searchFailures.clear()
        searchHasMore = false; searchTotal = 0; searchPage = 1
        loadError = null
        searchSourcesDone = 0
        val aggregate = searchAllOverride || store.searchAllSources
        val candidates = if(aggregate) {
            // 当前源优先，跳过已知不可用的源，最多并发聚合 6 个（复用 3 线程池）
            val others = sources.filter { it.id != current.id && probes[it.id]?.dot != Dot.DEAD }
            (listOf(current) + others).take(AGGREGATE_LIMIT)
        } else listOf(current)
        searchAggregate = candidates.size > 1
        searchSourceTotal = candidates.size
        searchAggregating = searchAggregate && candidates.size > 1
        var firstArrival = true
        candidates.forEach { source ->
            searchExecutor.execute {
                val result = client.searchPage(source.api, word, 1)
                main.post {
                    // 不用 generation：首个结果到达会 rebuild 一次，generation 随之变化，
                    // 若继续用它守卫，其余源的后续结果会被全部丢弃（状态永远停在 1/N）。
                    // 这里以序号 + 关键词为准，换源/重新搜索都会先改 searchWord。
                    if(seq != searchSeq || searchWord != word) return@post
                    searchSourcesDone++
                    val pageData = result.page
                    if(pageData == null) searchFailures[source.api] = result.error ?: "连接失败"
                    else {
                        val fresh = pageData.details.map { ThirdSearchHit(source, it) }
                        searchHits = ThirdCms.dedupeHits(searchHits + fresh, current.api)
                        searchDetails = searchHits.map { it.detail }
                        searchHitByVideoId.clear()
                        searchHits.forEach { searchHitByVideoId[compositeVideoId(it.source, it.detail.video.id)] = it }
                        if(candidates.size == 1) {
                            searchHasMore = pageData.hasMore; searchTotal = pageData.total; searchPage = 1
                        }
                    }
                    val finished = searchSourcesDone >= candidates.size
                    searchLoading = !finished
                    if(finished) {
                        searchAggregating = false
                        if(searchHits.isEmpty()) loadError = searchFailures.values.firstOrNull()?.let { "所有片源均未返回结果（$it）" }
                            ?: "连接超时或接口无响应"
                    }
                    // 首个结果到达才建网格；后续结果只刷新 adapter，避免整页重建抢走焦点
                    if(firstArrival || (gridAdapter == null && searchDetails.isNotEmpty())) { firstArrival = false; rebuild() }
                    else {
                        searchStatusView?.text = searchSummaryText()
                        gridAdapter?.submit(-1)
                        gridStatus?.text = gridStatusText()
                    }
                }
            }
        }
    }

    /** 「仅当前源」分页追加。 */
    private fun requestSearchMore(source: ThirdSource, word: String) {
        if(searchLoadingMore || !searchHasMore) return
        searchLoadingMore = true
        val seq = searchSeq
        val requestPage = searchPage + 1
        executor.execute {
            val result = client.searchPage(source.api, word, requestPage)
            main.post {
                if(seq != searchSeq || searchWord != word) return@post
                searchLoadingMore = false
                val pageData = result.page
                if(pageData != null) {
                    val known = searchHits.mapTo(HashSet()) { it.detail.video.id }
                    val fresh = pageData.details.filter { it.video.id !in known }.map { ThirdSearchHit(source, it) }
                    val insertAt = searchDetails.size
                    searchHits = searchHits + fresh
                    searchDetails = searchHits.map { it.detail }
                    fresh.forEach { searchHitByVideoId[compositeVideoId(it.source, it.detail.video.id)] = it }
                    searchHasMore = pageData.hasMore; searchTotal = pageData.total; searchPage = requestPage
                    gridAdapter?.submit(insertAt)
                    searchStatusView?.text = searchSummaryText()
                    gridStatus?.text = gridStatusText()
                } else gridStatus?.text = "加载更多失败，继续下滑可重试"
            }
        }
    }

    /** 搜索结果顶栏文案：聚合模式展示已返回源数与失败源数。 */
    private fun searchSummaryText(): String {
        val word = searchWord.orEmpty()
        val filtered = filteredSearchDetails().size
        return when {
            searchLoading && searchAggregate -> "正在聚合搜索「$word」…（${searchSourcesDone}/${searchSourceTotal} 个源已返回）"
            searchLoading -> "正在搜索「$word」…"
            searchAggregate -> buildString {
                append("搜索「$word」 · 聚合 ${searchSourceTotal} 个源 · 共 ${searchDetails.size} 条")
                if(searchFailures.isNotEmpty()) append("（${searchFailures.size} 个源无响应）")
                if(filtered != searchDetails.size) append(" · 筛选后 $filtered 条")
            }
            searchDetails.isEmpty() && loadError != null -> "搜索「$word」失败 · ${searchFailures.size} 个源无响应"
            searchDetails.isEmpty() -> "搜索「$word」 · 暂无结果"
            searchTotal > searchDetails.size -> "搜索「$word」 · $filtered/${searchDetails.size} 条（共 $searchTotal 条）"
            else -> "搜索「$word」 · $filtered/${searchDetails.size} 条"
        }
    }

    private fun gridStatusText(): String = when {
        searchWord != null -> when {
            searchAggregating -> "正在聚合搜索…（${searchSourcesDone}/${searchSourceTotal} 个源已返回）"
            searchLoadingMore -> "正在加载更多…"
            searchDetails.isEmpty() -> ""
            searchAggregate -> buildString {
                append("聚合 ${searchSourceTotal} 个源 · 共 ${searchDetails.size} 条")
                if(searchFailures.isNotEmpty()) append(" · ${searchFailures.size} 个源无响应")
            }
            searchHasMore -> "继续下滑加载更多"
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

    /** 分类选择：网格弹窗一屏铺开，避免长分类条逐个横移；当前分类获得焦点并自动滚到可见位置。 */
    private fun showCategoryPicker() {
        if(categories.isEmpty()) { Toast.makeText(activity, "分类还在加载，稍后再试", Toast.LENGTH_SHORT).show(); return }
        val options = listOf(ThirdCategory("", "全部")) + categories
        val cols = 5
        val buttons = mutableListOf<TextView>()
        val content = column().apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
        lateinit var dialog: AlertDialog
        options.chunked(cols).forEach { chunk ->
            val line = row()
            chunk.forEach { option ->
                val id = option.id.ifEmpty { null }
                val chip = text(option.name, 15f, if(id == categoryId) TvStyle.accent else TvStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(9), dp(10), dp(9)); minHeight = dp(40)
                    focusStyle(this, selected = id == categoryId)
                    setOnClickListener { dialog?.dismiss(); pickCategory(id) }
                }
                buttons += chip
                line.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            }
            content.addView(line)
        }
        buttons.forEachIndexed { index, button ->
            button.nextFocusLeftId = buttons[if(index % cols == 0) index else index - 1].id
            button.nextFocusRightId = buttons[if(index % cols == cols - 1 || index == buttons.lastIndex) index else index + 1].id
            button.nextFocusUpId = if(index < cols) button.id else buttons[index - cols].id
            button.nextFocusDownId = if(index + cols <= buttons.lastIndex) buttons[index + cols].id else button.id
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        dialog = AlertDialog.Builder(activity).setTitle("选择分类").setView(scroll)
            .setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.68).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            // 末行按 ↓ 落到「取消」，避免焦点困在网格里
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            buttons.forEach { button -> if(button.nextFocusDownId == button.id) button.nextFocusDownId = cancel.id }
            val currentIndex = options.indexOfFirst { it.id.ifEmpty { null } == categoryId }.coerceAtLeast(0)
            scroll.post {
                buttons.getOrNull(currentIndex)?.let { button ->
                    button.requestFocus()
                    scroll.requestChildRectangleOnScreen(button, android.graphics.Rect(0, 0, button.width, button.height), false)
                }
            }
        }
        dialog.show()
    }

    private fun pickCategory(id: String?) {
        if(id == categoryId) return
        categoryId = id; videos.clear(); loadError = null; page = 1; hasMore = false
        catalogRequested = false; rebuild()
    }

    // ---- 源切换与探活 ----

    private fun currentSource(): ThirdSource? = sources.firstOrNull { it.id == currentId }

    private fun switchSource(source: ThirdSource) {
        if(source.id == currentId) return
        currentId = source.id; store.setCurrent(source.id)
        resetBrowseState()
        scheduleProbes(); rebuild()
    }

    /** 统一清理浏览与搜索残留：换源、删源、从历史/收藏跨源打开时复用。 */
    private fun resetBrowseState() {
        categoryId = null; searchWord = null
        searchDetails = emptyList(); searchHits = emptyList(); searchHitByVideoId.clear(); searchIndex.clear()
        searchAggregate = false; searchAggregating = false; searchAllOverride = false
        searchSourcesDone = 0; searchSourceTotal = 0; searchFailures.clear()
        filterType = null; filterYear = null; filterArea = null
        videos.clear(); loadError = null; page = 1; hasMore = false
        categories = emptyList(); categoriesCached = false; catalogRequested = false
        resetSearchState()
    }

    /** 换源/删源后清空搜索页与分页状态，避免旧源结果残留。 */
    private fun resetSearchState() {
        main.removeCallbacks(suggestDebounce)
        searchInput = ""; suggestions = emptyList(); suggestSeq++
        searchRequested = false; searchLoading = false; searchLoadingMore = false; searchAggregating = false
        searchPage = 1; searchHasMore = false; searchTotal = 0
    }

    private fun scheduleProbes() {
        val cached = store.probes()
        val now = System.currentTimeMillis()
        sources.forEach { source ->
            // 30 分钟内的探活结果直接复用，冷启动不再全量请求
            cached[source.api]?.takeIf { now - it.at in 0..PROBE_TTL_MS }?.let { record ->
                probes[source.id] = if(record.ok) ProbeState(Dot.OK, record.total)
                    else ProbeState(Dot.DEAD, 0, record.reason)
            }
            if(probes[source.id] == null) {
                probes[source.id] = ProbeState(Dot.RUNNING, 0)
                val gen = generation
                probeExecutor.execute {
                    val result = client.probe(source.api)
                    store.saveProbe(source.api, ThirdProbeRecord(result.ok, result.total, result.reason, System.currentTimeMillis()))
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
            nameColumn.addView(text(source.name, 15f, if(isCurrent) TvStyle.accent else TvStyle.text))
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
        if(sources.isEmpty()) message(list, "暂无片源信息，请添加源或导入配置")
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
        // 操作行左右串联：添加源 → 导入配置 → 导入/导出配置
        add.nextFocusRightId = importTvbox.id
        importTvbox.nextFocusLeftId = add.id
        importTvbox.nextFocusRightId = transfer.id
        transfer.nextFocusLeftId = importTvbox.id
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
        tvTools.confirm("批量删除源", "确定删除选中的 ${targets.size} 个源吗？删除后可通过导入配置或扫码导入找回。", null) {
            val remaining = sources.filterNot { it.id in sourceSelected }
            store.saveSources(remaining.take(ThirdCms.MAX_SOURCES))
            if(remaining.none { it.id == currentId }) store.setCurrent(remaining.firstOrNull()?.id)
            targets.forEach { probes.remove(it.id) }
            sourceSelected.clear(); sourceBatchMode = false
            resetBrowseState()
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
                            val source = ThirdSource(id, name.ifEmpty { hostOf(api) }, api)
                            store.saveSources(sources + source); store.setCurrent(source.id)
                            probes[source.id] = ProbeState(Dot.OK, probe.total)
                            resetBrowseState()
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
                    added += ThirdSource(id, site.name, site.api)
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
            searchWord = null; searchDetails = emptyList(); searchHits = emptyList()
            searchHitByVideoId.clear(); searchIndex.clear()
            searchRequested = false; searchPage = 1; searchHasMore = false; searchTotal = 0
            searchAggregate = false; searchAggregating = false; searchAllOverride = false
            searchSourcesDone = 0; searchSourceTotal = 0; searchFailures.clear()
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

    /** 提交搜索：切回浏览态展示结果并写入搜索历史；allSources 用于历史/收藏失效后的全源重搜。 */
    private fun submitSearch(word: String, allSources: Boolean = false) {
        val clean = word.trim()
        if(clean.isEmpty() || currentSource() == null) return
        main.removeCallbacks(suggestDebounce)
        searchWord = clean; searchInput = clean
        store.rememberSearch(clean)
        searchAllOverride = allSources
        searchDetails = emptyList(); searchHits = emptyList(); searchHitByVideoId.clear(); searchIndex.clear()
        searchPage = 1; searchHasMore = false; searchTotal = 0
        searchRequested = false; searchLoading = false; searchLoadingMore = false
        searchAggregate = false; searchAggregating = false; searchSourcesDone = 0; searchSourceTotal = 0
        searchFailures.clear()
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

    private fun openDetail(data: ThirdDetail, source: ThirdSource? = null) {
        detail = data
        detailSource = source ?: currentSource()
        synopsisExpanded = false
        val progress = store.progress(detailSource?.id.orEmpty(), data.video.id)
        detailGroup = progress?.group?.takeIf { data.groups.getOrNull(it) != null } ?: ThirdCms.defaultGroupIndex(data.groups)
        playGroup = detailGroup; playEpisode = progress?.episode ?: 0
        autoFallbackGroups.clear()
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
        val sourceRow = row()
        sourceRow.addView(text("来源：${detailSource?.name.orEmpty()}", 12f, muted), lp(-2, -2))
        if(sources.size > 1) sourceRow.addView(text("换源 ›", 12f, TvStyle.accent).apply {
            // 文字在胶囊内水平垂直居中，避免默认左上对齐
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4)); focusStyle(this)
            setOnClickListener { showSourceSwitcher(data) }
        }, lp(-2, -2).apply { leftMargin = dp(4) })
        right.addView(sourceRow)
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

        // 主按钮：有进度默认继续播放并持焦点；收藏与换源同排
        val progress = store.progress(detailSource?.id.orEmpty(), data.video.id)
        val mainActions = row().apply { setPadding(0, 0, 0, dp(10)) }
        var mainFocus: TextView? = null
        if(progress != null && data.groups.getOrNull(progress.group)?.second?.getOrNull(progress.episode) != null) {
            mainFocus = addButton(mainActions, "继续播放 第${progress.episode + 1}集") {
                playEpisode(progress.group, progress.episode, progress.positionMs)
            }
        }
        val start = addButton(mainActions, if(progress != null) "从头再看" else "从第01集开始") { playEpisode(detailGroup, 0) }
        if(mainFocus == null) mainFocus = start
        val favorited = detailSource?.let { store.isFavorite(it.api, data.video.id) } == true
        val favoriteButton = addButton(mainActions, if(favorited) "已收藏" else "收藏") { toggleDetailFavorite(data) }
        if(mainFocus !== start) mainFocus?.nextFocusRightId = start.id
        start.nextFocusRightId = favoriteButton.id
        favoriteButton.nextFocusLeftId = start.id
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

    /** 详情页收藏/取消收藏：只记录影片与来源源，随主备份迁移。 */
    private fun toggleDetailFavorite(data: ThirdDetail) {
        val source = detailSource ?: return
        val progress = store.progress(source.id, data.video.id)
        val group = progress?.group?.takeIf { data.groups.getOrNull(it) != null } ?: ThirdCms.defaultGroupIndex(data.groups)
        val episode = progress?.episode ?: 0
        val label = data.groups.getOrNull(group)?.second?.getOrNull(episode)?.label.orEmpty()
        val added = store.toggleFavorite(ThirdFavorite(source.api, source.name, data.video.id, data.video.name, data.video.cover,
            episode, label, progress?.positionMs ?: 0, System.currentTimeMillis()))
        backupRequest()
        Toast.makeText(activity, if(added) "已收藏《${data.video.name}》" else "已取消收藏", Toast.LENGTH_SHORT).show()
        renderDetailRefresh()
    }

    // ---- 详情页换源 ----

    /** 换源弹窗：列出当前源之外的所有源，连接正常的排在前面。 */
    private fun showSourceSwitcher(data: ThirdDetail) {
        val ordered = sources.filter { it.api != detailSource?.api }
            .sortedBy { if(probes[it.id]?.dot == Dot.DEAD) 1 else 0 }
        if(ordered.isEmpty()) { Toast.makeText(activity, "没有其它片源可切换", Toast.LENGTH_SHORT).show(); return }
        tvTools.choose("换源播放《${data.video.name}》", ordered.map { it.name }, null, { choice ->
            ordered.getOrNull(choice)?.let { target -> switchDetailSource(data, target) }
        })
    }

    /** 在目标源按片名重找同一部影片，并尽量保留当前集号；目标源已有进度时优先续播。 */
    private fun switchDetailSource(data: ThirdDetail, target: ThirdSource) {
        val gen = generation
        val episode = playEpisode
        Toast.makeText(activity, "正在「${target.name}」查找《${data.video.name}》…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = client.searchPage(target.api, data.video.name, 1)
            main.post {
                if(gen != generation) return@post
                val key = ThirdCms.searchKey(data.video.name, data.year)
                val page = result.page
                val matched = page?.details?.firstOrNull { ThirdCms.searchKey(it.video.name, it.year) == key }
                    ?: page?.details?.firstOrNull()
                if(matched == null) {
                    Toast.makeText(activity, "「${target.name}」暂未找到这部影片", Toast.LENGTH_SHORT).show()
                    return@post
                }
                openDetail(matched, target)
                // 目标源没有进度时沿用当前集号，越界则落在最后一集
                if(store.progress(target.id, matched.video.id) == null) {
                    val size = matched.groups.getOrNull(detailGroup)?.second?.size ?: 0
                    if(size > 0) { playEpisode = episode.coerceAtMost(size - 1); renderDetailRefresh() }
                }
                Toast.makeText(activity, "已切换到「${target.name}」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class EpisodeAdapter(private val episodes: List<ThirdEpisode>) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(val view: TextView) : RecyclerView.ViewHolder(view)
        private val progressEpisode = store.progress(detailSource?.id.orEmpty(), detail?.video?.id.orEmpty())

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
        autoFallbackGroups.clear()
        mode = Mode.PLAY; playGroup = group; playEpisode = episode
        pendingStartMs = startMs; pendingAutoplay = true; hasPendingPlay = true
        rebuild()
    }

    private fun attachPlayer(startMs: Long, autoplay: Boolean) {
        detachPlayerViews()
        // 换集/重建时先停掉进行中的快进预览，并释放可能残存的旧实例，避免音频串音
        scrubbing = false; main.removeCallbacks(scrubTick); lastBandwidthBps = 0
        player?.let { stale -> stale.removeListener(playerListener); stale.release() }
        player = null
        val data = detail ?: return
        val group = data.groups.getOrNull(playGroup) ?: return
        val episode = group.second.getOrNull(playEpisode) ?: return
        // 不透明黑底：盖住下层目录导航栏，避免 SurfaceView 打孔区透出导航
        val host = FrameLayout(activity).apply { isFocusable = false; setBackgroundColor(Color.BLACK) }
        val view = PlayerView(activity).apply { useController = false; resizeMode = resizeModeValue(store.resizeMode) }
        playerView = view
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        // 加载/缓冲提示用白色：播放器黑底上灰色文字看不清
        val loading = text("正在加载…", 15f, Color.WHITE).apply { gravity = Gravity.CENTER }
        val errorView = text("", 14f, TvStyle.accent).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        val center = column().apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        center.addView(loading); center.addView(errorView)
        host.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playLoading = loading; playErrorText = errorView

        val instance = ExoPlayer.Builder(activity).build()
        instance.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        view.player = instance
        instance.addListener(playerListener)
        // 带宽估计回调：刷新缓冲提示里的实时网速
        instance.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                lastBandwidthBps = bitrateEstimate
                if(playLoading?.visibility == View.VISIBLE) showBuffering()
            }
        })
        instance.setMediaItem(MediaItem.fromUri(episode.url), startMs.coerceAtLeast(0L))
        instance.prepare()
        instance.setPlaybackSpeed(store.playbackSpeed)
        // 从头播放时应用片头跳过；续播不动，避免把用户拽回已看位置
        val startMark = store.skipMark(skipKey(data.video.id))
        if(startMs <= 0L && (startMark?.intro ?: 0) > 0) {
            instance.seekTo(startMark!!.intro * 1000L)
            Toast.makeText(activity, "已跳过片头 ${startMark.intro} 秒", Toast.LENGTH_SHORT).show()
        }
        outroTriggered = false
        main.removeCallbacks(outroTick)
        main.postDelayed(outroTick, 1_000L)
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
        topBar.addView(text("${detailSource?.name.orEmpty()} · $groupName", 12f, muted))
        topBar.visibility = View.GONE
        host.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // 底部控制层：上一集 / 下一集 / 返回详情 + 进度条 + 时间
        val bottom = column().apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(dp(14), dp(10), dp(14), dp(12)) }
        // 进度条聚焦时不做整框高亮：轨道加粗、端点出现滑块圆点、上方浮出时间气泡
        val track = FrameLayout(activity).apply {
            background = rounded(Color.argb(70, 255, 255, 255), radius = 6); clipChildren = false
        }
        val fill = View(activity).apply { background = rounded(TvStyle.accent, radius = 6) }
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        val thumb = View(activity).apply {
            background = rounded(TvStyle.accent, Color.WHITE, 12); visibility = View.GONE
        }
        track.addView(thumb, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.START or Gravity.CENTER_VERTICAL))
        // 气泡用 INVISIBLE 而非 GONE：占位不变，聚焦/失焦时进度条高度不跳动
        val bubble = text("", 13f).apply {
            background = rounded(Color.argb(210, 0, 0, 0), radius = 8)
            setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = View.INVISIBLE
        }
        // 进度条本身可聚焦：聚焦后左右键按住平滑拖动进度
        val seekBar = FrameLayout(activity).apply {
            id = View.generateViewId(); isFocusable = true; tag = "seekbar"; setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        seekBar.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        seekBar.addView(track, FrameLayout.LayoutParams(-1, dp(4), Gravity.CENTER_VERTICAL))
        seekBar.setOnFocusChangeListener { _, focused ->
            (track.layoutParams as FrameLayout.LayoutParams).height = dp(if(focused) 8 else 4)
            track.requestLayout()
            thumb.visibility = if(focused) View.VISIBLE else View.GONE
            bubble.visibility = if(focused) View.VISIBLE else View.INVISIBLE
            if(focused) updatePlayUi()
        }
        bottom.addView(seekBar, lp(-1, -2))
        val controls = row().apply { setPadding(0, dp(8), 0, 0) }
        overlayButtons.clear()
        val prev = addButton(controls, "上一集") { jumpEpisode(-1) }
        val rewind = addButton(controls, "快退") { seek(-10_000L) }
        val forward = addButton(controls, "快进") { seek(10_000L) }
        lateinit var speed: TextView
        speed = addButton(controls, "倍速 ${PlaybackSpeed.label(store.playbackSpeed)}") { cycleSpeed(speed) }
        lateinit var ratio: TextView
        ratio = addButton(controls, "比例 ${resizeModeLabel(store.resizeMode)}") { cycleResize(ratio) }
        lateinit var skip: TextView
        skip = addButton(controls, "跳过") { showSkipDialog(skip) }
        val next = addButton(controls, "下一集") { jumpEpisode(1) }
        val back = addButton(controls, "返回详情") { onBack() }
        val time = text("", 13f)
        controls.addView(time, lp(0, -2).apply { weight = 1f; gravity = Gravity.END; leftMargin = dp(10) })
        bottom.addView(controls)
        bottom.visibility = View.GONE
        host.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        overlay = bottom; overlayTopView = topBar; progressFill = fill; progressTrackView = track
        progressThumb = thumb; progressBubble = bubble; seekBarView = seekBar; playTime = time
        val chain = listOf(prev, rewind, forward, speed, ratio, skip, next, back)
        chain.forEachIndexed { index, view ->
            view.nextFocusRightId = chain.getOrNull(index + 1)?.id ?: View.NO_ID
            view.nextFocusLeftId = chain.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusUpId = seekBar.id
        }
        seekBar.nextFocusDownId = prev.id
        seekBar.nextFocusLeftId = seekBar.id
        seekBar.nextFocusRightId = seekBar.id
        overlayButtons += chain
    }

    /** 倍速循环切换并记忆：0.75× → 1× → 1.25× → 1.5× → 1.75× → 2×。 */
    private fun cycleSpeed(button: TextView) {
        val options = PlaybackSpeed.options
        val index = options.indexOf(store.playbackSpeed).takeIf { it >= 0 } ?: options.indexOf(1f)
        val next = options[(index + 1) % options.size]
        store.playbackSpeed = next
        player?.setPlaybackSpeed(next)
        button.text = "倍速 ${PlaybackSpeed.label(next)}"
        Toast.makeText(activity, "播放速度 ${PlaybackSpeed.label(next)}", Toast.LENGTH_SHORT).show()
    }

    /** 画面比例循环：适应(原始比例) → 拉伸(满屏) → 裁剪(去黑边)，本机记忆。 */
    private fun cycleResize(button: TextView) {
        val next = (store.resizeMode + 1) % 3
        store.resizeMode = next
        playerView?.resizeMode = resizeModeValue(next)
        button.text = "比例 ${resizeModeLabel(next)}"
        Toast.makeText(activity, "画面比例：${resizeModeLabel(next)}", Toast.LENGTH_SHORT).show()
    }

    private fun resizeModeLabel(mode: Int) = when(mode) { 1 -> "拉伸"; 2 -> "裁剪"; else -> "适应" }
    private fun resizeModeValue(mode: Int) = when(mode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    /** 开始平滑快进/快退：暂停声音、只跳关键帧做画面预览，由节奏器按爬坡步长推进。 */
    private fun startScrub(direction: Int) {
        val instance = player ?: return
        if(scrubbing) {
            if(scrubDirection != direction) { scrubDirection = direction; scrubStartAt = android.os.SystemClock.uptimeMillis() }
            return
        }
        scrubbing = true
        scrubDirection = direction
        scrubPositionMs = instance.currentPosition.coerceAtLeast(0L)
        scrubStartAt = android.os.SystemClock.uptimeMillis()
        instance.pause()
        // 预览阶段只定位关键帧：雷鸟鹏7这类低端电视上解码开销最小，画面跟手
        instance.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        if(!overlayVisible) showOverlay(focusButtons = false)
        main.removeCallbacks(hideOverlayRun)   // 按住期间不收起控制层
        main.postDelayed(scrubTick, 180L)
    }

    /** 松开按键：回到精确定位落点，恢复播放与控制层自动收起。 */
    private fun endScrub() {
        if(!scrubbing) return
        scrubbing = false
        main.removeCallbacks(scrubTick)
        val instance = player ?: return
        instance.setSeekParameters(SeekParameters.DEFAULT)
        val duration = instance.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        instance.seekTo(scrubPositionMs.coerceIn(0L, duration))
        // 松开即恢复播放：预览前的暂停只是实现细节，用户预期松开继续看
        instance.play()
        // 焦点放回进度条，方便继续微调或下移到按钮
        seekBarView?.requestFocus()
        main.removeCallbacks(hideOverlayRun); main.postDelayed(hideOverlayRun, 6_000L)
        updatePlayUi()
    }

    /** 片头片尾设置的存储键：源接口 + 影片，换源后各自独立。 */
    private fun skipKey(videoId: String) = detailSource?.api.orEmpty() + "|" + videoId

    private fun showSkipDialog(anchor: View) {
        val videoId = detail?.video?.id ?: return
        val key = skipKey(videoId)
        val mark = store.skipMark(key) ?: ThirdSkipMark(0, 0)
        tvTools.choose("跳过片头片尾 · 仅当前影片", listOf(
            "片头  ${if(mark.intro > 0) "${mark.intro} 秒" else "关闭"}",
            "片尾  ${if(mark.outro > 0) "${mark.outro} 秒" else "关闭"}",
            "关闭全部"
        ), anchor, { choice ->
            when(choice) {
                0 -> pickSkipSeconds(anchor, "跳过片头") { seconds ->
                    store.setSkipMark(key, ThirdSkipMark(seconds, mark.outro))
                    if(seconds > 0) skipToIntro(seconds)
                }
                1 -> pickSkipSeconds(anchor, "跳过片尾") { seconds ->
                    store.setSkipMark(key, ThirdSkipMark(mark.intro, seconds))
                    outroTriggered = false
                    Toast.makeText(activity, if(seconds > 0) "片尾将跳过最后 $seconds 秒" else "已关闭片尾跳过", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    store.setSkipMark(key, null)
                    Toast.makeText(activity, "已关闭片头片尾跳过", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun pickSkipSeconds(anchor: View, title: String, onPick: (Int) -> Unit) {
        val options = listOf(0, 30, 60, 90, 120)
        tvTools.choose("$title · 仅当前影片", options.map { if(it == 0) "关闭" else "$it 秒" }, anchor, { index ->
            onPick(options.getOrElse(index) { 0 })
        })
    }

    /** 设置片头后立刻生效：当前播放位置还在片头范围内时直接跳到新位置。 */
    private fun skipToIntro(seconds: Int) {
        val instance = player ?: return
        val target = seconds * 1000L
        if(instance.currentPosition < target) instance.seekTo(target)
        Toast.makeText(activity, "已跳过片头 $seconds 秒", Toast.LENGTH_SHORT).show()
    }

    /** 每秒检查一次片尾：到达片尾窗口且正在播放时自动跳下一集，只触发一次。 */
    private fun checkOutro() {
        if(outroTriggered) return
        val instance = player ?: return
        if(!instance.isPlaying) return
        val data = detail ?: return
        val mark = store.skipMark(skipKey(data.video.id)) ?: return
        if(mark.outro <= 0) return
        val duration = instance.duration
        if(duration <= 0 || instance.currentPosition < duration - mark.outro * 1000L) return
        outroTriggered = true
        val episodes = data.groups.getOrNull(playGroup)?.second ?: return
        if(playEpisode + 1 < episodes.size) {
            Toast.makeText(activity, "已跳过片尾，播放下一集", Toast.LENGTH_SHORT).show()
            jumpEpisode(1)
        } else showOverlay()
    }

    private val overlayButtons = mutableListOf<TextView>()
    private var overlayTopView: View? = null
    private var progressTrackView: View? = null
    private var lastSeekAt = 0L
    /** 片尾只自动跳一次，避免用户在片尾附近来回拖动时反复跳集。 */
    private var outroTriggered = false
    private val outroTick: Runnable = object : Runnable {
        override fun run() {
            checkOutro()
            if(player != null) main.postDelayed(this, 1_000L)
        }
    }

    private fun showOverlay(focusButtons: Boolean = true) {
        overlayVisible = true
        overlayTopView?.visibility = View.VISIBLE
        overlay?.visibility = View.VISIBLE
        updatePlayUi()
        main.removeCallbacks(hideOverlayRun)
        main.postDelayed(hideOverlayRun, 10_000L)
        // 快进预览期间不抢焦点：焦点跳到按钮会中断对左右键的拦截
        if(focusButtons) {
            val focusTarget = overlayButtons.firstOrNull { it.text == "上一集" && playEpisode > 0 } ?: overlayButtons.firstOrNull()
            focusTarget?.requestFocus()
        }
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
        // 按住连发会每秒产生多次按键：限制定位频率，避免大量 seek 把播放器拖死
        val now = android.os.SystemClock.uptimeMillis()
        if(now - lastSeekAt < 120L) return
        lastSeekAt = now
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
        // 快进预览期间以虚拟位置为准：播放器实际位置异步追赶，直接读会来回跳
        val rawPosition = if(scrubbing) scrubPositionMs else instance.currentPosition
        val position = rawPosition.coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val timeText = "${formatTime(position)} / ${if(duration > 0) formatTime(duration) else "直播中"}"
        playTime?.text = timeText
        progressBubble?.text = timeText
        val track = progressTrackView
        val fill = progressFill
        if(track != null && track.width > 0) {
            val ratio = if(duration > 0) position.toDouble() / duration else 0.0
            if(fill != null) {
                fill.layoutParams = FrameLayout.LayoutParams((track.width * ratio).toInt().coerceAtLeast(1), -1)
                fill.requestLayout()
            }
            progressThumb?.let { thumb ->
                val size = thumb.layoutParams.width
                if(size > 0) {
                    val thumbLp = thumb.layoutParams as FrameLayout.LayoutParams
                    thumbLp.leftMargin = ((track.width * ratio).toInt() - size / 2).coerceIn(0, (track.width - size).coerceAtLeast(0))
                    thumb.layoutParams = thumbLp
                }
            }
        }
        if(overlayVisible) { main.removeCallbacks(uiTick); main.postDelayed(uiTick, 500) }
    }

    private fun saveProgressNow() {
        val instance = player ?: return
        val data = detail ?: return
        val source = detailSource ?: return
        val position = instance.currentPosition
        if(position <= 0) return
        store.saveProgress(source.id, data.video.id, ThirdProgress(playEpisode, position, playGroup))
        rememberHistory(source, data, position, instance.duration.coerceAtLeast(0))
    }

    /** 观看历史打点：同一集 30 秒内只更新一次，避免播放中频繁写 prefs；看完时立即落一条「已看完」。 */
    private fun rememberHistory(source: ThirdSource, data: ThirdDetail, position: Long, duration: Long) {
        val now = System.currentTimeMillis()
        val completed = duration > 0 && position >= duration - 5_000
        val key = source.api + "|" + data.video.id + "|" + playGroup + "|" + playEpisode
        val finalKey = if(completed) "$key|done" else key
        if(finalKey == lastHistoryKey && now - lastHistoryAt < 30_000) return
        lastHistoryKey = finalKey; lastHistoryAt = now
        val label = data.groups.getOrNull(playGroup)?.second?.getOrNull(playEpisode)?.label.orEmpty()
        store.rememberHistory(ThirdHistoryEntry(source.api, source.name, data.video.id, data.video.name, data.video.cover,
            playEpisode, label, position, duration, now, completed))
        if(completed) store.clearProgress(source.id, data.video.id)
        // 历史要跟着主备份走：看完或距上次备份满 5 分钟才请求一次，避免播放中反复整包写盘
        if(completed || now - lastHistoryBackupAt >= 5 * 60_000L) { lastHistoryBackupAt = now; backupRequest() }
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRun)
        main.postDelayed(saveRun, 5_000L)
    }

    private fun releasePlayback() {
        main.removeCallbacks(saveRun); main.removeCallbacks(hideOverlayRun); main.removeCallbacks(uiTick); main.removeCallbacks(outroTick)
        scrubbing = false; main.removeCallbacks(scrubTick)
        outroTriggered = false
        overlayVisible = false; overlayButtons.clear()
        overlay = null; overlayTopView = null; progressFill = null; progressTrackView = null; playTime = null
        progressThumb = null; progressBubble = null; seekBarView = null; playerView = null
        playLoading = null; playErrorText = null
        playerHost?.let { host -> (host.parent as? FrameLayout)?.removeView(host) }
        playerHost = null
        player?.let { instance -> instance.removeListener(playerListener); instance.release() }
        player = null
        keepScreenOn(false)
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
