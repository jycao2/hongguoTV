// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors

private const val TYPE_CARD=0
private const val TYPE_FOOTER=1
private const val FOOTER_NONE=0
private const val FOOTER_LOADING=1
private const val FOOTER_END=2
private const val FOOTER_ERROR=3
private const val FOOTER_MORE_HOT=4

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private val bg by lazy { TvStyle.background }; private val surface by lazy { TvStyle.surface }
    private val accent by lazy { TvStyle.accent }; private val white by lazy { TvStyle.text }; private val muted by lazy { TvStyle.muted }
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val randomRecommender by lazy {
        val raw=getSharedPreferences("random_pool", MODE_PRIVATE).getString("pool", null)
        val items=raw?.let { RandomPoolSnapshot.decode(it, System.currentTimeMillis()) } ?: emptyList()
        RandomRecommender(repository, items)
    }
    private var randomReplenishing=false
    private var randomDialog: AlertDialog?=null
    private var exitDialog: AlertDialog?=null
    private var killOnDestroy=false
    private var third: ThirdSourceScreen?=null
    private val danmakuRepo=DanmakuRepository(repository.http)
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var localPermissionContinuation: (() -> Unit)?=null
    private var clockView: TextView?=null
    private var clockTick: Runnable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val gridColumns=6
    private val waterfallMaxPages=10
    private var catalogRecycler: RecyclerView?=null
    private var catalogAdapter: CatalogAdapter?=null
    private var loadingMore=false
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var extras: PlaybackExtras
    private lateinit var thirdStore: ThirdStore
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private var danmakuView: DanmakuView?=null
    private var danmakuLoader: DanmakuLoader?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var favoriteGroupFilter: String?=null
    private var favoriteFilterFirst: TextView?=null
    private var favoriteFilterButtons: List<TextView> =emptyList()
    private var favoriteFilterFocus: Int=-1
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    /** 本次播放连续跳过的不兼容集数：成功后归零，最多自动跳 5 集。 */
    private var incompatibleSkips=0
    private var startPosition=0L
    private var outroSkipSeconds=0
    private var outroTriggered=false
    private var ffActive=false
    private var ffSpeedBefore=1f
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var backdropView: ImageView?=null
    private var backdropBitmap: Bitmap?=null
    private var generation=0
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?,val board: RankBoard?=null,val genre: Genre?=null,val aiCatalog: Boolean=false,val aiGenre: Genre?=null)
    private var ranking: ComicRanking?=null
    private var rankBoard=RankBoard.COMIC
    // 榜单按枚举顺序循环切换，新增榜单只需在 RankBoard 里加一项
    private fun nextRankBoard()=RankBoard.entries[(rankBoard.ordinal+1)%RankBoard.entries.size]
    private var rankSwitch: View?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    // AI 剧分类是排行榜页内的子视图，不占用首页内容类型页签，也不参与搜索口径
    private var aiCatalog=false
    private var aiGenre: Genre?=null
    private var aiEntry: View?=null
    private var aiBack: View?=null
    private var aiGenreButton: View?=null
    private var genre: Genre?=null
    private var genreButton: View?=null
    private var genreClear: View?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var azFirst: View?=null
    private val azLastRow=mutableListOf<View>()
    private var suggestRow: LinearLayout?=null
    private var suggestFirst: View?=null
    private var suggestTicket=0
    private var suggestVertical=false
    private var suggestFocusIndex=-1
    private var searchBody: LinearLayout?=null
    private var searchColumns=6
    private var phoneSearched=false
    private var keyboardLastLabel: String?=null
    private var keyboardKeys=emptyMap<String,TextView>()
    private var keyboardRows=emptyList<List<TextView>>()
    private var historyFirst: TextView?=null
    private var historyItems=emptyList<TextView>()
    private var suggestScrollRef: ScrollView?=null
    private var fullSearchColumns=6
    private var lastSuggestWords=emptyList<String>()
    private var lastSuggestKey=""
    private var resultFirstCard: View?=null
    private var searchFocusPending=false
    private val pendingSuggest=Runnable { fetchSuggest() }
    private var historyManage: View?=null
    /** 设置页重建后要恢复焦点的设置项标签前缀。 */
    private var settingsFocusKey: String?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                danmakuLoader?.let { loader -> if(player?.isPlaying==true) { val position=currentPosition(); io.execute { runCatching { loader.loadUpTo(position) } } } }
                maybePrefetch()
                val p=player; val data=detail
                if(p!=null && data!=null && playbackReady && !playError && outroSkipSeconds>0 && !outroTriggered && p.isPlaying) {
                    val remaining=p.duration-p.currentPosition
                    if(p.duration>0 && remaining in 0..outroSkipSeconds*1000L) {
                        outroTriggered=true
                        if(library.autoNext && episodeIndex<data.episodes.lastIndex) {
                            Toast.makeText(this@MainActivity,"已跳过片尾，播放下一集",Toast.LENGTH_SHORT).show()
                            playEpisode(episodeIndex+1)
                        } else if(continueToNextSeason()) { /* season jump in progress */ }
                    }
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun heightDp()=resources.displayMetrics.heightPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        TvStyle.apply(getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).getString("theme", null))
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); extras=PlaybackExtras(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        // 三方片源配置与观看历史随主备份一起保存/恢复：本地已有的保留，不覆盖
        thirdStore=ThirdStore(this)
        library.thirdSourcesProvider={ thirdStore.backupSources() }
        library.thirdSourcesRestore={ thirdStore.mergeRestored(it) }
        library.thirdHistoryProvider={ thirdStore.backupHistory() }
        library.thirdHistoryRestore={ thirdStore.mergeHistory(it) }
        library.thirdFavoritesProvider={ thirdStore.backupFavorites() }
        library.thirdFavoritesRestore={ thirdStore.mergeFavorites(it) }
        InstallStatsPing.startOnce(applicationContext)
        backdropView=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        loadBackdrop()
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) handleLocalBackupOnStartup {
                if(!isDestroyed && foreground) { showCatalog(load=true); favoriteMonitor.check() }
            }
        }
    }
    private fun handleLocalBackupOnStartup(done: () -> Unit) {
        if(!library.localBackupEnabled || library.localRestoreChecked) {
            library.localRestoreChecked=true
            library.maybeBackupOnStartup()
            done(); return
        }
        val storage=library.localBackup()
        if(!storage.supported || (!storage.restoreAllowed && library.localPermissionAsked)) {
            library.localRestoreChecked=true
            library.maybeBackupOnStartup()
            done(); return
        }
        if(storage.restoreAllowed) { restoreLocalBackup(done); return }
        library.localPermissionAsked=true
        TvDialog.confirm(this,"本地备份","开启后，收藏、观看记录与播放设置会备份到本机公共目录，卸载重装后可自动恢复。备份是明文文件，可随时在设置中关闭。") {
            localPermissionContinuation={ restoreLocalBackup(done) }
            if(!storage.request(this)) {
                localPermissionContinuation=null; library.localRestoreChecked=true
                Toast.makeText(this,"当前设备无法授予存储权限，可在设置中使用手机备份迁移",Toast.LENGTH_LONG).show()
                done()
            }
        }.also { it.setOnDismissListener { library.localRestoreChecked=true; done() }; it.show() }
    }
    private fun restoreLocalBackup(done: () -> Unit) {
        io.execute {
            val data=runCatching { library.readLocalBackup() }.getOrNull()
            runOnUiThread {
                if(isDestroyed) return@runOnUiThread
                val restored=data!=null && library.isEmptyForRestore()
                if(restored) {
                    favoriteMonitor.stop()
                    library.applyLocalBackup(data!!)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    Toast.makeText(this,"已从本机备份恢复收藏与记录",Toast.LENGTH_LONG).show()
                }
                library.localRestoreChecked=true
                library.maybeBackupOnStartup()
                done()
            }
        }
    }
    private fun localBackupLabel(): String {
        if(!library.localBackupEnabled) return "本地备份与恢复  ·  已关闭"
        val storage=library.localBackup()
        if(!storage.supported) return "本地备份与恢复  ·  不可用"
        if(storage.needsPermission) return "本地备份与恢复  ·  待授权"
        return "本地备份与恢复  ·  已开启"
    }
    private fun showLocalBackupDialog(anchor: TextView) {
        val storage=library.localBackup()
        if(!storage.supported) {
            TvDialog.info(this,"本地备份与恢复","当前设备没有可用的公共存储，无法自动备份；可使用「手机备份与恢复」迁移记录。").show()
            return
        }
        if(!library.localBackupEnabled) {
            TvDialog.confirm(this,"本地备份与恢复","开启后，收藏、观看记录与播放设置会备份到本机公共目录，卸载重装后可自动恢复。备份是明文文件，可随时关闭。") {
                library.localBackupEnabled=true; anchor.text=localBackupLabel()
                if(storage.needsPermission) requestLocalBackupPermission(anchor) else localBackupAction(anchor)
            }.show()
            return
        }
        val needsPermission=storage.needsPermission
        val actions=if(needsPermission) arrayOf("授权存储权限","关闭本地自动备份") else arrayOf(if(library.isEmptyForRestore() && library.hasLocalBackup()) "立即恢复" else "立即备份","关闭本地自动备份")
        TvDialog.choose(this,"本地备份与恢复",actions.toList(), { which ->
            if(which==0) {
                if(needsPermission) requestLocalBackupPermission(anchor) else localBackupAction(anchor)
            } else {
                library.localBackupEnabled=false; anchor.text=localBackupLabel()
                Toast.makeText(this,"已关闭本地自动备份，已有备份文件保留",Toast.LENGTH_SHORT).show()
            }
        }).show()
    }
    /** Empty library with an existing backup must restore instead of overwriting it with nothing. */
    private fun localBackupAction(anchor: TextView) {
        if(library.isEmptyForRestore() && library.hasLocalBackup()) {
            restoreLocalBackup { anchor.text=localBackupLabel() }
        } else backupWithToast(anchor)
    }
    private fun requestLocalBackupPermission(anchor: TextView) {
        localPermissionContinuation={
            anchor.text=localBackupLabel()
            if(library.localBackup().backupAllowed) localBackupAction(anchor)
        }
        if(!library.localBackup().request(this)) {
            localPermissionContinuation=null
            anchor.text=localBackupLabel()
            Toast.makeText(this,"当前设备无法授予存储权限，请使用手机备份迁移",Toast.LENGTH_LONG).show()
        }
    }
    private fun backupWithToast(anchor: TextView) {
        library.backupNow { ok ->
            anchor.text=localBackupLabel()
            Toast.makeText(this,if(ok) "本机备份已更新" else "备份失败，请检查存储权限",Toast.LENGTH_SHORT).show()
        }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==LocalBackupStorage.REQUEST_MANAGE) {
            val next=localPermissionContinuation; localPermissionContinuation=null
            if(next!=null && !isDestroyed) next()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==LocalBackupStorage.REQUEST_READ || requestCode==LocalBackupStorage.REQUEST_WRITE) {
            val next=localPermissionContinuation; localPermissionContinuation=null
            if(next!=null && !isDestroyed) next()
        }
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        backdropView?.takeIf { backdropBitmap!=null }?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        // 本地测试包（versionName 不含 +构建号）右上角常驻 beta 标记，与正式包区分；播放页不显示
        if(!BuildConfig.VERSION_NAME.contains('+')) {
            root.addView(text("beta",12f,accent).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable=false },
                FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(10); rightMargin=dp(14) })
        }
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        applyClock()
        return container
    }
    /** Re-adds the optional top-right clock after every root rebuild; ticks on minute boundaries. */
    private fun applyClock() {
        clockTick?.let(main::removeCallbacks); clockTick=null
        clockView?.let { root.removeView(it) }; clockView=null
        if(!library.isLoaded || !library.showClock) return
        val view=text("",15f,muted).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable=false; isClickable=false
            setPadding(dp(8),dp(3),dp(8),dp(3))
        }
        // Local test builds show the beta badge in the same corner; keep both visible.
        val topMargin=if(BuildConfig.VERSION_NAME.contains('+')) dp(10) else dp(30)
        root.addView(view,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { this.topMargin=topMargin; rightMargin=dp(14) })
        clockView=view
        val tick=object: Runnable {
            override fun run() {
                clockView?.text=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
                main.postDelayed(this,60_000L-(System.currentTimeMillis()%60_000L)+100L)
            }
        }
        clockTick=tick
        tick.run()
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        (catalogRecycler?.layoutManager as? GridLayoutManager)?.let { scrollPositions[catalogPageKey]=it.findFirstVisibleItemPosition() }
        homeScreen=null; selectionPreview=null; catalogRecycler=null; catalogAdapter=null; loadingMore=false
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        azFirst=null; azLastRow.clear(); suggestRow=null; suggestFirst=null; suggestTicket++; main.removeCallbacks(pendingSuggest)
        suggestVertical=false; searchBody=null; keyboardKeys=emptyMap(); keyboardRows=emptyList(); historyFirst=null; historyItems=emptyList(); suggestScrollRef=null; resultFirstCard=null
        historyManage=null; rankSwitch=null; rankRefresh=null; rankSubtitle=null; genreButton=null; genreClear=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; favoriteFilterFirst=null; favoriteFilterButtons=emptyList(); homeUpdates=null; resumeCards.clear()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int) {
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,rankBoard,genre,aiCatalog,aiGenre); tabFocus[tab]=catalogFocus
        // 从其他模块切回搜索页时重置为全新搜索状态，不保留上次的搜索词与结果
        if(next==1) {
            query=""; phoneSearched=false; lastSuggestWords=emptyList<String>(); lastSuggestKey=""; suggestFocusIndex=-1; keyboardLastLabel=null
            tabState.remove(1); tabFocus.remove(1)
        }
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        rankBoard=saved?.board ?: rankBoard; genre=saved?.genre ?: genre
        aiCatalog=saved?.aiCatalog ?: false; aiGenre=saved?.aiGenre
        showCatalog(load=(next<=2 && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
        // 切回搜索页时焦点落输入框，避免页面重建后焦点丢失
        if(next==1) searchInput?.requestFocus()
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        // 切换内容类型后旧联想不再适用，清空回显记忆
        lastSuggestWords=emptyList<String>(); lastSuggestKey=""; phoneSearched=false
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null; genre=null
        showCatalog(load=(tab==0 || query.isNotBlank()),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun inlineHomeFilters()=tab==0 && resources.configuration.fontScale<=1.2f && widthDp()>=900
    private fun localCatalog()=tab==3 || tab==4 || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==3) library.favorites().filter { favoriteGroupFilter==null || library.groupOf(it.id)==favoriteGroupFilter } else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusFilter: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // 片源入口隐藏时导航栏没有第 7 项，避免 nav[6] 越界
        if(tab==6 && library.sourceHidden) tab=0
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && page==1 && genre==null) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        brand.addView(text("v${BuildConfig.VERSION_NAME.substringBefore('+')}",12f,muted).apply { setPadding(dp(5),0,0,dp(9)) },lp(-2,-2).apply { gravity=Gravity.BOTTOM })
        top.addView(brand,lp(dp(172),dp(44)))
        listOf("首页","搜索","排行榜","收藏","最近观看","设置","片源").forEachIndexed { index,label ->
            // 片源入口默认隐藏，可在「设置 → 片源隐藏」中开启
            if(index==6 && library.sourceHidden) return@forEachIndexed
            val item=addButton(top,label,index==tab) { switchTab(index) }
            fun paint(focused: Boolean) {
                item.setTextColor(if(focused) bg else if(index==tab) accent else muted)
                item.background=rounded(if(focused) white else if(index==tab) TvStyle.selectedSurface else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || index==tab) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false); item.setOnFocusChangeListener { _,focused -> paint(focused) }; nav+=item
        }
        container.addView(top)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        if(tab==6) {
            val instance=third ?: ThirdSourceScreen(this, { showCatalog() }, { library.requestBackup() }).also { third=it }
            instance.show(container)
            nav[tab].nextFocusDownId=instance.topFocusId
            val target=if(instance.topFocusId != View.NO_ID) container.findViewById<View>(instance.topFocusId) else null
            if(target != null) target.requestFocus() else nav[tab].requestFocus()
            return
        }
        if(tab==5) {
            // 设置页：内容可滚动，页脚固定在屏幕底部居中，始终可见；去掉容器下内边距让页脚真正贴底
            container.setPadding(container.paddingLeft,container.paddingTop,container.paddingRight,0)
            val page=column()
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); page.addView(scroll,lp(-1,0).apply { weight=1f })
            val footerHost=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(12),0,0) }
            page.addView(footerHost,lp(-1,-2))
            container.addView(page,lp(-1,0).apply { weight=1f })
            settings(body,footerHost); if(focusNav) nav[tab].requestFocus(); return
        }
        if(tab<=1) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(!inlineHomeFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            // AI 剧只在排行榜内提供分类入口，不占首页/搜索的内容类型页签
            listOf(ContentType.COMIC,ContentType.SHORT).forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            if(tab==0) {
                genreButton=addButton(types,genre?.let { "题材 · ${it.name}" } ?: "题材") { showGenrePicker() }.apply { nextFocusUpId=nav[tab].id }
                addButton(types,"随机") { showRandomPicks() }.apply { nextFocusUpId=nav[tab].id }
            }
            if(inlineHomeFilters()) {
                top.addView(Space(this),lp(0,1).apply { weight=1f })
                typeButtons.values.forEach { view -> (view as TextView).apply { textSize=12f; minHeight=dp(34); setPadding(dp(12),dp(7),dp(12),dp(7)) } }
                types.setPadding(0,0,0,0); top.addView(types)
            } else {
                if(tab==0) types.addView(text("长按确认 · 管理片单",12f,muted).apply { setPadding(dp(14),0,0,0) })
                container.addView(types)
            }
            nav.forEach { it.nextFocusDownId=(typeButtons[library.contentType] ?: typeButtons.values.firstOrNull())?.id ?: nav[tab].id }
        }
        if(tab==1) {
            // 机型分级：≥900dp 三栏（键盘+历史 / 联想 / 结果），700-900dp 两栏，<700dp 回退纵向堆叠
            val mode=when { widthDp()>=900 -> 3; widthDp()>=700 -> 2; else -> 0 }
            val tall=heightDp()>=520
            val roomy=widthDp()>=1100
            searchColumns=when(mode) {
                3 -> (((widthDp()-(if(roomy) 380f else 300f)-(if(roomy) 190f else 150f)-24f)+14f)/118f).toInt().coerceIn(3,gridColumns)
                2 -> (((widthDp()-340f-24f)+14f)/118f).toInt().coerceIn(3,gridColumns)
                else -> gridColumns
            }
            // 无联想词时中栏整列消失，结果网格按全宽重新算列数
            fullSearchColumns=if(mode==3) (((widthDp()-(if(roomy) 380f else 300f)-24f)+14f)/118f).toInt().coerceIn(3,gridColumns) else searchColumns
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入${library.contentType.label}名称，或用字母键拼首拼"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=typeButtons.getValue(library.contentType).id; searchButton?.nextFocusUpId=input.nextFocusUpId
            if(mode==0) {
                lateinit var recent: TextView
                recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
                searchActions.addAll(listOf(recent))
            }
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
            input.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 用户编辑输入框后解除手机输入的联想抑制，恢复常规联想
                    if(phoneSearched && s?.toString()?.trim()!=query) phoneSearched=false
                    scheduleSuggest()
                }
            })
            val keyHeight=if(tall) dp(36) else dp(30)
            fun azKey(label: String): TextView {
                val view=button(label) { onAzKey(label) }
                view.textSize=13f; view.minHeight=keyHeight; view.setPadding(dp(4),dp(4),dp(4),dp(4))
                val paint=view.onFocusChangeListener
                view.setOnFocusChangeListener { v,focused -> paint?.onFocusChange(v,focused); if(focused) keyboardLastLabel=label }
                return view
            }
            // 统一键盘：6 列×7 行（字母数字混排），末行删除/清空/手机输入各占两列
            val keyboard=column()
            val keyLines=mutableListOf<List<TextView>>()
            val keyViews=linkedMapOf<String,TextView>()
            listOf("ABCDEF","GHIJKL","MNOPQR","STUVWX","YZ0123","456789").forEach { keys ->
                val line=row().apply { setPadding(0,0,0,dp(4)) }
                val views=keys.map { key -> azKey(key.toString()).also { line.addView(it,lp(0,-2).apply { weight=1f; rightMargin=dp(6) }); keyViews[key.toString()]=it } }
                keyboard.addView(line); keyLines+=views
            }
            val lastLine=row().apply { setPadding(0,0,0,dp(4)) }
            val del=azKey("删除").also { lastLine.addView(it,lp(0,-2).apply { weight=2f; rightMargin=dp(6) }); keyViews["删除"]=it }
            val clear=azKey("清空").also { lastLine.addView(it,lp(0,-2).apply { weight=2f; rightMargin=dp(6) }); keyViews["清空"]=it }
            lateinit var phone: TextView
            phone=button("手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it,fromPhone=true) } }
            phone.textSize=13f; phone.minHeight=keyHeight; phone.setPadding(dp(4),dp(4),dp(4),dp(4)); keyViews["手机输入"]=phone
            lastLine.addView(phone,lp(0,-2).apply { weight=2f })
            keyboard.addView(lastLine)
            val lastViews=listOf(del,clear,phone); keyLines+=lastViews
            keyLines.forEachIndexed { r,views ->
                views.forEachIndexed { c,key ->
                    key.nextFocusLeftId=views[if(c==0) 0 else c-1].id
                    key.nextFocusRightId=views[if(c==views.size-1) c else c+1].id
                    val up=keyLines.getOrNull(r-1); val down=keyLines.getOrNull(r+1)
                    // 末行三键各覆盖两列：向下按列折半、向上按列展开，保持同列直觉
                    key.nextFocusUpId=up?.get(if(r==keyLines.size-1) minOf(c*2,up.size-1) else minOf(c,up.size-1))?.id ?: input.id
                    key.nextFocusDownId=down?.get(if(r==5) minOf(c/2,down.size-1) else minOf(c,down.size-1))?.id ?: key.id
                }
            }
            azFirst=keyLines.first().first(); azLastRow.clear(); azLastRow.addAll(lastViews)
            keyboardKeys=keyViews; keyboardRows=keyLines.toList()
            var catalogBody: LinearLayout?=null
            if(mode==0) {
                container.addView(keyboard)
                val suggestScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
                val suggestLine=row(); suggestScroll.addView(suggestLine); suggestRow=suggestLine; suggestVertical=false
                container.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(4) })
            } else {
                // 顶部对齐：左栏随历史增多变高时，键盘顶边保持固定，不挤压搜索框间距
                val contentRow=row().apply { gravity=Gravity.TOP }
                // 左栏与联想栏包滚动容器：小尺寸电视内容超高时可滚动，焦点移出视口时自动滚入
                val leftScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
                val leftColumn=column().apply { setPadding(0,dp(2),dp(10),0) }
                leftScroll.addView(leftColumn)
                leftColumn.addView(keyboard)
                // 左栏底部搜索历史：内嵌最新 4 条 + 全部历史入口，空历史整块隐藏
                val history=library.searches()
                if(tall && history.isNotEmpty()) {
                    val block=column()
                    val head=row().apply { setPadding(0,dp(2),0,dp(4)) }
                    head.addView(text("搜索历史",13f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
                    lateinit var clearHistory: TextView
                    clearHistory=button("清空") { tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",clearHistory) { library.clearSearches(); showCatalog(); main.post { searchInput?.requestFocus() } } }
                        .apply { textSize=12f; minHeight=dp(28); setPadding(dp(10),dp(4),dp(10),dp(4)) }
                    head.addView(clearHistory,lp(-2,-2))
                    block.addView(head)
                    val items=history.take(4).map { entry ->
                        button("${entry.type.label} · ${entry.query}") { runSearch(entry.query,entry.type) }.apply {
                            textSize=13f; minHeight=dp(32); setPadding(dp(10),dp(4),dp(10),dp(4)); gravity=Gravity.CENTER_VERTICAL
                            maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
                        }
                    }
                    items.forEachIndexed { index,item ->
                        block.addView(item,lp(-1,-2).apply { bottomMargin=dp(4) })
                        item.nextFocusUpId=if(index==0) clearHistory.id else items[index-1].id
                    }
                    historyItems=items
                    if(history.size>4) {
                        lateinit var all: TextView
                        all=button("全部历史（${history.size}）›") { showSearchHistory(all) }.apply { textSize=12f; minHeight=dp(30); setPadding(dp(10),dp(4),dp(10),dp(4)) }
                        block.addView(all,lp(-1,-2)); historyItems=items+all
                    }
                    leftColumn.addView(block)
                    historyFirst=clearHistory
                    // 历史↑回到离开键盘时的按键，保持焦点记忆
                    clearHistory.nextFocusUpId=(keyboardLastLabel?.let { keyViews[it] } ?: del).id
                }
                val midScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
                val midColumn=column().apply { setPadding(0,dp(2),dp(10),0) }
                midScroll.addView(midColumn)
                val rightColumn=column()
                if(mode==3) {
                    suggestRow=midColumn; suggestVertical=true; suggestScrollRef=midScroll
                    contentRow.addView(leftScroll,lp(if(roomy) dp(380) else dp(300),-1))
                    contentRow.addView(midScroll,lp(if(roomy) dp(190) else dp(150),-1))
                    contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
                } else {
                    contentRow.addView(leftScroll,lp(dp(340),-1))
                    contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
                    val suggestScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
                    val suggestLine=row(); suggestScroll.addView(suggestLine); suggestRow=suggestLine; suggestVertical=false
                    rightColumn.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(4) })
                }
                container.addView(contentRow,lp(-1,0).apply { weight=1f })
                catalogBody=rightColumn
            }
            searchBody=catalogBody
            // 键盘末行先指向历史（结果区建立后由 wireSearchPanel 补全联想与网格指向）
            azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: View.NO_ID }
        } else if(tab==2) {
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            aiEntry=null; aiBack=null; aiGenreButton=null
            heading.addView(text(if(aiCatalog) "AI剧 · 全部分类" else "${rankBoard.label}热播榜",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(aiCatalog) {
                aiBack=addButton(heading,"‹ 返回AI剧榜") {
                    aiCatalog=false; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    aiBack?.post { if(aiBack?.isAttachedToWindow==true) aiBack?.requestFocus() }
                }.apply { nextFocusUpId=nav[tab].id }
                aiGenreButton=addButton(heading,aiGenre?.let { "题材 · ${it.name}" } ?: "题材") { showGenrePicker() }
                    .apply { nextFocusUpId=nav[tab].id }
            } else {
                if(rankBoard==RankBoard.AI) {
                    // 只有 AI 剧榜提供分类浏览入口，其余榜单保持原有切换/刷新两个按钮
                    aiEntry=addButton(heading,"全部分类") {
                        aiCatalog=true; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                        aiEntry?.post { if(aiEntry?.isAttachedToWindow==true) aiEntry?.requestFocus() }
                    }.apply { nextFocusUpId=nav[tab].id }
                }
                rankSwitch=addButton(heading,"看${nextRankBoard().label}榜") {
                    rankBoard=nextRankBoard()
                    page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    // 重建期间网格被清空，焦点必须落回按钮，否则会丢到不可见区域
                    rankSwitch?.post { if(rankSwitch?.isAttachedToWindow==true) rankSwitch?.requestFocus() }
                }.apply { nextFocusUpId=nav[tab].id }
                rankRefresh=addButton(heading,"刷新榜单") { page=1; catalogFocus=""; showCatalog(true)
                    rankRefresh?.post { if(rankRefresh?.isAttachedToWindow==true) rankRefresh?.requestFocus() }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            val defaultSource=if(aiCatalog) "来源：红果AI剧分类" else "来源：红果${rankBoard.label}热播榜"
            val loadingText=if(aiCatalog) "正在读取AI剧分类…" else "正在读取最新榜单…"
            rankSubtitle=text(if(load) loadingText else ranking?.updatedText?.ifBlank { defaultSource } ?: defaultSource,13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=(if(aiCatalog) aiBack ?: aiGenreButton else aiEntry ?: rankSwitch)?.id ?: View.NO_ID }
        } else if(tab>2) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==3) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==3) {
                favoriteCheck=addButton(heading,"检查更新") {
                    if(favoriteMonitor.running) Toast.makeText(this,"正在检查收藏更新…",Toast.LENGTH_SHORT).show()
                    else if(library.favorites().isEmpty()) Toast.makeText(this,"先收藏几部剧，再来检查更新",Toast.LENGTH_SHORT).show()
                    else { favoriteMonitor.check(force=true); Toast.makeText(this,"开始检查收藏更新…",Toast.LENGTH_SHORT).show() }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==4 && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            if(tab==3) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        // 三栏/两栏搜索页的结果区已在 contentRow 内挂载，其余页面走全宽纵向容器
        val body=searchBody ?: column().also { container.addView(it,lp(-1,0).apply { weight=1f }) }
        if(tab==3) {
            val groups=library.groups()
            if(favoriteGroupFilter!=null && favoriteGroupFilter !in groups) favoriteGroupFilter=null
            if(groups.isNotEmpty()) {
                val filterRow=row().apply { setPadding(0,0,0,dp(6)) }
                val options=listOf<String?>(null)+groups
                favoriteFilterButtons=options.mapIndexed { index,group ->
                    addButton(filterRow,group ?: "全部",favoriteGroupFilter==group) {
                        favoriteGroupFilter=group; favoriteFilterFocus=index; showCatalog(focusFilter=true)
                    }.apply { nextFocusUpId=favoriteCheck?.id ?: nav[tab].id }
                }
                favoriteFilterFirst=favoriteFilterButtons.first()
                container.addView(filterRow,container.indexOfChild(favoriteStatus))
            }
        }
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=1; hasMore=false
        }
        if(load) {
            if(tab==0) catalogGrid(body,focusNav,focusType,loading=true)
            else { message(body,"正在加载…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
            val requestAi=if(requestTab==2) aiCatalog else false; val requestAiGenre=if(requestAi) aiGenre else null
            work({
                val result=when(requestTab) {
                    0 -> repository.home(requestPage,requestType,requestGenre)
                    2 -> if(requestAi) repository.home(requestPage,ContentType.AI,requestAiGenre) else repository.ranking(requestBoard,requestPage)
                    else -> repository.search(requestQuery,requestPage,requestType)
                }
                result
            }, { result ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==0 && requestPage==1 && requestGenre==null && (homeFromCache || tvTools.showing || updater.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    // 手机输入的精确剧名搜索零结果时自动恢复联想，帮助用户纠正剧名
                    if(requestTab==1 && result.items.isEmpty()) { phoneSearched=false; scheduleSuggest() }
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    if(requestTab==2) rankSubtitle?.text=if(requestAi) "来源：红果AI剧分类" else ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    // 搜索完成后焦点落结果首卡；零结果回输入框，避免页面重建后焦点丢失
                    if(requestTab==1 && searchFocusPending) {
                        searchFocusPending=false
                        if(result.items.isEmpty()) searchInput?.requestFocus()
                        else catalogRecycler?.post { (resultFirstCard ?: searchInput)?.requestFocus() }
                    }
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text=if(requestAi) "AI剧分类加载失败" else "榜单加载失败"
                // 搜索失败时焦点回输入框，避免页面重建后焦点丢失
                if(requestTab==1 && searchFocusPending) { searchFocusPending=false; searchInput?.requestFocus() }
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType,focusFilter=focusFilter)
        // 页面重建后初始化联想区：回显点击联想词前的列表，或无词时收起中栏
        if(tab==1) fetchSuggest()
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,focusFilter: Boolean=false) {
        if(tab==0 && page==1 && collection==null && genre==null) { renderHome(body,focusNav,focusType,loading); return }
        collectionBack=null; genreClear=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        val focusGenre=if(tab==2 && aiCatalog) aiGenre else genre
        catalogPageKey="$tab:${if(aiCatalog) "ai" else ""}:${collection.orEmpty()}:${focusGenre?.slug.orEmpty()}:$page:$query"
        if(tab==0 && genre!=null) {
            val bar=row().apply { setPadding(0,0,0,dp(6)) }
            bar.addView(text("题材 · ${genre?.name}",16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            genreClear=addButton(bar,"全部题材") { genre=null; page=1; catalogFocus=""; tabState.remove(0); tabFocus.remove(0); showCatalog(true) }
                .apply { nextFocusUpId=genreButton?.id ?: typeButtons[library.contentType]?.id ?: nav[tab].id }
            body.addView(bar)
            typeButtons.values.forEach { it.nextFocusDownId=genreClear!!.id }
            genreButton?.nextFocusDownId=genreClear!!.id
        }
        if(tab==2 && aiCatalog && aiGenre!=null) {
            val bar=row().apply { setPadding(0,0,0,dp(6)) }
            bar.addView(text("题材 · ${aiGenre?.name}",16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            genreClear=addButton(bar,"全部题材") { aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true) }
                .apply { nextFocusUpId=aiGenreButton?.id ?: nav[tab].id }
            body.addView(bar)
            aiGenreButton?.nextFocusDownId=genreClear!!.id
        }
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val displayed=mutableListOf<Series>()
        displayed+=if(localCatalog()) catalog else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog
        if(displayed.isEmpty()) {
            message(body,if(loading) "正在加载推荐…" else when(tab) { 1 -> if(query.isBlank()) "输入名称关键词，或用字母键拼首拼选择联想词" else "没有找到相关${library.contentType.label}，换个关键词试试"; 3 -> if(favoriteGroupFilter!=null) "「$favoriteGroupFilter」分组暂无收藏" else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            wireSearchPanel(null)
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else nav[tab].requestFocus()
            return
        }
        val cols=if(tab==1) searchColumns else gridColumns
        val adapter=CatalogAdapter(displayed,cols)
        val grid=GridLayoutManager(this,cols)
        grid.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int)=if(adapter.isFooter(position)) cols else 1 }
        val recycler=RecyclerView(this).apply {
            id=View.generateViewId(); clipToPadding=false; isVerticalScrollBarEnabled=false; itemAnimator=null
            layoutManager=grid; this.adapter=adapter
            // Focus entering the grid lands on the first visible card, keeping the user's place.
            isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { _,focused -> if(focused) { val first=grid.findFirstVisibleItemPosition().coerceAtLeast(0); post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() } } }
            // 长按下键到达最后一项后拦截焦点搜索，避免焦点跳出网格跑到顶部导航栏形成循环
            setOnKeyListener { _,keyCode,event ->
                if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_DOWN) {
                    val focusedChild=focusedChild
                    if(focusedChild!=null) {
                        val pos=getChildAdapterPosition(focusedChild)
                        val total=adapter.itemCount
                        if(pos>=0 && pos>=total-1) return@setOnKeyListener true
                    }
                }
                false
            }
        }
        catalogRecycler=recycler; catalogAdapter=adapter
        adapter.footer=when {
            localCatalog() -> if(collection=="hot" && collectionReturn?.hasMore==true) FOOTER_MORE_HOT else FOOTER_NONE
            hasMore -> FOOTER_NONE
            else -> FOOTER_END
        }
        body.addView(recycler,lp(-1,0).apply { weight=1f })
        nav.forEach {
            it.nextFocusDownId=when {
                tab==2 -> (if(aiCatalog) aiBack ?: aiGenreButton ?: rankRefresh else aiEntry ?: rankSwitch ?: rankRefresh)?.id ?: recycler.id
                tab<=1 -> typeButtons[library.contentType]?.id ?: recycler.id
                else -> favoriteCheck?.id ?: historyManage?.id ?: recycler.id
            }
        }
        rankSwitch?.nextFocusDownId=recycler.id; rankRefresh?.nextFocusDownId=recycler.id; historyManage?.nextFocusDownId=recycler.id; favoriteCheck?.nextFocusDownId=favoriteFilterFirst?.id ?: recycler.id
        aiEntry?.nextFocusDownId=recycler.id; aiBack?.nextFocusDownId=recycler.id
        favoriteFilterButtons.forEach { it.nextFocusDownId=recycler.id }
        collectionBack?.nextFocusDownId=recycler.id; genreClear?.nextFocusDownId=recycler.id
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: recycler.id }
        genreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        aiGenreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        searchInput?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchButton?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchActions.forEach { it.nextFocusDownId=azFirst?.id ?: recycler.id }
        wireSearchPanel(recycler)
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus()
        else {
            val found=displayed.indexOfFirst { it.id==catalogFocus }
            val target=if(found>=0) found else scrollPositions[catalogPageKey]?.coerceIn(0,displayed.size-1) ?: 0
            grid.scrollToPositionWithOffset(target,0)
            recycler.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }
    private fun maybeLoadMore() {
        val adapter=catalogAdapter ?: return
        if(randomDialog?.isShowing==true || loadingMore || !hasMore || localCatalog() || screen!="catalog") return
        if(page>=waterfallMaxPages || catalog.size>=waterfallMaxPages*20) { hasMore=false; adapter.updateFooter(FOOTER_END); return }
        val requestTab=tab; val requestPage=page+1; val requestQuery=query; val requestType=library.contentType
        val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
        val requestAi=if(requestTab==2) aiCatalog else false; val requestAiGenre=if(requestAi) aiGenre else null
        loadingMore=true; adapter.updateFooter(FOOTER_LOADING)
        work({
            when(requestTab) {
                0 -> repository.home(requestPage,requestType,requestGenre)
                2 -> if(requestAi) repository.home(requestPage,ContentType.AI,requestAiGenre) else repository.ranking(requestBoard,requestPage)
                else -> repository.search(requestQuery,requestPage,requestType)
            }
        }, { result ->
            loadingMore=false
            page=requestPage
            hasMore=result.hasMore
            result.ranking?.let { r -> ranking=ComicRanking((ranking?.positions.orEmpty())+r.positions,r.totalPages,r.updatedText.ifBlank { ranking?.updatedText.orEmpty() }) }
            if(requestTab==2 && !requestAi) rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
            val known=catalog.mapTo(HashSet()) { it.id }
            val fresh=result.items.filter { it.id !in known }
            catalog=catalog+result.items
            val current=catalogAdapter
            if(current!=null) {
                val visible=fresh.filter { tab!=0 || it.id !in library.hidden() }
                val insertAt=current.displayed.size
                current.displayed.addAll(visible)
                if(visible.isNotEmpty()) current.notifyItemRangeInserted(insertAt,visible.size)
                current.updateFooter(if(hasMore && page<waterfallMaxPages && catalog.size<waterfallMaxPages*20) FOOTER_NONE else FOOTER_END)
            }
        }, { problem ->
            loadingMore=false
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                showCatalog(true)
            } else {
                Toast.makeText(this,"加载失败，继续浏览会自动重试",Toast.LENGTH_SHORT).show()
                catalogAdapter?.updateFooter(FOOTER_ERROR)
            }
        })
    }
    private inner class CardHolder(val card: LinearLayout,val artwork: FrameLayout,val image: ImageView,val rank: TextView,val title: TextView,val badge: TextView): RecyclerView.ViewHolder(card) {
        var boundId=""
    }
    private inner class CatalogAdapter(val displayed: MutableList<Series>,val cols: Int): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var footer=FOOTER_NONE
        fun isFooter(position: Int)=footer!=FOOTER_NONE && position==displayed.size
        override fun getItemCount()=displayed.size+if(footer!=FOOTER_NONE) 1 else 0
        override fun getItemViewType(position: Int)=if(isFooter(position)) TYPE_FOOTER else TYPE_CARD
        fun updateFooter(next: Int) {
            if(footer==next) { if(next!=FOOTER_NONE) notifyItemChanged(displayed.size); return }
            val had=footer!=FOOTER_NONE; footer=next
            if(next==FOOTER_NONE) notifyItemRemoved(displayed.size)
            else if(had) notifyItemChanged(displayed.size)
            else notifyItemInserted(displayed.size)
        }
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
            if(viewType==TYPE_FOOTER) {
                val frame=FrameLayout(this@MainActivity)
                frame.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
                return object: RecyclerView.ViewHolder(frame) {}
            }
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
            // 海报容器按自身实际测量宽度定高，回收复用、列宽变化都不会出现同屏高低不一
            val artwork=object: FrameLayout(this@MainActivity) {
                override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                    val w=MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/TvStyle.POSTER_ASPECT).toInt(),MeasureSpec.EXACTLY))
                }
            }
            val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            artwork.addView(image,FrameLayout.LayoutParams(-1,-1))
            val rank=TextView(this@MainActivity).apply { textSize=11f; setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(4),dp(8),dp(4)) }
            artwork.addView(rank,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
            val title=text("",14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) }
            val badge=text("",12f).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
            card.addView(artwork,lp(-1,-2))
            card.addView(title,lp(-1,-2))
            card.addView(badge,lp(-1,-2))
            card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
            return CardHolder(card,artwork,image,rank,title,badge)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
            if(getItemViewType(position)==TYPE_FOOTER) { bindFooter(holder.itemView as FrameLayout); return }
            holder as CardHolder
            val item=displayed[position]; holder.boundId=item.id
            if(position==0) resultFirstCard=holder.card
            val card=holder.card
            card.tag=item.id; card.contentDescription=item.title
            loadCover(holder.image,item.cover)
            // AI 剧分类复用的是片库卡片，没有名次与热度，按普通片库卡片展示
            val ranked=tab==2 && ranking!=null
            val rankPosition=if(ranked) ranking?.positions?.get(item.id) else null
            if(ranked) {
                val rank=rankPosition?.rank
                val rankLabel=if(rank!=null) "第 $rank 名" else "名次暂无"
                val styled=android.text.SpannableString(rankLabel)
                if(rank!=null) {
                    val start=rankLabel.indexOf(' ')+1; val end=rankLabel.lastIndexOf(' ')
                    styled.setSpan(android.text.style.RelativeSizeSpan(1.45f),start,end,0)
                    styled.setSpan(android.text.style.StyleSpan(Typeface.BOLD),start,end,0)
                }
                holder.rank.text=styled
                holder.rank.setTextColor(if(rank==1) white else Color.rgb(24,26,30))
                holder.rank.background=rounded(if(rank==1) accent else Color.argb(235,255,255,255),6)
                holder.rank.visibility=View.VISIBLE
                card.contentDescription="$rankLabel，${item.title}，${rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
            } else holder.rank.visibility=View.GONE
            holder.title.text=item.title
            val progress=if(tab==4 || collection=="resume") library.progress(item.id) else null
            holder.badge.text=if(tab==3) library.favoriteLabel(item.id) else if(ranked) rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无" else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge
            holder.badge.setTextColor(if(ranked || tab==3) accent else muted)
            if(tab==3) favoriteBadges[item.id]=holder.badge
            card.setOnClickListener { catalogFocus=item.id; openDetail(item,tab==4 || collection=="resume" || collection=="updates") }
            card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
            card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
            card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) { catalogFocus=item.id; loadCover(holder.image,item.cover); selectionPreview?.show(item,selectionStatus(item)) } }
            card.nextFocusUpId=if(position<cols) suggestFirst?.id ?: historyFirst?.id ?: azLastRow.firstOrNull()?.id ?: searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteFilterFirst?.id ?: favoriteCheck?.id ?: historyManage?.id ?: nav[tab].id else View.NO_ID
            if(position>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? CardHolder)?.let { h -> if(h.boundId.isNotEmpty() && favoriteBadges[h.boundId]===h.badge) favoriteBadges.remove(h.boundId) }
        }
        private fun bindFooter(frame: FrameLayout) {
            frame.removeAllViews()
            if(footer==FOOTER_MORE_HOT) {
                frame.addView(button("下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
                return
            }
            val label=when(footer) {
                FOOTER_LOADING -> "正在加载…"
                FOOTER_ERROR -> "加载失败，继续浏览会自动重试"
                else -> if(tab==2) "已展示全部 ${displayed.size} 部" else "已加载全部，试试搜索"
            }
            val hint=text(label,14f,muted).apply {
                gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(8)); isFocusable=true; isFocusableInTouchMode=true
                setOnFocusChangeListener { _,focused -> setTextColor(if(focused) white else muted) }
            }
            frame.addView(hint,FrameLayout.LayoutParams(-1,-2))
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val top=if(inlineHomeFilters()) nav[0] else typeButtons.getValue(library.contentType)
        val home=HomeScreen(this,top,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
        if(inlineHomeFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) nav[0].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later=library.later(); val hidden=library.hidden(); val hot=catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                if(updates.size>8) { { showCollection("updates") } } else null),
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null),
            HomeScreen.Shelf("hot","热门发现 · ${library.contentType.label}",hot.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(hot.size>10) { { showCollection("hot") } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        val adapter=catalogAdapter
        if(removed && (localCatalog() || tab==0) && adapter!=null) {
            val index=adapter.displayed.indexOfFirst { it.id==changedId }
            if(localCatalog()) catalog=localItems()
            if(index>=0) {
                adapter.displayed.removeAt(index)
                adapter.notifyItemRemoved(index)
                if(adapter.displayed.isEmpty()) { catalogFocus=""; showCatalog(); return }
                val next=minOf(index,adapter.displayed.size-1)
                catalogFocus=adapter.displayed[next].id
                val recycler=catalogRecycler
                recycler?.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() }
            }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val grouped=library.favorite(series.id)
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(grouped) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看")
        if(grouped) labels+="设置分组 · ${library.groupOf(series.id) ?: "未分组"}"
        labels+="查看剧集详情"
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4 || collection=="resume") labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            val detailIndex=if(grouped) 4 else 3
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> if(grouped) showGroupPicker(series,anchor) else openDetail(series)
                else -> if(choice==detailIndex) openDetail(series) else if(tab==4 || collection=="resume") manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun showGroupPicker(series: Series,anchor: View) {
        val options=listOf("未分组")+library.groups()+listOf("＋ 新建分组…")
        fun apply() { if(tab==3) showCatalog() else refreshLibraryCards(series.id) }
        tvTools.choose("选择分组 · ${series.title}",options,anchor,{ index ->
            when {
                index==0 -> { library.setGroup(series.id,null); apply() }
                index==options.lastIndex -> tvTools.phoneForm(anchor,PhoneForm(
                    heading="新建收藏分组",
                    description="输入分组名称后发送到电视，电视会立即创建该分组。",
                    fields=listOf(PhoneField("name","分组名称","最多 12 个字",12)),
                    doneHint="请回到电视查看新建的分组。",
                    invalidMessage="请输入 1—12 个字符的分组名称"
                )) { values ->
                    val name=values["name"].orEmpty()
                    if(!library.setGroup(series.id,name)) Toast.makeText(this,"分组数量已达上限或名称无效",Toast.LENGTH_SHORT).show()
                    apply()
                }
                else -> { library.setGroup(series.id,options[index]); apply() }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun settings(parent: LinearLayout, footerHost: LinearLayout) {
        val settingRows=mutableListOf<TextView>()
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val groups=row().apply { gravity=Gravity.TOP }
        val playback=column(); val device=column()
        listOf(playback,device).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                background=rounded(bg)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            settingRows+=view
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var autoSeasonButton: TextView
        autoSeasonButton=setting(playback,"系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}") {
            library.autoSeason=!library.autoSeason
            autoSeasonButton.text="系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}"
        }
        val danmakuPrefRows=mutableListOf<TextView>()
        var danmakuExpanded=false
        var rechainSettings: ()->Unit={}
        lateinit var danmakuToggle: TextView
        fun syncDanmakuRows() {
            val show=library.danmaku
            danmakuToggle.visibility=if(show) View.VISIBLE else View.GONE
            danmakuPrefRows.forEach { it.visibility=if(show && danmakuExpanded) View.VISIBLE else View.GONE }
            danmakuToggle.text="弹幕设置  ·  ${if(danmakuExpanded) "收起" else "展开"}"
            rechainSettings()
        }
        lateinit var danmakuButton: TextView
        danmakuButton=setting(playback,"官方弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}") {
            library.danmaku=!library.danmaku
            danmakuButton.text="官方弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}"
            if(!library.danmaku) danmakuExpanded=false
            syncDanmakuRows()
        }
        danmakuToggle=setting(playback,"弹幕设置  ·  展开") {
            danmakuExpanded=!danmakuExpanded
            syncDanmakuRows()
        }
        lateinit var danmakuOpacity: TextView
        danmakuOpacity=setting(playback,"弹幕不透明度  ·  ${library.danmakuOpacity}%") { showDanmakuOpacityPicker(danmakuOpacity) }
        lateinit var danmakuFont: TextView
        danmakuFont=setting(playback,"弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}") { showDanmakuFontPicker(danmakuFont) }
        lateinit var danmakuSpeed: TextView
        danmakuSpeed=setting(playback,"弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}") { showDanmakuSpeedPicker(danmakuSpeed) }
        lateinit var danmakuRows: TextView
        danmakuRows=setting(playback,"弹幕展示行数  ·  ${library.danmakuRows} 行") { showDanmakuRowsPicker(danmakuRows) }
        lateinit var danmakuReset: TextView
        danmakuReset=setting(playback,"恢复弹幕默认设置") {
            resetDanmakuPrefs()
            danmakuOpacity.text="弹幕不透明度  ·  ${library.danmakuOpacity}%"
            danmakuFont.text="弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}"
            danmakuSpeed.text="弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}"
            danmakuRows.text="弹幕展示行数  ·  ${library.danmakuRows} 行"
            Toast.makeText(this,"弹幕设置已恢复默认",Toast.LENGTH_SHORT).show()
        }
        danmakuPrefRows.addAll(listOf(danmakuOpacity,danmakuFont,danmakuSpeed,danmakuRows,danmakuReset))
        lateinit var blockedWords: TextView
        blockedWords=setting(playback,"弹幕屏蔽词  ·  ${extras.blockedWords.size} 个") { showBlockedWordDialog(blockedWords) }
        lateinit var keySettings: TextView
        keySettings=setting(playback,"遥控器按键  ·  快进 ${extras.seekStepSeconds} 秒") { showKeySettings(keySettings) }
        lateinit var autoSkipButton: TextView
        autoSkipButton=setting(playback,"不兼容片源自动跳过  ·  ${if(extras.autoSkipUnplayable) "开启" else "关闭"}") {
            extras.autoSkipUnplayable=!extras.autoSkipUnplayable
            autoSkipButton.text="不兼容片源自动跳过  ·  ${if(extras.autoSkipUnplayable) "开启" else "关闭"}"
        }
        syncDanmakuRows()

        lateinit var themeButton: TextView
        themeButton=setting(device,"系统风格  ·  ${TvStyle.theme.label}") {
            library.theme=TvStyle.toggle().name; recreate()
        }
        lateinit var clockButton: TextView
        clockButton=setting(device,"显示系统时间  ·  ${if(library.showClock) "开启" else "关闭"}") {
            library.showClock=!library.showClock
            clockButton.text="显示系统时间  ·  ${if(library.showClock) "开启" else "关闭"}"
            applyClock()
        }
        lateinit var backdropButton: TextView
        fun backdropLabel()="自定义背景  ·  ${if(backdropFile()!=null) "已启用" else if(backdropStyle()=="forced") "强制欣赏" else "默认风格"}"
        backdropButton=setting(device,backdropLabel()) {
            tvTools.choose("自定义背景",listOf("手机上传图片","恢复默认背景"),backdropButton,{ choice ->
                when(choice) {
                    0 -> tvTools.backdropTransfer(backdropButton) { bytes,ext -> saveBackdrop(bytes,ext); backdropButton.post { backdropButton.text=backdropLabel() } }
                    1 -> tvTools.choose("恢复默认背景",listOf("强制欣赏","默认风格"),backdropButton,{ style ->
                        resetBackdrop(if(style==0) "forced" else "plain")
                        backdropButton.text=backdropLabel()
                    })
                }
            })
        }
        lateinit var localBackupButton: TextView
        localBackupButton=setting(device,localBackupLabel()) { showLocalBackupDialog(localBackupButton) }
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        lateinit var statsButton: TextView
        statsButton=setting(device,"观看统计  ·  本机累计") { showWatchStats(statsButton) }
        lateinit var sourceHiddenButton: TextView
        sourceHiddenButton=setting(device,"片源隐藏  ·  ${if(library.sourceHidden) "开启" else "关闭"}") {
            // 开关即导航栏「片源」入口的显隐，重建页面后把焦点放回本项
            library.sourceHidden=!library.sourceHidden
            if(library.sourceHidden && tab==6) tab=0
            settingsFocusKey="片源隐藏"
            showCatalog()
        }
        fun cacheBytes()=listOfNotNull(cacheDir,externalCacheDir).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        fun cacheLabel(bytes: Long?)="清理缓存  ·  ${when {
            bytes==null -> "计算中…"
            bytes in 0..1023 -> "$bytes B"; bytes in 1024..1048575 -> "${bytes/1024} KB"; else -> "%.1f MB".format(bytes/1048576f)
        }}"
        lateinit var clearCache: TextView
        clearCache=setting(device,cacheLabel(null)) {
            TvDialog.confirm(this,"清理缓存","清除封面图片和已下载的安装包等缓存数据，不影响收藏和观看记录。") {
                io.execute {
                    listOfNotNull(cacheDir,externalCacheDir).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                    // 统计同样放后台，避免删完立刻在主线程遍历目录
                    val remaining=cacheBytes()
                    main.post {
                        if(isDestroyed) return@post
                        clearCache.text=cacheLabel(remaining)
                        Toast.makeText(this,"缓存已清理",Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
        }
        // 大小统计会遍历缓存目录，放到后台算完再回填，避免设置页构建时阻塞主线程
        io.execute {
            val bytes=cacheBytes()
            main.post { if(!isDestroyed) clearCache.text=cacheLabel(bytes) }
        }
        lateinit var updates: TextView
        updates=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
        lateinit var checkButton: TextView
        checkButton=setting(device,"接口自检  ·  首页 / 搜索 / 播放") { showInterfaceCheck(checkButton) }
        val license=setting(device,"开源许可") {
            TvDialog.info(this,"开源许可","本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：jycao2/hongguoTV。").show()
        }
        // 页脚固定在滚动区之外（屏幕底部居中）：始终可见，因此不再需要抢焦点
        val settingsFooter=text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    所有数据保存在本机",12f,muted).apply {
            id=View.generateViewId(); isFocusable=false; setPadding(dp(10),dp(8),dp(10),dp(3))
        }
        val settingsDisclaimer=text("本应用仅用于技术交流与学习，不提供、不存储、不传播任何视频内容，内容版权归原平台所有。",11f,muted).apply {
            gravity=Gravity.CENTER; setPadding(dp(10),0,dp(10),dp(6))
        }
        rechainSettings={
            val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton,autoSeasonButton,danmakuButton)+
                (if(library.danmaku) listOf(danmakuToggle)+(if(danmakuExpanded) danmakuPrefRows else emptyList()) else emptyList())+
                listOf(blockedWords,keySettings,autoSkipButton)
            val right=listOf(themeButton,clockButton,backdropButton,localBackupButton,backup,hidden,statsButton,sourceHiddenButton,clearCache,updates,checkButton,license)
            listOf(left,right).forEach { items -> items.forEachIndexed { index,view ->
                view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
                // 页脚固定在屏幕上，不再作为焦点链末端，最后一项按下不再移动焦点
                view.nextFocusDownId=items.getOrNull(index+1)?.id ?: View.NO_ID
            } }
            left.forEachIndexed { i,v -> v.nextFocusLeftId=v.id; v.nextFocusRightId=right.getOrNull(i)?.id ?: v.id }
            right.forEachIndexed { i,v -> v.nextFocusLeftId=left.getOrNull(i)?.id ?: v.id; v.nextFocusRightId=v.id }
            nav.forEach { it.nextFocusDownId=qualityButton.id }
        }
        rechainSettings()
        footerHost.addView(settingsFooter,lp(-2,-2))
        footerHost.addView(settingsDisclaimer,lp(-2,-2))
        // 页面重建（如切换片源显隐）后按标签恢复焦点，避免跳回顶部导航
        val wanted=settingsFocusKey.also { settingsFocusKey=null }
        val target=wanted?.let { key -> settingRows.firstOrNull { it.text.toString().startsWith(key) } }
        if(target!=null) target.requestFocus() else nav[tab].requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType,fromPhone: Boolean=false) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        if(library.contentType!=type) { library.contentType=type; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
        // 手机输入的精确剧名默认不再联想，纯首拼或编辑输入后恢复
        phoneSearched=fromPhone
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; searchFocusPending=true; showCatalog(true)
    }
    private fun onAzKey(key: String) {
        val input=searchInput as? EditText ?: return
        when(key) {
            "删除" -> { val length=input.text.length; if(length>0) input.text.delete(length-1,length) }
            "清空" -> input.setText("")
            else -> if(input.text.length<20) input.append(key.lowercase())
        }
        input.setSelection(input.text.length)
        scheduleSuggest()
    }
    private fun scheduleSuggest() {
        main.removeCallbacks(pendingSuggest)
        main.postDelayed(pendingSuggest,300)
    }
    private fun fetchSuggest() {
        if(isDestroyed || tab!=1 || screen!="catalog") return
        val key=(searchInput as? EditText)?.text?.toString()?.trim().orEmpty()
        if(key.isEmpty() || !key.matches(Regex("[a-zA-Z0-9]+"))) {
            suggestTicket++
            // 已提交搜索后（输入框显示的就是搜索词）隐藏联想栏：否则联想卡片在结果未加载时渲染，
            // nextFocusRight 指向自身，导致右键回不到搜索结果、左键又跳进联想列形成焦点陷阱。
            // 用户重新编辑输入框时联想自然恢复
            val keep=if(!phoneSearched && lastSuggestWords.isNotEmpty() && key==lastSuggestKey && key!=query) lastSuggestWords else emptyList()
            renderSuggest(keep); return
        }
        // 手机输入的精确剧名直接出结果，不展示联想；纯首拼仍提供联想辅助
        if(phoneSearched && !key.matches(Regex("[a-zA-Z]+"))) { suggestTicket++; renderSuggest(emptyList()); return }
        val ticket=++suggestTicket
        io.execute {
            val words=repository.suggest(key)
            main.post { if(!isDestroyed && ticket==suggestTicket && tab==1 && screen=="catalog") renderSuggest(words,key) }
        }
    }
    private fun renderSuggest(words: List<String>,key: String="") {
        val container=suggestRow ?: return
        val refocus=container.hasFocus()
        val restore=suggestFocusIndex.takeIf { it in words.indices }
        container.removeAllViews(); suggestFirst=null
        // 记录最近的联想列表，供点击联想词搜索后回显；空列表同时隐藏中栏整列
        lastSuggestWords=words; lastSuggestKey=if(words.isEmpty()) "" else key
        if(words.isEmpty()) {
            suggestFocusIndex=-1
            syncSuggestPanel(false)
            wireSearchPanel(catalogRecycler)
            return
        }
        syncSuggestPanel(true)
        // setSingleLine 开启横向滚动模式，marquee 才按全文宽度布局滚得起来；仅 maxLines=1 只会截断省略
        val views=words.map { word -> button(word) { runSearch(word) }.apply { textSize=14f; minHeight=dp(34); setSingleLine(true); marqueeRepeatLimit=-1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(10),dp(6),dp(10),dp(6)) } }
        views.forEachIndexed { index,view ->
            if(suggestVertical) container.addView(view,lp(-1,-2).apply { bottomMargin=dp(4) })
            else container.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) })
            // 竖排联想：左回键盘离开位置、右/下进结果区（结果首卡优先，否则指向结果网格，避免原地自锁）；横排保持词间左右互连
            view.nextFocusLeftId=if(suggestVertical) (keyboardLastLabel?.let { keyboardKeys[it] } ?: azFirst)?.id ?: historyFirst?.id ?: View.NO_ID else views[if(index==0) 0 else index-1].id
            view.nextFocusRightId=if(suggestVertical) resultFirstCard?.id ?: catalogRecycler?.id ?: view.id else views[if(index==views.size-1) index else index+1].id
            view.nextFocusUpId=if(index==0) searchInput?.id ?: View.NO_ID else views[index-1].id
            view.nextFocusDownId=if(index==views.size-1) resultFirstCard?.id ?: catalogRecycler?.id ?: view.id else views[index+1].id
            // 保留 focusStyle 的焦点绘制，追加词条索引记忆；超长联想词获得焦点时跑马灯滚动全名，失焦回退省略号
            val paint=view.onFocusChangeListener
            view.setOnFocusChangeListener { v,focused ->
                paint?.onFocusChange(v,focused)
                if(focused) suggestFocusIndex=index
                view.ellipsize=if(focused) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                view.isSelected=focused
                view.marqueeRepeatLimit=-1
            }
        }
        suggestFirst=views.firstOrNull()
        wireSearchPanel(catalogRecycler)
        // 联想刷新后优先回到记忆中的词条，避免焦点跳回第一条
        val target=restore?.let { views[it] } ?: suggestFirst
        if(refocus) (target ?: azLastRow.firstOrNull() ?: searchInput)?.requestFocus()
    }
    /** 联想栏动态显隐：无词时中栏整列消失，结果网格按全宽列数重新分布。 */
    private fun syncSuggestPanel(hasWords: Boolean) {
        val scroll=suggestScrollRef ?: return
        val target=if(hasWords) View.VISIBLE else View.GONE
        if(scroll.visibility!=target) {
            scroll.visibility=target
            (catalogRecycler?.layoutManager as? GridLayoutManager)?.spanCount=if(hasWords) searchColumns else fullSearchColumns
            catalogAdapter?.notifyDataSetChanged()
        }
    }
    /** 统一接线：键盘末列右移出键盘、键盘末行 → 历史 → 联想 → 结果网格，历史区可整体缺省。 */
    private fun wireSearchPanel(recycler: View?) {
        // 键盘每行末列按右移出键盘：有联想词进联想，否则直达结果网格
        keyboardRows.forEach { row -> row.last().nextFocusRightId=suggestFirst?.id ?: recycler?.id ?: row.last().id }
        azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: suggestFirst?.id ?: recycler?.id ?: searchInput?.id ?: View.NO_ID }
        historyFirst?.let { first -> first.nextFocusUpId=(keyboardLastLabel?.let { keyboardKeys[it] } ?: azLastRow.firstOrNull())?.id ?: searchInput?.id ?: View.NO_ID }
        historyItems.forEachIndexed { index,item ->
            if(index>0) item.nextFocusUpId=historyItems[index-1].id
            item.nextFocusDownId=historyItems.getOrNull(index+1)?.id ?: recycler?.id ?: searchInput?.id ?: View.NO_ID
            item.nextFocusRightId=suggestFirst?.id ?: recycler?.id ?: View.NO_ID
        }
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun showRandomPicks() {
        if(randomDialog?.isShowing==true) return
        val picks=randomRecommender.pick(12)
        if(picks.isNotEmpty()) { showRandomDialog(picks); return }
        // 池空或近期都展示过：先补充再展示
        showRandomDialog(emptyList(), loading = true)
        replenishRandomPool {
            val next=randomRecommender.pick(12)
            if(next.isNotEmpty()) showRandomDialog(next) else randomDialog?.dismiss().also { Toast.makeText(this,"暂时无法随机荐剧，请稍后重试",Toast.LENGTH_SHORT).show() }
        }
    }

    private fun replenishRandomPool(onDone: () -> Unit) {
        if(randomReplenishing) return
        randomReplenishing=true
        io.execute {
            val added=runCatching { randomRecommender.replenish() }.getOrDefault(0)
            if(added>0) {
                val encoded=RandomPoolSnapshot.encode(randomRecommender.snapshot(), System.currentTimeMillis())
                getSharedPreferences("random_pool", MODE_PRIVATE).edit().putString("pool", encoded).apply()
            }
            main.post { randomReplenishing=false; if(!isDestroyed) onDone() }
        }
    }

    private fun showRandomDialog(items: List<Series>, loading: Boolean=false) {
        randomDialog?.dismiss()
        val body=column().apply { setPadding(0,0,0,0) }
        val title=TvDialog.title(this,if(loading) "正在随机挑选…" else "随机荐剧")
        body.addView(title)
        val content=FrameLayout(this)
        body.addView(content,lp(-1,dp(420)).apply { bottomMargin=dp(10) })
        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        TvDialog.styleWindow(this,dlg)
        randomDialog=dlg
        dlg.show()
        if(items.isEmpty() && !loading) {
            content.addView(text("候选池为空，正在补充…",15f,muted).apply { setPadding(0,dp(20),0,0) })
        } else if(items.isNotEmpty()) {
            // 弹窗专用固定尺寸卡片：宽120dp，上图（3:4固定高）下名称+集数，小图拉伸撑满
            val cardWidth=dp(120)
            val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
            val randomAdapter=object: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=items.size
                override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
                    val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                    val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    card.addView(image,lp(-1,posterHeight))
                    val title=text("",13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
                    val badge=text("",11f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                    card.addView(title,lp(-1,-2)); card.addView(badge,lp(-1,-2))
                    card.layoutParams=RecyclerView.LayoutParams(cardWidth,-2).apply { rightMargin=dp(12); bottomMargin=dp(10) }
                    return object: RecyclerView.ViewHolder(card) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
                    val item=items[position]; val card=holder.itemView as LinearLayout
                    val image=card.getChildAt(0) as ImageView
                    val title=card.getChildAt(1) as TextView
                    val badge=card.getChildAt(2) as TextView
                    card.contentDescription=item.title
                    loadCover(image,item.cover)
                    title.text=item.title; badge.text=item.badge
                    card.setOnClickListener { randomDialog?.dismiss(); openDetail(item,false) }
                    card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) loadCover(image,item.cover) }
                }
            }
            val grid=GridLayoutManager(this,4)
            val recycler=RecyclerView(this).apply { clipToPadding=false; itemAnimator=null }
            recycler.layoutManager=grid; recycler.adapter=randomAdapter
            content.addView(recycler,lp(-1,-1))
        }
        // 「换一批」按钮
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val change=TvDialog.capsuleButton(this,"换一批") {
            dlg.dismiss()
            val picks=randomRecommender.pick(12)
            if(picks.isNotEmpty()) showRandomDialog(picks)
            else { showRandomDialog(emptyList(), loading = true); replenishRandomPool { val n=randomRecommender.pick(12); if(n.isNotEmpty()) showRandomDialog(n) } }
        }
        val close=TvDialog.capsuleButton(this,"关闭") { dlg.dismiss() }
        buttons.addView(change,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(close,lp(-2,-2))
        body.addView(buttons)
        change.requestFocus()
        dlg.setOnDismissListener { randomDialog=null }
    }
    // 退出前二次确认：收藏有更新优先展示，否则从随机荐剧池取 3 部；都没有就直接退出
    private fun showExitConfirm() {
        if(exitDialog?.isShowing==true) return
        val updates=library.updates()
        val updated=library.favorites().filter { (updates[it.id]?.added ?: 0) > 0 }.take(3)
        val fromUpdates=updated.isNotEmpty()
        // 收藏更新优先，其次随机荐剧池，最后退化到首页 7 天缓存随机抽 3 部，全部为本地数据
        val items=if(fromUpdates) updated else randomRecommender.pick(3).ifEmpty { library.cachedHome(library.contentType)?.items?.shuffled()?.take(3) ?: emptyList() }
        val body=column().apply { setPadding(0,0,0,0) }
        val heading=TvDialog.title(this,if(fromUpdates) "收藏更新，看完再走？" else if(items.isNotEmpty()) "猜你喜欢，再看一会儿？" else "确定要退出红果TV吗？")
        heading.gravity=Gravity.CENTER; heading.setPadding(0,0,0,dp(14))
        body.addView(heading,lp(-1,-2))
        val cardWidth=dp(120)
        val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
        val cards=row()
        items.forEach { item ->
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(image,lp(-1,posterHeight))
            val cardTitle=text("",13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
            val badge=text("",11f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
            card.addView(cardTitle,lp(-1,-2)); card.addView(badge,lp(-1,-2))
            card.contentDescription=item.title
            loadCover(image,item.cover)
            cardTitle.text=item.title; badge.text=if(fromUpdates) library.favoriteLabel(item.id) else item.badge
            card.setOnClickListener { exitDialog?.dismiss(); openDetail(item,false) }
            card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) loadCover(image,item.cover) }
            cards.addView(card,lp(cardWidth,-2).apply { rightMargin=dp(12) })
        }
        if(items.isNotEmpty()) body.addView(cards,lp(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
        else body.addView(ImageView(this).apply { setImageResource(R.drawable.exit_placeholder); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            lp(dp(200),dp(200)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(8) })
        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        TvDialog.styleWindow(this,dlg)
        val buttons=row().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(14),0,0) }
        val exitButton=TvDialog.capsuleButton(this,"退出不看了") { killOnDestroy=true; library.flush(); dlg.dismiss(); finish() }
        val stayButton=TvDialog.capsuleButton(this,"再看一会儿") { dlg.dismiss() }
        buttons.addView(exitButton,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(stayButton,lp(-2,-2))
        body.addView(buttons,lp(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(14); bottomMargin=dp(6) })
        exitDialog=dlg
        dlg.show()
        stayButton.requestFocus()
        dlg.setOnDismissListener { exitDialog=null }
    }
    private fun showGenrePicker() {
        val aiMode=tab==2 && aiCatalog
        val anchor=if(aiMode) aiGenreButton ?: return else genreButton ?: return
        val type=if(aiMode) ContentType.AI else library.contentType
        val current=if(aiMode) aiGenre else genre
        io.execute {
            val result=runCatching { repository.genres(type) }
            main.post {
                if(isDestroyed || !foreground || screen!="catalog") return@post
                if(aiMode) { if(tab!=2 || !aiCatalog) return@post } else if(tab!=0) return@post
                result.onSuccess { genres ->
                    tvTools.choose("选择${type.label}题材",listOf("全部题材")+genres.map { it.name },anchor,{ index ->
                        val next=if(index==0) null else genres[index-1]
                        if(next!=current) {
                            if(aiMode) aiGenre=next else genre=next
                            page=1; catalogFocus=""; tabState.remove(tab); tabFocus.remove(tab); showCatalog(true)
                        }
                    })
                }.onFailure { tvTools.info("题材暂不可用","题材列表加载失败，请稍后重试。",anchor) }
            }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}"+library.progress(data.series.id)?.let { "  ·  看到第 ${it.episodeIndex+1} 集" }.orEmpty(),13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        // 评分与收藏数来自详情页同一份数据，卡片列表没有这些字段；缺失时整行不显示
        if(!data.social.isEmpty) {
            val metrics=mutableListOf<String>()
            val score=if(data.social.rating>0) String.format(Locale.CHINA,"%.1f 分",data.social.rating) else ""
            if(score.isNotEmpty()) metrics+=score+data.social.ratingCount.takeIf { it.isNotBlank() }?.let { "（$it 人评分）" }.orEmpty()
            if(data.social.favoriteText.isNotBlank()) metrics+=data.social.favoriteText
            if(data.social.heatText.isNotBlank()) metrics+=data.social.heatText
            val line=SpannableString(metrics.joinToString("  ·  "))
            if(score.isNotEmpty()) {
                line.setSpan(ForegroundColorSpan(accent),0,score.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.setSpan(StyleSpan(Typeface.BOLD),0,score.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            info.addView(TextView(this).apply { text=line; textSize=13f; setTextColor(muted); includeFontPadding=false; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,0,0,dp(6)) })
        }
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        content.addView(hero)
        // 系列剧季导航：同名条目按季号排序，快速跳季并回显每季已看到的集数
        val seasons=(listOf(data.series)+data.sameName).distinctBy { it.id }
            .sortedBy { val number=seasonNumber(it.title); if(number==Int.MAX_VALUE) 0 else number }
        if(seasons.size>1) {
            val seasonRow=row(); seasonRow.setPadding(0,dp(3),0,dp(6))
            seasonRow.addView(text("系列 ${seasons.size} 季",13f,muted),lp(dp(78),dp(42)))
            val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=row(); strip.addView(line)
            seasons.forEach { season ->
                val number=seasonNumber(season.title)
                val seen=library.progress(season.id)?.let { " · 看到${it.episodeIndex+1}集" }.orEmpty()
                val title=if(number==Int.MAX_VALUE) season.title.take(12) else "第 ${number} 季"
                addButton(line,title+seen,season.id==data.series.id) { if(season.id!=data.series.id) openDetail(season) }
            }
            seasonRow.addView(strip,lp(0,dp(42)).apply { weight=1f })
            content.addView(seasonRow)
        }
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        val extraRows=mutableListOf<MutableList<View>>()
        fun recommendationRow(title: String,items: List<Series>) {
            if(items.isEmpty()) return
            val section=column()
            val heading=row(); heading.setPadding(0,dp(20),0,dp(9))
            heading.addView(View(this).apply { background=TvStyle.shape(this@MainActivity,TvStyle.accent,radius=2) },lp(dp(3),dp(14)).apply { rightMargin=dp(8) })
            heading.addView(text(title,16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            heading.addView(text("左右查看更多",10f,muted))
            section.addView(heading)
            val horizontal=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
            val width=((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
            val posterHeight=((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
            val cards=mutableListOf<View>()
            items.take(12).forEach { series ->
                val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=TvStyle.card(this@MainActivity,false); contentDescription="$title，${series.title}" }
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                card.addView(image,lp(-1,posterHeight))
                card.addView(text(series.title,14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) })
                card.addView(text(series.badge.ifBlank { series.tags },12f,muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) })
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) loadCover(image,series.cover) }
                card.setOnClickListener { openDetail(series) }
                if(cards.size<6) loadCover(image,series.cover)
                line.addView(card,lp(width,-2).apply { rightMargin=dp(14) })
                cards+=card
            }
            horizontal.addView(line); section.addView(horizontal)
            content.addView(section,lp(-1,-2).apply { topMargin=dp(4) })
            extraRows+=cards
        }
        recommendationRow("系列剧推荐",data.sameName)
        recommendationRow("相关推荐",data.related)
        val lastRowStart=((episodes.size-1)/10)*10
        val firstRow=extraRows.firstOrNull()
        episodes.forEachIndexed { i,v ->
            v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id
            v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id
            if(i>=10) v.nextFocusUpId=episodes[i-10].id
            v.nextFocusDownId=when {
                i+10<episodes.size -> episodes[i+10].id
                firstRow!=null && i>=lastRowStart -> firstRow[(i-lastRowStart).coerceAtMost(firstRow.lastIndex)].id
                else -> v.id
            }
        }
        extraRows.forEachIndexed { rowIndex,cards ->
            cards.forEachIndexed { i,v ->
                v.nextFocusLeftId=cards[(i-1).coerceAtLeast(0)].id
                v.nextFocusRightId=cards[(i+1).coerceAtMost(cards.lastIndex)].id
                v.nextFocusUpId=if(rowIndex>0) extraRows[rowIndex-1].getOrNull(i.coerceAtMost(extraRows[rowIndex-1].lastIndex))?.id ?: play.id
                    else episodes.getOrNull((lastRowStart+i).coerceAtMost(episodes.lastIndex))?.id ?: play.id
                v.nextFocusDownId=if(rowIndex<extraRows.lastIndex) extraRows[rowIndex+1].getOrNull(i.coerceAtMost(extraRows[rowIndex+1].lastIndex))?.id ?: v.id else v.id
            }
        }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        updater.interrupt()
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews(); stopDanmaku()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        val skipMark=library.skipMark(data.series.id)
        outroSkipSeconds=skipMark?.outroSeconds ?: 0; outroTriggered=false; ffActive=false
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            val danmakuLayer=DanmakuView(this)
            danmakuView=danmakuLayer
            danmakuLayer.setBlockedFilter { text -> extras.blocks(text) }
            danmakuLayer.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
            root.addView(danmakuLayer,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.38f).toInt(),Gravity.TOP).apply { topMargin=dp(12) })
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    长按右键 3× 快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        applyClock()
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true; incompatibleSkips=0
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}"); startDanmaku() }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && continueToNextSeason()) { /* season jump in progress */ }
                        else { requestedAutoplay=false; p.pause(); showEndedPanel() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) { syncMediaSession(); danmakuView?.seekTo(newPosition.positionMs) } }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            val introSkip=if(position<=0 && (skipMark?.introSeconds ?: 0)>0) skipMark!!.introSeconds*1000L else position.coerceAtLeast(0)
            p.setMediaSource(source); p.seekTo(introSkip); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            if(introSkip>0 && position<=0) Toast.makeText(this,"已跳过片头 ${skipMark!!.introSeconds} 秒",Toast.LENGTH_SHORT).show()
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        // 记住播放身份的匿名 Cookie 罐必须继承，否则预加载与正式播放不是同一个会话。
        val http=repository.http.newBuilder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared near end")
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val incompatible=problem is UnplayableVideoException
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=!incompatible && (if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException)
        playbackText.text=when {
            incompatible -> "本集片源暂不兼容：平台未提供电视可解码的清晰度，可播放下一集。"
            decoding -> "电视无法解码当前视频，可尝试更低清晰度。"
            else -> "播放失败，可重试或切换清晰度。"
        }
        val actions=row(); controls.addView(actions)
        if(incompatible) {
            val hasNext=episodeIndex<detail?.episodes.orEmpty().lastIndex
            if(hasNext && extras.autoSkipUnplayable && incompatibleSkips<5) {
                // 整批集数可能都是不可解码片源：最多连跳 5 集，避免无限空转
                incompatibleSkips++
                playbackText.text="本集片源暂不兼容，自动跳过 · 已跳过 $incompatibleSkips 集"
                addButton(actions,"返回选集") { returnToDetail(true) }
                main.postDelayed({ if(foreground && screen=="player" && playError) playEpisode(episodeIndex+1) },1200)
            } else {
                if(hasNext) addButton(actions,"播放下一集") { playEpisode(episodeIndex+1) }.requestFocus()
                val back=addButton(actions,"返回选集") { returnToDetail(true) }
                if(!hasNext) back.requestFocus()
            }
        } else {
            addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
            addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
            addButton(actions,"返回选集") { returnToDetail(true) }
        }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.e("HongguoTV","Playback failure: ${problem.javaClass.simpleName} msg=${problem.message}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""), problem)
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!="player") return
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && !ffActive && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { ffActive -> "3× 快进中"; p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun applyDanmakuPrefs()=danmakuView?.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
    private fun resetDanmakuPrefs() {
        library.danmakuOpacity=DanmakuPrefs.OPACITY_DEFAULT; library.danmakuFont=DanmakuPrefs.FONT_DEFAULT
        library.danmakuSpeed=DanmakuPrefs.SPEED_DEFAULT; library.danmakuRows=DanmakuPrefs.ROWS_DEFAULT
        applyDanmakuPrefs()
    }
    private fun showDanmakuOpacityPicker(anchor: TextView,done: (()->Unit)?=null) {
        val options=listOf(100,90,80,70,60,50,40,30,21)
        tvTools.choose("弹幕不透明度 · ${library.danmakuOpacity}%",options.map { "$it%" },anchor,{ choice ->
            library.danmakuOpacity=options[choice]; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕不透明度  ·  ${library.danmakuOpacity}%"
            done?.invoke()
        })
    }
    private fun showDanmakuFontPicker(anchor: TextView,done: (()->Unit)?=null) {
        tvTools.choose("弹幕字体大小 · ${DanmakuPrefs.fontLabel(library.danmakuFont)}",DanmakuPrefs.FONT_LABELS,anchor,{ choice ->
            library.danmakuFont=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}"
            done?.invoke()
        })
    }
    private fun showDanmakuSpeedPicker(anchor: TextView,done: (()->Unit)?=null) {
        tvTools.choose("弹幕移动速度 · ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}",DanmakuPrefs.SPEED_LABELS,anchor,{ choice ->
            library.danmakuSpeed=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}"
            done?.invoke()
        })
    }
    private fun showDanmakuRowsPicker(anchor: TextView,done: (()->Unit)?=null) {
        tvTools.choose("弹幕展示行数 · ${library.danmakuRows} 行",(DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).map { "$it 行" },anchor,{ choice ->
            library.danmakuRows=choice+DanmakuPrefs.ROWS_MIN; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.text="弹幕展示行数  ·  ${library.danmakuRows} 行"
            done?.invoke()
        })
    }
    /** In-player danmaku preferences; reopens itself after each change so several can be tuned in one visit. */
    private fun showDanmakuPrefsDialog(anchor: TextView) {
        fun labels()=listOf("不透明度 ${library.danmakuOpacity}%","字体大小 ${DanmakuPrefs.fontLabel(library.danmakuFont)}","移动速度 ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}","展示行数 ${library.danmakuRows} 行","恢复默认设置")
        tvTools.choose("弹幕设置",labels(),anchor,{ choice ->
            main.post {
                if(screen!="player" || !anchor.isAttachedToWindow) return@post
                when(choice) {
                    0 -> showDanmakuOpacityPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    1 -> showDanmakuFontPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    2 -> showDanmakuSpeedPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    3 -> showDanmakuRowsPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    4 -> { resetDanmakuPrefs(); showDanmakuPrefsDialog(anchor) }
                }
            }
        })
    }
    /** 弹幕屏蔽词：常用词多选 + 保留用户已添加的自定义词；确认后立即对正在播放的弹幕生效。 */
    private fun showBlockedWordDialog(anchor: TextView) {
        val presets=listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手")
        val current=extras.blockedWords
        val words=(presets+current.filterNot { it in presets }).distinct()
        val checked=BooleanArray(words.size) { words[it] in current }
        val content=column()
        content.addView(TvDialog.title(this,"弹幕屏蔽词"))
        val list=ListView(this).apply {
            adapter=TvDialog.choiceAdapter(this@MainActivity,words,multiple=true)
            choiceMode=ListView.CHOICE_MODE_MULTIPLE
            words.forEachIndexed { index,word -> setItemChecked(index,checked[index]) }
            setOnItemClickListener { _,_,index,_ -> checked[index]=!checked[index] }
        }
        content.addView(list,lp(-1,0).apply { weight=1f })
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val clear=TvDialog.capsuleButton(this,"清空") {
            extras.blockedWords=emptySet(); applyBlockedWords(); anchor.text="弹幕屏蔽词  ·  0 个"
            list.clearChoices(); list.requestLayout()
        }
        val save=TvDialog.capsuleButton(this,"保存") {
            extras.blockedWords=words.filterIndexed { index,_ -> list.isItemChecked(index) }.toSet()
            applyBlockedWords(); anchor.text="弹幕屏蔽词  ·  ${extras.blockedWords.size} 个"
            Toast.makeText(this@MainActivity,"屏蔽词已保存，立即生效",Toast.LENGTH_SHORT).show()
        }
        buttons.addView(clear,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(save,lp(-2,-2))
        content.addView(buttons)
        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); list.requestFocus()
    }
    private fun applyBlockedWords() = danmakuView?.setBlockedFilter { text -> extras.blocks(text) }

    /** 本机观看统计：直接按最近观看记录汇总，不额外存储、不产生任何网络请求。 */
    private fun showWatchStats(anchor: TextView) {
        val rows=library.history()
        if(rows.isEmpty()) { tvTools.info("观看统计 · 本机","还没有观看记录，播放几集后再来看。",anchor); return }
        val totalMs=rows.sumOf { it.position.coerceAtLeast(0) }
        val minutes=totalMs/60_000
        val finished=rows.count { it.completed }
        val tags=rows.flatMap { it.series.tags.split(" · ").map(String::trim).filter(String::isNotEmpty) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
        val body=buildString {
            append("累计观看 ${rows.size} 集，共 ${minutes} 分钟（约 ${minutes/60} 小时 ${minutes%60} 分钟）。\n")
            append("完整看完 $finished 集，涉及 ${rows.map { it.series.id }.distinct().size} 部剧。\n")
            if(tags.isNotEmpty()) {
                append("\n题材分布\n")
                tags.forEach { (tag,count) -> append("· $tag  $count 集\n") }
            }
            append("\n统计来自本机观看记录，不上传、不联网。")
        }
        tvTools.info("观看统计 · 本机",body,anchor)
    }

    /** 接口自检：用户逐项点击，单次串行请求，失败只提示不重试，避免给站点增加并发压力。
     *  状态圆点内嵌在按钮文字前：灰=未检测，橙=检测中，绿=通，红=不通；3 秒内重复点击直接忽略。 */
    private fun showInterfaceCheck(anchor: TextView) {
        val content=column()
        val status=text("选择一项开始检测",14f,muted).apply { setPadding(dp(8),0,dp(8),dp(10)) }
        content.addView(status)
        lateinit var dialog: AlertDialog
        val actions=row()
        val buttons=mutableListOf<TextView>()
        val dotIdle=TvStyle.outline
        val dotOk=Color.rgb(86,196,122)
        val dotFail=Color.rgb(235,95,95)
        // 每个按钮独立的冷却与在检状态，互不影响
        val lastAt=mutableMapOf<String,Long>()
        val busy=mutableSetOf<String>()
        fun dotLabel(name: String,color: Int): CharSequence {
            val span=android.text.SpannableString("●  $name")
            span.setSpan(object: android.text.style.ReplacementSpan() {
                override fun getSize(paint: android.graphics.Paint,text: CharSequence?,start: Int,end: Int,fm: android.graphics.Paint.FontMetricsInt?): Int =
                    (paint.textSize*0.55f).toInt()
                override fun draw(canvas: android.graphics.Canvas,text: CharSequence?,start: Int,end: Int,x: Float,top: Int,y: Int,bottom: Int,paint: android.graphics.Paint) {
                    val r=paint.textSize*0.22f
                    val cy=(top+bottom)/2f
                    val oldColor=paint.color
                    paint.color=color
                    canvas.drawCircle(x+r,cy,r,paint)
                    paint.color=oldColor
                }
            },0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
        fun check(name: String,btn: TextView,probe: ()->String) {
            val now=System.currentTimeMillis()
            if(name in busy || now-(lastAt[name] ?: 0L)<3_000) { status.text="$name 请稍候再试"; return }
            lastAt[name]=now
            busy+=name; status.text="正在检测 $name…"
            btn.text=dotLabel(name,accent)
            val started=android.os.SystemClock.elapsedRealtime()
            io.execute {
                val result=runCatching(probe)
                main.post {
                    if(!dialog.isShowing) return@post
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    result.fold(
                        { detail -> status.text="$name · 正常 · $detail · ${elapsed}ms"; btn.text=dotLabel(name,dotOk) },
                        { problem -> status.text="$name · 失败 · ${(problem.message ?: problem.javaClass.simpleName).take(80)}"; btn.text=dotLabel(name,dotFail) })
                    busy-=name
                }
            }
        }
        fun add(name: String,probe: ()->String): TextView {
            lateinit var btn: TextView
            btn=button(name) { check(name,btn,probe) }
            btn.text=dotLabel(name,dotIdle)
            buttons+=btn
            actions.addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
            return btn
        }
        add("首页") { val page=repository.home(1,library.contentType); "返回 ${page.items.size} 条" }
        add("搜索") { val page=repository.search("修仙",1,library.contentType); "返回 ${page.items.size} 条" }
        add("播放") {
            val progress=library.history().firstOrNull() ?: throw IllegalStateException("暂无观看记录，先播放一集")
            val info=repository.stream(progress.episodeId,library.maxQuality); "获取成功 · ${info.quality}"
        }
        add("联想") { val words=repository.suggest("修仙"); "联想 ${words.size} 个" }
        content.addView(actions)
        content.addView(text("说明：每项只发一次请求，签名搜索接口被平台停用时「搜索」会走网站兜底。",12f,muted).apply { setPadding(dp(8),dp(10),dp(8),0) })
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); buttons.first().requestFocus()
    }

    /** 遥控器按键：就地循环切换取值，避免多层弹窗来回跳焦点。 */
    private fun showKeySettings(anchor: TextView) {
        val content=column()
        fun sync(anchorText: TextView) { anchorText.text="遥控器按键  ·  快进 ${extras.seekStepSeconds} 秒" }
        lateinit var step: TextView
        step=button("快进步长  ·  ${extras.seekStepSeconds} 秒") {
            val options=PlaybackExtras.SEEK_STEPS
            extras.seekStepSeconds=options[(options.indexOf(extras.seekStepSeconds)+1)%options.size]
            step.text="快进步长  ·  ${extras.seekStepSeconds} 秒"; sync(anchor)
        }
        lateinit var speed: TextView
        speed=button("长按快进  ·  ${PlaybackExtras.FAST_SPEEDS.first { it==extras.longPressSpeed }.toInt()}×") {
            val options=PlaybackExtras.FAST_SPEEDS
            extras.longPressSpeed=options[(options.indexOf(extras.longPressSpeed)+1)%options.size]
            speed.text="长按快进  ·  ${extras.longPressSpeed.toInt()}×"
        }
        fun menuLabel()=when(extras.menuKeyAction) {
            PlaybackExtras.MENU_EPISODES -> "选集"
            PlaybackExtras.MENU_DANMAKU -> "弹幕开关"
            else -> "播放设置"
        }
        lateinit var menu: TextView
        menu=button("菜单键  ·  ${menuLabel()}") {
            extras.menuKeyAction=when(extras.menuKeyAction) {
                PlaybackExtras.MENU_EPISODES -> PlaybackExtras.MENU_DANMAKU
                PlaybackExtras.MENU_DANMAKU -> PlaybackExtras.MENU_PANEL
                else -> PlaybackExtras.MENU_EPISODES
            }
            menu.text="菜单键  ·  ${menuLabel()}"
        }
        listOf(step,speed,menu).forEachIndexed { index,view ->
            content.addView(view,lp(-1,dp(48)).apply { bottomMargin=dp(8) })
            view.nextFocusDownId=listOf(step,speed,menu).getOrNull(index+1)?.id ?: View.NO_ID
            view.nextFocusUpId=listOf(step,speed,menu).getOrNull(index-1)?.id ?: View.NO_ID
        }
        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); step.requestFocus()
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else extras.seekStepSeconds*1000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun startFastForward() {
        val p=player ?: return
        if(ffActive || !playbackReady || p.duration<=0) return
        ffActive=true; ffSpeedBefore=p.playbackParameters.speed
        p.setPlaybackSpeed(extras.longPressSpeed); showHud(); updatePlaybackText()
    }
    private fun stopFastForward() {
        if(!ffActive) return
        ffActive=false; player?.setPlaybackSpeed(ffSpeedBefore)
        updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        lateinit var episodes: TextView
        episodes=addButton(transport,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(transport,"播放设置") {
            val seriesId=detail?.series?.id.orEmpty()
            val mark=library.skipMark(seriesId)
            val danmakuOn=extras.danmakuEnabled(seriesId,library.danmaku)
            fun skipLabel(seconds: Int)=if(seconds>0) "$seconds 秒" else "关闭"
            tvTools.choose("播放设置",buildList {
                add("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}"); add("清晰度 ${library.maxQuality}P"); add("画面 ${library.frameMode.label}"); add("定时 ${sleepTimer.label()}")
                add("跳过片头 ${skipLabel(mark?.introSeconds ?: 0)}"); add("跳过片尾 ${skipLabel(mark?.outroSeconds ?: 0)}"); add("从头播放"); add("弹幕 ${if(danmakuOn) "开启" else "关闭"}（仅本剧）")
                if(danmakuOn) add("弹幕设置")
            },more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> showSkipPicker(more,true)
                        5 -> showSkipPicker(more,false)
                        6 -> { seekTo(0); hidePanel() }
                        7 -> toggleSeriesDanmaku(seriesId)
                        8 -> showDanmakuPrefsDialog(more)
                    }
                }
            })
        }.apply { tag="playback-settings" }
        play.requestFocus()
    }
    /** Season finale auto-continues into the next season only when both the master autoplay and the season switch are on. */
    private fun continueToNextSeason(): Boolean {
        val data=detail ?: return false
        if(!library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) return false
        val next=data.nextSeason ?: return false
        Toast.makeText(this,"即将播放《${next.title}》",Toast.LENGTH_SHORT).show()
        openDetail(next,resume=true,autoplay=true)
        return true
    }
    private fun showEndedPanel() {
        val data=detail
        if(playError || data==null || !library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) {
            playbackText.text="本集已结束"; showPanel(); return
        }
        val cached=library.cachedHome(library.contentType)?.items.orEmpty().filter { it.id!=data.series.id }
        if(cached.isNotEmpty()) { showRecommendationPanel(cached); return }
        playbackText.text="本集已结束"; showPanel()
        // The home cache can be empty on a cold start that went straight into playback; fetch once and upgrade the panel.
        work({ repository.home(1,library.contentType) }, { page ->
            if(screen=="player" && player?.playbackState==Player.STATE_ENDED && detail?.series?.id==data.series.id) {
                val items=page.items.filter { it.id!=data.series.id }
                if(items.isNotEmpty()) showRecommendationPanel(items)
            }
        }, { /* keep the plain ended panel */ })
    }
    private fun showRecommendationPanel(items: List<Series>) {
        if(playError || screen!="player") return
        panel=true; controls.visibility=View.VISIBLE; controls.removeAllViews()
        controls.addView(text("《${detail?.series?.title}》已播完 · 为你推荐热门${library.contentType.label}",14f,muted).apply { setPadding(dp(4),0,0,dp(8)) })
        val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
        val line=row(); strip.addView(line); controls.addView(strip)
        items.take(10).forEach { item ->
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(8)); focusStyle(card)
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(image,lp(dp(96),(dp(96)/TvStyle.POSTER_ASPECT).toInt()))
            loadCover(image,item.cover)
            card.addView(text(item.title,12f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(4),dp(2),0) },lp(dp(96),-2))
            card.contentDescription=item.title
            card.setOnClickListener { openDetail(item) }
            line.addView(card,lp(-2,-2).apply { rightMargin=dp(10) })
        }
        val actions=row().apply { setPadding(0,dp(10),0,0) }; controls.addView(actions)
        addButton(actions,"返回详情") { returnToDetail(true) }
        line.getChildAt(0)?.requestFocus()
        showHud()
    }
    private fun showSkipPicker(anchor: TextView,intro: Boolean) {
        val data=detail ?: return
        val options=listOf(0,15,30,45,60,90,120)
        val current=library.skipMark(data.series.id)?.let { if(intro) it.introSeconds else it.outroSeconds } ?: 0
        tvTools.choose((if(intro) "跳过片头" else "跳过片尾")+" · 仅对本剧生效",options.map { if(it==0) "关闭" else "$it 秒" },anchor,{ index ->
            val old=library.skipMark(data.series.id) ?: SkipMark(0,0)
            val next=if(intro) old.copy(introSeconds=options[index]) else old.copy(outroSeconds=options[index])
            library.setSkipMark(data.series.id,next)
            if(!intro) { outroSkipSeconds=next.outroSeconds; outroTriggered=false }
            Toast.makeText(this,"已保存，仅对《${data.series.title}》生效",Toast.LENGTH_SHORT).show()
            showHud()
        },"当前 ${if(current>0) "$current 秒" else "关闭"}") {}
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val content=column()
        content.addView(TvDialog.title(this,"全局默认倍速"))
        val list=ListView(this).apply {
            adapter=TvDialog.choiceAdapter(this@MainActivity,speeds.map(PlaybackSpeed::label))
            choiceMode=ListView.CHOICE_MODE_SINGLE
            setItemChecked(selected,true)
            setOnItemClickListener { _,_,index,_ ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                speedDialog?.dismiss()
            }
        }
        content.addView(list,lp(-1,0).apply { weight=1f })
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val cancel=TvDialog.capsuleButton(this,"取消") { speedDialog?.dismiss() }
        buttons.addView(cancel,lp(-2,-2))
        content.addView(buttons)
        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); list.setSelection(selected); list.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val selected=options.indexOf(library.maxQuality)
        val content=column()
        content.addView(TvDialog.title(this,"全局默认清晰度"))
        val list=ListView(this).apply {
            adapter=TvDialog.choiceAdapter(this@MainActivity,options.map { "${it}P" })
            choiceMode=ListView.CHOICE_MODE_SINGLE
            setItemChecked(selected,true)
            setOnItemClickListener { _,_,index,_ ->
                val target=options[index]; val changed=library.maxQuality!=target
                library.maxQuality=target
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }
        }
        content.addView(list,lp(-1,0).apply { weight=1f })
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val cancel=TvDialog.capsuleButton(this,"取消") { }
        buttons.addView(cancel,lp(-2,-2))
        content.addView(buttons)
        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        cancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); list.setSelection(selected); list.requestFocus()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun backdropFile()=filesDir.listFiles()?.firstOrNull { it.name.startsWith("backdrop.") }
    /** Scale [raw] to fill the screen and lay a theme-colored scrim over it so text stays readable. */
    private fun frameBackdrop(raw: Bitmap): Bitmap? {
        val width=resources.displayMetrics.widthPixels; val height=resources.displayMetrics.heightPixels
        if(width<=0 || height<=0) return null
        val scale=maxOf(width.toFloat()/raw.width,height.toFloat()/raw.height)
        val drawWidth=(raw.width*scale).toInt(); val drawHeight=(raw.height*scale).toInt()
        val scaled=if(drawWidth==raw.width && drawHeight==raw.height) raw else Bitmap.createScaledBitmap(raw,drawWidth,drawHeight,true)
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(scaled,(width-drawWidth)/2f,(height-drawHeight)/2f,null)
            drawColor(Color.argb(205,Color.red(bg),Color.green(bg),Color.blue(bg)))
        }
        if(scaled!==raw) raw.recycle()
        return output
    }
    private fun decodeBackdrop(bytes: ByteArray): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        var sample=1
        val width=resources.displayMetrics.widthPixels; val height=resources.displayMetrics.heightPixels
        while(bounds.outWidth/(sample*2)>=width && bounds.outHeight/(sample*2)>=height) sample*=2
        val raw=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        return frameBackdrop(raw)
    }
    /** The bundled "强制欣赏" backdrop: first-launch default, selectable from 恢复默认背景. */
    private fun decodeForcedBackdrop(): Bitmap? =
        BitmapFactory.decodeResource(resources,R.drawable.backdrop_forced)?.let { frameBackdrop(it) }
    /** Built-in backdrop style: "forced" (bundled picture, default) or "plain" (theme color only). */
    private fun backdropStyle()=getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).getString("backdropStyle","forced") ?: "forced"
    private fun loadBackdrop() {
        io.execute {
            val bitmap=backdropFile()?.let { runCatching { decodeBackdrop(it.readBytes()) }.getOrNull() }
                ?: if(backdropStyle()=="forced") runCatching { decodeForcedBackdrop() }.getOrNull() else null
            main.post {
                if(isDestroyed) return@post
                backdropBitmap=bitmap
                backdropView?.let {
                    if(bitmap==null) { (it.parent as? android.view.ViewGroup)?.removeView(it); it.setImageDrawable(null) }
                    else { if(it.parent==null && screen!="player") root.addView(it,0,FrameLayout.LayoutParams(-1,-1)); it.setImageBitmap(bitmap) }
                }
            }
        }
    }
    private fun saveBackdrop(bytes: ByteArray,extension: String) {
        io.execute {
            val bitmap=runCatching {
                filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() }
                java.io.File(filesDir,"backdrop.$extension").writeBytes(bytes)
                decodeBackdrop(bytes)
            }.getOrNull()
            main.post {
                if(isDestroyed) return@post
                if(bitmap==null) { Toast.makeText(this@MainActivity,"图片无法使用，请换一张试试",Toast.LENGTH_LONG).show(); return@post }
                backdropBitmap=bitmap
                backdropView?.let {
                    if(it.parent==null && screen!="player") root.addView(it,0,FrameLayout.LayoutParams(-1,-1))
                    it.setImageBitmap(bitmap)
                }
                Toast.makeText(this@MainActivity,"背景已更新",Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** Drop any uploaded picture and switch back to one of the built-in styles. */
    private fun resetBackdrop(style: String) {
        io.execute { filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() } }
        getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).edit().putString("backdropStyle",style).apply()
        loadBackdrop()
        Toast.makeText(this,if(style=="forced") "已恢复为强制欣赏" else "已恢复默认背景",Toast.LENGTH_SHORT).show()
    }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    /** Starts the official-comment overlay for the current episode; safe to call repeatedly. */
    private fun startDanmaku() {
        val layer=danmakuView ?: return
        val data=detail ?: return
        val p=player
        if(!extras.danmakuEnabled(data.series.id,library.danmaku) || p==null || !playbackReady || playError || p.duration<=0) return
        danmakuLoader=null
        val loader=DanmakuLoader(danmakuRepo,data.series.id,data.episodes[episodeIndex],p.duration)
        danmakuLoader=loader
        layer.setBlockedFilter { text -> extras.blocks(text) }
        layer.bind(loader,{ currentPosition() })
        val startPosition=currentPosition()
        io.execute { runCatching { loader.loadUpTo(startPosition) } }
    }
    /** 播放中切换当前剧的弹幕并记住：全局弹幕关闭时先提示打开总开关，避免出现「开了却看不到」。 */
    private fun toggleSeriesDanmaku(seriesId: String) {
        if(!library.danmaku) {
            Toast.makeText(this,"弹幕总开关已关闭，请先在设置或播放设置中开启",Toast.LENGTH_SHORT).show(); return
        }
        val on=!extras.danmakuEnabled(seriesId,true)
        extras.setDanmakuEnabled(seriesId,on)
        if(on) startDanmaku() else stopDanmaku()
        Toast.makeText(this,"已为《${detail?.series?.title.orEmpty()}》${if(on) "开启" else "关闭"}弹幕",Toast.LENGTH_SHORT).show()
    }
    private fun stopDanmaku() {
        danmakuLoader=null
        danmakuView?.unbind()
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        ffActive=false; outroSkipSeconds=0; outroTriggered=false
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        stopDanmaku(); danmakuView=null
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(player?.playbackState==Player.STATE_ENDED) returnToDetail() else if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> showCatalog()
            else -> if(tab==6 && third?.onBack()==true) {} else if(tab==2 && aiCatalog) { aiCatalog=false; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true); aiEntry?.post { if(aiEntry?.isAttachedToWindow==true) aiEntry?.requestFocus() } } else if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else showExitConfirm() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(tab==6 && third?.onKey(event)==true) return true
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_UP) { stopFastForward(); return true }
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if(event.repeatCount==0) seek(1,0) else startFastForward()
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) when(extras.menuKeyAction) {
                            PlaybackExtras.MENU_EPISODES -> showEpisodePanel(hud)
                            PlaybackExtras.MENU_DANMAKU -> detail?.series?.id?.let { toggleSeriesDanmaku(it) }
                            else -> showPanel()
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        updater.pause()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); third?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        third?.stop()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        third?.release()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
        if(killOnDestroy) android.os.Process.killProcess(android.os.Process.myPid())
    }
}
