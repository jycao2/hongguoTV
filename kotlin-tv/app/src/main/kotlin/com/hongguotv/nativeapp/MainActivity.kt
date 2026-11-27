// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Movie
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import com.hongguotv.nativeapp.vc2.Vc2
import com.hongguotv.nativeapp.vc2.Vc2NativeDecoder
import java.util.Locale
import java.util.concurrent.Executors
import com.hongguotv.core.ThirdCms

private const val TYPE_CARD=0
private const val TYPE_FOOTER=1
private const val TYPE_HEADER=2
private const val TYPE_MORE=3
private const val FOOTER_NONE=0
private const val FOOTER_LOADING=1
private const val FOOTER_END=2
private const val FOOTER_ERROR=3
private const val FOOTER_MORE_HOT=4
// 上新区的时间窗，id 直接对应服务端 online_time 维度的选项；循环切换只在这四个里转
private val NEW_RELEASE_WINDOWS=listOf("days_7" to "近7天","days_14" to "近14天","days_30" to "近30天","days_90" to "近90天")

/**
 * 切换系统风格 / 界面缩放必须 recreate() 让新主题和新密度生效，而重建后的 Activity
 * 是一份全新实例，页面状态全部归零（会退回首页）。这里在重建前记下当前页签和焦点所在行，
 * 由新实例在 onCreate 里取走一次；正常冷启动不会命中，不影响桌面图标进来的行为。
 */
private object PageRestore {
    var tab=-1
    var focusKey: String?=null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    /** 界面整体缩放：包一层调过密度的 Context，dp/sp 等比生效（见 UiScale 注释）。 */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(base))
        // 必须早于任何 OkHttpClient / HttpURLConnection 构建装上全局 ProxySelector，
        // 否则客户端捕获的是旧 selector，运行中切换代理对已存在的客户端不生效
        AppProxy.install(base)
    }
    private val bg by lazy { TvStyle.background }; private val surface by lazy { TvStyle.surface }
    private val accent by lazy { TvStyle.accent }; private val white by lazy { TvStyle.text }; private val muted by lazy { TvStyle.muted }
    private val gold by lazy { TvStyle.gold }
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

    // ——— 推荐流（官方「猜你喜欢」个性化流）：上下换剧、播完自动下一部、确认进详情 ———
    /** 推荐用设备身份：必须注册过的干净身份，裸随机身份 tab/v 一律 110 ILLEGAL_ACCESS。 */
    private val recommendIdentity by lazy {
        RecommendIdentity(repository.http, object: RecommendIdentity.IdentityStore {
            private val prefs=getSharedPreferences("recommend-identity-v1", MODE_PRIVATE)
            override fun get(key: String): String?=prefs.getString(key, null)
            override fun put(key: String,value: String) { prefs.edit().putString(key, value).apply() }
        })
    }
    /** 短超时专用 client：推荐接口被风控挂起时最多等 2 个 host × 9s，不至于把首屏拖到 45 秒。 */
    private val discovery by lazy {
        DiscoveryRepository(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
                .cookieJar(repository.http.cookieJar)
                .build(),
            deviceProvider = { recommendIdentity.device() },
            loginCookie = { runCatching { accountManager.account?.cookie }.getOrNull() },
        )
    }
    /** 推荐队列：按官方流顺序累积；详情缓存与首集地址缓存让上下换剧不用现等网络。 */
    private val recommendItems=mutableListOf<Series>()
    private val recommendDetails=java.util.concurrent.ConcurrentHashMap<String,Detail>()
    /** 首集地址缓存（10 分钟 TTL）：命中后 playEpisode 连取址请求都省掉。 */
    private val recommendStreams=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,StreamInfo>>()
    /** 已 prepare 好的首集流（只给紧邻的下一部备一份）：换剧时省掉现场下载首块的 0.5~1 秒 */
    private val recommendVideos=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,RemoteVideo>>()
    private var recommendCursor=-1
    private var recommendLoading=false
    private var recommendEnd=false
    private var recommendStatus=""
    private var recommendGeneration=0
    /** 最近一次预取的起点；Int.MIN_VALUE=没预取过（-1 是预热场景的合法起点）。 */
    private var recommendPrefetching=Int.MIN_VALUE
    private val recommendWaiters=mutableListOf<(Boolean)->Unit>()
    /** 自由刷剧过渡动画最短停留：够看清提示，又不至于让人觉得卡住。 */
    private val FEED_TRANSITION_MS=900L
    /** 自动预拉只做一次：失败后停在落点页等用户手动重试，别在失败里反复打接口。 */
    private var recommendAutoTried=false
    /** true=当前播放页是推荐 feed：键位与视觉换成 feed 语义（↑↓ 换剧、确认进详情、海报背景）。 */
    private var recommendMode=false
    private var freeFeedButton: TextView?=null
    private var feedTransition: View?=null
    private var feedEntering=false
    /** 自由刷剧从哪个页签进入，返回时就回哪（用户 2026-11-18）。 */
    private var feedReturnTab=0
    /** 用户在本剧按过 ←/→ 换集：开启本剧连播，不再自动下滑换剧（用户 2026-11-18）。 */
    private var feedSeriesBinge=false
    /** feed 里按确认进了详情页：BACK 要回到刷剧而不是首页（用户 2026-11-18）。 */
    private var feedDetailBrowsing=false
    /** feed 键位提示只在每次进入刷剧时亮一次，之后的自动换集/换剧不再唤起 HUD。 */
    private var feedHintShown=false
    /** 「已开启本剧连播」这句提示每次进自由刷剧只给一次：每按一次右键弹一次太吵（用户 2026-11-19）。 */
    private var bingeHintShown=false
    private var homeMenuFired=false
    private val homeMenuRunnable=Runnable { homeMenuFired=true; enterFreeFeed() }
    /** 首页静默预热（每会话一次）：推荐队列 + 紧邻下一部的详情与首集取址备好，
     *  点「自由刷剧」/长按菜单进来基本只剩缓冲时间（用户 2026-11-18：首次进入偏慢）。
     *  2026-11-23 只备第一部：停在首页也照发 2 个取址请求纯属白添风控密度。 */
    private var feedPrewarmed=false
    private val feedPrewarmRunnable=Runnable {
        if(feedPrewarmed || isDestroyed || !foreground) return@Runnable
        if(screen!="catalog" || tab!=0) return@Runnable
        feedPrewarmed=true
        ensureRecommendFeed { ok -> if(ok) prefetchRecommend(-1) }
    }
    /** 连播时连续起播失败计数：连续 3 部放不出来就退回推荐页，避免无限自动跳。 */
    private var recommendFailStreak=0

    private var exitDialog: AlertDialog?=null
    private var killOnDestroy=false
    private var third: ThirdSourceScreen?=null
    /** 手机遥控会话。独立于 TvTools 持有：那边每次打开新弹窗都会 dismiss 上一个。 */
    private var remote: PhoneRemoteSession?=null
    private var remoteHolding=false
    private var thirdWantsScreenOn=false
    /** 播放器自己是否要求常亮。显式持有位，不靠 screen/requestedAutoplay 反推。 */
    private var playerScreenOn=false
    private var remotePublishedAt=0L
    /** 正在兑现缓存意图，防止 remotePublish 递归消费。 */
    private var remoteApplying=false
    private var remotePendingSeek: Long?=null
    /** 云端记录懒拉取只踢一次：登录后云端历史若还没拉过，手机第一次要 /resume 时触发，避免反复刷新打扰电视页面。 */
    private var remoteCloudKicked=false
    /**
     * 列表页顶栏那个「手机遥控」入口。会话状态一变就要跟着改文案，
     * 但每次 `showCatalog()` 都会重建视图，所以这里只在重建时重新绑定。
     */
    private var remoteEntryView: TextView?=null
    /**
     * 顶栏空间吃紧（特大缩放 + 高密度屏，有效宽度可能只剩 700dp 出头）：
     * 品牌去掉版本号、工具与遥控入口改用短文案，否则「设置」会被挤出屏幕外、
     * 方向键也走不过去（模拟器 320dpi / 特大档实测）。由 showCatalog 每次重建时赋值。
     */
    private var navTight=false
    private val danmakuRepo=DanmakuRepository(repository.http)
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    // 预取槽有效期 3 分钟：短剧一集往往就 1~3 分钟，默认 120 秒会让「起播就备好」的那份流
    // 在用户真正换集时刚好过期（用户 2026-11-20 提的加载慢，一半损失在这里）。
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() },180_000)
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    // 窗口取址预热：播放就绪后用一次批量请求覆盖前后若干集，手动切集直接命中缓存。
    private var warmJob: java.util.concurrent.Future<*>?=null
    private var warmScope: RequestScope?=null
    private var warmWindow=""
    // 已提前挂进 ExoPlayer 播放列表的下一集：由播放器无缝切换，不再 stop/clearMediaItems。
    private var queuedNextId=""
    private var queuedIndex=-1
    private var queuedVideo: RemoteVideo?=null
    private var queuedHttp: okhttp3.OkHttpClient?=null
    private var queuedFromIndex=-1
    private var queuedFromDuration=0L
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
    /** 右上角实时网速悬浮条（设置/语音开关，默认关闭）：随每次 root 重建重新挂载，所有界面常显。 */
    private var netSpeedView: TextView?=null
    private var netSpeedTick: Runnable?=null
    private var lastRxBytes=-1L
    private var lastTxBytes=-1L
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
    private var catalogFocusPos=RecyclerView.NO_POSITION
    /** 焦点当前是否在网格卡片内：用于在 Activity 层拦截下键，即使焦点被回收清空也按记住的位置继续下移 */
    private var gridFocusOwned=false
    /** 跨区/跨行移动的目标位置：焦点卡被滚动回收时，恢复逻辑优先聚焦它而不是抢回原卡。 */
    private var pendingGridFocusPos=RecyclerView.NO_POSITION
    private var loadingMore=false
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    /** 与 scrollPositions 配对的像素偏移（首个可见卡片 top 相对 RecyclerView 顶）：原位恢复视口用。 */
    private val scrollOffsets=mutableMapOf<String,Int>()
    /** 上次触发列表重建时的云端数据签名（登录态+云端记录数+云端收藏数）：状态文本变化不再重建页面。 */
    private var lastCloudSig=0
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    /** 下一集已预挂进播放列表时，按 → 直接让播放器内部切（不重建播放器）。 */
    private val seamlessNextOnSkip=true
    private var homeScreen: HomeScreen?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var accountManager: AccountManager
    private var accountButton: SettingRow?=null
    private lateinit var tvTools: TvTools
    private lateinit var extras: PlaybackExtras
    private lateinit var thirdStore: ThirdStore
    private lateinit var updater: AppUpdater
    private lateinit var profileUpdater: PlaybackProfileUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private var keyHints: TextView?=null
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
    /** 官方取址限流剩余毫秒（>0 表示上次失败是 PlaybackThrottledException）：等待期专用重试路径，不烧普通重试预算。 */
    private var throttledRemainingMs=0L
    /** 上一轮限流的本地等待时长：官方风控窗口比它宣称的恢复时间更长（2026-11-21 实测：等满宣称时间
     *  重试仍被拒且逐档加罚 10s→60s→120s→300s），连续被拒时本地等待翻倍退避；播放成功归零。 */
    private var lastThrottleWait=0L
    /** 本次播放连续跳过的不兼容集数：成功后归零，最多自动跳 5 集。 */
    private var incompatibleSkips=0
    /** 排着队的「不兼容自动跳下一集」定时任务：用户抢先点了上一集/选集时必须撤掉。 */
    private var skipUnplayableJob: Runnable?=null
    // ---- bytevc2 原生软解模式（同屏复用主播放页，用户 2026-11-20 拍板）----
    // 音轨由 ExoPlayer 照常播（bytevc2 容器里音频是标准编码），进度/seek/倍速/弹幕时钟全部沿用；
    // 视频由 NDK 解码器(libbvc2dec.so)软解后画到叠加的 SurfaceView 上，与音频时钟对齐。
    private var vc2Active=false
    /** 本集原生解码已经失败过：置位后不再重试 vc2，回落「暂不兼容」提示，防止无限重播循环。 */
    private var vc2Failed=false
    private var vc2Decoder: Vc2NativeDecoder?=null
    private var vc2Surface: SurfaceView?=null
    private var vc2Thread: Thread?=null
    private var vc2Cache: java.io.File?=null
    private var startPosition=0L
    /**
     * 本次会话里每一集看到的位置，键为「剧 id:集 id」。
     * 换集后返回上一集要接着播，不能因为换集时把整部剧只有一条的进度记录覆盖掉就从头开始。
     */
    private val episodePositions=mutableMapOf<String,Long>()
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
            // 限流等待：等的是官方冷却到期（core 冷却期内一个请求都不发），不消耗普通重试预算；
            // 否则 1s/2.5s/5s 三次预算几秒烧光，300 秒冷却期只能停在手动面板等人。
            else if(throttledRemainingMs>0) {
                throttledRemainingMs=0
                android.util.Log.i("HongguoTV","Playback recovery after throttle cooldown at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    /** root 的外壳：换集过渡层挂这一层，页面怎么 removeAllViews 都盖得住。 */
    private lateinit var shell: FrameLayout
    /** 换集过渡层：冻结的上一帧 + 中央转圈，新一集首帧出来才撤（2026-11-19）。 */
    private var freezeLayer: FrameLayout?=null
    private var freezeShownAt=0L
    /** 换集过渡开始时刻：这段时间内的 IOException: Canceled 是主动停流造成的，要忽略（0=不在过渡） */
    private var transitionFrom=0L
    private var freezeTip: TextView?=null
    private var freezeFade: Runnable?=null
    /** 正在淡出的过渡层（连按换集时先收掉它，避免两层半透明叠在一起发灰） */
    private var freezeFading: FrameLayout?=null
    /** 延迟显示 loading 的任务：500ms 内切完就在 releaseFreeze 里被撤掉，等于从未出现过 */
    private var freezeLoadingJob: Runnable?=null
    /** 过渡层淡出时长：只柔化「旧帧 → 新画面」这一下，不增加等待（新画面此刻已经可见） */
    private val FREEZE_FADE_MS=180L
    /** 定格截图的长边上限。过渡只有零点几秒且要盖住画面，720 足够；
     *  PixelCopy 的耗时大头是 GPU→CPU 读回，像素少一半时间就少一半（用户 2026-11-20 反馈切换慢）。 */
    private val FREEZE_MAX_EDGE=720f
    /** 自动连播（用户没按键）时转圈/文案的延迟：快切全程只有「上一帧 → 新画面」，
     *  500ms 内首帧到了就压根不显示 loading；手动按键换集仍用 0ms 即时反馈。 */
    private val FREEZE_LOADING_DELAY_AUTO=500L
    /** 片尾前多久主动接管自动续播（tick 是 1 秒一次，必须大于 1000ms 才能保证采样落在窗口内）。 */
    private val AUTO_NEXT_FREEZE_LEAD_MS=1300L
    private var backdropView: ImageView?=null
    private var backdropBitmap: Bitmap?=null
    private var generation=0
    private var screen="catalog"
    private var tab=0

    /**
     * 语音触发设置类弹窗时的锚点。
     *
     * 设置页里的弹窗都要求一个 anchor，用来在关窗后把焦点还回去、并顺手刷新那一行的文案。
     * 语音场景下设置页根本不在场：给一个永不 attach 的 TextView，`isAttachedToWindow`
     * 恒为 false，弹窗关闭时的焦点恢复会自动跳过，写文案也只是写进一个没人看的对象。
     */
    private val voiceAnchor by lazy { TextView(this).apply { id=View.generateViewId() } }
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    // 播放中长按菜单键呼出快捷面板：短按仍在抬起时执行用户设定行为
    private var menuLongFired=false
    private val menuLongRunnable=Runnable { menuLongFired=true; showQuickPanel() }
    // 收藏排序：update 最近更新 / watch 最近观看 / name 名称；管理模式支持多选批量取消收藏
    private var favoriteManage=false
    private val favoriteSelected=linkedSetOf<String>()
    private var favoriteSortButtons=emptyList<TextView>()
    private var favoriteSortFirst: TextView?=null
    private var favoriteSelectAll: TextView?=null
    private var favoriteRemove: TextView?=null
    private var favoriteManageButton: TextView?=null
    // 高级搜索：与关键词搜索共用结果网格，但条件、分页游标和筛选面板单独维护
    private var advancedSearch=false
    private var discoverySelection=DiscoverySelection()
    private var discoveryGroups=emptyList<DiscoveryGroup>()
    private var discoveryCursor: DiscoveryCursor?=null
    private var filterToggle: TextView?=null
    private var filterSummary: TextView?=null
    private var filterExpanded=true
    /** 进入高级搜索后首次出结果自动收起面板（issue #35.2）；之后尊重用户手动展开/收起。 */
    private var filterAutoCollapse=false
    private var filterPanel: LinearLayout?=null
    private var filterGroupsScroll: View?=null
    private var filterChips=linkedMapOf<Pair<String,String>,TextView>()
    private var filterRows=mutableListOf<List<TextView>>()
    private var filterCollapse: TextView?=null
    private var filterReset: TextView?=null
    private var filterRefresh: TextView?=null
    // 条件变更后重建页面时，把焦点送回刚刚操作的那个条件
    private var pendingFilterFocus: Pair<String,String>?=null
    // cursor/discovery 一并缓存：分类接口的续页靠服务器 session，切走再回来若沿用别的页签的条件就会拉到错内容
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?,val board: RankBoard?=null,val genre: Genre?=null,val aiCatalog: Boolean=false,val aiGenre: Genre?=null,val newRelease: Boolean=false,val newReleaseWindow: String?=null,val cursor: DiscoveryCursor?=null,val discovery: DiscoverySelection?=null)
    private var ranking: ComicRanking?=null
    private var rankBoard=RankBoard.COMIC
    // 榜单按枚举顺序循环切换，新增榜单只需在 RankBoard 里加一项
    private fun nextRankBoard()=RankBoard.entries[(rankBoard.ordinal+1)%RankBoard.entries.size]
    /** 上新区跟随当前榜单的内容类型：漫剧榜看漫剧上新、短剧榜看真人剧上新，条件固定「最新上架 + 时间窗」。 */
    private fun newReleaseSelection(): DiscoverySelection {
        val genreId=when(rankBoard) { RankBoard.COMIC -> "comic_series"; RankBoard.REAL -> "short_play"; RankBoard.AI -> "ai_series" }
        // choose("genre") 会清空其它维度，所以必须先选 genre，再叠排序和时间窗
        return DiscoverySelection().choose("genre",genreId).choose("sort","online_time").choose("online_time",newReleaseWindow)
    }
    private fun newReleaseLabel()=NEW_RELEASE_WINDOWS.firstOrNull { it.first==newReleaseWindow }?.second ?: NEW_RELEASE_WINDOWS[0].second
    /** 时间窗一次选到位置，不再四档轮转：弹层里标出当前档，确认即切换。 */
    private fun showNewReleaseWindowPicker() {
        val anchor=newReleaseWindowButton ?: return
        val current=NEW_RELEASE_WINDOWS.indexOfFirst { it.first==newReleaseWindow }
        val labels=NEW_RELEASE_WINDOWS.map { (id,name) -> if(id==newReleaseWindow) "$name · 当前" else name }
        tvTools.choose("上新时间范围",labels,anchor,{ index ->
            val next=NEW_RELEASE_WINDOWS.getOrNull(index)?.first ?: return@choose
            if(next==newReleaseWindow) return@choose
            newReleaseWindow=next; library.newReleaseWindow=next
            page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; discoveryCursor=null; discoverySelection=newReleaseSelection()
            tabState.remove(2); tabFocus.remove(2); pendingNewReleaseFocus="window"; showCatalog(true)
        },focusIndex=current,anchorToButton=true)
    }
    /** 上新区重建后把焦点还给用户刚按的那个按钮；没有待处理请求时返回 false。 */
    private fun consumePendingNewReleaseFocus(): Boolean {
        val target=when(pendingNewReleaseFocus) {
            "back" -> newReleaseEntry
            "window" -> newReleaseWindowButton
            "refresh" -> rankRefresh
            else -> null
        }
        pendingNewReleaseFocus=null
        return target?.takeIf { it.isAttachedToWindow }?.requestFocus()==true
    }
    private var rankSwitch: View?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    // AI 剧分类是排行榜页内的子视图，不占用首页内容类型页签，也不参与搜索口径
    private var aiCatalog=false
    private var aiGenre: Genre?=null
    private var aiEntry: View?=null
    private var aiBack: View?=null
    private var aiGenreButton: View?=null
    // 上新区：也挂在排行榜页下，条件固定「最新上架 + 时间窗」，数据走分类接口而不是网页榜单
    private var newRelease=false
    private var newReleaseWindow=NEW_RELEASE_WINDOWS[0].first
    private var newReleaseEntry: View?=null
    private var newReleaseBack: View?=null
    private var newReleaseWindowButton: View?=null
    // 切换条件后整页重建，异步回调的落焦会抢走按钮焦点：记下用户刚按的是哪个按钮，加载完再送回
    private var pendingNewReleaseFocus: String?=null
    private var genre: Genre?=null
    private var genreButton: View?=null
    private var genreClear: View?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    /** 设置页左栏排序模式（用户 2026-11-25）：>=0 表示有分类被长按抓起（当前位置）；
     *  move/end 回调由 settings() 注入、dispatchKeyEvent 在排序模式下触发（上下移动、确认/返回放下）。 */
    private var railReorderPos=-1
    private var railReorderMove: ((Int)->Unit)?=null
    private var railReorderEnd: ((Boolean)->Unit)?=null
    // 二级分段首项：导航 ↓ 先落到分段，再进内容
    private var segmentFirst: View?=null
    // 我的模块（最近观看/收藏）各分段按钮：切进我的时焦点先落当前页对应的分段，方便上下切区
    private var segmentChipsByTab: Map<Int,View> =emptyMap()
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
    /** 最近观看页本机记录是否已展开全部：默认折叠两行+「查看全部」卡（用户 2026-11-24），切页签后回到折叠。 */
    private var historyShowAll=false
    private var historyItems=emptyList<TextView>()
    private var suggestScrollRef: ScrollView?=null
    private var fullSearchColumns=6
    private var lastSuggestWords=emptyList<String>()
    private var lastSuggestKey=""
    private var resultFirstCard: View?=null
    private var searchFocusPending=false
    private val pendingSuggest=Runnable { fetchSuggest() }
    private var historyManage: View?=null
    private var historySync: View?=null
    private var historyRefresh: View?=null
    private var favoriteCloudSync: View?=null
    private var favoriteCloudRefresh: View?=null
    /** 云端记录卡片点击后的续播进度：只用于起播，落地仍由播放中的 saveProgress 写本机。 */
    private var pendingCloudResume: CloudProgress?=null
    /** 设置页重建后要恢复焦点的设置项标签前缀。 */
    private var settingsFocusKey: String?=null
    /** 手机快传来的 APK：等用户在系统设置里完成授权后自动续装，不必重新扫码。 */
    private var pendingQuickApk: java.io.File?=null
    /** 设置页「自定义背景」文案刷新：快传图片一键设为背景后同步显示。 */
    private var refreshBackdropSetting: (()->Unit)?=null
    private var refreshSaverStyleSetting: (()->Unit)?=null
    private var refreshSaverMediaSetting: (()->Unit)?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var detailEpisodePage=0
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var progressBar: PlaybackSeekBar
    /** 自动重试时的转圈，单独可见；手动面板态切回 controls。 */
    private var recoverySpinner: ProgressBar?=null
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    /** 播放页右上角的鼠标退出按钮：只在最近一次操作来自鼠标时出现，见 [TvPointer]。 */
    private var playerExit: TextView?=null
    /** 最近一次操作是不是鼠标做的：遥控一按就翻回 false，按钮随之收起。 */
    private var mouseSeen=false
    /** 播放页鼠标快捷按钮：左下「选集」、右上「收藏」，只在控制条展开时出现。 */
    private var pointerLayer: FrameLayout?=null
    private var pointerFav: TextView?=null
    private var pointerCenter: TextView?=null
    /** 最近一次输入是否来自指针（鼠标/触控板）：滚轮只滚视口，网格不该为它补焦点。 */
    private var pointerInput=false
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    /** 本会话内确认整集只剩 bytevc2 的剧集 id：切清晰度/重播直接走 streamFast 融合兜底，
     *  不再试普通直链（会 403 → 触发自动降档闪屏，最终仍回落 360p vc2，白花几秒）。 */
    private val vc2Episodes=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** 当前集的清晰度阶梯与正在用的档位（core 已按集缓存 ~1h）：播放失败自动降档沿它逐级下探，
     *  普通档耗尽后落 bytevc2 垫底档，全程零请求。 */
    private var activeTiers: List<StreamInfo> = emptyList()
    private var activeTier: StreamInfo? = null
    /** 本次播放会话内的临时降档，不改用户全局设置；重进播放页或手动改清晰度时清除。 */
    private var sessionQuality: Int?=null
    /** 剧内「已验证可播的最高档」：某档真正播到首帧 ready 就记下来，同剧后续集直接从这档起播
     *  （用户 2026-11-21：每集优先最高分辨率，但别每集都重复撞硬解失败）。bvc2 档不记录——
     *  全 bvc2 剧没有普通档可验证，记录会把后续集压到 bvc2 的档位上。 */
    private val verifiedQuality=mutableMapOf<String,Int>()
    /** 本集自动降档重放用的档位（probe）：只对当前这一集生效，换集即失效，不污染下一集起播档。 */
    private var probeQuality: Int?=null
    /** 本集硬解失败的档位（codec|qNum）：降档/上爬时跳过，同一集不重复撞同一个死档。 */
    private val failedTiers=mutableSetOf<String>()
    /** 每剧向上探测计数：每 5 集把起播档抬高一级试一次（capped 到全局默认档），
     *  给「这集片源其实能解更高档」自愈机会；失败会被自动降档拉回，约 1.5 秒、五集摊一次。 */
    private val probeUp=mutableMapOf<String,Int>()
    /** 本集起播档位：手动选择 > 本集探测重放 > 剧内已验证档 > 全局默认。 */
    private fun startQuality(): Int =
        sessionQuality ?: probeQuality
        ?: verifiedQuality[detail?.series?.id.orEmpty()]?.takeIf { it>0 }
        ?: library.maxQuality
    private fun tierKey(t: StreamInfo)="${t.codec}|${t.qNum}"
    /**
     * 播放界面里的「播放设置」只作用于当前这部剧，不写回「设置 → 播放」里的全局默认。
     * 用户看这一部时想把倍速调到 1.5×、画面调成铺满，不该顺手改掉明天看别的剧时的默认值。
     */
    private var sessionSpeed: Float?=null
    private var sessionFrame: VideoFrameMode?=null
    private var downgradeEpisode=""
    private var downgradeCount=0
    /** 本集解码失败且阶梯耗尽后的「赌 CDN 换编码变体」自动重试，只做一次（2026-11-21 用户拍板）。
     *  重试不打取址接口（阶梯缓存内零请求），失败面板再弹出来；换集/播通后复位。 */
    private var decodeRetried=false
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            // 三方播放器走自己的 updatePlayUi，不会经过 updatePlaybackText，
            // 只靠那两处挂点的话手机上的进度会永远停在扫码那一刻。
            remotePublish()
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
                            Toast.makeText(this@MainActivity,L10n.tr("已跳过片尾，播放下一集"),Toast.LENGTH_SHORT).show()
                            playEpisode(episodeIndex+1,savedPosition(episodeIndex+1))
                        } else if(continueToNextSeason()) { /* season jump in progress */ }
                    }
                }
                // 片尾自动续播：下一集已经预挂进播放列表时，ExoPlayer 会用 REASON_AUTO 自己切过去，
                // 而那个回调发生在切换「之后」，解码器交接的黑底来不及盖（用户 2026-11-20 报的就是这里）。
                // 所以在片尾前主动接管，走和手动换集同一套冻结逻辑；用户没按键，
                // loading 延迟 500ms 才出现，500ms 内切完就只看到「上一帧 → 新画面」。
                if(p!=null && data!=null && playbackReady && !playError && !outroTriggered && queuedNextId.isNotEmpty()
                    && p.isPlaying && freezeLayer==null && library.autoNext && episodeIndex<data.episodes.lastIndex) {
                    val d=p.duration
                    if(d>0) {
                        val remaining=d-p.currentPosition
                        if(remaining in 1..AUTO_NEXT_FREEZE_LEAD_MS) seamlessNext(silent=true)
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
    /** 「名称  ·  值」整串刷新：SettingRow 拆到左右两侧，HUD 按钮（TextView）保持整串原样（规范 §6.1）。 */
    private fun View?.setValueLabel(label: String) = SettingRow.setValueLabel(this,label)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    /** UI 文案出口统一走 [L10n.tr]：简体原样、繁中字典转换、英文词条替换；未收录的自动回退中文。
     *  集中在这一层拦截，几千处调用点无需逐个改。 */
    /**
     * 通用文本。用 [LocalizedTextView] 而不是裸 TextView：页面里大量文案是加载完才回填的
     * （榜单副标题、状态行、计数），构建时那一遍 [UiL10n.apply] 扫不到，只能靠控件自己兜。
     */
    private fun text(value: String,size: Float=16f,color: Int=white)=LocalizedTextView(this).apply { text=L10n.tr(value); textSize=size; setTextColor(color); includeFontPadding=false }
    /** 会「后改文案」的控件用它：弹窗状态行、进度提示在建好之后才 setText，构建时的那遍翻译扫不到。 */
    private fun textLive(value: String,size: Float=16f,color: Int=white)=LocalizedTextView(this).apply { text=L10n.tr(value); textSize=size; setTextColor(color); includeFontPadding=false }
    /** 顶栏纯文字 tab：无按钮背景，选中/聚焦用强调色 */
    private fun tabLabel(label: String,selected: Boolean,onClick: ()->Unit): TextView = TextView(this).apply {
        text=L10n.tr(label); textSize=15f; isFocusable=true; isClickable=true
        setTextColor(if(selected) accent else muted); includeFontPadding=false
        setPadding(dp(14),dp(8),dp(14),dp(8))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _,f -> setTextColor(if(f||selected) accent else muted) }
    }
    /** 顶栏图标按钮：emoji 图标，无背景，聚焦变强调色 */
    private fun iconLabel(icon: String,desc: String,onClick: ()->Unit): TextView = TextView(this).apply {
        text=icon; textSize=19f; isFocusable=true; isClickable=true
        contentDescription=desc; setTextColor(muted); includeFontPadding=false
        setPadding(dp(10),dp(6),dp(10),dp(6))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _,f -> setTextColor(if(f) accent else muted) }
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(44); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    /** 顶栏文字导航：icon + label，选中态粗体 accent 色，焦点态 raised 底+accent 边框，默认态 muted。
     *  iconRes 传 0 表示只显示文字（极窄宽度下省掉图标的宽度，见 showCatalog 的 bareNav）。 */
    private fun pillNav(iconRes: Int,label: String,selected: Boolean,onClick: ()->Unit,compact: Boolean=false): TextView {
        val padH=if(compact) 10 else 14
        val padV=if(compact) 6 else 8
        val drawPad=if(compact) 4 else 6
        val h=if(compact) 32 else 36
        val ts=if(compact) 14f else 15f
        val view=text(label,ts,white).apply {
            gravity=Gravity.CENTER; isFocusable=true; isFocusableInTouchMode=true; isClickable=true; includeFontPadding=false
            // isFocusableInTouchMode 必须有：这台电视的 IR 鼠标棒事件带 SOURCE_TOUCHSCREEN，
            // 点一下窗口就进触摸模式，普通 view 的 requestFocus 全部静默失败——
            // 鼠标侧键返回到列表页后，goBack 想把焦点收回导航栏会失败，返回键从此没反应。
            if(iconRes != 0) { setCompoundDrawablesWithIntrinsicBounds(iconRes,0,0,0); compoundDrawablePadding=dp(drawPad) }
            setPadding(dp(padH),dp(padV),dp(padH),dp(padV)); minHeight=dp(h); setOnClickListener { onClick() }
        }
        view.id=View.generateViewId()
        fun paint(focused: Boolean) {
            when {
                selected -> {
                    view.background=rounded(Color.TRANSPARENT,accent)
                    view.setTextColor(accent); view.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                    view.compoundDrawables[0]?.setTint(accent)
                }
                focused -> {
                    view.background=rounded(TvStyle.raised,accent); view.setTextColor(white); view.typeface=Typeface.DEFAULT
                    view.compoundDrawables[0]?.setTint(white)
                }
                else -> {
                    view.background=rounded(Color.TRANSPARENT,Color.TRANSPARENT); view.setTextColor(muted); view.typeface=Typeface.DEFAULT
                    view.compoundDrawables[0]?.setTint(muted)
                }
            }
        }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
        return view
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        val bootPrefs=getSharedPreferences("native-library-v1", Context.MODE_PRIVATE)
        TvStyle.apply(bootPrefs.getString("theme", null))
        // 多语言：手动选择优先，未选（空串）跟随系统 Locale。core 不依赖 Android，
        // 这里把系统语言解析注入进去；切换语言后由设置页触发 recreate() 重建全部文案。
        L10n.configure(bootPrefs.getString("language", null))
        super.onCreate(state)
        // 最近任务卡片显式设标签：部分电视 ROM（TCL）不读 application label，
        // taskDescription.label 为 null 时回退显示组件名（com.hongguotv.nativeapp.M…）。
        // 2026-11-27 再加两层保险：window title（部分 ROM 的 recents 取它）+ manifest 的
        // activity 级 android:label，避免只依赖 TaskDescription 一种来源。
        val appLabel=getString(R.string.app_name)
        setTaskDescription(ActivityManager.TaskDescription(appLabel))
        title=appLabel
        // 系统风格 / 界面缩放切换触发的重建：把原页面和焦点带回来
        if(PageRestore.tab>=0) {
            tab=PageRestore.tab
            settingsFocusKey=PageRestore.focusKey
            PageRestore.tab=-1; PageRestore.focusKey=null
        }
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); extras=PlaybackExtras(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }
        shell=FrameLayout(this).apply { addView(root,FrameLayout.LayoutParams(-1,-1)); setContentView(this) }
        // 上新区沿用上次选择的时间窗（只在本机记，识别不了的值回落到默认档）
        newReleaseWindow=library.newReleaseWindow.takeIf { id -> NEW_RELEASE_WINDOWS.any { it.first==id } } ?: NEW_RELEASE_WINDOWS[0].first
        setupMouseGestures()
        // 永久遥控的密钥/端口落盘，必须先于任何 PhoneRemoteSession 构造
        RemoteAccess.init(this)
        XiaoAiAccess.init(this)
        accountManager=AccountManager(this,library)
        // 登录态供给相关推荐：plan/v1 相关视频（show_type=160）只有登录身份才下发。
        // 必须给完整凭证（Cookie + 账号设备身份），只给 Cookie 会因 did/iid 不配套被服务端当匿名。
        repository.loginCookieProvider={ accountManager.account }
        accountManager.onAccountChanged={
            accountButton?.value=accountManager.accountLabel
            // 账号事件只在云端数据实际变化时才重建列表页：读取过程的每条状态文本
            // （正在读取/已读取 N 部/读取失败）都会触发 changed()，若全部重建，
            // 用户在最近观看/收藏页往下滚的几秒内会被整页弹回顶部 2~3 次（issue #19.2）。
            // 签名=登录态+云端记录数+云端收藏数+上新计数；数据到达/清空/上新角标变化时才变化。
            val sig=if(accountManager.signedIn) 1+accountManager.cloudRows().size+accountManager.cloudFavoriteRows().size*1000+(library.updatedCloudFavorites()+library.cloudNewCount())*1000000 else 0
            if(sig!=lastCloudSig) {
                lastCloudSig=sig
                // isLoaded 必须查：recreate()（换主题/缩放）会新建 AccountManager 并可能立刻回调，
                // 此时本机记录还在异步加载，showCatalog 里 navHiddenAt 一碰 state() 就崩。
                // 管理模式只操作本机收藏，不因云端刷新打断。
                if(library.isLoaded && screen=="catalog" && (tab==4 || collection=="resume" || (tab==3 && !favoriteManage))) showCatalog(keepViewport=true)
            }
        }
        // 三方片源配置与观看历史随主备份一起保存/恢复：本地已有的保留，不覆盖
        thirdStore=ThirdStore(this)
        library.thirdSourcesProvider={ thirdStore.backupSources() }
        library.thirdSourcesRestore={ thirdStore.mergeRestored(it) }
        library.thirdHistoryProvider={ thirdStore.backupHistory() }
        library.thirdHistoryRestore={ thirdStore.mergeHistory(it) }
        library.thirdFavoritesProvider={ thirdStore.backupFavorites() }
        library.thirdFavoritesRestore={ thirdStore.mergeFavorites(it) }
        // 播放器键位/快进偏好（playback-extras-v1）与收藏排序随主备份写出/恢复（2026-11-21 查缺补漏）
        library.extrasProvider={
            mapOf("autoSkip" to extras.autoSkipUnplayable,"seekStep" to extras.seekStepSeconds,"fastSpeed" to extras.longPressSpeed,
                "menuKeyAction" to extras.menuKeyAction,"dpadUpDownAction" to extras.dpadUpDownAction,
                "mouseRightAction" to extras.mouseRightAction,"favoriteSort" to library.favoriteSort,
                "railOrder" to library.railOrder,"language" to library.language)
        }
        library.extrasRestore={ s ->
            extras.autoSkipUnplayable=s.autoSkip; extras.seekStepSeconds=s.seekStep; extras.longPressSpeed=s.fastSpeed
            extras.menuKeyAction=s.menuKeyAction; extras.dpadUpDownAction=s.dpadUpDownAction; extras.mouseRightAction=s.mouseRightAction
            library.favoriteSort=s.favoriteSort; library.railOrder=s.railOrder
            library.language=s.language
        }
        InstallStatsPing.startOnce(applicationContext)
        backdropView=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        loadBackdrop()
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        // 取址协议档案：先把上次缓存的换上（几毫秒），再按 6 小时节流后台拉一次
        profileUpdater=PlaybackProfileUpdater(this).apply { loadCached(); refreshIfDue() }
        connectivity=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // registerDefaultNetworkCallback 要 API 24，低版本退回全量回调
        runCatching {
            if(Build.VERSION.SDK_INT>=24) connectivity.registerDefaultNetworkCallback(networkCallback)
            else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback)
            networkRegistered=true
        }
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
        TvDialog.confirm(this,"本地备份","开启后，收藏、观看记录与播放设置会备份到本机公共目录，卸载重装后可自动恢复。备份是明文文件，可随时在设置中关闭。云端观看记录不会随本地备份迁移，登录同一账号后会自动拉取。") {
            localPermissionContinuation={ restoreLocalBackup(done) }
            if(!storage.request(this)) {
                localPermissionContinuation=null; library.localRestoreChecked=true
                Toast.makeText(this,L10n.tr("当前设备无法授予存储权限，可在设置中使用手机备份迁移"),Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this,L10n.tr("已从本机备份恢复收藏与记录"),Toast.LENGTH_LONG).show()
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
    private fun showLocalBackupDialog(anchor: View) {
        val storage=library.localBackup()
        if(!storage.supported) {
            TvDialog.info(this,"本地备份与恢复","当前设备没有可用的公共存储，无法自动备份；可使用「手机备份与恢复」迁移记录。").show()
            return
        }
        if(!library.localBackupEnabled) {
            TvDialog.confirm(this,"本地备份与恢复","开启后，收藏、观看记录与播放设置会备份到本机公共目录，卸载重装后可自动恢复。备份是明文文件，可随时关闭。云端观看记录不会随本地备份迁移，登录同一账号后会自动拉取。") {
                library.localBackupEnabled=true; anchor.setValueLabel(localBackupLabel())
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
                library.localBackupEnabled=false; anchor.setValueLabel(localBackupLabel())
                Toast.makeText(this,L10n.tr("已关闭本地自动备份，已有备份文件保留"),Toast.LENGTH_SHORT).show()
            }
        }).show()
    }
    /** Empty library with an existing backup must restore instead of overwriting it with nothing. */
    private fun localBackupAction(anchor: View) {
        if(library.isEmptyForRestore() && library.hasLocalBackup()) {
            restoreLocalBackup { anchor.setValueLabel(localBackupLabel()) }
        } else backupWithToast(anchor)
    }
    private fun requestLocalBackupPermission(anchor: View) {
        localPermissionContinuation={
            anchor.setValueLabel(localBackupLabel())
            if(library.localBackup().backupAllowed) localBackupAction(anchor)
        }
        if(!library.localBackup().request(this)) {
            localPermissionContinuation=null
            anchor.setValueLabel(localBackupLabel())
            Toast.makeText(this,L10n.tr("当前设备无法授予存储权限，请使用手机备份迁移"),Toast.LENGTH_LONG).show()
        }
    }
    private fun backupWithToast(anchor: View) {
        library.backupNow { ok ->
            anchor.setValueLabel(localBackupLabel())
            Toast.makeText(this@MainActivity, L10n.tr(if(ok) "本机备份已更新" else "备份失败，请检查存储权限"),Toast.LENGTH_SHORT).show()
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
        cancelLoadingAnim()
        root.removeAllViews(); root.setBackgroundColor(bg)
        backdropView?.takeIf { backdropBitmap!=null }?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        // 本地测试包（versionName 不含 +构建号）常驻 beta 标记放左上角（右上角留给网速/时钟），与正式包区分；播放页不显示
        if(!BuildConfig.VERSION_NAME.contains('+')) {
            root.addView(text("beta",12f,accent).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable=false },
                FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply { topMargin=dp(10); leftMargin=dp(14) })
        }
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        applyClock()
        applyNetSpeedOverlay()
        // 页面构建完成后统一兜底翻译一次（见 UiL10n），补上漏包 L10n.tr 的出口；
        // 再补一次延迟扫描，兜住「构建后才异步到达的数据」填充的卡片文案。
        root.post { UiL10n.apply(root) }
        root.postDelayed({ if(root.isAttachedToWindow) UiL10n.apply(root) },900)
        return container
    }
    /** Re-adds the optional top-right clock after every root rebuild; ticks on minute boundaries. */
    private fun applyClock() {
        clockTick?.let(main::removeCallbacks); clockTick=null
        clockView?.let { root.removeView(it) }; clockView=null
        val showClock=library.isLoaded && library.showClock
        // 时钟恢复原位（右上角 dp10）；仅当网速悬浮文字开启时才下移一行避让
        val topMargin=dp(10)+(if(netSpeedShown()) dp(30) else 0)
        if(!showClock) return
        val view=text("",15f,muted).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable=false; isClickable=false
            setPadding(dp(8),dp(3),dp(8),dp(3))
        }
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
    /**
     * 右上角顶部实时网速悬浮文字：显示设备总收发速率（↓ 下行 / ↑ 上行），
     * 设置页「外观与界面」或语音开关，默认关闭。挂在每次 root 重建之后（含播放页），
     * 贴屏幕最顶端（dp10），纯文字无底色；beta 标记与时钟顺次下移避让，所有界面常显。
     */
    /** 网速悬浮文字当前是否应显示：右上角 beta/时钟纵向避让的依据。 */
    private fun netSpeedShown()=library.isLoaded && library.netSpeed
    private fun applyNetSpeedOverlay(show: Boolean=library.netSpeed) {
        netSpeedTick?.let(main::removeCallbacks); netSpeedTick=null
        // base()/播放器重建会直接清空 root，旧引用即使非空也已经不在屏幕上，必须重新挂载。
        netSpeedView?.let { root.removeView(it) }; netSpeedView=null
        if(!library.isLoaded || !show || !library.netSpeed) { lastRxBytes=-1; lastTxBytes=-1; return }
        val view=text("网速 · 测量中…",12f,muted).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable=false; isClickable=false
            setPadding(dp(8),dp(3),dp(8),dp(3))
        }
        root.addView(view,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(10); rightMargin=dp(14) })
        netSpeedView=view
        val tick=object: Runnable {
            override fun run() {
                val rx=android.net.TrafficStats.getTotalRxBytes()
                val tx=android.net.TrafficStats.getTotalTxBytes()
                if(rx<0 || tx<0) netSpeedView?.text=L10n.tr("网速 · 当前设备不支持")
                else {
                    if(lastRxBytes>=0 && lastTxBytes>=0) {
                        netSpeedView?.text="↓ ${speedLabel(rx-lastRxBytes)}   ↑ ${speedLabel(tx-lastTxBytes)}"
                    }
                    lastRxBytes=rx; lastTxBytes=tx
                }
                main.postDelayed(this,1000L)
            }
        }
        netSpeedTick=tick
        tick.run()
    }
    /** 速率格式化：1 MB/s 以上一位小数，其余整数 KB/s 或 B/s。 */
    private fun speedLabel(bytesPerSec: Long): String=when {
        bytesPerSec>=1L shl 20 -> String.format(java.util.Locale.US,"%.1f MB/s",bytesPerSec/1048576f)
        bytesPerSec>=1024L -> "${bytesPerSec/1024} KB/s"
        else -> "$bytesPerSec B/s"
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
        // 保存首个可见卡片的 adapter 位置与像素偏移：账号事件等触发的整页重建用这对值原位恢复视口。
        // 只存位置不带偏移，恢复后整屏内容会整体上移；只按 displayedIndex 换算则会被分节标题错位。
        (catalogRecycler?.layoutManager as? GridLayoutManager)?.let { lm ->
            val recycler=catalogRecycler ?: return
            val child=lm.getChildAt(0)
            if(child!=null) {
                val pos=recycler.getChildAdapterPosition(child)
                if(pos!=RecyclerView.NO_POSITION) {
                    scrollPositions[catalogPageKey]=pos
                    scrollOffsets[catalogPageKey]=child.top-recycler.paddingTop
                }
            }
        }
        homeScreen=null; selectionPreview=null; catalogRecycler=null; catalogAdapter=null; loadingMore=false
        catalogFocusPos=RecyclerView.NO_POSITION
        nav.clear(); segmentFirst=null; segmentChipsByTab=emptyMap(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        azFirst=null; azLastRow.clear(); suggestRow=null; suggestFirst=null; suggestTicket++; main.removeCallbacks(pendingSuggest)
        suggestVertical=false; searchBody=null; keyboardKeys=emptyMap(); keyboardRows=emptyList(); historyFirst=null; historyItems=emptyList(); suggestScrollRef=null; resultFirstCard=null
        historyManage=null; historySync=null; historyRefresh=null; rankSwitch=null; rankRefresh=null; rankSubtitle=null; genreButton=null; genreClear=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; favoriteFilterFirst=null; favoriteFilterButtons=emptyList(); homeUpdates=null; resumeCards.clear()
        favoriteSortFirst=null; favoriteSortButtons=emptyList(); favoriteSelectAll=null; favoriteRemove=null; favoriteManageButton=null
        filterToggle=null; filterSummary=null; filterPanel=null; filterGroupsScroll=null; filterChips=linkedMapOf(); filterRows=mutableListOf()
        filterCollapse=null; filterReset=null; filterRefresh=null
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket || scope.isCancelled) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    // 快速切集/切页会取消上一次的取流，取消本身不是播放失败：
                    // 否则 cancelled 的 IOException 会被当成播放错误，暂停画面并弹重试面板。
                    if(!isDestroyed && generation==ticket && !scope.isCancelled) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private var loadingDotsAnim: Runnable?=null
    private var loadingPulseAnim: ObjectAnimator?=null
    /**
     * 详情页加载过渡块（用户 2026-11-22）：居中的旋转指示器 + 加粗剧名 + 呼吸点状态行，
     * 整块做呼吸透明度动画；毛玻璃封面背景透出，替换掉原来缩在左上角的一行小字。
     */
    private fun loadingBlock(parent: LinearLayout,title: String) {
        cancelLoadingAnim()
        val status=text("正在加载剧集",14f,white).apply { alpha=0.75f }
        val block=column().apply {
            tag="loading_block"
            gravity=Gravity.CENTER
            addView(ProgressBar(this@MainActivity).apply {
                isIndeterminate=true
                indeterminateTintList=android.content.res.ColorStateList.valueOf(accent)
            },lp(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
            addView(text(title,19f,white).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER },
                lp(-2,-2).apply { topMargin=dp(14) })
            addView(status,lp(-2,-2).apply { topMargin=dp(9) })
        }
        parent.addView(block,lp(-1,0).apply { weight=1f; topMargin=dp(20) })
        loadingPulseAnim=ObjectAnimator.ofFloat(block,View.ALPHA,0.55f,1f).apply {
            duration=900; repeatCount=ObjectAnimator.INFINITE; repeatMode=ObjectAnimator.REVERSE; start()
        }
        var dots=0
        val tick=object: Runnable {
            override fun run() {
                dots=(dots+1)%4
                status.text=L10n.tr("正在加载剧集")+"·".repeat(dots)
                main.postDelayed(this,420L)
            }
        }
        loadingDotsAnim=tick; main.postDelayed(tick,420L)
    }
    /** 任何页面重建都会经过 base()：在这里统一停掉加载动画，避免回调打到已拆掉的 View。 */
    private fun cancelLoadingAnim() {
        loadingDotsAnim?.let { main.removeCallbacks(it) }; loadingDotsAnim=null
        loadingPulseAnim?.cancel(); loadingPulseAnim=null
    }
    /** 错误页居中块：网络异常图标 + 主文案 + 错误详情 + 重试（用户 2026-11-27）。
     *  失败路径可能残留 loadingBlock 的转圈与「正在加载剧集」，先连动画带视图一并清掉，
     *  否则两者叠在一起（第二张图）。 */
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        cancelLoadingAnim()
        // 只移除加载块，保留标题栏（返回按钮）；从后往前删避免下标错位
        for(i in parent.childCount-1 downTo 0) if(parent.getChildAt(i).tag=="loading_block") parent.removeViewAt(i)
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        // 居中块撑满剩余高度，内容垂直居中（gravity=CENTER 同时管子 view 的横向对齐）
        val center=column().apply {
            gravity=Gravity.CENTER
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_network_error); setColorFilter(muted)
            },lp(-2,-2).apply { bottomMargin=dp(16) })
            addView(text("暂时无法加载，请检查网络后重试。",19f,white).apply { gravity=Gravity.CENTER })
            addView(text(L10n.tr(detail),13f,muted).apply { gravity=Gravity.CENTER },lp(-2,-2).apply { topMargin=dp(9) })
            addView(button(L10n.tr("重试"),true,retry),lp(-2,-2).apply { topMargin=dp(20) })
        }
        parent.addView(center,lp(-1,0).apply { weight=1f })
        (center.getChildAt(center.childCount-1) as? View)?.requestFocus()
    }
    /** 导航栏模块是否被用户隐藏：首页(0) 与设置(5) 始终显示。 */
    private fun navHiddenAt(index: Int): Boolean = when(index) {
        1 -> library.navHidden("search")
        2 -> library.navHidden("rank")
        3 -> library.navHidden("favorite")
        4 -> library.navHidden("history")
        6 -> library.sourceHidden
        7 -> library.navHidden("nas")
        else -> false
    }
    private fun switchTab(next: Int) {
        if(navHiddenAt(next)) return
        // 离开 feed 上下文：从详情/播放页直接切走时不能残留刷剧状态，否则点播会被当成刷剧自动换剧
        recommendMode=false; feedDetailBrowsing=false; feedSeriesBinge=false
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,rankBoard,genre,aiCatalog,aiGenre,newRelease,newReleaseWindow,discoveryCursor,discoverySelection); tabFocus[tab]=catalogFocus
        // 从其他模块切回搜索页时重置为全新搜索状态，不保留上次的搜索词与结果
        if(next==1) {
            query=""; phoneSearched=false; lastSuggestWords=emptyList<String>(); lastSuggestKey=""; suggestFocusIndex=-1; keyboardLastLabel=null
            advancedSearch=false; discoverySelection=DiscoverySelection(); discoveryGroups=emptyList(); discoveryCursor=null; filterExpanded=true
            tabState.remove(1); tabFocus.remove(1)
        }
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        // 重新进最近观看页回到默认折叠形态，云端记录始终两屏内可达
        if(next==4) historyShowAll=false
        rankBoard=saved?.board ?: rankBoard; genre=saved?.genre ?: genre
        aiCatalog=saved?.aiCatalog ?: false; aiGenre=saved?.aiGenre
        newRelease=saved?.newRelease ?: false; newReleaseWindow=saved?.newReleaseWindow ?: newReleaseWindow
        // 续页游标与分类条件跟着页签一起存：上新/高级筛选的 session 不能跨页签复用，否则续页拉到的是别的条件
        discoveryCursor=saved?.cursor; discoverySelection=saved?.discovery ?: discoverySelection
        // 我的模块：焦点先落当前页对应的分段按钮（最近观看/收藏），上下键直接切区，不必先进卡片
        showCatalog(load=(next<=2 && catalog.isEmpty() && (next!=1 || query.isNotBlank())),focusSegment=(next==3||next==4))
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
    private fun localCatalog()=tab==3 || tab==4 || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==3) {
            val base=library.favorites().filter { favoriteGroupFilter==null || library.groupOf(it.id)==favoriteGroupFilter }
            when(library.favoriteSort) {
                "watch" -> base.sortedByDescending { library.progress(it.id)?.updatedAt ?: 0L }
                "name" -> base.sortedBy { it.title }
                else -> base.sortedByDescending { library.favoriteUpdate(it.id)?.checkedAt ?: 0L }
            }
        } else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusFilter: Boolean=false,keepViewport: Boolean=false,focusSegment: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null; pendingCloudResume=null
        // 当前模块被隐藏时回到首页，避免停留在不可见的页签
        if(navHiddenAt(tab)) tab=0
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && page==1 && genre==null) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val wdp=widthDp()
        val compactNav=wdp<1600
        // 顶栏两级收缩：导航项一多（片源/本地库都打开）+ 高密度面板，960dp 就会把「设置」顶到屏幕外
        // （模拟器 320dpi 实测：入口挤成一团、设置整块看不见也聚焦不到）。
        // 一级（<1000dp）去掉版本号、工具与遥控入口改短文案；二级（<800dp）导航 pill 再去掉图标。
        val tightNav=wdp<1000
        val bareNav=wdp<800
        navTight=tightNav
        val brand=row()
        val brandIcon=ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) }
        val brandName=text("红果",if(tightNav) 19f else 22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); includeFontPadding=false }
        val brandVer=text("v${BuildConfig.VERSION_NAME}",12f,muted).apply { includeFontPadding=false }
        brand.addView(brandIcon,lp(dp(28),dp(28)).apply { rightMargin=dp(if(tightNav) 7 else 9) })
        brand.addView(brandName)
        if(!tightNav) brand.addView(brandVer,lp(-2,-2).apply { topMargin=dp(6) })
        top.addView(brand,lp(-2,dp(44)).apply { rightMargin=dp(if(tightNav) 12 else 20) })
        // 一级导航 Pill：首页 / 搜索 / 排行榜 / 片源 / 我的；收藏与最近观看并入我的
        // 「自由刷剧」不再单独占一个导航位（用户 2026-11-18），入口留在首页内容类型行
        val navMargin=if(compactNav) dp(4) else dp(6)
        val homeButton=pillNav(if(bareNav) 0 else com.hongguotv.nativeapp.R.drawable.ic_nav_home,"首页",tab==0,{ switchTab(0) },compactNav)
        val areaButtons=listOf(
            Triple(1,"搜索",listOf(1)),
            Triple(2,"排行榜",listOf(2)),
            Triple(7,"本地库",listOf(7)),
            Triple(6,"片源",listOf(6)),
            Triple(3,"我的",listOf(4,3))).mapIndexed { index,entry ->
            val iconRes=when(index) { 0->com.hongguotv.nativeapp.R.drawable.ic_nav_search; 1->com.hongguotv.nativeapp.R.drawable.ic_nav_rank; 2->com.hongguotv.nativeapp.R.drawable.ic_nav_nas; 3->com.hongguotv.nativeapp.R.drawable.ic_nav_source; else->com.hongguotv.nativeapp.R.drawable.ic_nav_mine }
            val pillIcon=if(bareNav) 0 else iconRes
            lateinit var view: TextView
            view=pillNav(pillIcon,entry.second,tab==entry.first || tab in entry.third,{
                val target=entry.third.firstOrNull { !navHiddenAt(it) } ?: entry.third.first()
                if(target!=tab) switchTab(target) else view.requestFocus()
            },compactNav)
            // 子模块全部隐藏时整个意图区消失
            view.visibility=if(entry.third.all { navHiddenAt(it) }) View.GONE else View.VISIBLE
            view
        }
        lateinit var quickTool: TextView
        quickTool=button(if(tightNav) "快传" else "手机快传",false) {
            tvTools.quickTransfer(quickTool,{ file,kind -> handleQuickTransfer(file,kind) },{},
                { tvTools.quickFiles(quickTool,{ f,k -> handleQuickTransfer(f,k,true,quickTool) },{ tvTools.quickTransfer(quickTool,{ file,kind -> handleQuickTransfer(file,kind) },{}) },{}) })
        }.apply { textSize=13f; minHeight=dp(if(tightNav) 36 else 38); val pad=dp(if(tightNav) 10 else 12); setPadding(pad,dp(7),pad,dp(7)) }
        // 遥控入口放在顶栏而不是只在设置里：用户在任何一个列表页看剧时
        // 想换手机操作，都能就地打开，不用退回首页再一路找进设置。
        lateinit var remoteTool: TextView
        remoteTool=remoteEntryButton()
        val settingsTool=button("设置",tab==5) { switchTab(5) }
            .apply { textSize=13f; minHeight=dp(if(tightNav) 36 else 38); val pad=dp(if(tightNav) 10 else 12); setPadding(pad,dp(7),pad,dp(7)) }
        listOf(homeButton,areaButtons[0],areaButtons[1],areaButtons[2],areaButtons[3],areaButtons[4]).forEach { item ->
            top.addView(item,lp(-2,-2).apply { rightMargin=navMargin })
        }
        // 右上工具与首页内容类型一起，等类型按钮挂完后再按 类型 → 留白 → 工具 的顺序追加
        // 索引语义：0=首页 1=搜索 2=排行榜 3=收藏 4=历史 5=设置 6=片源 7=本地库
        nav.addAll(listOf(homeButton,areaButtons[0],areaButtons[1],areaButtons[4],areaButtons[4],settingsTool,areaButtons[3],areaButtons[2]))
        listOf(homeButton,areaButtons[0],areaButtons[1],areaButtons[2],areaButtons[3],areaButtons[4],quickTool,remoteTool,settingsTool).let { chain ->
            chain.forEachIndexed { index,item ->
                item.nextFocusLeftId=chain.getOrNull(index-1)?.id ?: item.id
                item.nextFocusRightId=chain.getOrNull(index+1)?.id ?: item.id
                item.nextFocusUpId=item.id
            }
        }
        container.addView(top)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        // 二级分段：我的（最近观看/收藏）同层切换，隐藏的模块不占位
        var segmentChips: List<TextView> =emptyList()
        if(tab in 3..4) {
            val options=listOf(4 to "最近观看",3 to "收藏")
            val visible=options.filter { !navHiddenAt(it.first) }
            val segRow=row().apply { setPadding(0,0,0,dp(8)) }
            segmentChips=visible.map { (index,label) ->
                chip(label,index==tab) { if(index!=tab) switchTab(index) }.apply {
                    textSize=14f; minHeight=dp(34); setPadding(dp(16),dp(7),dp(16),dp(7))
                }
            }
            segmentChipsByTab=visible.map { it.first }.zip(segmentChips).toMap()
            segmentChips.forEach { segRow.addView(it,lp(-2,-2).apply { rightMargin=dp(6) }) }
            segmentFirst=segmentChips.firstOrNull()
            if(segmentChips.isNotEmpty()) container.addView(segRow)
        }
        if(tab==7) {
            val ns = NasScreen(this, tvTools)
            ns.show(container)
            // 本地库/片源页没有「设置」按钮，但顶栏位置还在：把唯一的遥控入口放过来，
            // 免得用户正在本地库看片时想开手机遥控，还得先退回首页。
            top.addView(Space(this),lp(0,1).apply { weight=1f })
            top.addView(remoteTool,lp(-2,-2))
            remoteTool.nextFocusLeftId=remoteTool.id; remoteTool.nextFocusRightId=remoteTool.id
            if(ns.topFocusId != View.NO_ID) remoteTool.nextFocusDownId=ns.topFocusId
            nav.forEach { it.nextFocusDownId = ns.topFocusId }
            if(ns.topFocusId != View.NO_ID) container.findViewById<View>(ns.topFocusId)?.requestFocus() else nav[tab].requestFocus()
            return
        }
        if(tab==6) {
            val instance=third ?: ThirdSourceScreen(this, { showCatalog() }, { library.requestBackup() }).also { third=it }
            instance.show(container)
            top.addView(Space(this),lp(0,1).apply { weight=1f })
            top.addView(remoteTool,lp(-2,-2))
            remoteTool.nextFocusLeftId=remoteTool.id; remoteTool.nextFocusRightId=remoteTool.id
            if(instance.topFocusId != View.NO_ID) remoteTool.nextFocusDownId=instance.topFocusId
            nav.forEach { it.nextFocusDownId=instance.topFocusId }
            val target=if(instance.topFocusId != View.NO_ID) container.findViewById<View>(instance.topFocusId) else null
            if(target != null) target.requestFocus() else nav[tab].requestFocus()
            return
        }
        if(tab==5) {
            // 右上工具固定在行末：留白推开后接手机快传与设置（设置页也需要顶栏工具，否则 nav[5] 不在视图树中）
            top.addView(Space(this),lp(0,1).apply { weight=1f })
            // 顺序固定为 手机快传 → 遥控 → 设置（设置在最右，用户习惯位置，勿调）
            top.addView(quickTool,lp(-2,-2).apply { rightMargin=dp(8) })
            top.addView(remoteTool,lp(-2,-2).apply { rightMargin=dp(8) })
            top.addView(settingsTool,lp(-2,-2))
            // 设置页：左右分栏（左分类/常用，右列表滚动），页脚固定在屏幕底部居中
            container.setPadding(container.paddingLeft,container.paddingTop,container.paddingRight,0)
            val body=column()
            val footerHost=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(12),0,0) }
            container.addView(body,lp(-1,0).apply { weight=1f })
            container.addView(footerHost,lp(-1,-2))
            settings(body,footerHost); if(focusNav) nav[tab].requestFocus(); return
        }
        if(tab<=1 && collection==null) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(tab==1 && advancedSearch) {
                // 高级搜索模式不建内容类型页签，类型统一由筛选面板的服务端选项决定
                types.addView(text("高级搜索",14f).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(2),0,0,0) },lp(-2,-2))
                types.addView(Space(this),lp(0,1).apply { weight=1f })
                filterToggle=chip("‹ 返回关键词搜索",false) { switchAdvanced(false) }.apply { nextFocusUpId=nav[tab].id }
                types.addView(filterToggle,lp(-2,-2))
                container.addView(types)
                nav.forEach { it.nextFocusDownId=filterToggle?.id ?: nav[tab].id }
            } else {
            // 内容类型 tab：漫剧 / 短剧（互斥切换），chip 样式强调 tab 属性
            listOf(ContentType.COMIC,ContentType.SHORT).forEach { type ->
                typeButtons[type]=chip(type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id; textSize=14f; minHeight=dp(38); setPadding(dp(16),dp(8),dp(16),dp(8)) }
                types.addView(typeButtons[type],lp(-2,-2).apply { rightMargin=dp(8) })
            }
            if(tab==0) types.addView(text("长按确认 · 管理片单",12f,muted).apply { setPadding(dp(8),0,0,0) })
            // 快捷入口提示（用户 2026-11-18）：不点按钮也能进自由刷剧
            if(tab==0) types.addView(text("长按菜单键 · 自由刷剧",12f,muted).apply { setPadding(dp(14),0,0,0); isFocusable=false })
            if(tab==0) {
                // 题材筛选、自由刷剧推到最右，与左侧漫剧/短剧 tab 用弹性间距分隔
                types.addView(Space(this),lp(0,1).apply { weight=1f })
                genreButton=addButton(types,genre?.let { "题材 · ${it.name}" } ?: "题材") { showGenrePicker() }.apply { nextFocusUpId=nav[tab].id }
                // 自由刷剧（替代原随机荐剧，用户 2026-11-18）：点了直接进推荐播放，不再是落点页
                freeFeedButton=addButton(types,"自由刷剧") { enterFreeFeed() }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==1) {
                types.addView(Space(this),lp(0,1).apply { weight=1f })
                filterToggle=chip("高级搜索",false) { switchAdvanced(true) }.apply { nextFocusUpId=nav[tab].id }
                types.addView(filterToggle,lp(-2,-2))
            }
            // 内容类型（漫剧/短剧/题材/随机）统一放在内容区首行，不挤进顶栏
            typeButtons.values.forEach { view -> (view as TextView).apply { textSize=13f; minHeight=dp(36); setPadding(dp(14),dp(7),dp(14),dp(7)) } }
            container.addView(types)
            nav.forEach { it.nextFocusDownId=(if(tab==1) filterToggle else typeButtons[library.contentType] ?: typeButtons.values.firstOrNull())?.id ?: nav[tab].id }
            }
        }
        // 右上工具固定在行末：类型按钮靠左，留白推开后接手机快传、遥控与设置
        top.addView(Space(this),lp(0,1).apply { weight=1f })
        top.addView(quickTool,lp(-2,-2).apply { rightMargin=dp(8) })
        top.addView(remoteTool,lp(-2,-2).apply { rightMargin=dp(8) })
        top.addView(settingsTool,lp(-2,-2))
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
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id; searchButton?.nextFocusUpId=input.nextFocusUpId
            // 输入框会吞掉方向键：↑ 显式交回分段/内容类型，避免焦点困在搜索框里
            input.setOnKeyListener { _,key,event ->
                if(key==KeyEvent.KEYCODE_DPAD_UP && event.action==KeyEvent.ACTION_DOWN) {
                    (segmentFirst ?: typeButtons[library.contentType] ?: nav.getOrNull(tab))?.requestFocus(); true
                } else false
            }
            if(mode==0) {
                lateinit var recent: TextView
                recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
                searchActions.addAll(listOf(recent))
            }
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }
            // 高级搜索改用筛选面板，不再占一行关键词输入，把高度留给筛选和结果网格
            if(!advancedSearch) container.addView(searchRow)
            input.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 用户编辑输入框后解除手机输入的联想抑制，恢复常规联想
                    if(phoneSearched && s?.toString()?.trim()!=query) phoneSearched=false
                    scheduleSuggest()
                }
            })
            if(advancedSearch) {
                // 高级搜索不占键盘/联想/历史三栏，筛选面板与结果网格各占整行
                buildFilterPanel(container)
            } else {
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
            }
        } else if(tab==2) {
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            aiEntry=null; aiBack=null; aiGenreButton=null
            newReleaseEntry=null; newReleaseBack=null; newReleaseWindowButton=null
            // 这三个按钮只在部分子视图里存在，重建时先清空，避免焦点链指向已经拆掉的旧按钮
            rankSwitch=null; rankRefresh=null
            heading.addView(text(when { newRelease -> "新剧上新"; aiCatalog -> "AI剧 · 全部分类"; else -> "${rankBoard.label}热播榜" },25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(newRelease) {
                newReleaseBack=addButton(heading,"‹ 返回${rankBoard.label}榜") {
                    newRelease=false; page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
                    discoveryCursor=null; discoverySelection=DiscoverySelection()
                    tabState.remove(2); tabFocus.remove(2); pendingNewReleaseFocus="back"; showCatalog(true)
                }.apply { nextFocusUpId=nav[tab].id }
                newReleaseWindowButton=addButton(heading,"时间 · ${newReleaseLabel()}") { showNewReleaseWindowPicker() }
                    .apply { nextFocusUpId=nav[tab].id }
                rankRefresh=addButton(heading,"刷新") {
                    page=1; catalogFocus=""; discoveryCursor=null; discoverySelection=newReleaseSelection()
                    pendingNewReleaseFocus="refresh"; showCatalog(true)
                }.apply { nextFocusUpId=nav[tab].id }
            } else if(aiCatalog) {
                aiBack=addButton(heading,"‹ 返回AI剧榜") {
                    aiCatalog=false; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    aiBack?.post { if(aiBack?.isAttachedToWindow==true) aiBack?.requestFocus() }
                }.apply { nextFocusUpId=nav[tab].id }
                aiGenreButton=addButton(heading,aiGenre?.let { "题材 · ${it.name}" } ?: "题材") { showGenrePicker() }
                    .apply { nextFocusUpId=nav[tab].id }
            } else {
                // 上新视图用分类接口的「最新上架 + 时间窗」，与网页榜单是两条数据通路，因此单独给入口
                newReleaseEntry=addButton(heading,"上新") {
                    newRelease=true; aiCatalog=false; aiGenre=null
                    page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
                    discoveryCursor=null; discoverySelection=newReleaseSelection(); pendingNewReleaseFocus=null
                    tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    newReleaseEntry?.post { if(newReleaseEntry?.isAttachedToWindow==true) newReleaseEntry?.requestFocus() }
                }.apply { nextFocusUpId=nav[tab].id }
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
            val defaultSource=when {
                newRelease -> "来源：红果${rankBoard.label} · 最新上架 · ${newReleaseLabel()}"
                aiCatalog -> "来源：红果AI剧分类"
                else -> "来源：红果${rankBoard.label}热播榜"
            }
            val loadingText=when { newRelease -> "正在读取最新上架…"; aiCatalog -> "正在读取AI剧分类…"; else -> "正在读取最新榜单…" }
            rankSubtitle=text(if(load) loadingText else ranking?.updatedText?.ifBlank { defaultSource } ?: defaultSource,13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=(when { newRelease -> newReleaseBack ?: newReleaseWindowButton; aiCatalog -> aiBack ?: aiGenreButton; else -> newReleaseEntry ?: aiEntry ?: rankSwitch })?.id ?: View.NO_ID }
        } else if(tab>2) {
            if(tab!=3 && favoriteManage) { favoriteManage=false; favoriteSelected.clear() }
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==3) if(favoriteManage) "管理收藏" else "我的收藏" else "最近观看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==3 && !favoriteManage) {
                favoriteCheck=addButton(heading,"检查更新") {
                    if(favoriteMonitor.running) Toast.makeText(this,L10n.tr("正在检查收藏更新…"),Toast.LENGTH_SHORT).show()
                    else if(library.favorites().isEmpty() && !accountManager.signedIn) Toast.makeText(this,L10n.tr("先收藏几部剧，再来检查更新"),Toast.LENGTH_SHORT).show()
                    else {
                        // 本机收藏逐部查详情；云端收藏顺带对账书架（0 额外请求），两个区一起更新
                        if(library.favorites().isNotEmpty()) favoriteMonitor.check(force=true)
                        if(accountManager.signedIn && !accountManager.isBusy) accountManager.refreshFavorites()
                        Toast.makeText(this,L10n.tr("开始检查收藏更新…"),Toast.LENGTH_SHORT).show()
                    }
                }.apply { nextFocusUpId=nav[tab].id }
                favoriteManageButton=addButton(heading,"管理") {
                    favoriteManage=true; favoriteSelected.clear(); showCatalog()
                }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==3 && !favoriteManage && accountManager.signedIn) {
                // 云端收藏与最近观看页同款：只读展示 + 本机收藏手动推云端
                favoriteCloudRefresh=addButton(heading,"刷新云端收藏") {
                    if(accountManager.isBusy) Toast.makeText(this,L10n.tr("账号操作进行中，请稍候"),Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(this,L10n.tr("正在刷新云端收藏…"),Toast.LENGTH_SHORT).show()
                        accountManager.refreshFavorites {
                            favoriteCloudRefresh?.takeIf { it.isAttachedToWindow }?.requestFocus()
                        }
                        main.postDelayed({ favoriteCloudRefresh?.takeIf { it.isAttachedToWindow }?.requestFocus() }, 600)
                    }
                }.apply {
                    id=View.generateViewId()
                    nextFocusUpId=nav[tab].id
                    if(accountManager.isBusy) { isEnabled=false; alpha=.4f }
                }
                favoriteCloudSync=addButton(heading,"同步到云端") {
                    accountManager.syncFavoritesToCloud { pushed,skipped,failed ->
                        val message=buildString {
                            append(if(pushed>0) "已上传 $pushed 部到云端" else "云端已是最新，无需上传")
                            if(skipped>0) append("；$skipped 部云端已有或无法同步")
                            if(failed>0) append("；$failed 部上传失败，可稍后重试")
                        }
                        tvTools.info("收藏同步完成",message,favoriteCloudSync)
                    }
                }.apply {
                    id=View.generateViewId()
                    nextFocusUpId=nav[tab].id
                    if(accountManager.isBusy) { isEnabled=false; alpha=.4f }
                }
            }
            if(tab==3 && favoriteManage) {
                favoriteSelectAll=addButton(heading,if(favoriteSelected.size==catalog.size && catalog.isNotEmpty()) "取消全选" else "全选") {
                    if(favoriteSelected.size==catalog.size) favoriteSelected.clear() else catalog.forEach { favoriteSelected+=it.id }
                    catalogAdapter?.notifyDataSetChanged(); refreshFavoriteManageBar()
                }.apply { nextFocusUpId=nav[tab].id }
                favoriteRemove=addButton(heading,"取消收藏所选（${favoriteSelected.size}）") {
                    val targets=catalog.filter { it.id in favoriteSelected }
                    if(targets.isEmpty()) return@addButton
                    tvTools.confirm("取消收藏","将取消收藏选中的 ${targets.size} 部剧，观看记录会保留。",favoriteRemove) {
                        targets.forEach { library.toggle(it) }
                        favoriteSelected.clear(); favoriteManage=false; catalogFocus=""; showCatalog()
                    }
                }.apply { nextFocusUpId=nav[tab].id; isEnabled=favoriteSelected.isNotEmpty(); alpha=if(isEnabled) 1f else .4f }
                addButton(heading,"退出管理") { favoriteManage=false; favoriteSelected.clear(); showCatalog() }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==4 && accountManager.signedIn) {
                // 手动刷新：只重新拉取云端记录，不写本机；云端记录只读，本机记录用右侧按钮上传
                historyRefresh=addButton(heading,"刷新云端记录") {
                    if(accountManager.isBusy) Toast.makeText(this,L10n.tr("账号操作进行中，请稍候"),Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(this,L10n.tr("正在刷新云端记录…"),Toast.LENGTH_SHORT).show()
                        accountManager.refreshHistory {
                            historyRefresh?.takeIf { it.isAttachedToWindow }?.requestFocus()
                        }
                        main.postDelayed({ historyRefresh?.takeIf { it.isAttachedToWindow }?.requestFocus() }, 600)
                    }
                }.apply {
                    id=View.generateViewId()
                    nextFocusUpId=nav[tab].id
                    if(accountManager.isBusy) { isEnabled=false; alpha=.4f }
                }
                // 手动同步：本机记录推到云端；云端记录只读，不在这里提供删除
                historySync=addButton(heading,"同步到云端") {
                    accountManager.syncLocalToCloud { pushed,skipped,failed ->
                        val message=buildString {
                            append(if(pushed>0) "已上传 $pushed 部到云端" else "云端已是最新，无需上传")
                            if(skipped>0) append("；$skipped 部云端已是最新")
                            if(failed>0) append("；$failed 部上传失败，可稍后重试")
                        }
                        tvTools.info("同步完成",message,historySync)
                    }
                }.apply {
                    id=View.generateViewId()
                    nextFocusUpId=nav[tab].id
                    if(accountManager.isBusy) { isEnabled=false; alpha=.4f }
                }
            }
            if(tab==4 && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录",onExtra={
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    })
                }.apply { id=View.generateViewId(); nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            if(tab==3 && !favoriteManage) {
                // 排序行：管理模式隐藏；收藏页任意位置按菜单键也可循环切换排序
                val sortRow=row().apply { setPadding(0,0,0,dp(6)) }
                sortRow.addView(text("排序",13f,muted).apply { setPadding(dp(4),0,dp(6),0) })
                val sortOptions=listOf("update" to "最近更新","watch" to "最近观看","name" to "名称")
                favoriteSortButtons=sortOptions.mapIndexed { index,(key,label) ->
                    addButton(sortRow,label,library.favoriteSort==key) {
                        if(library.favoriteSort!=key) { library.favoriteSort=key; showCatalog(); favoriteSortButtons.getOrNull(index)?.let { btn -> btn.post { if(btn.isAttachedToWindow) btn.requestFocus() } } }
                    }.apply { nextFocusUpId=favoriteCheck?.id ?: nav[tab].id; minHeight=dp(36); setPadding(dp(12),dp(6),dp(12),dp(6)) }
                }
                // 行内左右显式接线：最右不再被默认焦点搜索带到 tab 栏
                favoriteSortButtons.forEachIndexed { index,btn ->
                    btn.nextFocusLeftId=favoriteSortButtons[(index-1).coerceAtLeast(0)].id
                    btn.nextFocusRightId=favoriteSortButtons[(index+1).coerceAtMost(favoriteSortButtons.lastIndex)].id
                }
                favoriteSortFirst=favoriteSortButtons.firstOrNull()
                container.addView(sortRow)
            }
            if(tab==3) {
                favoriteStatus=text(if(favoriteManage) "已选 ${favoriteSelected.size} 部 · 确认键勾选/取消 · 返回键退出管理" else favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        // 三栏/两栏搜索页的结果区已在 contentRow 内挂载，其余页面走全宽纵向容器
        val body=searchBody ?: column().also { container.addView(it,lp(-1,0).apply { weight=1f }) }
        if(tab==3) {
            val groups=library.groups()
            if(favoriteGroupFilter!=null && favoriteGroupFilter !in groups) favoriteGroupFilter=null
            if(groups.isNotEmpty() && !favoriteManage) {
                val filterRow=row().apply { setPadding(0,0,0,dp(6)) }
                val options=listOf<String?>(null)+groups
                favoriteFilterButtons=options.mapIndexed { index,group ->
                    addButton(filterRow,group ?: "全部",favoriteGroupFilter==group) {
                        favoriteGroupFilter=group; favoriteFilterFocus=index; showCatalog(focusFilter=true)
                    }.apply { nextFocusUpId=favoriteSortFirst?.id ?: favoriteCheck?.id ?: nav[tab].id }
                }
                favoriteFilterFirst=favoriteFilterButtons.first()
                container.addView(filterRow,container.indexOfChild(favoriteStatus))
            }
        }
        // 分段与页面首个可操作控件互连：导航 ↓ 分段，分段 ↓ 内容，焦点从内容 ↑ 可回到分段
        if(segmentChips.isNotEmpty()) {
            val firstControl: View?=when(tab) {
                3 -> if(favoriteManage) favoriteSelectAll else favoriteSortFirst ?: favoriteFilterFirst ?: favoriteCheck
                else -> historyRefresh ?: historySync ?: historyManage
            }
            segmentChips.forEachIndexed { index,item ->
                item.nextFocusUpId=nav[tab].id
                item.nextFocusLeftId=segmentChips[(index-1).coerceAtLeast(0)].id
                item.nextFocusRightId=segmentChips[(index+1).coerceAtMost(segmentChips.lastIndex)].id
                item.nextFocusDownId=firstControl?.id ?: View.NO_ID
            }
            nav.forEach { it.nextFocusDownId=segmentChips.first().id }
        }
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=1; hasMore=false
        }
        if(load && tab==1 && advancedSearch) {
            loadDiscovery(body)
        } else if(load && tab==2 && newRelease) {
            loadNewRelease(body)
        } else if(load) {
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
                    homeScreen?.refresh?.text=L10n.tr("热门已更新 · 按确认查看")
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    // 手机输入的精确剧名搜索零结果时自动恢复联想，帮助用户纠正剧名
                    if(requestTab==1 && result.items.isEmpty()) { phoneSearched=false; scheduleSuggest() }
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    if(requestTab==2) rankSubtitle?.text=if(requestAi) "来源：红果AI剧分类" else ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    // 从「上新」退回榜单后，焦点要落回「上新」入口而不是首张卡片
                    if(requestTab==2 && pendingNewReleaseFocus!=null) catalogRecycler?.postDelayed({ consumePendingNewReleaseFocus() },80)
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
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text=if(requestAi) "AI剧分类加载失败" else "榜单加载失败"; if(requestTab==2) pendingNewReleaseFocus=null
                // 搜索失败时焦点回输入框，避免页面重建后焦点丢失
                if(requestTab==1 && searchFocusPending) { searchFocusPending=false; searchInput?.requestFocus() }
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,L10n.tr("搜索结果已过期，已回到第 1 页刷新"),Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType,focusFilter=focusFilter,keepViewport=keepViewport,focusSegment=focusSegment)
        // 页面重建后初始化联想区：回显点击联想词前的列表，或无词时收起中栏
        if(tab==1 && !advancedSearch) fetchSuggest()
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,focusFilter: Boolean=false,keepViewport: Boolean=false,focusSegment: Boolean=false) {
        // keepViewport：后台事件（云端数据到达）触发的重建，用户可能正在浏览页面。
        // 此时不走 catalogFocus 优先的常规恢复——鼠标滚轮不移动焦点，catalogFocus 停在进页首卡，
        // 常规恢复会把整屏弹回顶部（issue #19.2）；改按 scrollPositions+scrollOffsets 原位恢复。
        // hadGridFocus 必须在下方 gridFocusOwned=false 之前捕获：焦点原在网格内才回网格。
        val hadGridFocus=gridFocusOwned
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
        catalogPageKey="$tab:${if(aiCatalog) "ai" else ""}:${if(newRelease) "new-${newReleaseWindow}" else ""}:${collection.orEmpty()}:${focusGenre?.slug.orEmpty()}:$page:$query${if(tab==1 && advancedSearch) ":discovery:${discoverySelection.values}" else ""}"
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
        // 最近观看/收藏页：本机记录在前；登录且有云端数据时追加「云端记录/云端收藏」区，同剧可在两区各出现一次
        val cloudRows=if(tab==4 && collection==null && accountManager.signedIn) accountManager.cloudRows() else emptyList()
        val cloudFavs=if(tab==3 && !favoriteManage && collection==null && accountManager.signedIn) accountManager.cloudFavoriteRows() else emptyList()
        // 已登录但云端收藏还没拉到（启动时拉取失败等）：进页面顺手补拉一次，完成后 onAccountChanged 重建展示
        if(tab==3 && !favoriteManage && collection==null && accountManager.signedIn &&
            !accountManager.cloudFavoritesLoaded && !accountManager.isBusy) accountManager.refreshFavorites()
        // 云端收藏的续播角标来自云端观看记录：没拉到同样顺手补拉，拉到后经签名变化重建
        if(tab==3 && !favoriteManage && collection==null && accountManager.signedIn &&
            !accountManager.cloudHistoryLoaded && !accountManager.isBusy) accountManager.refreshHistory()
        val cols=if(tab==1 && !advancedSearch) searchColumns else gridColumns
        // 最近观看页本机记录默认折叠成两行：第二行末格是「查看全部」卡（与首页货架尾卡同款），
        // 点开展开全部。不折叠时本机记录一长，要往下翻很久才到云端区（用户 2026-11-24）。
        // 云端记录/云端收藏区不受影响，始终跟在折叠后的本机区后面。
        var historyMore=false
        val localTotal=displayed.size
        if(tab==4 && collection==null && !historyShowAll && displayed.size>2*cols) {
            while(displayed.size>2*cols-1) displayed.removeAt(displayed.size-1)
            historyMore=true
        }
        val localCount=displayed.size
        if(cloudRows.isNotEmpty()) displayed+=cloudRows.map { it.series }
        if(cloudFavs.isNotEmpty()) displayed+=cloudFavs.map { it.series }
        if(displayed.isEmpty()) {
            message(body,if(loading) "正在加载推荐…" else when(tab) { 1 -> if(advancedSearch) "当前条件下没有内容，可以换个条件或点「重置」" else if(query.isBlank()) "输入名称关键词，或用字母键拼首拼选择联想词" else "没有找到相关${library.contentType.label}，换个关键词试试"; 2 -> if(newRelease) "${newReleaseLabel()}内暂无${rankBoard.label}上新，按「时间」换成更长的范围" else "本页没有更多内容"; 3 -> if(favoriteGroupFilter!=null) "「$favoriteGroupFilter」分组暂无收藏" else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            wireSearchPanel(null)
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else nav[tab].requestFocus()
            return
        }
        val adapter=CatalogAdapter(displayed,cols)
        if(historyMore) { adapter.morePos=localCount+(if(localCount>0) 1 else 0); adapter.moreTotal=localTotal }
        if(cloudRows.isNotEmpty()) {
            adapter.cloudRows=cloudRows
            adapter.cloudStart=localCount
            adapter.headers=buildMap {
                if(localCount>0) put(0,"本机记录")
                // 「查看全部」卡占一格：云端区标题的 adapter 位置要再让一位
                put(localCount+(if(localCount>0) 1 else 0)+(if(historyMore) 1 else 0),"云端记录")
            }
        }
        if(cloudFavs.isNotEmpty()) {
            adapter.cloudFavs=cloudFavs
            adapter.cloudFavStart=localCount
            adapter.headers=buildMap {
                if(localCount>0) put(0,"本机收藏")
                // 云端区标题聚合上新计数：进来一眼看到有多少部更新
                val cloudUpdates=library.updatedCloudFavorites()+library.cloudNewCount()
                put(localCount+if(localCount>0) 1 else 0,"云端收藏"+if(cloudUpdates>0) " · $cloudUpdates 部有更新" else "")
            }
        }
        val grid=GridLayoutManager(this,cols)
        grid.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int)=if(adapter.isFooter(position) || adapter.isHeader(position)) cols else 1 }
        val recycler=RecyclerView(this).apply {
            id=View.generateViewId(); clipToPadding=false; isVerticalScrollBarEnabled=false; itemAnimator=null
            layoutManager=grid; this.adapter=adapter
            // Focus entering the grid lands on the first visible CARD, keeping the user's place.
            // 首个可见项可能是分节标题（不可聚焦）：不跳过的话焦点停在 RecyclerView 本身，↓ 键从此失灵。
            isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { _,focused -> if(focused && !pointerInput && pendingGridFocusPos==RecyclerView.NO_POSITION) {
                // 跨区移动（pendingGridFocusPos 已挂）时焦点会暂时收到 RecyclerView 本身防首页闪跳，
                // 此时不能抢落首卡，等 attemptPendingFocus 落到目标卡
                var first=grid.findFirstVisibleItemPosition().coerceAtLeast(0)
                while(first<adapter.itemCount && (adapter.isHeader(first) || adapter.isFooter(first))) first++
                post { findViewHolderForAdapterPosition(first)?.itemView?.requestFocus() }
            } }
            // 下键统一在 MainActivity.dispatchKeyEvent 里处理：网格重排/分页时 View 层收不到按键
        }
        catalogRecycler=recycler; catalogAdapter=adapter; catalogFocusPos=RecyclerView.NO_POSITION; gridFocusOwned=false; pendingGridFocusPos=RecyclerView.NO_POSITION
        recycler.viewTreeObserver.addOnGlobalFocusChangeListener { o,n ->
            // n===recycler：跨区移动把焦点暂存到 RecyclerView 本身（防首页闪跳），不算掉出网格。
            // 注意不能对 recycler 自己调 getChildAdapterPosition——它挂的是父容器 LinearLayout
            // 的 LayoutParams，强转 RecyclerView.LayoutParams 会 ClassCastException 崩溃。
            if(n!=null && inCatalogGrid(n,recycler)) {
                if(n===recycler) {
                    gridFocusOwned=true
                } else {
                    val pos=recycler.getChildAdapterPosition(n)
                    if(pos!=RecyclerView.NO_POSITION) {
                        catalogFocusPos=pos; gridFocusOwned=true
                        if(pos==pendingGridFocusPos) pendingGridFocusPos=RecyclerView.NO_POSITION
                    }
                }
            } else if(o!=null && o!==recycler && inCatalogGrid(o,recycler)) {
                gridFocusOwned=false
                // 焦点卡在跨区移动中被滚出屏幕回收：把焦点抓到移动目标上，别让它落去顶栏，
                // 也别让恢复逻辑把焦点抢回原卡、把刚滚出来的距离再滚回去（两者互搏=按了没反应）。
                if(pendingGridFocusPos!=RecyclerView.NO_POSITION) grabPendingFocus()
            }
            // 旧焦点为 null：焦点卡在重排中被回收，立刻补回网格，别停在顶栏「首页」
            else if(o==null && gridFocusOwned) {
                if(pendingGridFocusPos!=RecyclerView.NO_POSITION) grabPendingFocus() else restoreCatalogFocus()
            }
        }
        adapter.footer=when {
            localCatalog() -> if(collection=="hot" && collectionReturn?.hasMore==true) FOOTER_MORE_HOT else FOOTER_NONE
            hasMore -> FOOTER_NONE
            else -> FOOTER_END
        }
        body.addView(recycler,lp(-1,0).apply { weight=1f })
        nav.forEach {
            it.nextFocusDownId=segmentFirst?.id ?: when {
                tab==2 -> (when { newRelease -> newReleaseBack ?: newReleaseWindowButton ?: rankRefresh; aiCatalog -> aiBack ?: aiGenreButton ?: rankRefresh; else -> newReleaseEntry ?: aiEntry ?: rankSwitch ?: rankRefresh })?.id ?: recycler.id
                tab<=1 -> (if(tab==1) filterToggle else typeButtons[library.contentType])?.id ?: recycler.id
                else -> favoriteCheck?.id ?: historyRefresh?.id ?: historySync?.id ?: historyManage?.id ?: recycler.id
            }
        }
        rankSwitch?.nextFocusDownId=recycler.id; rankRefresh?.nextFocusDownId=recycler.id; historyManage?.nextFocusDownId=recycler.id
        historyRefresh?.nextFocusDownId=recycler.id; historySync?.nextFocusDownId=recycler.id
        historyRefresh?.nextFocusRightId=historySync?.id ?: historyManage?.id ?: View.NO_ID
        historySync?.nextFocusLeftId=historyRefresh?.id ?: View.NO_ID
        historySync?.nextFocusRightId=historyManage?.id ?: View.NO_ID
        historyManage?.nextFocusLeftId=historySync?.id ?: historyRefresh?.id ?: View.NO_ID
        // 收藏页头部按钮行：检查更新/管理左右互连，↓ 回排序行；管理模式按钮 ↓ 直接进网格
        favoriteCheck?.nextFocusDownId=favoriteSortFirst?.id ?: favoriteFilterFirst?.id ?: recycler.id
        favoriteCheck?.nextFocusRightId=favoriteManageButton?.id ?: View.NO_ID
        favoriteManageButton?.nextFocusLeftId=favoriteCheck?.id ?: View.NO_ID
        favoriteManageButton?.nextFocusDownId=favoriteSortFirst?.id ?: favoriteFilterFirst?.id ?: recycler.id
        favoriteSelectAll?.nextFocusDownId=recycler.id; favoriteRemove?.nextFocusDownId=recycler.id
        aiEntry?.nextFocusDownId=recycler.id; aiBack?.nextFocusDownId=recycler.id
        newReleaseEntry?.nextFocusDownId=recycler.id; newReleaseBack?.nextFocusDownId=recycler.id
        newReleaseWindowButton?.nextFocusDownId=recycler.id
        // 排行榜页头按钮行按真实排列顺序左右互连（上新 / 全部分类 / 看X榜 / 刷新，上新页则是 返回 / 时间 / 刷新）。
        // 只靠框架 focusSearch 时，最左侧的「上新」常被跳过，从网格 ↑ 回按钮后摸不回去；
        // 两端的键设成自己，避免按到头时焦点乱跳到右上角工具区或直接钻进网格。
        listOfNotNull(
            if(newRelease) newReleaseBack else newReleaseEntry,
            newReleaseWindowButton,
            aiEntry ?: aiBack,
            rankSwitch,
            rankRefresh,
        ).let { buttons ->
            buttons.forEachIndexed { index,button ->
                button.nextFocusLeftId=buttons.getOrNull(index-1)?.id ?: button.id
                button.nextFocusRightId=buttons.getOrNull(index+1)?.id ?: button.id
            }
        }
        favoriteFilterButtons.forEach { it.nextFocusDownId=recycler.id }
        collectionBack?.nextFocusDownId=recycler.id; genreClear?.nextFocusDownId=recycler.id
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: recycler.id }
        genreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        aiGenreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        searchInput?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchButton?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchActions.forEach { it.nextFocusDownId=azFirst?.id ?: recycler.id }
        wireSearchPanel(recycler)
        wireFilterPanelToGrid(recycler)
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus()
        else if(focusSegment && segmentChipsByTab.containsKey(tab)) segmentChipsByTab[tab]?.requestFocus()
        else {
            if(keepViewport) {
                // 原位恢复：回到重建前同一位置、同一像素偏移。savedPos 是 adapter 位置（含分节标题），
                // 不能再走 displayedIndex 换算；数据变短时夹到末尾。
                val savedPos=scrollPositions[catalogPageKey]
                val targetAdapter=if(savedPos!=null && savedPos>=0) savedPos.coerceIn(0,(adapter.itemCount-1).coerceAtLeast(0)) else 0
                val focusPos=if(adapter.isHeader(targetAdapter) && targetAdapter+1<adapter.itemCount) targetAdapter+1 else targetAdapter
                grid.scrollToPositionWithOffset(targetAdapter,scrollOffsets[catalogPageKey] ?: 0)
                recycler.post { if(catalogRecycler===recycler && hadGridFocus) recycler.findViewHolderForAdapterPosition(focusPos)?.itemView?.requestFocus() }
            } else {
                val found=displayed.indexOfFirst { it.id==catalogFocus }
                val target=if(found>=0) found else scrollPositions[catalogPageKey]?.coerceIn(0,displayed.size-1) ?: 0
                val targetAdapter=adapter.adapterPosition(target)
                grid.scrollToPositionWithOffset(targetAdapter,0)
                recycler.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(targetAdapter)?.itemView?.requestFocus() }
            }
        }
    }
    private fun inCatalogGrid(view: View,recycler: RecyclerView): Boolean {
        var cur: View?=view
        while(cur!=null) { if(cur===recycler) return true; cur=cur.parent as? View }
        return false
    }
    /**
     * 网格内的下键统一在这里处理：算下一行同列格子并直接 requestFocus。
     * 交给框架 focusSearch 时，分页/重排会把被聚焦卡回收，焦点被静默清空后回落到窗口第一个可聚焦项（顶栏「首页」），
     * 表现为「首页 ↔ 卡片」来回跳。按记住的位置下移则焦点始终留在网格里。
     */
    private fun handleCatalogDown(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && screen=="catalog" && gridFocusOwned
            && randomDialog?.isShowing!=true && exitDialog?.isShowing!=true) {
            val recycler=catalogRecycler; val adapter=catalogAdapter
            if(recycler!=null && adapter!=null) {
                val focused=recycler.focusedChild
                var pos=if(focused!=null) recycler.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
                if(pos==RecyclerView.NO_POSITION) pos=catalogFocusPos
                if(pos!=RecyclerView.NO_POSITION && !adapter.isHeader(pos) && !adapter.isFooter(pos) && !adapter.isMore(pos)) {
                    val index=adapter.displayedIndex(pos)
                    if(index in 0 until adapter.displayed.size) {
                        val target=adapter.gridNeighbor(index,1)
                        // 目标不存在（已是最后一行）也吃掉按键：别让焦点掉出网格
                        if(target>=0) moveGridFocus(adapter.adapterPosition(target))
                        else pendingGridFocusPos=RecyclerView.NO_POSITION
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    /**
     * 网格内上键：和 [handleCatalogDown] 对称，按「上一行同列」移动。
     *
     * 之前上键交给框架 focusSearch：最近观看页的「本机记录」分节标题插在卡片前面、本身不可聚焦，
     * RecyclerView 从首行卡片往上搜只能搜到它，于是按键被吞掉、按了毫无反应。
     * 这里首行显式跳到页头按钮（刷新云端记录 / 同步到云端 / 管理等）。
     */
    private fun handleCatalogUp(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_UP && screen=="catalog" && gridFocusOwned
            && randomDialog?.isShowing!=true && exitDialog?.isShowing!=true) {
            val recycler=catalogRecycler; val adapter=catalogAdapter
            if(recycler!=null && adapter!=null) {
                val focused=recycler.focusedChild
                var pos=if(focused!=null) recycler.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
                if(pos==RecyclerView.NO_POSITION) pos=catalogFocusPos
                if(pos!=RecyclerView.NO_POSITION && !adapter.isHeader(pos) && !adapter.isFooter(pos) && !adapter.isMore(pos)) {
                    val index=adapter.displayedIndex(pos)
                    if(index in 0 until adapter.displayed.size) {
                        val target=adapter.gridNeighbor(index,-1)
                        if(target>=0) moveGridFocus(adapter.adapterPosition(target))
                        else {
                            // 首行：先回本页的菜单按钮（排行榜的看榜/刷新、搜索的输入行），
                            // 再按键才回顶栏；不再一步跳到顶部菜单栏。
                            val up=when(tab) {
                                2 -> (when { newRelease -> newReleaseBack ?: newReleaseWindowButton ?: rankRefresh; aiCatalog -> aiEntry ?: aiGenreButton; else -> rankSwitch ?: rankRefresh })
                                    ?: nav.getOrNull(tab)
                                // 高级搜索：关键词搜索行是隐藏的（searchInput 等为 GONE），此前首行 ↑ 落到
                                // 隐藏视图上 requestFocus 静默失败＝卡片上按不上去（2026-11-23 用户反馈）。
                                // 展开时回条件区末行，收起时回「展开筛选」按钮，两者都接得回「‹ 返回关键词搜索」→ 顶栏。
                                1 -> (if(advancedSearch) {
                                    if(filterExpanded) filterRows.lastOrNull()?.firstOrNull() else filterCollapse ?: filterToggle
                                } else {
                                    searchInput ?: searchButton ?: searchActions.firstOrNull()?.takeIf { it.isFocusable }
                                        ?: typeButtons[library.contentType]
                                }) ?: nav.getOrNull(tab)
                                else -> historyRefresh ?: historySync ?: historyManage ?: favoriteCheck ?: favoriteSortFirst
                                    ?: genreClear ?: collectionBack ?: typeButtons[library.contentType]
                                    ?: segmentFirst ?: nav.getOrNull(tab)
                            }
                            if(up==null) return false
                            up.requestFocus()
                        }
                        return true
                    }
                }
            }
        }
        return false
    }
    /** 把焦点移到网格内指定 adapter 位置：已布局直接移，未布局先滚动再补焦点（跨分节标题时一行的滚动量不够，最多补三行）。 */
    private fun moveGridFocus(pos: Int) {
        val recycler=catalogRecycler ?: return
        pendingGridFocusPos=pos
        if(focusGridPos(pos)) { pendingGridFocusPos=RecyclerView.NO_POSITION; return }
        // 目标在屏幕外：只按一行高度滚动。用 scrollToPosition 会把目标整行锚到顶部，
        // 当前焦点卡瞬间被滚出屏幕并回收，焦点被清空后框架直接回落到顶栏「首页」。
        // 滚动方向必须跟着目标走：向上找目标时要往回滚，否则上一行永远进不了布局。
        // 焦点被回收由 grabPendingFocus 兜底接住（优先聚焦目标，而不是抢回原卡）。
        val focused=recycler.focusedChild
        if(focused==null) { recycler.scrollToPosition(pos); recycler.post { attemptPendingFocus(pos,0) }; return }
        // 跨分节移动要滚不止一行，滚动中原焦点卡会被回收、焦点被清空，
        // 框架兜底落到窗口第一个可聚焦项（顶栏「首页」）→ 用户看到首页高亮一闪而逝。
        // 滚动前把焦点收到 RecyclerView 本身（可聚焦、无高亮），滚动全程没有子卡焦点可丢；
        // 落目标时 attemptPendingFocus 再把焦点交还卡片。focus listener 侧有 pending 守卫不抢活。
        // 注意 RecyclerView 构造时是 FOCUS_AFTER_DESCENDANTS，直接 requestFocus 会先去聚焦
        // 第一个可见子卡（错误落点）；要临时切成 FOCUS_BEFORE_DESCENDANTS 强制落在自身。
        recycler.descendantFocusability=ViewGroup.FOCUS_BEFORE_DESCENDANTS
        recycler.requestFocus()
        recycler.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
        val margin=(focused.layoutParams as? RecyclerView.LayoutParams)?.let { it.topMargin+it.bottomMargin } ?: 0
        val step=focused.height+margin
        val current=recycler.getChildAdapterPosition(focused)
        val downward=current!=RecyclerView.NO_POSITION && pos>current
        recycler.scrollBy(0,if(downward) step else -step)
        recycler.post { attemptPendingFocus(pos,if(downward) step else -step) }
    }
    /** 跨区移动中焦点被回收的兜底：优先把焦点抓到 pending 目标卡上。 */
    private fun grabPendingFocus() {
        val pos=pendingGridFocusPos
        if(pos==RecyclerView.NO_POSITION) { restoreCatalogFocus(); return }
        val recycler=catalogRecycler ?: return
        recycler.post {
            if(screen!="catalog" || recycler!==catalogRecycler || recycler.focusedChild!=null) return@post
            if(focusGridPos(pos)) { pendingGridFocusPos=RecyclerView.NO_POSITION; return@post }
            // 目标还没进布局：锚过去再试一次
            recycler.scrollToPosition(pos)
            recycler.post {
                if(focusGridPos(pos)) pendingGridFocusPos=RecyclerView.NO_POSITION
            }
        }
    }
    /** moveGridFocus 的补焦循环：目标可见则落焦点，否则继续滚（最多三行）。 */
    private fun attemptPendingFocus(pos: Int, step: Int) {
        val recycler=catalogRecycler ?: return
        if(screen!="catalog" || recycler!==catalogRecycler) return
        if(focusGridPos(pos)) { pendingGridFocusPos=RecyclerView.NO_POSITION; return }
        var tries=0
        fun attempt() {
            if(screen!="catalog" || recycler!==catalogRecycler) return
            if(focusGridPos(pos)) { pendingGridFocusPos=RecyclerView.NO_POSITION; return }
            if(tries<3) { tries++; recycler.scrollBy(0,step); recycler.post { attempt() } }
        }
        attempt()
    }
    /** 网格内目标位置当前可见且已挂窗则聚焦，返回是否真的落上焦点。 */
    private fun focusGridPos(pos: Int): Boolean {
        val recycler=catalogRecycler ?: return false
        val view=recycler.findViewHolderForAdapterPosition(pos)?.itemView ?: return false
        // scrap/待布局的 holder 可能还没 attach：对它 requestFocus 是假成功，焦点随后会被框架抢走
        if(!view.isAttachedToWindow) return false
        val took=view.requestFocus()
        if(!took) return false
        return recycler.focusedChild===view || recycler.focusedChild?.parent===view
    }
    /** 记住当前焦点卡位置，供列表结构变更（footer 插入/移除、分页插入）后补回焦点，避免掉到顶栏首页 */
    private fun rememberCatalogFocus() {
        val recycler=catalogRecycler ?: return
        recycler.focusedChild?.let { catalogFocusPos=recycler.getChildAdapterPosition(it) }
    }
    private fun restoreCatalogFocus() {
        val recycler=catalogRecycler ?: return
        // 焦点不在网格里，或正在用鼠标：都不补焦点。
        // 滚轮只滚视口，被回收卡片的 parent 链已断，inCatalogGrid 认不出它曾属于网格，
        // gridFocusOwned 仍是 true；这时按旧位置 scrollToPosition 会把整屏拉回旧卡，
        // 再和框架把焦点送去顶栏「首页」来回互搏（issue #22.1）。
        if(!gridFocusOwned || pointerInput) return
        val pos=catalogFocusPos
        if(pos==RecyclerView.NO_POSITION) return
        recycler.post {
            if(screen!="catalog" || recycler!==catalogRecycler || recycler.focusedChild!=null) return@post
            val view=recycler.findViewHolderForAdapterPosition(pos)?.itemView
            if(view!=null) view.requestFocus() else {
                recycler.scrollToPosition(pos)
                recycler.post { recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
            }
        }
    }
    private fun maybeLoadMore() {
        val adapter=catalogAdapter ?: return
        if(randomDialog?.isShowing==true || loadingMore || !hasMore || localCatalog() || screen!="catalog") return
        if((tab==1 && advancedSearch) || (tab==2 && newRelease)) { loadMoreDiscovery(adapter); return }
        if(page>=waterfallMaxPages || catalog.size>=waterfallMaxPages*20) { hasMore=false; rememberCatalogFocus(); adapter.updateFooter(FOOTER_END); restoreCatalogFocus(); return }
        val requestTab=tab; val requestPage=page+1; val requestQuery=query; val requestType=library.contentType
        val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
        val requestAi=if(requestTab==2) aiCatalog else false; val requestAiGenre=if(requestAi) aiGenre else null
        loadingMore=true; rememberCatalogFocus(); adapter.updateFooter(FOOTER_LOADING); restoreCatalogFocus()
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
                restoreCatalogFocus()
            }
        }, { problem ->
            loadingMore=false
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                Toast.makeText(this,L10n.tr("搜索结果已过期，已回到第 1 页刷新"),Toast.LENGTH_LONG).show()
                showCatalog(true)
            } else {
                Toast.makeText(this,L10n.tr("加载失败，继续浏览会自动重试"),Toast.LENGTH_SHORT).show()
                catalogAdapter?.updateFooter(FOOTER_ERROR)
                restoreCatalogFocus()
            }
        })
    }
    /** 从新季标题提取季数（《xxx 2》/ 第2季 / 第二季）；提取不出返回 null，角标退化为「新季已上线」。 */
    private fun seasonNumberOf(title: String?): Int? {
        if(title.isNullOrBlank()) return null
        Regex("第\\s*([0-9]+)\\s*季").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("第\\s*([一二三四五六七八九])\\s*季").find(title)?.groupValues?.get(1)?.let { return "一二三四五六七八九".indexOf(it)+1 }
        if(Regex("第\\s*十\\s*季").containsMatchIn(title)) return 10
        Regex("(\\d+)\\s*$").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return if(it in 2..99) it else null }
        return null
    }

    private inner class CardHolder(val card: LinearLayout,val artwork: FrameLayout,val image: ImageView,val rank: TextView,val title: TextView,val badge: TextView,val updateTag: TextView): RecyclerView.ViewHolder(card) {
        var boundId=""
    }
    private inner class CatalogAdapter(val displayed: MutableList<Series>,val cols: Int): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var footer=FOOTER_NONE
        /** 分节标题：key 为 adapter 位置；仅最近观看页的本机/云端双区使用，其余页面为空。 */
        var headers: Map<Int,String> = emptyMap()
        /** displayed 中云端区的起始下标；云端卡角标、徽标与点击续播都走云端进度。 */
        var cloudStart=Int.MAX_VALUE
        var cloudRows: List<CloudProgress> = emptyList()
        /** 收藏页云端区：只读展示，卡片可带云端进度角标。 */
        var cloudFavs: List<CloudFavorite> = emptyList()
        var cloudFavStart=Int.MAX_VALUE
        /** 「查看全部」卡（最近观看本机区折叠尾卡）的 adapter 位置；-1=无。该卡不对应 displayed 条目。 */
        var morePos=-1
        /** 折叠前的本机记录总数：更多卡副标「共 N 部」用。 */
        var moreTotal=-1
        fun isHeader(position: Int)=headers.containsKey(position)
        fun isMore(position: Int)=morePos>=0 && position==morePos
        fun isFooter(position: Int)=footer!=FOOTER_NONE && position==displayed.size+headers.size+(if(morePos>=0) 1 else 0)
        /** adapter 位置 → displayed 下标（前面每有一个分节标题或「查看全部」卡就减一）。 */
        fun displayedIndex(position: Int): Int {
            var index=position
            for(key in headers.keys) if(key<position) index--
            if(morePos>=0 && morePos<position) index--
            return index
        }
        /** displayed 下标 → adapter 位置。 */
        fun adapterPosition(index: Int): Int {
            var position=index
            for(key in headers.keys.sorted()) if(key<=position) position++
            if(morePos>=0 && morePos<=position) position++
            return position
        }
        /** displayed 下标 i 前面是否紧贴一个分节标题（标题独占一行，网格行计算要跳过）。 */
        private fun sectionStart(index: Int): Boolean {
            if(index<=0) return false
            return headers.containsKey(adapterPosition(index)-1)
        }
        /** 同列上/下一视觉行的卡片下标：跨「本机/云端」分节标题无缝移动，没有则 -1。 */
        fun gridNeighbor(index: Int, dir: Int): Int =
            GridNav.neighbor(GridNav.coordinates(displayed.size,cols,::sectionStart),index,dir)
        override fun getItemCount()=displayed.size+headers.size+(if(morePos>=0) 1 else 0)+if(footer!=FOOTER_NONE) 1 else 0
        override fun getItemViewType(position: Int)=if(isHeader(position)) TYPE_HEADER else if(isFooter(position)) TYPE_FOOTER else if(isMore(position)) TYPE_MORE else TYPE_CARD
        fun updateFooter(next: Int) {
            val footerPos=displayed.size+headers.size+(if(morePos>=0) 1 else 0)
            if(footer==next) { if(next!=FOOTER_NONE) notifyItemChanged(footerPos); return }
            val had=footer!=FOOTER_NONE; footer=next
            if(next==FOOTER_NONE) notifyItemRemoved(footerPos)
            else if(had) notifyItemChanged(footerPos)
            else notifyItemInserted(footerPos)
        }
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
            if(viewType==TYPE_HEADER) {
                val label=text("",16f,muted).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(4),dp(10),0,dp(6)) }
                label.layoutParams=RecyclerView.LayoutParams(-1,-2)
                return object: RecyclerView.ViewHolder(label) {}
            }
            if(viewType==TYPE_FOOTER) {
                val frame=FrameLayout(this@MainActivity)
                frame.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
                return object: RecyclerView.ViewHolder(frame) {}
            }
            if(viewType==TYPE_MORE) {
                // 与首页货架尾卡同款：大「›」占海报位 +「查看全部」标题 + 分区名副标
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                val art=object: FrameLayout(this@MainActivity) {
                    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                        val w=MeasureSpec.getSize(widthMeasureSpec)
                        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/TvStyle.POSTER_ASPECT).toInt(),MeasureSpec.EXACTLY))
                    }
                }
                art.addView(TextView(this@MainActivity).apply { text="›"; textSize=34f; gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
                val title=text("查看全部",14f).apply { gravity=Gravity.TOP; includeFontPadding=true; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) }
                title.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                val badge=text("",12f,muted).apply { minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                card.addView(art,lp(-1,-2)); card.addView(title,lp(-1,title.measuredHeight)); card.addView(badge,lp(-1,-2))
                card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
                return object: RecyclerView.ViewHolder(card) {}
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
            // 云端收藏上新角标（已更新 N 集 / 第 X 季已上线 / 新收藏）：挂在海报右上角
            val updateTag=TextView(this@MainActivity).apply { textSize=11f; setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(4),dp(8),dp(4)); visibility=View.GONE }
            artwork.addView(updateTag,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
            // 部分机型单行/两行标题实测行高不一致，minLines 保底失效；用换行探针实测两行高度并固定，热度/集数标签才能对齐
            val title=text("测\n测",14f).apply { gravity=Gravity.TOP; includeFontPadding=true; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) }
            title.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            val titleHeight=title.measuredHeight
            val badge=text("",12f).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
            card.addView(artwork,lp(-1,-2))
            card.addView(title,lp(-1,titleHeight))
            card.addView(badge,lp(-1,-2))
            card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
            return CardHolder(card,artwork,image,rank,title,badge,updateTag)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
            if(getItemViewType(position)==TYPE_HEADER) { (holder.itemView as TextView).text=headers[position]; return }
            if(getItemViewType(position)==TYPE_FOOTER) { bindFooter(holder.itemView as FrameLayout); return }
            if(getItemViewType(position)==TYPE_MORE) {
                val card=holder.itemView as LinearLayout
                (card.getChildAt(1) as TextView).text=L10n.tr("查看全部")
                (card.getChildAt(2) as TextView).text=L10n.tr("本机记录")+if(moreTotal>0) " · 共 $moreTotal 部" else ""
                card.contentDescription="查看全部本机记录"
                card.setOnClickListener {
                    // 展开：焦点落在折叠时看不到的第一张卡上，视觉连续
                    historyShowAll=true
                    catalogFocus=displayed.getOrNull(2*cols-1)?.id ?: ""
                    showCatalog()
                }
                return
            }
            holder as CardHolder
            val di=displayedIndex(position)
            val item=displayed[di]; holder.boundId=item.id
            val cloudRow=if(di>=cloudStart) cloudRows.getOrNull(di-cloudStart) else null
            val cloudFav=if(di>=cloudFavStart) cloudFavs.getOrNull(di-cloudFavStart) else null
            val cloudFavProgress=if(cloudFav!=null) accountManager.cloudRows().firstOrNull { it.series.id==item.id } else null
            // 云端优先；云端记录还没拉到/没这部时退回本机播放记录，一样能续播
            val cloudFavLocalResume=if(cloudFav!=null && cloudFavProgress==null) library.progress(item.id) else null
            val cloudFavResumeLabel=when {
                cloudFavProgress!=null -> "第 ${cloudFavProgress.episodeIndex+1} 集 · ${formatTime(cloudFavProgress.position)}"
                cloudFavLocalResume!=null -> "第 ${cloudFavLocalResume.episodeIndex+1} 集 · ${formatTime(cloudFavLocalResume.position)}"
                else -> null
            }
            val cloudFavCanResume=cloudFavProgress!=null || cloudFavLocalResume!=null
            // 云端收藏上新状态：加更琥珀、新季/新收藏红；角标挂海报右上，副标题行同步提示
            val cloudFavUpdate=cloudFav?.let { library.cloudUpdate(it.series.id) }
            val cloudFavAdded=cloudFavUpdate?.added ?: 0
            val cloudFavNew=cloudFav!=null && library.isCloudNew(item.id)
            val cloudFavSeason=cloudFavUpdate?.takeIf { it.hasNewSeason }
            val cloudFavTag=when {
                cloudFav==null -> null
                cloudFavSeason!=null -> seasonNumberOf(cloudFavSeason.newSeasonTitle)?.let { "第$it 季已上线" } ?: "新季已上线"
                cloudFavAdded>0 -> "已更新 $cloudFavAdded 集"
                cloudFavNew -> "新收藏"
                else -> null
            }
            val cloudFavSuffix=when {
                cloudFavSeason!=null -> "《${cloudFavSeason.newSeasonTitle ?: "新季"}》已上线"
                cloudFavAdded>0 -> "+$cloudFavAdded 集更新"
                cloudFavNew -> "新收藏"
                else -> ""
            }
            if(di==0) resultFirstCard=holder.card
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
            } else if(cloudRow!=null || cloudFav!=null) {
                // 云端卡角标：与本机同剧并存时一眼可辨
                holder.rank.text=L10n.tr("云端")
                holder.rank.setTextColor(white)
                holder.rank.background=rounded(accent,6)
                holder.rank.visibility=View.VISIBLE
                card.contentDescription=if(cloudRow!=null) "云端记录，${item.title}，第 ${cloudRow.episodeIndex+1} 集" else "云端收藏，${item.title}"
            } else holder.rank.visibility=View.GONE
            if(cloudFavTag!=null) {
                val urgent=cloudFavSeason!=null || cloudFavNew
                holder.updateTag.text=cloudFavTag
                holder.updateTag.setTextColor(if(urgent) Color.rgb(121,31,31) else Color.rgb(99,56,6))
                holder.updateTag.background=rounded(if(urgent) Color.rgb(247,193,193) else Color.rgb(250,222,166),6)
                holder.updateTag.visibility=View.VISIBLE
                card.contentDescription="$cloudFavTag，${card.contentDescription}"
            } else holder.updateTag.visibility=View.GONE
            holder.title.text=item.title
            val progress=if(tab==4 || collection=="resume") library.progress(item.id) else null
            // 收藏管理模式：角标前缀 ✓ 表示已勾选，确认键只切换勾选不进详情
            val managePicked=tab==3 && favoriteManage && item.id in favoriteSelected
            holder.badge.text=if(cloudRow!=null) "第 ${cloudRow.episodeIndex+1} 集 · ${formatTime(cloudRow.position)} · 云端" else if(cloudFav!=null) (cloudFavResumeLabel?.let { "$it · " } ?: "")+(if(cloudFavUpdate!=null) "更新至 ${cloudFavUpdate.total} 集" else "尚未检查更新")+if(cloudFavSuffix.isEmpty()) "" else " · $cloudFavSuffix" else if(tab==3) (if(favoriteManage && managePicked) "✓ " else "")+library.favoriteLabel(item.id) else if(ranked) rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无" else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge
            holder.badge.setTextColor(if(managePicked) gold else if(ranked || tab==3) accent else muted)
            // 只登记本机收藏卡的角标：同剧在云端区再出现一次时不能登记（favoriteBadges 按 id 存、
            // 后绑定的会覆盖前一个，刷新本机 label 会把文字写进云端卡的角标）
            if(tab==3 && !favoriteManage && cloudFav==null) favoriteBadges[item.id]=holder.badge
            card.setOnClickListener {
                catalogFocus=item.id
                if(tab==3 && favoriteManage) {
                    if(!favoriteSelected.remove(item.id)) favoriteSelected+=item.id
                    catalogAdapter?.notifyItemChanged(position); refreshFavoriteManageBar()
                } else if(cloudRow!=null) openDetail(item,true,cloud=cloudRow) else if(cloudFav!=null) {
                    // 点进详情即视为看过：重置上新基线、清掉角标与「新收藏」标记
                    library.acknowledgeCloudFavorite(item.id, CloudAccountClient.episodeTotal(item.badge), System.currentTimeMillis())
                    catalogAdapter?.notifyItemChanged(position)
                    // 云端记录优先续播；没有就用本机播放记录（云端收藏不等于没看过）
                    openDetail(item,cloudFavCanResume,cloud=cloudFavProgress)
                } else openDetail(item,tab==4 || collection=="resume" || collection=="updates")
            }
            card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
            card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
            card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) { catalogFocus=item.id; loadCover(holder.image,item.cover); selectionPreview?.show(item,selectionStatus(item)) } }
            // 高级搜索下 searchInput 未挂载但非空，会让 Elvis 链提前命中导致 ↑ 回不到筛选面板，故面板分支必须放最前
            card.nextFocusUpId=if(di<cols) (if(tab==1 && advancedSearch) filterRows.lastOrNull()?.firstOrNull()?.id else null) ?: suggestFirst?.id ?: historyFirst?.id ?: azLastRow.firstOrNull()?.id ?: searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteSelectAll?.id ?: favoriteSortFirst?.id ?: favoriteFilterFirst?.id ?: favoriteCloudRefresh?.id ?: favoriteCheck?.id ?: historySync?.id ?: historyManage?.id ?: nav[tab].id else View.NO_ID
            if(di>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
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
                gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(8))
            }
            frame.addView(hint,FrameLayout.LayoutParams(-1,-2))
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    // ——— 推荐流（官方「猜你喜欢」个性化流）———

    /** 自由刷剧入口（用户 2026-11-18）：先播过渡动画，动画期间预拉推荐流与后两部详情，结束后直接起播。 */
    private fun enterFreeFeed() {
        if(feedEntering || (screen=="player" && recommendMode)) return
        feedEntering=true
        feedReturnTab=tab; feedHintShown=false; bingeHintShown=false
        val startedAt=android.os.SystemClock.elapsedRealtime()
        showFreeFeedTransition()
        ensureRecommendFeed { ok ->
            if(!ok) {
                dismissFreeFeedTransition(); feedEntering=false
                Toast.makeText(this,L10n.tr("推荐暂不可用，稍后再试"),Toast.LENGTH_SHORT).show()
                return@ensureRecommendFeed
            }
            if(recommendCursor<0) recommendCursor=0
            // 动画这几百毫秒别浪费：把后面两部的详情与首集地址也取好，刷起来才不卡
            prefetchRecommend(recommendCursor)
            maybePrefetchRecommendPage()
            val series=recommendItems.getOrNull(recommendCursor)
            android.util.Log.i("HongguoTV","FreeFeed enter cursor=$recommendCursor items=${recommendItems.size} detailCached=${series?.let { recommendDetails.containsKey(it.id) }}")
            if(series==null || recommendDetails.containsKey(series.id)) { revealFreeFeed(startedAt); return@ensureRecommendFeed }
            // 第一部的详情趁动画现取：拿到（轻量详情，不带补季扫描）就揭幕起播，比
            // 揭幕后 openDetail 现场拉全量详情快 1-2 秒（用户 2026-11-18：首次进入偏慢）
            var revealed=false
            val revealOnce={ if(!revealed) { revealed=true; revealFreeFeed(startedAt) } }
            work({ repository.detailLite(series.id) },{ d ->
                recommendDetails[series.id]=d
                // 揭幕前把本部续播集的地址也备好（用户 2026-11-23：首次进入偏慢）：
                // 过渡动画还剩几百毫秒，正好并行打这一发取址；起播时 playEpisode 命中
                // recommendStreams 直接 prepare，省掉一次串行的取址往返（约 0.5~1 秒）。
                val progress=library.progress(series.id)
                val target=ResumePlayback.target(d.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
                val episode=d.episodes.getOrNull(target.index)
                val fresh=episode!=null && recommendStreams[episode]?.let { android.os.SystemClock.elapsedRealtime()-it.first<10*60_000L }==null
                if(fresh) {
                    val maxQ=library.maxQuality; val wanted=episode!!
                    prefetchWorker.submit {
                        runCatching { repository.stream(wanted,maxQ,quiet=true) }.getOrNull()?.let {
                            recommendStreams[wanted]=android.os.SystemClock.elapsedRealtime() to it
                        }
                    }
                }
                android.util.Log.i("HongguoTV","FreeFeed first detail ready after ${android.os.SystemClock.elapsedRealtime()-startedAt}ms streamPrefetch=${fresh}")
                revealOnce()
            },{ _ -> revealOnce() })
            // 兜底：详情 3.5 秒还没回来也照样揭幕，让 openDetail 自己的加载顶上，动画不至于一直盖着
            main.postDelayed({ if(!isDestroyed && !revealed) android.util.Log.w("HongguoTV","FreeFeed detail fallback reveal") ; if(!isDestroyed) revealOnce() },3500)
        }
    }

    /** 揭幕起播：保证过渡动画至少播完 FEED_TRANSITION_MS；详情可能提前就绪，也只揭一次幕。 */
    private fun revealFreeFeed(startedAt: Long) {
        val holdMs=(FEED_TRANSITION_MS-(android.os.SystemClock.elapsedRealtime()-startedAt)).coerceAtLeast(0)
        main.postDelayed({
            dismissFreeFeedTransition(); feedEntering=false
            if(foreground && screen=="catalog") playRecommend(recommendCursor.coerceIn(0,recommendItems.lastIndex))
        },holdMs)
    }

    /** 过渡动画：黑幕淡入 + 「即将进入自由刷剧」缩放浮现，遮住起播前的空白，也告诉用户点了有效。 */
    private fun showFreeFeedTransition() {
        dismissFreeFeedTransition()
        val layer=FrameLayout(this).apply {
            isClickable=true; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(Color.argb(0,0,0,0))
        }
        val title=text("即将进入自由刷剧",30f).apply {
            setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER; alpha=0f
            setPadding(0,0,0,0); translationY=dp(14).toFloat(); scaleX=.94f; scaleY=.94f
        }
        val sub=text("正在挑你爱看的第一部…",15f,muted).apply { gravity=Gravity.CENTER; alpha=0f; setPadding(0,dp(12),0,0) }
        val box=column().apply { gravity=Gravity.CENTER }
        box.addView(title,lp(-1,-2)); box.addView(sub,lp(-1,-2))
        layer.addView(box,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        feedTransition=layer
        ValueAnimator.ofInt(0,238).apply {
            duration=360; addUpdateListener { layer.setBackgroundColor(Color.argb(it.animatedValue as Int,0,0,0)) }; start()
        }
        title.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(300).start()
        sub.animate().alpha(1f).setStartDelay(230).setDuration(260).start()
    }

    private fun dismissFreeFeedTransition() {
        val layer=feedTransition ?: return
        feedTransition=null
        runCatching { root.removeView(layer) }
    }

    /** 队列有货直接过，没货（或正在拉）就挂到 loadRecommendFeed 的等待链上。 */
    private fun ensureRecommendFeed(then: (Boolean)->Unit) {
        if(recommendItems.isNotEmpty()) { maybePrefetchRecommendPage(); then(true); return }
        loadRecommendFeed(then)
    }

    /** 拉一页官方推荐流（后台），回 UI 追加队列；多个等待方排队，完成后逐个唤醒。 */
    private fun loadRecommendFeed(then: (Boolean)->Unit) {
        if(recommendLoading) { recommendWaiters.add(then); return }
        recommendLoading=true
        val generation=++recommendGeneration
        recommendStatus="正在获取推荐…"
        io.submit {
            val result=runCatching { discovery.nextPage() }
            main.post {
                if(isDestroyed || generation!=recommendGeneration) return@post
                recommendLoading=false
                var ok=false
                result.onSuccess { page ->
                    val known=recommendItems.mapTo(HashSet()) { it.id }
                    val fresh=page.items.filterNot { it.id in known || it.id in library.hidden() }
                    recommendItems+=fresh
                    if(!page.hasMore) recommendEnd=true
                    ok=recommendItems.isNotEmpty()
                    recommendStatus=if(recommendItems.isEmpty()) "推荐接口没有返回内容" else "已就绪 · ${recommendItems.size} 部候选 · ${if(discovery.lastRoute=="TAB") "个性化推荐" else "热门榜单"}"
                    android.util.Log.i("HongguoTV","recommend page fresh=${fresh.size} total=${recommendItems.size} route=${discovery.lastRoute} note=${discovery.lastNote}")
                    maybePrefetchRecommendPage()
                }.onFailure { error ->
                    recommendStatus="推荐暂不可用（${error.message ?: "网络异常"}）"
                    android.util.Log.w("HongguoTV","recommend failed",error)
                }
                val waiters=recommendWaiters.toList(); recommendWaiters.clear()
                for(waiter in waiters) waiter(ok)
                then(ok)
            }
        }
    }

    /** 队列剩不到 4 部就提前续拉下一页：刷到底部不用现场等网络（用户 2026-11-18）。 */
    private fun maybePrefetchRecommendPage() {
        if(recommendEnd || recommendLoading) return
        if(recommendItems.size-(recommendCursor+1)<=3) loadRecommendFeed { }
    }

    /** 从队列第 index 部起播：缓存过的详情跳过详情页直接起播，播完自动下一部。 */
    private fun playRecommend(index: Int) {
        if(index !in recommendItems.indices) return
        recommendCursor=index
        recommendMode=true; feedDetailBrowsing=false
        // 换了新的一部就回到「播完下滑换剧」的默认节奏；本剧连播要重新按 ←/→ 开启
        feedSeriesBinge=false
        val series=recommendItems[index]
        maybePrefetchRecommendPage()
        val cached=recommendDetails[series.id]
        val open={ if(cached!=null) openDetail(series,resume=true,preloaded=cached) else openDetail(series,resume=true) }
        // 换剧整页重建，中间一样会黑：先冻住当前画面，新剧首帧渲染出来才撤（用户 2026-11-19）
        if(screen=="player" && player!=null && playerView!=null) freezeFrame(seriesLoadingTip) { open() }
        else open()
    }

    /** ↑↓ 换剧：队列内前后移动，越过尾部自动续拉一页（正常都已被 maybePrefetch 提前拉好）。 */
    private fun switchRecommend(direction: Int) {
        val target=recommendCursor+direction
        if(target<0) { Toast.makeText(this,L10n.tr("已经是推荐里的第一部了"),Toast.LENGTH_SHORT).show(); return }
        if(target<recommendItems.size) { playRecommend(target); return }
        if(recommendEnd) { Toast.makeText(this,L10n.tr("推荐刷到底了，返回键回首页"),Toast.LENGTH_SHORT).show(); return }
        loadRecommendFeed { ok ->
            if(ok && target<recommendItems.size) playRecommend(target) else Toast.makeText(this,L10n.tr("没拉到更多推荐"),Toast.LENGTH_SHORT).show()
        }
    }

    /** 一集播完自动下滑到下一部（推荐 feed 的核心节奏）。 */
    private fun nextRecommend() {
        val target=recommendCursor+1
        if(target<recommendItems.size) { playRecommend(target); return }
        if(recommendEnd) { Toast.makeText(this,L10n.tr("推荐刷到底了，返回键回首页"),Toast.LENGTH_SHORT).show(); returnToRecommendPage(); return }
        loadRecommendFeed { ok -> if(ok && target<recommendItems.size) playRecommend(target) else returnToRecommendPage() }
    }

    /** 预取当前部起后面几部的详情与首集地址：独立线程池，绝不占用起播队列。
     *  首页预热传 -1（只备推荐第 0 部），点进自由刷剧传 recommendCursor（第 1、2 部）。 */
    private fun prefetchRecommend(index: Int) {
        if(recommendPrefetching==index) return
        recommendPrefetching=index
        // 两部预取收进同一个任务串行取址：此前每部各开一个线程并发打取址接口，
        // 加上起播那一发，进自由刷剧瞬间 3 个取址齐飞，服务端风控敏感期极易当场触发冷却
        // （2026-11-23 用户实测「一进自由刷剧就限流」）。串行后峰值并发降到 2。
        // 目标集必须在主线程先取好（library 非线程安全），后台只做网络。
        val targets=mutableListOf<Pair<Int,Pair<String,String?>>>()
        for(offset in 1..2) {
            val next=recommendItems.getOrNull(index+offset) ?: continue
            // 首页预热只备「紧邻下一部」的地址：用户停在首页不点刷剧时，每次开 App
            // 白发 2 个取址请求纯添风控密度（2026-11-23）；进 feed 后起播成功还会再调一次，届时两部都备。
            if(index<0 && offset==2) continue
            targets+=offset to (next.id to library.progress(next.id)?.episodeId)
        }
        prefetchWorker.submit {
            for((offset,target) in targets) {
                val (seriesId,wantedEpisode)=target
                // 详情已经缓存过也要继续往下走：地址/首块可能还没备，跳过就等于白预取
                val detail=recommendDetails[seriesId] ?: runCatching { repository.detailLite(seriesId) }.getOrNull()
                    ?.also { recommendDetails[seriesId]=it } ?: continue
                val episode=detail.episodes.firstOrNull { it==wantedEpisode } ?: detail.episodes.firstOrNull() ?: continue
                val now=android.os.SystemClock.elapsedRealtime()
                val info=recommendStreams[episode]?.takeIf { now-it.first<10*60*1000L }?.second
                    ?: runCatching { repository.stream(episode,library.maxQuality,quiet=true) }.getOrNull()
                        ?.also { recommendStreams[episode]=now to it } ?: continue
                // 只给「紧邻的下一部」预读首块：换剧时 RemoteVideo 已经能直接用，省掉现场 prepare 的 0.5~1 秒。
                // 再往后那部只备地址——慢网下每部多下 2MB 会跟正在播的抢带宽。
                if(offset==1 && !recommendVideos.containsKey(episode)) {
                    runCatching { RemoteVideo(repository.http,info).prepare() }.getOrNull()?.let { prepared ->
                        // 最多留 3 部，超出把最旧的关掉，别让预取一直占着连接
                        while(recommendVideos.size>=3) {
                            val oldest=recommendVideos.entries.minByOrNull { it.value.first } ?: break
                            recommendVideos.remove(oldest.key)?.second?.let { runCatching { it.close() } }
                        }
                        recommendVideos[episode]=android.os.SystemClock.elapsedRealtime() to prepared
                        android.util.Log.i("HongguoTV","Prefetch video ready episode=$episode from=$index")
                    }
                }
                android.util.Log.i("HongguoTV","Prefetch recommend from=$index id=$seriesId episode=$episode stream=${recommendStreams.containsKey(episode)}")
            }
        }
    }

    /** 退出全屏 feed：哪个页签进来就回哪（用户 2026-11-18）；首页回「自由刷剧」按钮上。播放器必须收干净。 */
    private fun returnToRecommendPage() {
        releaseFreeze()
        if(screen=="player") {
            resetPlaybackSession(); sleepTimer.cancel(); sleepStopped=false
            saveProgress(); library.flush(); releasePlayer()
        }
        recommendMode=false; feedSeriesBinge=false; feedDetailBrowsing=false
        val back=feedReturnTab
        if(tab==back) showCatalog() else switchTab(back)
        // 延迟一点再落焦点：首页后台加载完成的刷新（refreshLibraryCards）会把焦点抢回卡片，
        // 同帧 post 会输给它；300ms 后刷新基本都结算完了。
        main.postDelayed({
            if(screen!="catalog" || tab!=back) return@postDelayed
            val target=if(back==0) freeFeedButton else nav.getOrNull(back)
            if(target!=null && target.isAttachedToWindow) target.requestFocus()
        },300)
    }

    /** 推荐 feed 键位：↑↓ 换剧、←→ 短按换集/长按快进快退、确认 短按暂停/长按进详情、
     *  菜单 短按呼出控制条/长按快捷面板、返回回进入页；其余键交回播放页通用逻辑（用户 2026-11-18）。 */
    private fun handleRecommendKey(event: KeyEvent): Boolean {
        // 快捷面板打开时让路：方向键/确认透传给面板的焦点项（面板靠 nextFocus 网格自己导航），
        // 返回/菜单关闭面板；否则这里的换剧/暂停拦截会把面板架空（用户 2026-11-18 反馈）。
        if(panel) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    if(event.action==KeyEvent.ACTION_UP) {
                        // menuLongFired=这次长按刚把面板叫出来，抬起只消费不关闭；再按一次菜单/返回才是关闭
                        if(event.keyCode==KeyEvent.KEYCODE_MENU && menuLongFired) menuLongFired=false
                        else hidePanel()
                    }
                    return true
                }
            }
            return false
        }
        val down=event.action==KeyEvent.ACTION_DOWN
        when(event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if(!down) { saveProgress(); returnToRecommendPage() }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if(down && event.repeatCount==0) switchRecommend(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if(down && event.repeatCount==0) switchRecommend(1); return true }
            // ←/→：短按换集（转本剧连播）；按住 550ms 后转快进/快退，按 repeat 步进（5 次后加大步长）
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir=if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if(event.action==KeyEvent.ACTION_DOWN) {
                    if(event.repeatCount==0) { feedLongDir=dir; feedLongSeeking=false; main.removeCallbacks(feedLongRunnable); main.postDelayed(feedLongRunnable,550) }
                    else if(feedLongSeeking) seek(dir,event.repeatCount)
                } else {
                    main.removeCallbacks(feedLongRunnable)
                    if(feedLongSeeking) { feedLongSeeking=false; feedLongDir=0; updatePlaybackText(); showHud() }
                    else feedSkipEpisode(dir)
                }
                return true
            }
            // 确认：短按暂停/继续；按住 550ms 进剧集详情（feed 上下文保留，返回可续播）
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    if(event.repeatCount==0 && !playError) { feedCenterFired=false; main.removeCallbacks(feedCenterRunnable); main.postDelayed(feedCenterRunnable,550) }
                } else {
                    main.removeCallbacks(feedCenterRunnable)
                    if(playError) return true
                    if(feedCenterFired) { feedCenterFired=false; return true }
                    togglePlayback()
                    if(player?.playWhenReady==true) hidePlaybackOverlay() else showHud()
                }
                return true
            }
            // 菜单：短按呼出/保持控制条；长按 500ms 快捷面板（与点播页共用 menuLongRunnable）。
            // 带 FLAG_LONG_PRESS 的注入/遥控事件直接触发，不等计时器（计时器对抬起过快的注入无效）。
            KeyEvent.KEYCODE_MENU -> {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    if(event.flags and KeyEvent.FLAG_LONG_PRESS!=0) { menuLongFired=true; main.removeCallbacks(menuLongRunnable); showQuickPanel() }
                    else if(event.repeatCount==0 && !playError) { menuLongFired=false; main.postDelayed(menuLongRunnable,500) }
                } else {
                    main.removeCallbacks(menuLongRunnable)
                    if(menuLongFired) { menuLongFired=false; return true }
                    if(!playError) showHud()
                }
                return true
            }
        }
        return false
    }

    /** feed 长按 ←/→ 的定时器：到时转 seek 模式，UP 前到达才算长按，短按仍走换集。 */
    private var feedLongDir=0
    private var feedLongSeeking=false
    private val feedLongRunnable=Runnable { feedLongSeeking=true; if(feedLongDir!=0) seek(feedLongDir,0) }
    /** feed 长按确认的定时器：到时进详情；短按（UP 前未到时）走暂停/继续。 */
    private var feedCenterFired=false
    private val feedCenterRunnable=Runnable {
        feedCenterFired=true
        // 长按确认进详情但保留 feed 上下文：在详情页按返回直接续播当前剧（用户 2026-11-18）
        feedDetailBrowsing=true
        returnToDetail(forceDetail=true)
    }

    /** feed 短按 ←/→ 换集：转本剧连播并按需提示。实体遥控（handleRecommendKey）与手机遥控（remoteDpad）共用。 */
    private fun feedSkipEpisode(dir: Int) {
        if(skipEpisode(dir)) {
            // 「已开启本剧连播」只在真的从自动下滑切成连播时说一次；之后每次换集不再重复弹
            val opening=!feedSeriesBinge && library.feedAutoSwipe
            feedSeriesBinge=true
            if(opening && !bingeHintShown) { bingeHintShown=true; showBingeBadge() }
        }
    }

    /** 开启本剧连播时给一句提示，避免用户以为「下滑换剧」坏了。 */
    private fun showBingeBadge() {
        Toast.makeText(this,L10n.tr("已开启本剧连播 · 播完自动下一集"),Toast.LENGTH_SHORT).show()
    }

    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {        homeUpdateCount=library.updatedFavorites()
        val top=typeButtons.getValue(library.contentType)
        // 首页改由 Hero 展示当前焦点剧集（图、剧名、进度），不再额外占用一行文字预览
        val home=HomeScreen(this,top,::loadCover,{ _, _-> },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
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
                1 -> { val added=library.toggle(series); Toast.makeText(this@MainActivity, L10n.tr(if(added) "已收藏" else "已取消收藏"),Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this@MainActivity, L10n.tr(if(added) "已加入稍后看" else "已移出稍后看"),Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> if(grouped) showGroupPicker(series,anchor) else openDetail(series)
                else -> if(choice==detailIndex) openDetail(series) else if(tab==4 || collection=="resume") manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,L10n.tr("已隐藏热门推荐，可在设置中恢复"),Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
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
                    if(!library.setGroup(series.id,name)) Toast.makeText(this,L10n.tr("分组数量已达上限或名称无效"),Toast.LENGTH_SHORT).show()
                    apply()
                }
                else -> { library.setGroup(series.id,options[index]); apply() }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        if(!favoriteManage) favoriteStatus?.text=favoriteMonitor.status
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
    /** 管理模式就地刷新：勾选数、全选/删除按钮状态，不重建页面避免焦点丢失。 */
    private fun refreshFavoriteManageBar() {
        val count=favoriteSelected.size
        favoriteSelectAll?.text=if(count==catalog.size && catalog.isNotEmpty()) "取消全选" else "全选"
        favoriteRemove?.let { it.text="取消收藏所选（$count）"; it.isEnabled=count>0; it.alpha=if(it.isEnabled) 1f else .4f }
        if(favoriteManage) favoriteStatus?.text="已选 $count 部 · 确认键勾选/取消 · 返回键退出管理"
    }
    /**
     * 系统风格与界面缩放要整页重建才生效：记下当前页签与设置行，重建后回到同一处设置项，
     * 不再被扔回首页。（[focusKey] 与 settingRows 的文案前缀一致）
     */
    private fun recreateKeepingPage(focusKey: String) {
        PageRestore.tab=tab; PageRestore.focusKey=focusKey
        recreate()
    }
    /** 三栏右栏的选项注册：labels/selectedIndex 绑定时现取（值可能刚被改过），onPick 写偏好并刷新中间行 */
    private class RowOptions(val labels: ()->List<String>, val selectedIndex: ()->Int, val onPick: (Int)->Unit,
                             val multi: Boolean=false, val checked: (()->Set<Int>)? = null, val onToggle: ((Int)->Unit)? = null)
    private fun settings(parent: LinearLayout, footerHost: LinearLayout) {
        parent.addView(text("设置",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(10),0,dp(5)) })
        parent.addView(text("按类别调整，偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(12)) })
        // 左中右三栏（宽屏 ≥840dp）：左分类导航 / 中设置列表 / 右选项面板（当前项的可选项，可操作，
        // 取代弹窗选择器）；窄屏自动退回原来的左右两栏（选择走弹窗）。
        // 焦点语义：中栏行 →(右)→ 选项 →(左)→ 中栏行；开关行与动作行右栏只放占位图标，不可聚焦。
        val threePane=resources.configuration.screenWidthDp>=840
        // 实验室功能 / 关于红果（6-7）全是弹窗动作与长文说明，右栏没内容价值：
        // 这两个分类隐藏第三栏，退回两栏形态，右键不响应（用户 2026-11-26）
        fun panelOn(index: Int) = threePane && index<6
        val optionPanel=SettingOptionPanel(this)
        /** 已注册可选项的设置行：labels/selectedIndex 绑定时现取（值可能刚被改过） */
        val rowOptions=linkedMapOf<SettingRow,RowOptions>()
        // 左右分栏：左侧类别与常用入口，右侧只渲染当前类别；页脚仍在滚动区之外
        val shell=row().apply { gravity=Gravity.TOP }
        parent.addView(shell,lp(-1,0).apply { weight=1f })
        val railScroll=ScrollView(this).apply {
            isVerticalScrollBarEnabled=false; clipToPadding=false
            setPadding(dp(10),dp(10),dp(10),dp(10)); background=rounded(surface)
        }
        val rail=column()
        railScroll.addView(rail)
        // 中栏卡片：标题区固定在顶部，只有下方内容区滚动（用户 2026-11-26）；
        // 卡片高度包住内容，上限为中栏可用高度，超出就内部滚动（用户 2026-11-25）
        val middleCard=column().apply { background=rounded(surface) }
        val middleHeader=column().apply { setPadding(dp(18),dp(6),dp(18),0) }
        // 滚动区高度=内容高度；首帧拿不到 shell 高度时 post 一帧再量
        val rightScroll=object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int,heightSpec: Int) {
                super.onMeasure(widthSpec,heightSpec)
                val max=shell.height-middleHeader.height
                if(max>0 && measuredHeight>max) setMeasuredDimension(measuredWidth,max)
                else if(shell.height==0) post { requestLayout() }
            }
        }.apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
        val rightPane=column().apply { setPadding(dp(18),dp(2),dp(18),dp(18)) }
        rightScroll.addView(rightPane)
        middleCard.addView(middleHeader,lp(-1,-2))
        middleCard.addView(rightScroll,lp(-1,-2))
        shell.addView(railScroll,lp(dp(if(threePane) 188 else 236),-1))
        shell.addView(middleCard,lp(0,-2).apply { weight=1f; leftMargin=dp(14) })
        if(threePane) shell.addView(optionPanel,lp(dp(268),-1).apply { leftMargin=dp(14) })

        // 每个侧边栏分类一个 column：屏保从「外观与界面」拆出来独立成类，弹幕从「播放与观看」拆出来，
        // 避免单个分类堆十几项（用户 2026-11-19）。
        // 分组容器只装设置行（放进滚动区）；标题移入中栏固定头（groupHeaders 按 group 存放）
        val groupHeaders=linkedMapOf<LinearLayout,LinearLayout>()
        val groupColumn= { column().apply { setPadding(dp(18),dp(2),dp(18),dp(18)) } }
        val playback=groupColumn()
        val keys=groupColumn()
        val danmaku=groupColumn()
        val look=groupColumn()
        val saver=groupColumn()
        val device=groupColumn()
        val lab=groupColumn()
        val about=groupColumn()
        val settingRows=mutableListOf<SettingRow>()
        val groupRows=linkedMapOf<LinearLayout,MutableList<SettingRow>>()

        fun heading(group: LinearLayout,title: String,subtitle: String) {
            groupHeaders[group]=column().apply {
                addView(text(title,18f).apply {
                    typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(10),0,0)
                })
                addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
            }
        }
        /** 分类内的小分组标题：一个分类条目多时用它分段，不参与焦点链 */
        fun subHeading(group: LinearLayout,title: String) {
            group.addView(text(title,13f,accent).apply {
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                setPadding(0,dp(8),0,dp(8))
            })
        }
        /** 设置行（规范 §6.1）：名称在左、值在右。label 里的「名称  ·  值」自动拆到两侧。
         *  desc/hint 供右栏面板回显；两栏窄屏布局下仅用于弹窗说明，不挂面板。 */
        /** 按当前焦点行重绑右栏：有选项 → 选项列表；开关 → 状态卡片；动作 → 说明+提示。面板隐藏时跳过 */
        fun bindPane(view: SettingRow) {
            if(optionPanel.visibility!=View.VISIBLE) return
            val spec=rowOptions[view]
            when {
                spec!=null -> optionPanel.bindOptions(view.title,view.desc,spec.labels(),spec.selectedIndex(),view.id,
                    spec.multi,spec.checked?.invoke() ?: emptySet()) { i ->
                    if(spec.multi) {
                        spec.onToggle?.invoke(i)
                        optionPanel.setChecked(spec.checked?.invoke() ?: emptySet())
                    } else {
                        spec.onPick(i)
                        optionPanel.repaintOptions(spec.selectedIndex())
                    }
                }
                view.toggleMode -> optionPanel.bindPlaceholder(view.title,view.desc,"在中间列表按确认键切换",view.checked)
                else -> optionPanel.bindPlaceholder(view.title,view.desc,view.hint.ifEmpty { "按确认键打开" },null)
            }
        }
        /** 注册某行的可选项（仅三栏；两栏走原弹窗 action） */
        fun withOptions(row: SettingRow,labels: ()->List<String>,selectedIndex: ()->Int,onPick: (Int)->Unit) {
            if(threePane) rowOptions[row]=RowOptions(labels,selectedIndex,onPick)
        }
        /** 注册多选行：勾选即改（onToggle 收到被点下标，写偏好后由 bindPane 刷新勾选态） */
        fun withMultiOptions(row: SettingRow,labels: ()->List<String>,checked: ()->Set<Int>,onToggle: (Int)->Unit) {
            if(threePane) rowOptions[row]=RowOptions(labels,{-1},{},multi=true,checked=checked,onToggle=onToggle)
        }
        fun hookDetail(view: SettingRow,desc: String,hint: String) {
            if(!threePane) return
            view.desc=desc; view.hint=hint
            view.onFocus={ focused -> if(focused) bindPane(view) }
            view.onValueChanged={ if(view.hasFocus()) bindPane(view) }
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit,desc: String="",hint: String=""): SettingRow {
            val view=SettingRow(this).apply {
                val i=label.indexOf("  ·  ")
                if(i>=0) { title=label.substring(0,i); value=label.substring(i+5) } else title=label
                setOnClickListener {
                    // 三栏下带选项的行按确认=进右栏选项（不再弹窗）；两栏或动作行维持原行为
                    if(threePane && rowOptions.containsKey(this)) { if(!optionPanel.requestLandingFocus()) action() }
                    else action()
                }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            settingRows+=view
            groupRows.getOrPut(group){mutableListOf()}+=view
            hookDetail(view,desc,hint)
            return view
        }
        /** 开关行（规范 §6.1/6.3）：名称 + 右侧 TvSwitch，整行点击=拨开关并回调。 */
        fun toggleSetting(group: LinearLayout,label: String,initial: Boolean,onChange: (Boolean)->Unit,desc: String="",hint: String=""): SettingRow =
            setting(group,label,{},desc,hint).asToggle(initial,onChange)
        /** 侧栏里的小号动作按钮（常用：手机快传 / 备份与恢复），比设置行更矮更小 */
        fun railAction(label: String,action: ()->Unit)=button(label,onClick=action).apply {
            gravity=Gravity.CENTER_VERTICAL; textSize=13f; minHeight=dp(42); maxLines=2
            setPadding(dp(10),dp(6),dp(10),dp(6))
        }

        // 左栏分类（用户 2026-11-25：长按确认抓起、上下键移动排序，顺序存 railOrder 随本地/扫码备份迁移）
        val categoryDefs=linkedMapOf(
            "device" to Triple("设备与数据","账号与隐私、传输备份、本机记录与诊断",device),
            "playback" to Triple("播放与观看","清晰度、倍速与连播行为",playback),
            "keys" to Triple("按键与遥控","快进步长、菜单键与鼠标右键行为",keys),
            "danmaku" to Triple("弹幕与显示","显示开关、样式与屏蔽词",danmaku),
            "look" to Triple("外观与界面","主题、缩放与首页导航",look),
            "saver" to Triple("待机屏保","空闲后自动盖屏：挑自带画面，或用手机传自己的",saver),
            "lab" to Triple("实验室功能","外部遥控、语音与自动化接口",lab),
            "about" to Triple("关于红果","版本更新、接口自检与许可",about))
        // railOrder 里只认已知 key；未知 key 忽略、缺失 key 按默认顺序补齐
        fun orderedKeys(): List<String> {
            val stored=library.railOrder.split(',').map { it.trim() }.filter { it in categoryDefs }
            return (stored+categoryDefs.keys.filter { it !in stored }).distinct()
        }
        var categoryKeys=orderedKeys().toMutableList()
        var categories=categoryKeys.map { categoryDefs.getValue(it) }
        var activeKey=categoryKeys.first()
        var active=0
        val railButtons=categoryDefs.mapValues { (_,entry) ->
            button(entry.first,onClick={}).apply {
                gravity=Gravity.CENTER_VERTICAL; textSize=15f; minHeight=dp(38); maxLines=1
                setPadding(dp(12),dp(6),dp(12),dp(6))
            }
        }
        /** 按当前顺序把按钮重新挂回左栏（排序动画的前置步骤）。
         *  注意：removeAllViews 会把常用区块和当前焦点一起冲掉——
         *  常用区块必须在这里一并补回；焦点丢失后由调用方 requestFocus 收回（用户 2026-11-25） */
        var commonLabel: TextView? = null
        var quickSendView: TextView? = null
        var backupView: TextView? = null
        fun syncRailViews() {
            rail.removeAllViews()
            categoryKeys.forEach { key -> rail.addView(railButtons.getValue(key),lp(-1,-2).apply { bottomMargin=dp(3) }) }
            commonLabel?.let { rail.addView(it,lp(-1,-2)) }
            quickSendView?.let { rail.addView(it,lp(-1,-2).apply { bottomMargin=dp(6) }) }
            backupView?.let { rail.addView(it,lp(-1,-2).apply { bottomMargin=dp(6) }) }
        }
        syncRailViews()
        nav[5].nextFocusDownId=railButtons.getValue(categoryKeys.first()).id
        var showCategory: (Int,Boolean)->Unit = { _,_-> }
        var rechainSettings: ()->Unit = {}
        fun paintRail(index: Int,focused: Boolean) {
            val item=railButtons.getValue(categoryKeys[index])
            item.setTextColor(if(focused) white else if(index==active) accent else muted)
            item.background=rounded(if(focused) TvStyle.raised else if(index==active) TvStyle.selectedSurface else Color.TRANSPARENT,if(focused||index==active) accent else Color.TRANSPARENT)
            item.setTypeface(null,if(focused || index==active) Typeface.BOLD else Typeface.NORMAL)
        }

        heading(playback,"播放与观看",categoryDefs.getValue("playback").second)
        lateinit var qualityButton: SettingRow
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P",{ showQualityPicker(qualityButton,false) },
            desc="全局默认档位。片源没有该档时自动降到最高可用档，只影响新开播的剧，不改当前正在播的这集。")
        withOptions(qualityButton,
            labels={ PlaybackQuality.options.map { "${it}P" } },
            selectedIndex={ PlaybackQuality.options.indexOf(library.maxQuality).coerceAtLeast(0) },
            onPick={ i -> val q=PlaybackQuality.options[i]; library.maxQuality=q; sessionQuality=null
                downgradeEpisode=""; downgradeCount=0; decodeRetried=false
                qualityButton.value="${q}P" })
        lateinit var softQualityButton: SettingRow
        softQualityButton=setting(playback,"软解清晰度  ·  ${library.softQuality}P",{ showSoftQualityPicker(softQualityButton) },
            desc="bytevc2 软解档位的画质，默认 360P。老电视流畅优先，调高更清晰但更吃解码性能。")
        withOptions(softQualityButton,
            labels={ PlaybackQuality.softOptions.map { "${it}P" } },
            selectedIndex={ PlaybackQuality.softOptions.indexOf(library.softQuality).coerceAtLeast(0) },
            onPick={ i -> val q=PlaybackQuality.softOptions[i]; library.softQuality=q; softQualityButton.value="${q}P" })
        lateinit var defaultSpeed: SettingRow
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}",{ showSpeedPicker(defaultSpeed,false) },
            desc="全局默认播放速度。播放页里可以临时调速，只对当前这部剧生效。")
        withOptions(defaultSpeed,
            labels={ PlaybackSpeed.options.map { PlaybackSpeed.label(it) } },
            selectedIndex={ PlaybackSpeed.options.indexOf(library.playbackSpeed).coerceAtLeast(0) },
            onPick={ i -> val s=PlaybackSpeed.options[i]; library.playbackSpeed=s; defaultSpeed.value=PlaybackSpeed.label(s) })
        lateinit var frame: SettingRow
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}",{ showFramePicker(frame,false) },
            desc="画面填充方式：适应保持比例留黑边，裁剪铺满会切掉边缘。改的是全局默认，播放页可临时切换。")
        withOptions(frame,
            labels={ listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变") },
            selectedIndex={ VideoFrameMode.entries.indexOf(library.frameMode) },
            onPick={ i -> library.frameMode=VideoFrameMode.entries[i]; frame.value=library.frameMode.label })
        lateinit var autoNextButton: SettingRow
        autoNextButton=toggleSetting(playback,"自动连播",library.autoNext,{ library.autoNext=it },
            desc="一集播完自动播放下一集。关闭后播完停在片尾，等你按确认键。")
        lateinit var autoSeasonButton: SettingRow
        autoSeasonButton=toggleSetting(playback,"系列剧连播",library.autoSeason,{ library.autoSeason=it },
            desc="同一系列的多部剧按顺序自动接续播放，第一部播完接着播第二部。")
        // 自由刷剧节奏（用户 2026-11-18）：默认把当前剧连播完；开着则一集播完就自动下滑换新剧
        lateinit var feedSwipeButton: SettingRow
        feedSwipeButton=toggleSetting(playback,"自由刷剧自动下滑",library.feedAutoSwipe,{
            library.feedAutoSwipe=it
            if(recommendMode) keyHints?.text=keyHintText()
        },desc="开启后一集播完自动下滑换一部新的热门剧；关闭则把当前剧连播完。")
        lateinit var autoSkipButton: SettingRow
        autoSkipButton=toggleSetting(playback,"不兼容片源自动跳过",extras.autoSkipUnplayable,{ extras.autoSkipUnplayable=it },
            desc="片源打不开时自动换下一集或下一部，不弹提示打断观看。")
        // 「遥控器按键」升级为一级目录「按键与遥控」（用户 2026-11-26）：五项全部直选，不再循环切换
        heading(keys,"按键与遥控",categoryDefs.getValue("keys").second)
        fun keyDesc(text: String,extra: String="改完立即生效。")="$text$extra"
        lateinit var stepRow: SettingRow
        stepRow=setting(keys,"快进步长  ·  ${extras.seekStepSeconds} 秒",{
            val options=PlaybackExtras.SEEK_STEPS
            extras.seekStepSeconds=options[(options.indexOf(extras.seekStepSeconds)+1)%options.size]
            stepRow.value="${extras.seekStepSeconds} 秒"
        },desc="按一次左右键快进/快退多少秒。")
        withOptions(stepRow,
            labels={ PlaybackExtras.SEEK_STEPS.map { "$it 秒" } },
            selectedIndex={ PlaybackExtras.SEEK_STEPS.indexOf(extras.seekStepSeconds).coerceAtLeast(0) },
            onPick={ i -> extras.seekStepSeconds=PlaybackExtras.SEEK_STEPS[i]; stepRow.value="${extras.seekStepSeconds} 秒" })
        lateinit var speedRow: SettingRow
        speedRow=setting(keys,"长按快进  ·  ${PlaybackExtras.FAST_SPEEDS.first { it==extras.longPressSpeed }.toInt()}×",{
            val options=PlaybackExtras.FAST_SPEEDS
            extras.longPressSpeed=options[(options.indexOf(extras.longPressSpeed)+1)%options.size]
            speedRow.value="${extras.longPressSpeed.toInt()}×"
        },desc="按住左右键不放时的加速倍数。")
        withOptions(speedRow,
            labels={ PlaybackExtras.FAST_SPEEDS.map { "${it.toInt()}×" } },
            selectedIndex={ PlaybackExtras.FAST_SPEEDS.indexOf(extras.longPressSpeed).coerceAtLeast(0) },
            onPick={ i -> extras.longPressSpeed=PlaybackExtras.FAST_SPEEDS[i]; speedRow.value="${extras.longPressSpeed.toInt()}×" })
        fun menuLabel(action: String)=when(action) {
            PlaybackExtras.MENU_EPISODES -> "选集"
            PlaybackExtras.MENU_DANMAKU -> "弹幕开关"
            else -> "播放设置"
        }
        lateinit var menuRow: SettingRow
        menuRow=setting(keys,"菜单键  ·  ${menuLabel(extras.menuKeyAction)}",{
            extras.menuKeyAction=when(extras.menuKeyAction) {
                PlaybackExtras.MENU_EPISODES -> PlaybackExtras.MENU_DANMAKU
                PlaybackExtras.MENU_DANMAKU -> PlaybackExtras.MENU_PANEL
                else -> PlaybackExtras.MENU_EPISODES
            }
            menuRow.value=menuLabel(extras.menuKeyAction)
        },desc="播放页菜单键（遥控上的菜单/列表键）按下去打开哪个面板。")
        withOptions(menuRow,
            labels={ listOf("选集","弹幕开关","播放设置") },
            selectedIndex={ listOf(PlaybackExtras.MENU_EPISODES,PlaybackExtras.MENU_DANMAKU,PlaybackExtras.MENU_PANEL).indexOf(extras.menuKeyAction).coerceAtLeast(0) },
            onPick={ i -> extras.menuKeyAction=listOf(PlaybackExtras.MENU_EPISODES,PlaybackExtras.MENU_DANMAKU,PlaybackExtras.MENU_PANEL)[i]; menuRow.value=menuLabel(extras.menuKeyAction) })
        fun upDownLabel(action: String)=if(action==PlaybackExtras.UPDOWN_SWITCH) "切换集数" else "选集面板"
        lateinit var upDownRow: SettingRow
        upDownRow=setting(keys,"遥控上下键  ·  ${upDownLabel(extras.dpadUpDownAction)}",{
            extras.dpadUpDownAction=if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) PlaybackExtras.UPDOWN_PANEL else PlaybackExtras.UPDOWN_SWITCH
            upDownRow.value=upDownLabel(extras.dpadUpDownAction); keyHints?.text=keyHintText()
        },desc="播放页上下键的行为：直接切上一集/下一集，或打开选集面板。改完底部按键提示同步更新。")
        withOptions(upDownRow,
            labels={ listOf("切换集数","选集面板") },
            selectedIndex={ if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) 0 else 1 },
            onPick={ i -> extras.dpadUpDownAction=if(i==0) PlaybackExtras.UPDOWN_SWITCH else PlaybackExtras.UPDOWN_PANEL; upDownRow.value=upDownLabel(extras.dpadUpDownAction); keyHints?.text=keyHintText() })
        if(TvPointer.hasMouse(this)) {
            fun mouseLabel(action: String)=when(action) {
                PlaybackExtras.MOUSE_PLAY -> "暂停/播放"
                PlaybackExtras.MOUSE_EPISODES -> "打开选集"
                PlaybackExtras.MOUSE_HUD -> "显隐控制条"
                PlaybackExtras.MOUSE_PANEL -> "播放设置"
                else -> "退出播放"
            }
            lateinit var mouseRow: SettingRow
            mouseRow=setting(keys,"鼠标右键  ·  ${mouseLabel(extras.mouseRightAction)}",{
                val actions=PlaybackExtras.MOUSE_ACTIONS
                extras.mouseRightAction=actions[(actions.indexOf(extras.mouseRightAction)+1)%actions.size]
                mouseRow.value=mouseLabel(extras.mouseRightAction); keyHints?.text=keyHintText()
            },desc="播放页里鼠标右键的行为；只接了鼠标时才会出现这一项。")
            withOptions(mouseRow,
                labels={ PlaybackExtras.MOUSE_ACTIONS.map { mouseLabel(it) } },
                selectedIndex={ PlaybackExtras.MOUSE_ACTIONS.indexOf(extras.mouseRightAction).coerceAtLeast(0) },
                onPick={ i -> extras.mouseRightAction=PlaybackExtras.MOUSE_ACTIONS[i]; mouseRow.value=mouseLabel(extras.mouseRightAction); keyHints?.text=keyHintText() })
        }
        // 弹幕从「播放与观看」拆出来独立成类（用户 2026-11-19）：开关 + 样式 + 屏蔽词共 8 项，混在播放里太长
        heading(danmaku,"弹幕与显示",categoryDefs.getValue("danmaku").second)
        val danmakuPrefRows=mutableListOf<SettingRow>()
        var danmakuExpanded=false
        lateinit var danmakuToggle: SettingRow
        fun syncDanmakuRows() {
            val show=library.danmaku
            danmakuToggle.visibility=if(show) View.VISIBLE else View.GONE
            danmakuPrefRows.forEach { it.visibility=if(show && danmakuExpanded) View.VISIBLE else View.GONE }
            danmakuToggle.value=if(danmakuExpanded) "收起" else "展开"
            rechainSettings()
        }
        lateinit var danmakuButton: SettingRow
        danmakuButton=toggleSetting(danmaku,"官方弹幕",library.danmaku,{
            library.danmaku=it
            if(!library.danmaku) danmakuExpanded=false
            syncDanmakuRows()
        },desc="官方弹幕总开关。关闭后所有剧不再拉取与显示弹幕，样式设置保留。")
        danmakuToggle=setting(danmaku,"弹幕设置  ·  展开",{
            danmakuExpanded=!danmakuExpanded
            syncDanmakuRows()
        },desc="展开字号、速度、行数等样式细节。",hint="按确认键展开/收起")
        lateinit var danmakuOpacity: SettingRow
        danmakuOpacity=setting(danmaku,"弹幕不透明度  ·  ${library.danmakuOpacity}%",{ showDanmakuOpacityPicker(danmakuOpacity) },
            desc="弹幕文字的透明程度，调低更不挡画面。")
        withOptions(danmakuOpacity,
            labels={ listOf(100,90,80,70,60,50,40,30,21).map { "$it%" } },
            selectedIndex={ listOf(100,90,80,70,60,50,40,30,21).indexOf(library.danmakuOpacity).coerceAtLeast(0) },
            onPick={ i -> val v=listOf(100,90,80,70,60,50,40,30,21)[i]; library.danmakuOpacity=v; applyDanmakuPrefs(); danmakuOpacity.value="$v%" })
        lateinit var danmakuFont: SettingRow
        danmakuFont=setting(danmaku,"弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}",{ showDanmakuFontPicker(danmakuFont) },
            desc="弹幕文字的大小。")
        withOptions(danmakuFont,
            labels={ DanmakuPrefs.FONT_LABELS.toList() },
            selectedIndex={ library.danmakuFont.coerceIn(0,DanmakuPrefs.FONT_LABELS.lastIndex) },
            onPick={ i -> library.danmakuFont=i; applyDanmakuPrefs(); danmakuFont.value=DanmakuPrefs.fontLabel(i) })
        lateinit var danmakuSpeed: SettingRow
        danmakuSpeed=setting(danmaku,"弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}",{ showDanmakuSpeedPicker(danmakuSpeed) },
            desc="弹幕横穿屏幕的快慢。")
        withOptions(danmakuSpeed,
            labels={ DanmakuPrefs.SPEED_LABELS.toList() },
            selectedIndex={ library.danmakuSpeed.coerceIn(0,DanmakuPrefs.SPEED_LABELS.lastIndex) },
            onPick={ i -> library.danmakuSpeed=i; applyDanmakuPrefs(); danmakuSpeed.value=DanmakuPrefs.speedLabel(i) })
        lateinit var danmakuRows: SettingRow
        danmakuRows=setting(danmaku,"弹幕展示行数  ·  ${library.danmakuRows} 行",{ showDanmakuRowsPicker(danmakuRows) },
            desc="屏幕上同时出现的弹幕行数上限。")
        withOptions(danmakuRows,
            labels={ (DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).map { "$it 行" } },
            selectedIndex={ (library.danmakuRows-DanmakuPrefs.ROWS_MIN).coerceIn(0,DanmakuPrefs.ROWS_MAX-DanmakuPrefs.ROWS_MIN) },
            onPick={ i -> val v=i+DanmakuPrefs.ROWS_MIN; library.danmakuRows=v; applyDanmakuPrefs(); danmakuRows.value="$v 行" })
        lateinit var danmakuReset: SettingRow
        danmakuReset=setting(danmaku,"恢复弹幕默认设置",{
            resetDanmakuPrefs()
            danmakuOpacity.value="${library.danmakuOpacity}%"
            danmakuFont.value=DanmakuPrefs.fontLabel(library.danmakuFont)
            danmakuSpeed.value=DanmakuPrefs.speedLabel(library.danmakuSpeed)
            danmakuRows.value="${library.danmakuRows} 行"
            Toast.makeText(this,L10n.tr("弹幕设置已恢复默认"),Toast.LENGTH_SHORT).show()
        },desc="一键还原上面全部弹幕样式设置，不影响弹幕开关与屏蔽词。",hint="按确认键恢复")
        danmakuPrefRows.addAll(listOf(danmakuOpacity,danmakuFont,danmakuSpeed,danmakuRows,danmakuReset))
        lateinit var blockedWords: SettingRow
        // 屏蔽词改第三栏多选（用户 2026-11-26）：勾选即屏蔽、取消即恢复，不需要清空/保存按钮
        fun blockedWordList()=listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手")+extras.blockedWords.filterNot { w -> listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手").any { it==w } }
        blockedWords=setting(danmaku,"弹幕屏蔽词  ·  ${extras.blockedWords.size} 个",{ showBlockedWordDialog(blockedWords) },
            desc="包含勾选词的弹幕直接不显示，勾选即改、立即生效。")
        withMultiOptions(blockedWords,
            labels={ blockedWordList() },
            checked={ blockedWordList().mapIndexedNotNull { i,w -> if(w in extras.blockedWords) i else null }.toSet() },
            onToggle={ i ->
                val w=blockedWordList()[i]
                extras.blockedWords=if(w in extras.blockedWords) extras.blockedWords-w else extras.blockedWords+w
                applyBlockedWords()
                blockedWords.value="${extras.blockedWords.size} 个"
            })
        heading(look,"外观与界面",categoryDefs.getValue("look").second)
        lateinit var themeButton: SettingRow
        themeButton=setting(look,"系统风格  ·  ${TvStyle.theme.label}",{
            library.theme=TvStyle.toggle().name
            recreateKeepingPage("系统风格")
        },desc="深夜影院（深色）与暖玉白（浅色）两套全局配色。切换会整页重建，完成后回到这里。")
        withOptions(themeButton,
            labels={ TvStyle.Theme.entries.map { it.label } },
            selectedIndex={ TvStyle.Theme.entries.indexOf(TvStyle.theme) },
            onPick={ i -> val t=TvStyle.Theme.entries[i]; if(t!=TvStyle.theme) { library.theme=t.name; recreateKeepingPage("系统风格") } })
        // 界面语言（2026-11-26）：手动选 zh/tw/en，或跟随系统。切换后整页重建成新语言。
        lateinit var languageButton: SettingRow
        val languageLabels=listOf("跟随系统","简体中文","繁體中文","English")
        val languageCodes=listOf(L10n.FOLLOW_SYSTEM,L10n.ZH,L10n.TW,L10n.EN)
        fun languageLabel(): String {
            val idx=languageCodes.indexOf(library.language).coerceAtLeast(0)
            return "语言  ·  ${languageLabels[idx]}"
        }
        languageButton=setting(look,languageLabel(),{
            val current=languageCodes.indexOf(library.language).coerceAtLeast(0)
            tvTools.choose("界面语言",languageLabels.mapIndexed { i,l -> l+(if(i==current) "   ·   当前" else "") },
                languageButton,{ choice ->
                    val code=languageCodes[choice]
                    if(code!=library.language) {
                        library.language=code
                        L10n.configure(code)   // 立即生效于后续文案；页面重建后全部刷新
                        recreateKeepingPage("语言")
                    }
                },focusIndex=current)
        },desc="界面文字语言。未手动选择时跟随电视系统语言；切换后整页重建生效。")
        withOptions(languageButton,
            labels={ languageLabels },
            selectedIndex={ languageCodes.indexOf(library.language).coerceAtLeast(0) },
            onPick={ i -> val code=languageCodes[i]; if(code!=library.language) {
                library.language=code; L10n.configure(code); recreateKeepingPage("语言")
            } })
        lateinit var scaleButton: SettingRow
        scaleButton=setting(look,"界面缩放  ·  ${UiScale.label(this)}",{
            // 档位多了之后循环切换体验差（要按好几次才知道自己到哪一档），改成弹窗直接选
            val current=UiScale.index(this)
            tvTools.choose("界面缩放",UiScale.LEVELS.mapIndexed { index,level ->
                level.first+(if(index==current) "   ·   当前" else "")
            },scaleButton,{ choice ->
                if(choice!=current) { UiScale.set(this,choice); recreateKeepingPage("界面缩放") }
            },focusIndex=current)
        },desc="整体界面元素的大小档位，屏幕大或看得吃力就调大。切换会整页重建。")
        withOptions(scaleButton,
            labels={ UiScale.LEVELS.map { it.first } },
            selectedIndex={ UiScale.index(this) },
            onPick={ i -> if(i!=UiScale.index(this)) { UiScale.set(this,i); recreateKeepingPage("界面缩放") } })
        lateinit var clockButton: SettingRow
        clockButton=toggleSetting(look,"显示系统时间",library.showClock,{
            library.showClock=it
            applyClock()
        },desc="在界面角落常显当前时间。")
        // 待机屏保（用户 2026-11-19 重做）：表面只有两条线——
        //   ① 屏保画面：内置 4 种风格，跑马灯里挑；
        //   ② 我的素材：自己传图/视频，选中哪一条就立刻预览。
        // 填充/间隔/声音只在选了「我的素材」后才出现，内置风格下不显示，避免新用户面对一堆无关开关。
        heading(saver,"待机屏保",categoryDefs.getValue("saver").second)
        lateinit var screensaverButton: SettingRow
        fun screensaverLabel()="待机屏保  ·  ${ScreenSaverPrefs.label(library.screensaverMinutes)}"
        screensaverButton=setting(saver,screensaverLabel(),{
            library.screensaverMinutes=if(library.screensaverMinutes==0) ScreenSaverPrefs.LEVELS.first()
                else ScreenSaverPrefs.LEVELS.getOrNull(ScreenSaverPrefs.LEVELS.indexOf(library.screensaverMinutes)+1) ?: 0
            screensaverButton.value=screensaverLabel().substringAfter("  ·  ")
            resetIdleTimer()
        },desc="空闲多久后自动进入屏保，第一档为关闭。")
        withOptions(screensaverButton,
            labels={ listOf("关闭")+ScreenSaverPrefs.LEVELS.map { "$it 分钟" } },
            selectedIndex={ if(library.screensaverMinutes==0) 0 else ScreenSaverPrefs.LEVELS.indexOf(library.screensaverMinutes).let { if(it<0) 0 else it+1 } },
            onPick={ i -> library.screensaverMinutes=if(i==0) 0 else ScreenSaverPrefs.LEVELS.getOrNull(i-1) ?: ScreenSaverPrefs.LEVELS.first()
                screensaverButton.value=if(library.screensaverMinutes==0) "关闭" else "${library.screensaverMinutes} 分钟"; resetIdleTimer() })
        // 屏保画面 = 选哪一种屏保（内置 4 种 + 我的素材），点开是跑马灯式样例筛选
        lateinit var saverStyleButton: SettingRow
        fun saverStyleLabel()="屏保画面  ·  ${ScreenSaverPrefs.styleName(library.screensaverStyle)}"
        saverStyleButton=setting(saver,saverStyleLabel(),{ showSaverStylePicker(saverStyleButton) },
            desc="内置四种风格，或用自己传的图片/视频做屏保。选「我的素材」后会多出填充、间隔等设置。",hint="按确认键打开")
        // 不注册右栏选项：弹窗是跑马灯式样例预览（每档带动画演示），通用 radio 列表表达不了，保持原弹窗
        // 以下四行只在「我的素材」下出现
        val saverCustomRows=mutableListOf<SettingRow>()
        lateinit var saverMediaButton: SettingRow
        fun saverMediaLabel(): String {
            val files=saverMediaFiles()
            if(files.isEmpty()) return "我的素材  ·  还没有素材"
            val picked=saverPickedNames(files)
            return if(picked.isEmpty()) "我的素材  ·  ${files.size} 个 · 全部轮播"
            else if(picked.size==1) "我的素材  ·  只播 ${shortName(picked[0])}"
            else "我的素材  ·  轮播 ${picked.size}/${files.size} 个"
        }
        saverMediaButton=setting(saver,saverMediaLabel(),{ showSaverMediaManager(saverMediaButton) },
            desc="管理手机快传来的图片与视频，勾选哪些参与轮播。",hint="按确认键管理")
        lateinit var saverFitButton: SettingRow
        fun saverFitLabel()="画面填充  ·  ${ScreenSaverPrefs.fitName(library.screensaverFit)}"
        saverFitButton=setting(saver,saverFitLabel(),{
            library.screensaverFit=if(library.screensaverFit=="fit") "fill" else "fit"
            saverFitButton.value=ScreenSaverPrefs.fitName(library.screensaverFit)
        },desc="适应=完整显示留边；填充=铺满裁切；拉伸=强行铺满会变形；平铺=图片原尺寸重复铺满（视频与动图回落填充）。")
        withOptions(saverFitButton,
            labels={ ScreenSaverPrefs.FITS.map { it.second } },
            selectedIndex={ ScreenSaverPrefs.FITS.indexOfFirst { it.first==library.screensaverFit }.coerceAtLeast(0) },
            onPick={ i -> val key=ScreenSaverPrefs.FITS.getOrNull(i)?.first ?: return@withOptions
                library.screensaverFit=key; saverFitButton.value=ScreenSaverPrefs.fitName(key) })
        lateinit var saverIntervalButton: SettingRow
        fun saverIntervalLabel()="轮播间隔  ·  ${ScreenSaverPrefs.intervalLabel(library.screensaverInterval)}"
        saverIntervalButton=setting(saver,saverIntervalLabel(),{
            val idx=ScreenSaverPrefs.INTERVALS.indexOf(library.screensaverInterval).coerceAtLeast(0)
            library.screensaverInterval=ScreenSaverPrefs.INTERVALS[(idx+1)%ScreenSaverPrefs.INTERVALS.size]
            saverIntervalButton.value=ScreenSaverPrefs.intervalLabel(library.screensaverInterval)
        },desc="多张素材之间切换的间隔；视频例外，播完才换下一张。")
        withOptions(saverIntervalButton,
            labels={ ScreenSaverPrefs.INTERVALS.map { ScreenSaverPrefs.intervalLabel(it) } },
            selectedIndex={ ScreenSaverPrefs.INTERVALS.indexOf(library.screensaverInterval).coerceAtLeast(0) },
            onPick={ i -> val v=ScreenSaverPrefs.INTERVALS.getOrNull(i) ?: return@withOptions
                library.screensaverInterval=v; saverIntervalButton.value=ScreenSaverPrefs.intervalLabel(v) })
        lateinit var saverSoundButton: SettingRow
        saverSoundButton=toggleSetting(saver,"视频声音",!library.screensaverMuted,{
            library.screensaverMuted=!it
        },desc="素材视频屏保时是否带声音播放。")
        saverCustomRows.addAll(listOf(saverMediaButton,saverFitButton,saverIntervalButton,saverSoundButton))
        // 角标时钟位置：右上 / 左下 / 右下 / 不显示（内置风格是屏幕中央大时钟，不受影响）
        lateinit var saverClockButton: SettingRow
        fun saverClockLabel()="时钟位置  ·  ${ScreenSaverPrefs.clockPosName(library.screensaverClockPos)}"
        saverClockButton=setting(saver,saverClockLabel(),{
            val idx=ScreenSaverPrefs.CLOCK_POSITIONS.indexOfFirst { it.first==library.screensaverClockPos }.coerceAtLeast(0)
            library.screensaverClockPos=ScreenSaverPrefs.CLOCK_POSITIONS[(idx+1)%ScreenSaverPrefs.CLOCK_POSITIONS.size].first
            saverClockButton.value=ScreenSaverPrefs.clockPosName(library.screensaverClockPos)
        },desc="屏保角标时钟的位置。内置大时钟风格本身就是时钟，这项对它不生效。")
        withOptions(saverClockButton,
            labels={ ScreenSaverPrefs.CLOCK_POSITIONS.map { it.second } },
            selectedIndex={ ScreenSaverPrefs.CLOCK_POSITIONS.indexOfFirst { it.first==library.screensaverClockPos }.coerceAtLeast(0) },
            onPick={ i -> val key=ScreenSaverPrefs.CLOCK_POSITIONS.getOrNull(i)?.first ?: return@withOptions
                library.screensaverClockPos=key; saverClockButton.value=ScreenSaverPrefs.clockPosName(key) })
        setting(saver,"立即预览  ·  按确认查看",{ previewScreensaver() },
            desc="跳过空闲计时，按当前配置立刻全屏预览屏保，任意按键退出。",hint="按确认键预览")
        fun syncSaverRows() {
            val custom=library.screensaverStyle=="custom"
            saverCustomRows.forEach { it.visibility=if(custom) View.VISIBLE else View.GONE }
            rechainSettings()
        }
        refreshSaverStyleSetting={ saverStyleButton.value=saverStyleLabel().substringAfter("  ·  "); syncSaverRows() }
        refreshSaverMediaSetting={ saverMediaButton.value=saverMediaLabel().substringAfter("  ·  ") }
        syncSaverRows()
        lateinit var netSpeedButton: SettingRow
        netSpeedButton=toggleSetting(look,"网速显示",library.netSpeed,{
            library.netSpeed=it
            applyNetSpeedOverlay()
        },desc="右上角悬浮条实时显示当前网速，所有界面常显。")
        lateinit var backdropButton: SettingRow
        fun backdropLabel()="自定义背景  ·  ${if(backdropFile()!=null) "已启用" else if(backdropStyle()=="forced") "强制欣赏" else "默认风格"}"
        backdropButton=setting(look,backdropLabel(),{
            tvTools.choose("自定义背景",listOf("手机上传图片","恢复默认背景"),backdropButton,{ choice ->
                when(choice) {
                    0 -> tvTools.backdropTransfer(backdropButton) { bytes,ext -> saveBackdrop(bytes,ext) { refreshBackdropSetting?.invoke() } }
                    1 -> tvTools.choose("恢复默认背景",listOf("强制欣赏","默认风格"),backdropButton,{ style ->
                        resetBackdrop(if(style==0) "forced" else "plain")
                        backdropButton.value=backdropLabel().substringAfter("  ·  ")
                    })
                }
            })
        },desc="用手机传一张图片作为首页背景；「强制欣赏」是内置的备用背景风格。")
        withOptions(backdropButton,
            labels={ listOf("手机上传图片","恢复默认背景") },
            selectedIndex={ -1 },
            onPick={ choice ->
                when(choice) {
                    0 -> tvTools.backdropTransfer(backdropButton) { bytes,ext -> saveBackdrop(bytes,ext) { refreshBackdropSetting?.invoke() } }
                    else -> tvTools.choose("恢复默认背景",listOf("强制欣赏","默认风格"),backdropButton,{ style ->
                        resetBackdrop(if(style==0) "forced" else "plain")
                        backdropButton.value=backdropLabel().substringAfter("  ·  ")
                    })
                }
            })
        refreshBackdropSetting={ backdropButton.value=backdropLabel().substringAfter("  ·  ") }
        lateinit var navModulesButton: SettingRow
        // 导航模块显隐改第三栏多选（用户 2026-11-26）：勾选即改，不再弹窗；两栏窄屏保留弹窗兜底
        navModulesButton=setting(look,navModulesLabel(),{ showNavModules(navModulesButton) },
            desc="勾选=在顶部导航显示，取消勾选=收起入口。首页与设置始终显示，数据不会删除；改动立即生效并刷新本页。")
        withMultiOptions(navModulesButton,
            labels={ navModuleNames().map { it.second } },
            checked={ navModuleNames().mapIndexedNotNull { i,(key,_) -> if(!navModuleHidden(key)) i else null }.toSet() },
            onToggle={ i ->
                val (key,_) =navModuleNames()[i]
                val hide=!navModuleHidden(key)
                when(key) {
                    "source" -> library.sourceHidden=hide
                    "mine" -> { library.setNavHidden("favorite",hide); library.setNavHidden("history",hide) }
                    else -> library.setNavHidden(key,hide)
                }
                settingsFocusKey="导航模块"
                showCatalog()
            })

        heading(device,"设备与数据",categoryDefs.getValue("device").second)
        subHeading(device,"账号与隐私")
        val accountRow=setting(device,"账号",{ accountManager.showLoginDialog(this) },
            desc="登录红果账号后，收藏与观看记录同步到云端，换设备登录即可拉回。").apply { value=accountManager.accountLabel }
        accountButton=accountRow
        // 三栏：账号动作进右栏选项（登录方式/云端同步/退出），选中即执行；两栏兜底走原选择弹窗（用户 2026-11-25）
        withOptions(accountRow,
            labels={
                if(accountManager.signedIn) listOf("刷新云端记录","同步本机到云端","退出登录")
                else listOf("扫码登录","手机号验证码登录")
            },
            selectedIndex={-1},
            onPick={ i ->
                if(accountManager.isBusy) {
                    Toast.makeText(this,L10n.tr("账号操作进行中，请稍候"),Toast.LENGTH_SHORT).show()
                } else if(accountManager.signedIn) when(i) {
                    0 -> accountManager.refreshHistory()
                    1 -> accountManager.syncLocalToCloud { pushed,skipped,failed ->
                        Toast.makeText(this@MainActivity, L10n.tr(when {
                            failed>0 -> "同步完成：上传 $pushed 部，失败 $failed 部"
                            pushed>0 -> "已上传 $pushed 部到云端${if(skipped>0) "，$skipped 部云端已是最新" else ""}"
                            else -> "云端已是最新，无需上传"
                        }),Toast.LENGTH_SHORT).show()
                    }
                    else -> accountManager.signOut()
                } else when(i) {
                    0 -> accountManager.showQrLogin(this)
                    else -> accountManager.showPhoneLogin(this)
                }
                accountRow.value=accountManager.accountLabel
                bindPane(accountRow)
            })
        // 上报观看进度 = 官方推荐画像的信号来源；关掉只是「猜你喜欢」不再因你而变，云端历史/收藏照常
        // 归类到设备与数据（跟账号一起属于「我的数据」），不再夹在播放开关里（用户 2026-11-19）
        lateinit var reportButton: SettingRow
        reportButton=toggleSetting(device,"推荐画像上报",accountManager.reportEnabled,{
            accountManager.reportEnabled=it
        },desc="向官方上报观看进度作为推荐信号。关闭只是「猜你喜欢」不再因你而变，云端历史与收藏照常。")
        subHeading(device,"传输与备份")
        fun quickFilesLabel(count: Int,bytes: Long)="快传文件  ·  ${if(count<=0) "暂无" else "$count 个 · ${TransferRules.humanBytes(bytes)}"}"
        fun quickFilesDirs()=TransferKind.entries.map { java.io.File(filesDir,TransferRules.directoryName(it)) }.distinct()
        lateinit var quickFiles: SettingRow
        fun refreshQuickFiles() {
            io.execute {
                val list=runCatching { TransferFiles.list(quickFilesDirs()) }.getOrDefault(emptyList())
                main.post { if(!isDestroyed) quickFiles.value=quickFilesLabel(list.size,list.sumOf { it.file.length() }).substringAfter("  ·  ") }
            }
        }
        quickFiles=setting(device,quickFilesLabel(0,0),{
            tvTools.quickFiles(quickFiles,{ file,kind -> handleQuickTransfer(file,kind,true,quickFiles) },{
                tvTools.quickTransfer(quickFiles,{ file,kind -> handleQuickTransfer(file,kind) },{ refreshQuickFiles() })
            },{ refreshQuickFiles() })
        },desc="手机快传过来的 APK 与视频文件，可就地打开或管理。",hint="按确认键管理")
        refreshQuickFiles()
        lateinit var localBackupButton: SettingRow
        localBackupButton=setting(device,localBackupLabel(),{ showLocalBackupDialog(localBackupButton) },
            desc="本地备份收藏、观看记录与播放设置，卸载重装后可自动恢复；也可扫码迁到另一台设备。备份是明文文件，可随时关闭。")
        // 三栏：备份动作进右栏选项（按当前状态给出可做的事），选中即执行；两栏兜底走原弹窗（用户 2026-11-25）
        withOptions(localBackupButton,
            labels={
                val storage=library.localBackup()
                when {
                    !library.localBackupEnabled -> listOf("开启本地自动备份")
                    !storage.supported -> listOf("自动备份不可用（无公共存储）","关闭本地自动备份")
                    storage.needsPermission -> listOf("授权存储权限","关闭本地自动备份")
                    library.isEmptyForRestore() && library.hasLocalBackup() -> listOf("立即恢复","关闭本地自动备份")
                    else -> listOf("立即备份","关闭本地自动备份")
                }
            },
            selectedIndex={-1},
            onPick={ i ->
                val storage=library.localBackup()
                if(i==1) {
                    library.localBackupEnabled=false
                    Toast.makeText(this,L10n.tr("已关闭本地自动备份，已有备份文件保留"),Toast.LENGTH_SHORT).show()
                } else when {
                    !library.localBackupEnabled -> {
                        library.localBackupEnabled=true
                        if(storage.needsPermission) requestLocalBackupPermission(localBackupButton) else localBackupAction(localBackupButton)
                    }
                    storage.supported && storage.needsPermission -> requestLocalBackupPermission(localBackupButton)
                    storage.supported -> localBackupAction(localBackupButton)
                }
                localBackupButton.setValueLabel(localBackupLabel())
                bindPane(localBackupButton)
            })
        subHeading(device,"本机记录")
        lateinit var hidden: SettingRow
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部",{
            library.unhideAll(); hidden.value="0 部"; Toast.makeText(this,L10n.tr("热门推荐已恢复"),Toast.LENGTH_SHORT).show()
        },desc="把之前手动隐藏的热门推荐一次性全部恢复显示。",hint="按确认键恢复")
        lateinit var statsButton: SettingRow
        statsButton=setting(device,"观看统计  ·  本机累计",{ showWatchStats(statsButton) },
            desc="查看本机累计观看时长与集数分布。")
        fun cacheBytes()=listOfNotNull(cacheDir,externalCacheDir).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        fun cacheLabel(bytes: Long?)="清理缓存  ·  ${when {
            bytes==null -> "计算中…"
            bytes in 0..1023 -> "$bytes B"; bytes in 1024..1048575 -> "${bytes/1024} KB"; else -> "%.1f MB".format(bytes/1048576f)
        }}"
        lateinit var clearCache: SettingRow
        fun clearCacheNow() {
            io.execute {
                listOfNotNull(cacheDir,externalCacheDir).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                // 统计同样放后台，避免删完立刻在主线程遍历目录
                val remaining=cacheBytes()
                main.post {
                    if(isDestroyed) return@post
                    clearCache.value=cacheLabel(remaining).substringAfter("  ·  ")
                    Toast.makeText(this,L10n.tr("缓存已清理"),Toast.LENGTH_SHORT).show()
                }
            }
        }
        clearCache=setting(device,cacheLabel(null),{
            TvDialog.confirm(this,"清理缓存","清除封面图片和已下载的安装包等缓存数据，不影响收藏和观看记录。") { clearCacheNow() }.show()
        },desc="清除封面图片与已下载安装包等缓存，收藏和观看记录不受影响。",hint="按确认键清理")
        // 三栏：确认动作进右栏选项，不再弹确认框；两栏兜底仍走确认弹窗（用户 2026-11-25）
        withOptions(clearCache,labels={ listOf("立即清理","暂不清理") },selectedIndex={-1},
            onPick={ i -> if(i==0) clearCacheNow() })
        // 大小统计会遍历缓存目录，放到后台算完再回填，避免设置页构建时阻塞主线程
        io.execute {
            val bytes=cacheBytes()
            main.post { if(!isDestroyed) clearCache.value=cacheLabel(bytes).substringAfter("  ·  ") }
        }
        // 崩溃日志（用户 2026-11-21）：默认关闭；文件在 cacheDir/crash，「清理缓存」会自动带走，这里另给单独清理与扫码导出
        subHeading(device,"诊断")
        fun crashBytes()=CrashLogger.sizeBytes(this)
        fun crashLabel(bytes: Long?)="清理崩溃日志  ·  ${when {
            bytes==null -> "计算中…"
            bytes==0L -> "无日志"
            bytes in 1..1023 -> "$bytes B"; bytes in 1024..1048575 -> "${bytes/1024} KB"; else -> "%.1f MB".format(bytes/1048576f)
        }}（${CrashLogger.files(this).size} 个文件）"
        lateinit var crashToggle: SettingRow
        crashToggle=toggleSetting(device,"崩溃日志记录",CrashLogger.enabled(this),{ next ->
            CrashLogger.setEnabled(this,next)
            Toast.makeText(this@MainActivity, L10n.tr(if(next) "已开启：发生崩溃时会记录日志" else "已关闭：不再记录新日志（旧日志保留）"),Toast.LENGTH_SHORT).show()
        },desc="发生崩溃时在本机记录日志，便于排查问题。默认关闭。")
        lateinit var crashExport: SettingRow
        crashExport=setting(device,"导出崩溃日志  ·  手机扫码",{
            if(!CrashLogger.enabled(this) && CrashLogger.sizeBytes(this)==0L) {
                tvTools.info("导出崩溃日志","当前没有崩溃日志，也没有开启记录。开启后再次遇到崩溃即可回来导出。",crashExport)
            } else {
                io.execute {
                    val logs=CrashLogger.combined(this@MainActivity)
                    main.post {
                        if(isDestroyed) return@post
                        if(logs==null) tvTools.info("导出崩溃日志","当前没有崩溃日志。",crashExport)
                        else tvTools.crashLogTransfer(logs,crashExport)
                    }
                }
            }
        },desc="生成二维码用手机扫码带走崩溃日志，反馈问题时用。")
        lateinit var crashClear: SettingRow
        fun clearCrashNow() {
            io.execute {
                CrashLogger.clear(this@MainActivity)
                val remaining=CrashLogger.sizeBytes(this@MainActivity)
                main.post { if(!isDestroyed) { crashClear.value=crashLabel(remaining).substringAfter("  ·  "); Toast.makeText(this@MainActivity,L10n.tr("崩溃日志已清理"),Toast.LENGTH_SHORT).show() } }
            }
        }
        crashClear=setting(device,crashLabel(null),{
            if(CrashLogger.sizeBytes(this)==0L) { Toast.makeText(this,L10n.tr("当前没有崩溃日志"),Toast.LENGTH_SHORT).show() }
            else TvDialog.confirm(this,"清理崩溃日志","删除本机保存的全部崩溃日志文件，不影响其他数据。") { clearCrashNow() }.show()
        },desc="删除本机保存的全部崩溃日志文件，不影响其他数据。",hint="按确认键清理")
        // 三栏：确认动作进右栏选项，不再弹确认框；两栏兜底仍走确认弹窗（用户 2026-11-25）
        withOptions(crashClear,labels={ listOf("立即清理","暂不清理") },selectedIndex={-1},
            onPick={ i -> if(i==0) clearCrashNow() })
        io.execute { val bytes=crashBytes(); main.post { if(!isDestroyed) crashClear.value=crashLabel(bytes).substringAfter("  ·  ") } }
        // 侧栏底部「常用」（用户 2026-11-19）：屏幕放不下就跟侧栏一起滚动，不为了塞下它去砍分类
        lateinit var quickSend: TextView
        quickSend=railAction("手机快传  ·  传 APK / 视频") {
            tvTools.quickTransfer(quickSend,{ file,kind -> handleQuickTransfer(file,kind) },{ refreshQuickFiles() },
                { tvTools.quickFiles(quickSend,{ f,k -> handleQuickTransfer(f,k,true,quickFiles) },{ tvTools.quickTransfer(quickSend,{ file,kind -> handleQuickTransfer(file,kind) },{ refreshQuickFiles() }) },{ refreshQuickFiles() }) })
        }
        lateinit var backup: TextView
        backup=railAction("备份与恢复  ·  手机扫码") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,L10n.tr("本机收藏与记录已合并；云端记录请登录账号后拉取"),Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        rail.addView(TextView(this).also { commonLabel=it }.apply {
            text="常用"; textSize=11f; setTextColor(muted); setPadding(dp(12),dp(10),0,dp(5))
        },lp(-1,-2))
        quickSendView=quickSend
        backupView=backup
        syncRailViews()
        val commonButtons=listOf(quickSend,backup)
        // 常用入口也回显到右栏：链式挂监听，保留 focusStyle 原有绘制；面板隐藏（无右栏分类）时跳过
        if(threePane) {
            commonButtons.forEachIndexed { index,item ->
                val old=item.onFocusChangeListener
                item.onFocusChangeListener=View.OnFocusChangeListener { v,focused ->
                    old?.onFocusChange(v,focused)
                    if(focused && optionPanel.visibility==View.VISIBLE) {
                        if(index==0) optionPanel.bindPlaceholder("手机快传","用手机扫码把 APK 或视频直接传到电视，常用于装包与投喂本地视频。","按确认键打开",null)
                        else optionPanel.bindPlaceholder("备份与恢复","生成二维码把本机收藏与观看记录迁到另一台设备，重装或换机后扫码恢复。","按确认键打开",null)
                    }
                }
            }
        }
        heading(lab,"实验室功能",categoryDefs.getValue("lab").second)
        lateinit var remoteAddressButton: SettingRow
        remoteAddressButton=setting(lab,"遥控地址  ·  自动化 / 语音桥接",{ showRemoteAddress(remoteAddressButton) },
            desc="手机浏览器或自动化脚本通过 HTTP 控制电视的入口地址，含语音桥接用法说明。")
        lateinit var xiaoAiButton: SettingRow
        xiaoAiButton=setting(lab,xiaoAiLabel(),{ showXiaoAiDialog(xiaoAiButton) },
            desc="绑定小爱同学：喊一句话就能换台、搜剧、调设置。")
        lateinit var proxyButton: SettingRow
        proxyButton=setting(lab,proxyLabel(),{ showProxyDialog(proxyButton) },
            desc="部分片源域名被污染打不开时，填一个代理地址转发这些请求。")
        heading(about,"关于红果",categoryDefs.getValue("about").second)
        lateinit var updates: SettingRow
        updates=setting(about,"版本与更新  ·  ${BuildConfig.VERSION_NAME}",{ updater.show(updates) },
            desc="查看当前版本号与更新日志，手动检查或直接安装新版本。")
        lateinit var profileRow: SettingRow
        profileRow=setting(about,"取址协议档案",{ showProfileSheet(profileRow) },
            desc="播放取址用的签名常量与门地址，支持远程下发；出问题时一键恢复内置默认。")
        profileRow.value=profileUpdater.statusLine()
        lateinit var checkButton: SettingRow
        checkButton=setting(about,"接口自检  ·  首页 / 搜索 / 播放",{ showInterfaceCheck(checkButton) },
            desc="逐项探测首页、搜索与播放接口是否可用，看剧异常时先跑一遍它。")
        val license=setting(about,"开源许可",{
            TvDialog.info(this,"开源许可","本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：jycao2/hongguoTV。").show()
        },desc="本应用的开源协议、引用项目与致谢。",hint="按确认键查看")
        // 页脚固定在滚动区之外（屏幕底部居中）：始终可见，因此不再需要抢焦点
        val settingsFooter=text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与本机记录保存在设备 · 登录后可同步到红果账号",12f,muted).apply {
            id=View.generateViewId(); isFocusable=false; setPadding(dp(10),dp(8),dp(10),dp(3))
        }
        val settingsDisclaimer=text("本应用仅用于技术交流与学习，不提供、不存储、不传播任何视频内容，内容版权归原平台所有。",11f,muted).apply {
            gravity=Gravity.CENTER; setPadding(dp(10),0,dp(10),dp(6))
        }
        rechainSettings={
            val group=categories[active].third
            val items=groupRows[group].orEmpty().filter { it.visibility==View.VISIBLE }
            items.forEachIndexed { index,view ->
                view.nextFocusUpId=items.getOrNull(index-1)?.id ?: railButtons.getValue(categoryKeys[active]).id
                // 页脚固定在屏幕上，不再作为焦点链末端，最后一项按下不再移动焦点
                view.nextFocusDownId=items.getOrNull(index+1)?.id ?: View.NO_ID
                view.nextFocusLeftId=railButtons.getValue(categoryKeys[active]).id
                // 有选项的行右键放行给默认焦点搜索（右栏选项列）；其余行自指防掉进默认搜索
                view.nextFocusRightId=if(panelOn(active) && rowOptions.containsKey(view)) View.NO_ID else view.id
            }
            categoryKeys.forEachIndexed { index,key ->
                val item=railButtons.getValue(key)
                val up=railButtons.getValue(categoryKeys.getOrNull(index-1) ?: categoryKeys.first())
                val down=railButtons.getValue(categoryKeys.getOrNull(index+1) ?: categoryKeys.last())
                item.nextFocusUpId=up.id
                item.nextFocusDownId=down.id
                item.nextFocusLeftId=item.id
                item.nextFocusRightId=if(index==active) items.firstOrNull()?.id ?: item.id else item.id
            }
            commonButtons.forEachIndexed { index,item ->
                item.nextFocusUpId=if(index==0) railButtons.getValue(categoryKeys.last()).id else commonButtons[index-1].id
                item.nextFocusDownId=commonButtons.getOrNull(index+1)?.id ?: View.NO_ID
                item.nextFocusLeftId=item.id
                item.nextFocusRightId=items.firstOrNull()?.id ?: item.id
            }
            nav.forEach { it.nextFocusDownId=railButtons.getValue(categoryKeys.first()).id }
            // 顶栏「设置」按钮在顶栏链末尾、右键原本自指：首次进设置焦点停在它上面时，
            // 按右没反应（用户 2026-11-25）。右键改为进入当前分类中栏第一行
            nav[5].nextFocusRightId=items.firstOrNull()?.id ?: railButtons.getValue(categoryKeys.first()).id
        }
        showCategory={ index,focusFirst ->
            if(index in categories.indices) {
                val changed=index!=active
                active=index
                activeKey=categoryKeys[index]
                if(changed) {
                    middleHeader.removeAllViews()
                    groupHeaders[categories[index].third]?.let { middleHeader.addView(it,lp(-1,-2)) }
                    rightPane.removeAllViews()
                    rightPane.addView(categories[index].third,lp(-1,-2))
                }
                // 无右栏内容的分类隐藏第三栏，中栏吃满整行宽度
                optionPanel.visibility=if(panelOn(index)) View.VISIBLE else View.GONE
                categoryKeys.forEach { key -> paintRail(categoryKeys.indexOf(key),railButtons.getValue(key).hasFocus()) }
                rechainSettings()
                // 切分类会把中/右栏整块换掉，新长出来的文案没经过 base() 的那次兜底扫描：
                // 这里补一次（查不到就原样保留，重复调用安全）。
                main.post { UiL10n.apply(root) }
                if(focusFirst) {
                    (groupRows[categories[index].third].orEmpty().firstOrNull { it.visibility==View.VISIBLE } ?: railButtons.getValue(categoryKeys[index])).requestFocus()
                }
            }
        }
        middleHeader.addView(groupHeaders[categories[0].third],lp(-1,-2))
        rightPane.addView(categories[0].third,lp(-1,-2))
        syncDanmakuRows()
        // ---- 左栏排序模式（用户 2026-11-25）：长按确认抓起 → 上下键移动（滑动动画）→ 确认/返回放下并保存 ----
        fun startRailReorder(pos: Int) {
            railReorderPos=pos
            // 冻结上下焦点：排序期间方向键由 dispatchKeyEvent 接管
            railButtons.values.forEach { b -> b.nextFocusUpId=b.id; b.nextFocusDownId=b.id }
            railButtons.getValue(categoryKeys[pos]).animate().scaleX(1.08f).scaleY(1.08f).setDuration(140).start()
            Toast.makeText(this,L10n.tr("上下键移动 · 确认键放下"),Toast.LENGTH_SHORT).show()
        }
        railReorderMove={ delta ->
            val from=railReorderPos
            if(from>=0) {
                val to=from+delta
                if(to in categoryKeys.indices) {
                    val oldTop=categoryKeys.associate { it to railButtons.getValue(it).top.toFloat() }
                    val k=categoryKeys[from]; categoryKeys[from]=categoryKeys[to]; categoryKeys[to]=k
                    categories=categoryKeys.map { categoryDefs.getValue(it) }
                    if(from==active) active=to else if(to==active) active=from
                    activeKey=categoryKeys[active]
                    syncRailViews()
                    // removeAllViews 会摘掉焦点按钮，系统兜底会把焦点塞给顶栏——立刻收回被抓按钮，
                    // 否则高亮飞出左栏、左右键看起来像焦点在容器外乱跑（用户 2026-11-25）
                    val grabNow=railButtons.getValue(categoryKeys[to])
                    grabNow.requestFocus()
                    // 滑动动画：视图已按新顺序挂好，先瞬移回各自旧坐标，再滑回 0
                    categoryKeys.forEach { key ->
                        val b=railButtons.getValue(key)
                        b.translationY=(oldTop[key] ?: 0f)-b.top
                        b.animate().translationY(0f).setDuration(180).start()
                    }
                    railReorderPos=to
                    val grab=railButtons.getValue(categoryKeys[to])
                    railScroll.post {
                        if(grab.top<railScroll.scrollY) railScroll.smoothScrollTo(0,(grab.top-dp(6)).coerceAtLeast(0))
                        else if(grab.bottom>railScroll.scrollY+railScroll.height) railScroll.smoothScrollTo(0,grab.bottom-railScroll.height+dp(6))
                    }
                    rechainSettings()
                    categoryKeys.forEachIndexed { i,key -> paintRail(i,railButtons.getValue(key).hasFocus()) }
                }
            }
        }
        railReorderEnd={ save ->
            val from=railReorderPos
            railReorderPos=-1; railReorderMove=null
            if(from>=0) {
                val pos=from.coerceIn(0,categoryKeys.lastIndex)
                railButtons.getValue(categoryKeys[pos]).animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                if(save) {
                    // 顺序落盘并进备份文件：换设备扫码/本地恢复后分类顺序跟着走
                    library.railOrder=categoryKeys.joinToString(",")
                    library.requestBackup()
                }
                rechainSettings()
                categoryKeys.forEachIndexed { i,key -> paintRail(i,railButtons.getValue(key).hasFocus()) }
                // 放下后焦点稳稳落回该分类（中途 detach 可能已把焦点丢给顶栏）
                railScroll.post { railButtons.getValue(categoryKeys[pos]).requestFocus() }
            }
        }
        railButtons.forEach { (key,item) ->
            fun pos()=categoryKeys.indexOf(key)
            paintRail(pos(),false)
            item.setOnFocusChangeListener { _,focused ->
                paintRail(pos(),focused)
                if(focused) {
                    showCategory(pos(),false)
                    if(panelOn(pos())) optionPanel.bindCategory(categories[pos()].first,categories[pos()].second)
                }
            }
            item.setOnClickListener { showCategory(pos(),true) }
            item.setOnLongClickListener {
                if(railReorderPos<0) startRailReorder(pos())
                true
            }
        }
        if(threePane) optionPanel.bindCategory(categories[0].first,categories[0].second)
        rechainSettings()
        footerHost.addView(settingsFooter,lp(-2,-2))
        footerHost.addView(settingsDisclaimer,lp(-2,-2))
        // 页面重建（如切换片源显隐）后按标签恢复焦点，避免跳回顶部导航
        val wanted=settingsFocusKey.also { settingsFocusKey=null }
        // 焦点 key 用未翻译原文匹配：切换语言后 title 已是译文，「语言」→「語言」/「Language」会失配（2026-11-27）
        val target=wanted?.let { key -> settingRows.firstOrNull { it.sourceTitle.startsWith(key) || it.title.startsWith(key) } }
        if(target!=null) {
            val index=categories.indexOfFirst { it.third===(target.parent as? View) }
            if(index>=0) showCategory(index,false)
            target.requestFocus()
        } else nav[tab].requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType,fromPhone: Boolean=false) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        // 提交关键词即切回关键词搜索，高级筛选条件不继承（下次进入高级搜索仍保留）
        advancedSearch=false
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
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史",onExtra={
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        })
    }
    // ---- 高级搜索：服务端条件筛选与关键词搜索双态切换 ----
    private fun chip(label: String,selected: Boolean,onClick: ()->Unit): TextView = text(label,12f).apply {
        gravity=Gravity.CENTER; setPadding(dp(10),dp(6),dp(10),dp(6)); minHeight=dp(30)
        focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun switchAdvanced(value: Boolean) {
        if(advancedSearch==value) return
        advancedSearch=value
        query=""; (searchInput as? EditText)?.setText("")
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; discoveryCursor=null; pendingFilterFocus=null
        // 每次进入高级搜索都展开筛选，收起状态只在本次会话内保持
        if(value) { filterExpanded=true; filterAutoCollapse=true }
        tabState.remove(1); tabFocus.remove(1); searchFocusPending=false
        showCatalog(load=value)
        main.post { if(advancedSearch==value) (if(value) filterToggle ?: filterPanel else searchInput)?.requestFocus() }
    }
    private fun reloadDiscovery() {
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; discoveryCursor=null
        tabState.remove(1); tabFocus.remove(1)
        showCatalog(load=true)
    }
    private fun chooseFilter(type: String,id: String) {
        val next=discoverySelection.choose(type,id)
        if(next==discoverySelection) return
        discoverySelection=next
        // 选项改变后旧游标作废，必须重取第 1 页；焦点回到刚操作的条件
        pendingFilterFocus=type to id
        reloadDiscovery()
    }
    private fun resetFilters() {
        if(discoverySelection.values.isEmpty()) return
        discoverySelection=DiscoverySelection(); pendingFilterFocus=null
        reloadDiscovery()
    }
    private fun buildFilterPanel(container: LinearLayout) {
        val host=column().apply { setPadding(0,0,0,dp(6)) }
        filterPanel=host; container.addView(host)
        renderFilterPanel()
    }
    private fun filterSummaryText(): String {
        val lookup=discoveryGroups.associate { group -> group.type to group.options.associate { it.id to it.label } }
        val genre=discoverySelection.values["genre"].orEmpty()
        val genreText=if(genre.isEmpty()) "全部类型" else lookup["genre"]?.get(genre) ?: genre
        val others=DiscoverySelection.LABELS.keys.filter { it!="genre" }.mapNotNull { type ->
            discoverySelection.values[type]?.takeIf { it.isNotEmpty() }?.let { lookup[type]?.get(it) ?: it }
        }
        return if(others.isEmpty()) "$genreText · 全部条件" else "$genreText · ${others.joinToString(" · ")}"
    }
    private fun renderFilterPanel() {
        val host=filterPanel ?: return
        host.removeAllViews(); filterChips=linkedMapOf()
        val typeGroup=discoveryGroups.firstOrNull { it.type=="genre" }
            ?: DiscoveryGroup("genre","类型",listOf(DiscoveryFilter("short_play","真人剧"),DiscoveryFilter("comic_series","漫剧"),DiscoveryFilter("ai_series","AI剧")))
        val others=discoveryGroups.filter { it.type!="genre" }
        fun optionRow(group: DiscoveryGroup): List<TextView> {
            val views=mutableListOf<TextView>()
            views+=chip(if(group.type=="genre") "全部类型" else "全部",discoverySelection.values[group.type].isNullOrEmpty()) { chooseFilter(group.type,"") }
            group.options.forEach { option ->
                views+=chip(option.label,discoverySelection.values[group.type]==option.id) { chooseFilter(group.type,option.id) }
            }
            views.forEachIndexed { index,view ->
                filterChips[group.type to (if(index==0) "" else group.options[index-1].id)]=view
            }
            return views
        }
        fun lineOf(views: List<TextView>,label: String?): View {
            val line=row()
            if(label!=null) line.addView(text(label,12f,muted),lp(dp(40),-2))
            val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
            val chips=row()
            views.forEach { chips.addView(it,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(6) }) }
            scroll.addView(chips)
            line.addView(scroll,lp(0,-2).apply { weight=1f })
            return line
        }
        // 类型行在页头，其余 7 组条件两列排布：单列会把结果网格挤出屏幕
        val typeChips=optionRow(typeGroup)
        val header=row()
        header.addView(lineOf(typeChips,null),lp(0,-2).apply { weight=1f })
        lateinit var collapse: TextView
        collapse=chip(if(filterExpanded) "收起筛选" else "展开筛选",false) {
            filterExpanded=!filterExpanded
            renderFilterPanel()
            // 收起/展开改变了上下焦点链，需重新把面板与结果网格串起来，否则会跳到已隐藏的选项
            catalogRecycler?.let { wireFilterPanelToGrid(it) }
            filterCollapse?.requestFocus()
        }
        filterCollapse=collapse
        val reset=chip("重置",false) { resetFilters() }
        val refresh=chip("刷新",false) { reloadDiscovery() }
        filterReset=reset; filterRefresh=refresh
        listOf(collapse,reset,refresh).forEach { view -> header.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; leftMargin=dp(8) }) }
        host.addView(header)
        host.addView(text(filterSummaryText(),12f,muted).apply { setPadding(dp(2),dp(2),0,dp(4)) }.also { filterSummary=it })
        val groupsHost=column()
        // 视觉行：每行左右两组条件（右组可为空），焦点必须按视觉行而非按组上下移动
        val linePairs=mutableListOf<Pair<List<TextView>,List<TextView>>>()
        if(others.isEmpty()) {
            groupsHost.addView(text(if(discoveryGroups.isEmpty()) "正在读取筛选条件…" else "该类型没有更多筛选条件",12f,muted).apply { setPadding(dp(2),dp(4),0,dp(4)) })
        } else {
            others.chunked(2).forEach { pair ->
                val line=row()
                val pairRows=mutableListOf<List<TextView>>()
                pair.forEach { group ->
                    val chips=optionRow(group)
                    line.addView(lineOf(chips,group.label),lp(0,-2).apply { weight=1f; rightMargin=dp(10) })
                    pairRows+=chips
                }
                if(pair.size==1) line.addView(Space(this),lp(0,1).apply { weight=1f })
                linePairs+=pairRows[0] to pairRows.getOrElse(1) { emptyList() }
                groupsHost.addView(line,lp(-1,-2).apply { bottomMargin=dp(4) })
            }
        }
        val visualLines=(others.size+1)/2
        val groupsScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false; visibility=if(filterExpanded) View.VISIBLE else View.GONE }
        groupsScroll.addView(groupsHost)
        // 条件区可见行数按屏幕高度自适应：540P 小电视固定 4 行会把一行卡片裁到只剩 3/4
        // （2026-11-23 用户问「小电视是不是只能显示部分卡片」，实测 1080P 没事、540P 推演裁切）。
        // 挤掉的高度从条件区出——其余行用 ScrollView 滚，保证手动展开时结果区至少有一行完整卡片。
        val screenHdp=resources.displayMetrics.heightPixels/resources.displayMetrics.density
        val maxRows=if(screenHdp>=680) 4 else if(screenHdp>=560) 2 else 1
        host.addView(groupsScroll,lp(-1,if(others.isEmpty()) -2 else dp(minOf(visualLines,maxRows)*34)))
        filterGroupsScroll=groupsScroll
        // 焦点严格按视觉行走线：首行是类型选项+操作按钮，其余每行左右两组条件。
        // ←/→ 跨组相连，↑/↓ 在同一半区内按位置落到上/下一行，告别按↓却平移到同行另一组的跳动感
        val headerButtons=listOf(collapse,reset,refresh)
        val visualRows=(listOf(typeChips to headerButtons)+linePairs)
        filterRows=visualRows.map { it.first+it.second }.toMutableList()
        fun linkRow(chips: List<TextView>) {
            chips.forEachIndexed { index,view ->
                view.nextFocusLeftId=chips[if(index==0) 0 else index-1].id
                view.nextFocusRightId=chips[if(index==chips.lastIndex) chips.lastIndex else index+1].id
            }
        }
        visualRows.forEach { (left,right) ->
            linkRow(left); linkRow(right)
            if(left.isNotEmpty() && right.isNotEmpty()) {
                left.last().nextFocusRightId=right.first().id
                right.first().nextFocusLeftId=left.last().id
            }
        }
        fun aim(row: Pair<List<TextView>,List<TextView>>?,rightHalf: Boolean,pos: Int): Int {
            if(row==null) return View.NO_ID
            val chips=(if(rightHalf) row.second else row.first).ifEmpty { row.first }
            return chips.getOrNull(minOf(pos,chips.lastIndex))?.id ?: View.NO_ID
        }
        visualRows.forEachIndexed { rowIndex,(left,right) ->
            val up=visualRows.getOrNull(rowIndex-1)
            val down=visualRows.getOrNull(rowIndex+1)
            left.forEachIndexed { index,view ->
                view.nextFocusUpId=if(up!=null) aim(up,false,index) else filterToggle?.id ?: View.NO_ID
                view.nextFocusDownId=aim(down,false,index)
            }
            right.forEachIndexed { index,view ->
                view.nextFocusUpId=if(up!=null) aim(up,true,index) else filterToggle?.id ?: View.NO_ID
                view.nextFocusDownId=aim(down,true,index)
            }
        }
    }
    private fun wireFilterPanelToGrid(recycler: RecyclerView) {
        if(tab!=1 || !advancedSearch) return
        val first=filterRows.firstOrNull().orEmpty()
        val last=filterRows.lastOrNull().orEmpty()
        val target=first.firstOrNull()?.id ?: recycler.id
        filterToggle?.nextFocusDownId=target
        searchInput?.nextFocusDownId=target
        searchButton?.nextFocusDownId=target
        // 展开时末行（左右两组）↓ 都进结果网格；收起时条件组隐藏，页头 ↓ 直接进网格
        if(filterExpanded && filterRows.size>1) last.forEach { it.nextFocusDownId=recycler.id }
        else first.forEach { it.nextFocusDownId=recycler.id }
    }
    private fun focusFilterChip(key: Pair<String,String>): Boolean {
        val view=filterChips[key] ?: return false
        if(!view.isAttachedToWindow) return false
        view.requestFocus()
        return true
    }
    /**
     * 上新区：数据不走网页榜单，而是分类接口里「最新上架 + 时间窗」这组固定条件。
     * 首页需要 need_selector_panel 才能拿到续页 session，续页复用 loadMoreDiscovery。
     */
    private fun loadNewRelease(body: LinearLayout) {
        message(body,"正在读取最新上架…")
        val selection=newReleaseSelection()
        discoverySelection=selection
        work({ repository.filteredDiscovery(selection,null) }, { result ->
            catalog=result.items; hasMore=result.next!=null; discoveryCursor=result.next; page=1; ranking=null
            rankSubtitle?.text="来源：红果${rankBoard.label} · 最新上架 · ${newReleaseLabel()}"
            val keepNav=nav.any { it.hasFocus() }
            body.removeAllViews(); catalogGrid(body,keepNav,false)
            main.post {
                // 网格自己会在 layout 后把焦点放到卡片上，这里排到它之后再把焦点还回按钮
                if(pendingNewReleaseFocus!=null) { catalogRecycler?.postDelayed({ consumePendingNewReleaseFocus() },80); return@post }
                if(result.items.isEmpty()) {
                    // 空态别让用户盯着空白：副标题直接告诉他下一步按哪儿，焦点也落在时间按钮上
                    rankSubtitle?.text="${newReleaseLabel()}内暂无${rankBoard.label}上新，按「时间」放宽范围"
                    newReleaseWindowButton?.requestFocus(); return@post
                }
                if(keepNav) nav[tab].requestFocus() else (resultFirstCard ?: newReleaseBack)?.requestFocus()
            }
        }, { problem ->
            catalog=emptyList(); hasMore=false; discoveryCursor=null; pendingNewReleaseFocus=null
            rankSubtitle?.text=L10n.tr("上新加载失败")
            body.removeAllViews(); error(body,problem) { showCatalog(true) }
        })
    }
    private fun loadDiscovery(body: LinearLayout) {
        message(body,"正在读取筛选条件…")
        val selection=discoverySelection
        work({ repository.filteredDiscovery(selection,null) }, { result ->
            catalog=result.items; hasMore=result.next!=null; discoveryGroups=result.groups; discoveryCursor=result.next; page=1
            val keepNav=nav.any { it.hasFocus() }
            // 首次出结果自动收起筛选面板（issue #35.2）：展开的面板占掉大半屏，
            // 结果只剩一行还滚不动观感像「展示不全」。收起后结果占满屏；
            // 摘要行仍在，要改条件点「展开筛选」。只做一次，之后尊重用户手动展开。
            if(advancedSearch && filterAutoCollapse && result.items.isNotEmpty() && filterExpanded) {
                filterAutoCollapse=false; filterExpanded=false; renderFilterPanel()
            }
            body.removeAllViews(); catalogGrid(body,keepNav,false)
            main.post {
                val key=pendingFilterFocus.also { pendingFilterFocus=null }
                if(key!=null && focusFilterChip(key)) return@post
                if(result.items.isEmpty()) { filterToggle?.requestFocus(); return@post }
                if(keepNav) nav[tab].requestFocus() else (resultFirstCard ?: filterToggle)?.requestFocus()
            }
        }, { problem ->
            catalog=emptyList(); hasMore=false
            body.removeAllViews(); error(body,problem) { showCatalog(true) }
        })
    }
    private fun loadMoreDiscovery(adapter: CatalogAdapter) {
        val cursor=discoveryCursor ?: return
        if(page>=waterfallMaxPages) { hasMore=false; rememberCatalogFocus(); adapter.updateFooter(FOOTER_END); restoreCatalogFocus(); return }
        val selection=discoverySelection
        loadingMore=true; rememberCatalogFocus(); adapter.updateFooter(FOOTER_LOADING); restoreCatalogFocus()
        work({ repository.filteredDiscovery(selection,cursor) }, { result ->
            loadingMore=false; page+=1
            hasMore=result.next!=null; discoveryCursor=result.next
            val known=catalog.mapTo(HashSet()) { it.id }
            val fresh=result.items.filter { it.id !in known }
            catalog=catalog+fresh
            val current=catalogAdapter
            if(current!=null) {
                val insertAt=current.displayed.size
                current.displayed.addAll(fresh)
                if(fresh.isNotEmpty()) current.notifyItemRangeInserted(insertAt,fresh.size)
                current.updateFooter(if(hasMore && page<waterfallMaxPages) FOOTER_NONE else FOOTER_END)
                restoreCatalogFocus()
            }
        }, { _ ->
            loadingMore=false
            Toast.makeText(this,L10n.tr("加载失败，继续浏览会自动重试"),Toast.LENGTH_SHORT).show()
            catalogAdapter?.updateFooter(FOOTER_ERROR)
            restoreCatalogFocus()
        })
    }
    private fun showRandomPicks() {
        if(randomDialog?.isShowing==true) return
        val picks=randomRecommender.pick(4)
        if(picks.isNotEmpty()) { showRandomDialog(picks); return }
        // 池空或近期都展示过：先补充再展示
        showRandomDialog(emptyList(), loading = true)
        replenishRandomPool {
            val next=randomRecommender.pick(4)
            if(next.isNotEmpty()) showRandomDialog(next) else randomDialog?.dismiss().also { Toast.makeText(this,L10n.tr("暂时无法随机荐剧，请稍后重试"),Toast.LENGTH_SHORT).show() }
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
        // 内容区高度按屏幕算：一次只出 4 部（2×2 两行），高度按两行卡片实测算，
        // 小屏仍以「屏幕高-230dp」兜底，最低 200dp。
        val cardWidth=dp(132)
        val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
        val contentHeight=minOf(posterHeight*2+dp(178),resources.displayMetrics.heightPixels-dp(230)).coerceAtLeast(dp(200))
        body.addView(content,lp(-1,contentHeight).apply { bottomMargin=dp(10) })
        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        // 海报网格 2 列异步填充，宽度必须固定，不能收缩到内容：
        // 2×132 卡片 + 8 间距 + 卡片左右内边距 56 ≈ 330dp
        TvDialog.styleWindow(this,dlg,widthDp=330)
        randomDialog=dlg
        dlg.show()
        if(items.isEmpty() && !loading) {
            content.addView(text("候选池为空，正在补充…",15f,muted).apply { setPadding(0,dp(20),0,0) })
        } else if(items.isNotEmpty()) {
            // 弹窗专用固定尺寸卡片：宽132dp，上图（3:4固定高）下名称+集数，小图拉伸撑满
            val randomAdapter=object: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=items.size
                override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
                    val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                    val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    card.addView(image,lp(-1,posterHeight))
                    val title=text("",13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
                    val badge=text("",11f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                    card.addView(title,lp(-1,-2)); card.addView(badge,lp(-1,-2))
                    card.layoutParams=RecyclerView.LayoutParams(cardWidth,-2).apply { rightMargin=dp(8); bottomMargin=dp(10) }
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
            val grid=GridLayoutManager(this,2)
            val recycler=RecyclerView(this).apply { clipToPadding=false; itemAnimator=null }
            recycler.layoutManager=grid; recycler.adapter=randomAdapter
            content.addView(recycler,lp(-1,-1))
        }
        // 「换一批」按钮（居中）
        val buttons=row().apply { gravity=Gravity.CENTER; setPadding(0,dp(14),0,0) }
        val change=TvDialog.capsuleButton(this,"换一批") {
            dlg.dismiss()
            val picks=randomRecommender.pick(4)
            if(picks.isNotEmpty()) showRandomDialog(picks)
            else { showRandomDialog(emptyList(), loading = true); replenishRandomPool { val n=randomRecommender.pick(4); if(n.isNotEmpty()) showRandomDialog(n) } }
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
                    tvTools.choose(L10n.trFmt("选择{0}题材", L10n.tr(type.label)),listOf("全部题材")+genres.map { it.name },anchor,{ index ->
                        val next=if(index==0) null else genres[index-1]
                        if(next!=current) {
                            if(aiMode) aiGenre=next else genre=next
                            page=1; catalogFocus=""; tabState.remove(tab); tabFocus.remove(tab); showCatalog(true)
                        }
                    },anchorToButton=true)
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
    /**
     * @param preloaded 已经取到的详情。语音开播要「先确认有剧集再起播」，
     *   那时详情已经拉过一次，这里复用，不再重复请求（电视上多一次往返就是多等一秒）。
     */
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,cloud: CloudProgress?=null,preloaded: Detail?=null) {
        pendingDetail=series; detailAutoplay=autoplay; pendingCloudResume=cloud
        // 自由刷剧换剧：如果正停在播放页，整页原样保留（PlayerView/ExoPlayer/解码器/HUD 都不拆）。
        // feed 里详情页本来就不显示，先拆掉播放页再拼回来纯属白花——那一步正是换剧比换集多花的 1 秒。
        val keepPlayer=resume && recommendMode && screen=="player" && player!=null && playerView?.parent===root && !playError
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false; detailEpisodePage=-1
        var body: LinearLayout?=null
        if(!keepPlayer) {
            val built=base(series.title); body=built
            // 自由刷剧：过渡动画揭开后直接进播放，中间的「正在加载剧集…」详情页不显示（用户 2026-11-18），保持纯黑过渡
            if(recommendMode) { built.visibility=View.GONE; root.setBackgroundColor(Color.BLACK) } else loadingBlock(built,series.title)
        }
        val apply:(Detail)->Unit={ result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val cloudResume=pendingCloudResume.takeIf { it?.series?.id==series.id }; pendingCloudResume=null
            val target=if(cloudResume!=null) {
                // 云端进度续播：episodeId 对不上（换源/集数更新）时退回云端记录的集号；不写本机记录，起播后自然落本机
                val index=result.episodes.indexOf(cloudResume.episodeId).takeIf { it>=0 } ?: cloudResume.episodeIndex.coerceIn(0,result.episodes.lastIndex)
                ResumeTarget(index,cloudResume.position.coerceAtLeast(0))
            } else {
                val progress=library.progress(series.id)
                ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            }
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay,preservePlayer=keepPlayer) else showDetail(false)
        }
        android.util.Log.i("HongguoTV","Detail open preloaded=${preloaded!=null} keepPlayer=$keepPlayer resume=$resume")
        if(preloaded!=null) apply(preloaded)
        else work({ if(recommendMode) repository.detailLite(series.id) else repository.detail(series.id) }, apply, { problem ->
            // 详情都没取到：过渡层留着只会一直转圈盖住错误页
            releaseFreeze()
            val host=body ?: base(series.title)
            if(recommendMode) { host.visibility=View.VISIBLE; root.setBackgroundColor(bg) }
            error(host,problem) { openDetail(series,resume,detailAutoplay,cloud) }
        })
    }
    /** focusPager：翻页后焦点留在翻页按钮上（-1=‹，1=›），0=按 focusEpisode 决定，见函数末尾。 */
    private fun showDetail(focusEpisode: Boolean, focusPager: Int = 0) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }; hero.addView(image,lp(dp(140),dp(198)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}"+library.progress(data.series.id)?.let { "  ·  看到第 ${it.episodeIndex+1} 集" }.orEmpty(),13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        // 评分与收藏数来自详情页同一份数据，卡片列表没有这些字段；缺失时整行不显示
        if(!data.social.isEmpty) {
            val metrics=mutableListOf<String>()
            // 分数、评分人数、收藏与热度都是拼出来的（站点下发的还是「181万收藏」这种），
            // 这行又是带色 span 的 SpannableString，走不了整串翻译，只能逐段先译好
            val score=if(data.social.rating>0) String.format(Locale.CHINA,"%.1f",data.social.rating) else ""
            val raters=data.social.ratingCount.trim().let { if(it.endsWith("万")) it.dropLast(1)+"M" else it }
            if(score.isNotEmpty()) metrics+=score+if(raters.isEmpty()) "" else L10n.trFmt("（{0} 人评分）",raters)
            if(data.social.favoriteText.isNotBlank()) metrics+=UiL10n.text(data.social.favoriteText)
            if(data.social.heatText.isNotBlank()) metrics+=UiL10n.text(data.social.heatText)
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
        lateinit var jump: TextView
        jump=addButton(actions,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; detailEpisodePage=-1; showDetail(true) } }
        content.addView(hero)
        // 系列剧季导航与集数网格收进海报右侧栏：一屏内完成「看点什么、从哪集看」的决定
        val seasons=(listOf(data.series)+data.sameName).distinctBy { it.id }
            .sortedBy { val number=seasonNumber(it.title); if(number==Int.MAX_VALUE) 0 else number }
        val seasonButtons=mutableListOf<TextView>()
        if(seasons.size>1) {
            val seasonRow=row(); seasonRow.setPadding(0,dp(10),0,dp(2))
            seasonRow.addView(text("系列 ${seasons.size} 季",13f,accent).apply { gravity=Gravity.CENTER },lp(dp(78),dp(42)))
            val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=row(); strip.addView(line)
            seasons.forEach { season ->
                val number=seasonNumber(season.title)
                val seen=library.progress(season.id)?.let { " · 看到${it.episodeIndex+1}集" }.orEmpty()
                val title=if(number==Int.MAX_VALUE) season.title.take(12) else "第 ${number} 季"
                seasonButtons+=addButton(line,title+seen,season.id==data.series.id) { if(season.id!=data.series.id) openDetail(season) }
            }
            seasonRow.addView(strip,lp(0,dp(42)).apply { weight=1f })
            info.addView(seasonRow)
        }
        // 集数分页：每页 4 行 × 8 列 = 32 集，避免长剧一次性渲染上百按钮；已看降透明度、当前集高亮
        val episodeCols=8
        val pageSize=episodeCols*4
        val totalPages=((data.episodes.size+pageSize-1)/pageSize).coerceAtLeast(1)
        detailEpisodePage=if(detailEpisodePage<0) (episodeIndex/pageSize).coerceIn(0,totalPages-1) else detailEpisodePage.coerceIn(0,totalPages-1)
        // 翻页控件：集数区上方紧凑单行（左：集数 右：‹ 页码 ›），不占额外高度
        var prevPager: TextView?=null; var nextPager: TextView?=null
        if(totalPages>1) {
            val pager=row().apply { setPadding(0,dp(8),0,dp(4)); gravity=Gravity.CENTER_VERTICAL }
            pager.addView(text("集数",13f,muted))
            pager.addView(Space(this),lp(0,1).apply { weight=1f })
            prevPager=button("‹",detailEpisodePage>0) { if(detailEpisodePage>0){detailEpisodePage--;showDetail(true,-1)} }
            pager.addView(prevPager)
            pager.addView(text("${detailEpisodePage+1}/${totalPages}",12f,muted).apply { setPadding(dp(8),0,dp(8),0) })
            nextPager=button("›",detailEpisodePage<totalPages-1) { if(detailEpisodePage<totalPages-1){detailEpisodePage++;showDetail(true,1)} }
            pager.addView(nextPager)
            info.addView(pager)
        }
        val pageStart=detailEpisodePage*pageSize
        val pageEnd=minOf(pageStart+pageSize,data.episodes.size)
        val episodes=mutableListOf<View>()
        (pageStart until pageEnd).chunked(episodeCols).forEach { numbers ->
            val line=row()
            numbers.forEach { index ->
                val seen=watched || index<episodeIndex
                val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else savedPosition(index)) }
                item.contentDescription="第 ${index+1} 集"+(if(seen) "，已看" else if(index==episodeIndex && progress!=null && !progress.completed) "，看到 ${formatTime(progress.position)}" else "")
                if(seen) item.alpha=.45f
                line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item
            }
            repeat(episodeCols-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            info.addView(line,lp(-1,dp(49)))
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
        val lastRowStart=((episodes.size-1)/episodeCols)*episodeCols
        val firstRow=extraRows.firstOrNull()
        val pagerRow: View?=prevPager ?: nextPager
        val aboveEpisodes: View=pagerRow ?: seasonButtons.lastOrNull() ?: jump
        episodes.forEachIndexed { i,v ->
            v.nextFocusLeftId=when {
                i%episodeCols==0 -> prevPager?.id ?: v.id
                else -> episodes[i-1].id
            }
            v.nextFocusRightId=when {
                i==episodes.lastIndex -> nextPager?.id ?: v.id
                i%episodeCols==episodeCols-1 -> v.id
                else -> episodes[i+1].id
            }
            v.nextFocusUpId=if(i>=episodeCols) episodes[i-episodeCols].id else aboveEpisodes.id
            v.nextFocusDownId=when {
                i+episodeCols<episodes.size -> episodes[i+episodeCols].id
                firstRow!=null && i>=lastRowStart -> firstRow[(i-lastRowStart).coerceAtMost(firstRow.lastIndex)].id
                else -> v.id
            }
        }
        // 翻页按钮（集数上方）：↑ 回季条/操作行，↓ 进集数第一行；左右互连
        val abovePager: View=seasonButtons.lastOrNull() ?: jump
        prevPager?.let { it.nextFocusUpId=abovePager.id; it.nextFocusDownId=episodes.firstOrNull()?.id ?: it.id; it.nextFocusRightId=nextPager?.id ?: it.id }
        nextPager?.let { it.nextFocusUpId=abovePager.id; it.nextFocusDownId=episodes.firstOrNull()?.id ?: it.id; it.nextFocusLeftId=prevPager?.id ?: it.id }
        // 季条 ↑ 回操作行、↓ 进翻页/集数；操作行 ↓ 优先进季条，否则直达翻页或第一集
        seasonButtons.forEachIndexed { index,view ->
            view.nextFocusUpId=jump.id
            view.nextFocusDownId=pagerRow?.id ?: episodes.getOrNull(index.coerceAtMost(episodes.lastIndex))?.id ?: View.NO_ID
        }
        episodes.firstOrNull()?.let { first ->
            val down=seasonButtons.firstOrNull()?.id ?: pagerRow?.id ?: first.id
            listOf(play,favorite,synopsisButton,jump).forEach { it.nextFocusDownId=down }
        }
        extraRows.forEachIndexed { rowIndex,cards ->
            cards.forEachIndexed { i,v ->
                v.nextFocusLeftId=cards[(i-1).coerceAtLeast(0)].id
                v.nextFocusRightId=cards[(i+1).coerceAtMost(cards.lastIndex)].id
                v.nextFocusUpId=if(rowIndex>0) extraRows[rowIndex-1].getOrNull(i.coerceAtMost(extraRows[rowIndex-1].lastIndex))?.id ?: (pagerRow?.id ?: play.id)
                    else episodes.getOrNull((lastRowStart+i).coerceAtMost(episodes.lastIndex))?.id ?: (pagerRow?.id ?: play.id)
                v.nextFocusDownId=if(rowIndex<extraRows.lastIndex) extraRows[rowIndex+1].getOrNull(i.coerceAtMost(extraRows[rowIndex+1].lastIndex))?.id ?: v.id else v.id
            }
        }
        // 翻页后焦点留在翻页按钮上，方便连按继续翻；翻到头/尾时落到另一个仍能用的按钮，
        // 避免焦点停在一个按下没反应的按钮上。
        val pagerTarget=when {
            focusPager<0 -> if(detailEpisodePage<=0) nextPager ?: prevPager else prevPager ?: nextPager
            focusPager>0 -> if(detailEpisodePage>=totalPages-1) prevPager ?: nextPager else nextPager ?: prevPager
            else -> null
        }
        when {
            pagerTarget!=null -> pagerTarget.requestFocus()
            focusEpisode -> episodes.getOrNull((episodeIndex-pageStart).coerceIn(0,episodes.lastIndex))?.requestFocus()
            else -> play.requestFocus()
        }
    }
    /** 撤掉还没执行的「不兼容自动跳下一集」：用户已经自己动手换集了，不能再被拖回去。 */
    private fun cancelSkipUnplayable() {
        skipUnplayableJob?.let { main.removeCallbacks(it) }; skipUnplayableJob=null
    }
    // ===== 换集/换剧过渡层（用户 2026-11-19）=====
    // 以前换集要 stop+clearMediaItems 再重新 prepare，中间这段时间 PlayerView 只剩黑底，
    // 用户看到的就是「黑一下 + 转圈」。现在先把当前这一帧抓成截图盖上去，转圈叠在截图上，
    // 等新一集真的渲染出第一帧才撤掉——画面是「上一帧 → 下一集」，不再有黑。
    /** 抓当前视频帧并盖上过渡层。抓帧是异步的（PixelCopy 下一帧才回调），
     *  所以真正的切换放在回调里做，否则会抓到已经被 stop 掉的黑帧。 */
    private fun freezeFrame(tip: String,silent: Boolean=false,proceed: ()->Unit) {
        val loadingDelay=if(silent) FREEZE_LOADING_DELAY_AUTO else 0L
        val pv=playerView
        val surface=pv?.videoSurfaceView as? SurfaceView
        if(pv==null || surface==null || pv.width<=0 || pv.height<=0) {
            android.util.Log.w("HongguoTV","Freeze skip pv=${pv!=null} surface=${surface!=null} ${pv?.width}x${pv?.height} attached=${surface?.isAttachedToWindow}")
            showFreezeLayer(null,tip,loadingDelay); proceed(); return
        }
        // 先暂停：PixelCopy 的读回要 200~300ms，这期间画面还在播，用户按了键却像没反应。
        // 暂停后画面当帧就停在原处，切到新集后自动恢复播放。
        val wantPlay=player?.playWhenReady==true
        if(wantPlay) runCatching { player?.pause() }
        // 暂停之后 SurfaceView 仍然显示最后一帧，所以「转圈 + 文字」可以立刻叠上去：
        // 层是透明的，下面就是定格的旧画面。用户按完键当帧就有反馈，
        // 不用等截图回来——截图只负责在真正切断解码器时把画面「钉住」（用户 2026-11-20 反馈切换慢）。
        showFreezeLayer(null,tip,loadingDelay)
        // PixelCopy 是 API 24 才有的，而更新通道兼容包 minSdk=21（Android 5/6）。
        // 老设备直接走「透明层 + 转圈」的旧路径：不抓帧就不会 NoClassDefFoundError，
        // 也不会让 :app:lintRelease 的 NewApi 报错挂掉 CI（2026-11-20 实测踩到）。
        if(android.os.Build.VERSION.SDK_INT<24) {
            android.util.Log.w("HongguoTV","Freeze skip PixelCopy api=${android.os.Build.VERSION.SDK_INT}")
            proceed()
            if(wantPlay && player?.playWhenReady==false) runCatching { player?.play() }
            return
        }
        // 目标 Bitmap 必须按「视频本身的比例」建，不能用 PlayerView 的 16:9 尺寸：
        // PixelCopy 拷的是 Surface 的解码 buffer（视频原始画幅，不含黑边），
        // 拿 16:9 的尺寸去接就等于硬拉伸——竖屏剧被压扁、4:3 剧被拉宽（用户 2026-11-20）。
        // 只缩不放：4K 剧不能为了一张截图分配 33MB。比例一致，PixelCopy 不会变形。
        val vs=player?.videoSize
        // 兜底尺寸必须用 SurfaceView（视频容器，AspectRatioFrameLayout 已按视频比例校正），
        // 不能用 pv（PlayerView 全屏 16:9）：videoSize 无效时（如 bvc2 软解集旧版），
        // 16:9 尺寸会把竖屏冻结帧横向拉伸（2026-11-21 真机复现）。
        var shotW=surface.width; var shotH=surface.height
        if(vs!=null && vs.width>0 && vs.height>0) {
            val cap=FREEZE_MAX_EDGE/maxOf(vs.width,vs.height).toFloat()
            val fit=minOf(surface.width.toFloat()/vs.width,surface.height.toFloat()/vs.height,1f,cap)
            shotW=(vs.width*fit).toInt().coerceAtLeast(2)
            shotH=(vs.height*fit).toInt().coerceAtLeast(2)
        }
        val shot=runCatching { Bitmap.createBitmap(shotW,shotH,Bitmap.Config.ARGB_8888) }.getOrNull()
        if(shot==null) {
            android.util.Log.w("HongguoTV","Freeze bitmap alloc failed")
            showFreezeLayer(null,tip,loadingDelay); proceed(); if(wantPlay) runCatching { player?.play() }
            return
        }
        var done=false
        val finish={ bitmap: Bitmap? ->
            if(!done) {
                done=true; showFreezeLayer(bitmap,tip); proceed()
                // 抓帧前暂停的那一下要还回去：用户本来在播，切完必须继续播
                if(wantPlay && player?.playWhenReady==false) runCatching { player?.play() }
            }
        }
        val t0=android.os.SystemClock.elapsedRealtime()
        val requested=runCatching {
            PixelCopy.request(surface,shot,{ result ->
                android.util.Log.w("HongguoTV","Freeze copy result=$result took=${android.os.SystemClock.elapsedRealtime()-t0}ms")
                if(result==PixelCopy.SUCCESS) finish(shot) else { shot.recycle(); finish(null) }
            },Handler(Looper.getMainLooper()))
        }.isSuccess
        // 少数设备不允许对 SurfaceView 做 PixelCopy：退回纯色底 + 转圈，至少文案在
        if(!requested) { android.util.Log.w("HongguoTV","Freeze request threw"); shot.recycle(); finish(null) }
    }
    /** 把过渡层挂到 shell 上（root 重建不会带走它）。重复调用只刷新文案，不叠第二层。 */
    private fun showFreezeLayer(shot: Bitmap?,tip: String,loadingDelayMs: Long=0L) {
        freezeLayer?.let { layer ->
            freezeTip?.text=tip
            if(shot!=null) (layer.getChildAt(0) as? ImageView)?.let { it.setImageBitmap(shot); it.visibility=View.VISIBLE }
            return
        }
        // 过渡层挂起期间关掉 ExoPlayer 自带的缓冲圈（issue #35.1）：新集 prepare 缓冲时
        // PlayerView 的默认大圈会和过渡层的 38dp 小圈叠着转，「两个转圈一大一小」。
        // 撤层时恢复，正常播放中的缓冲提示不受影响。
        playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        // 上一个过渡层还在淡出（连按换集）：立刻收掉，否则两层半透明叠着会发灰
        freezeFading?.let { old ->
            old.animate().cancel()
            if(old.parent===shell) shell.removeView(old)
            if(freezeFading===old) freezeFading=null
        }
        // 层本身透明：没图时露出的是暂停后的定格画面，有图时 FIT_CENTER 两侧露出的也是
        // SurfaceView 自己的黑边，两种情况都跟播放器一致，不需要额外铺黑底。
        val layer=FrameLayout(this).apply { isFocusable=false; isClickable=false }
        // 延迟显示 loading：手动按键传 0（当帧即时反馈），自动连播传 500（快切时压根不出现）。
        val delayIn=loadingDelayMs
        // 图位一直在（没有截图时 GONE）：抓帧是后到的，回调里再补图就不用重建这一层。
        layer.addView(ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            visibility=if(shot!=null) View.VISIBLE else View.GONE
            if(shot!=null) setImageBitmap(shot)
        },FrameLayout.LayoutParams(-1,-1))
        val box=column().apply { gravity=Gravity.CENTER_HORIZONTAL; alpha=if(delayIn>0) 0f else 1f }
        box.addView(ProgressBar(this,null,android.R.attr.progressBarStyle).apply {
            indeterminateTintList=android.content.res.ColorStateList.valueOf(accent)
        },LinearLayout.LayoutParams(dp(38),dp(38)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        val label=text(tip,16f,Color.argb(240,255,255,255)).apply {
            setPadding(0,dp(10),0,0); gravity=Gravity.CENTER; maxLines=1
            // 不垫任何底色/压暗：压暗会改变画面亮度（用户 2026-11-20），靠阴影保证白字可读
            setShadowLayer(dp(4).toFloat(),0f,dp(1).toFloat(),Color.argb(210,0,0,0))
        }
        box.addView(label,LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
        layer.addView(box,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        // 用 postDelayed 而不是 animate().setStartDelay()：500ms 内就切完时能直接撤掉，
        // 转圈和文字从头到尾不会出现一帧，用户只看到「上一帧 → 新画面」。
        freezeLoadingJob?.let { main.removeCallbacks(it) }; freezeLoadingJob=null
        if(delayIn>0L) {
            val job=Runnable { if(freezeLayer===layer) box.animate().alpha(1f).setDuration(140).start() }
            freezeLoadingJob=job; main.postDelayed(job,delayIn)
        }
        shell.addView(layer,FrameLayout.LayoutParams(-1,-1))
        freezeLayer=layer; freezeTip=label
        freezeShownAt=android.os.SystemClock.elapsedRealtime()
        android.util.Log.w("HongguoTV","Freeze show tip=$tip shot=${shot!=null} shellKids=${shell.childCount}")
        // 兜底：片源彻底失败时错误面板会被这层盖住，15 秒必须自己撤。
        // 静默层（自动连播，提前 1.3 秒挂）万一没等到切换（用户暂停/关连播），3 秒就撤，别定格太久。
        freezeFade?.let { main.removeCallbacks(it) }
        val guard=Runnable { releaseFreeze() }; freezeFade=guard
        main.postDelayed(guard,if(delayIn>0L) 3000L else 15000L)
    }
    /** 撤掉过渡层。fade=true 用于「新一集首帧已经画出来了」这个时刻：
     *  新画面其实已经在下面渲染好了，让旧帧淡出 180ms 而不是瞬间消失，换集就不会「一闪」。
     *  这不增加等待——用户在这一刻已经能看到新内容，只是旧帧还叠着半透明的一层；
     *  真要离开播放页（回首页、露错误面板）时必须传 false，否则旧帧会盖住下一页。 */
    private fun releaseFreeze(fade: Boolean=false) {
        freezeFade?.let { main.removeCallbacks(it); }; freezeFade=null
        // 500ms 内切完时这个任务还没到点，撤掉就等于 loading 从未出现过
        freezeLoadingJob?.let { main.removeCallbacks(it) }; freezeLoadingJob=null
        val layer=freezeLayer ?: return
        freezeLayer=null; freezeTip=null
        // 过渡层撤掉后恢复 ExoPlayer 自带缓冲圈（showFreezeLayer 里为防双圈临时关掉）
        playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        val alive=android.os.SystemClock.elapsedRealtime()-freezeShownAt
        android.util.Log.w("HongguoTV","Freeze release alive=${alive}ms fade=$fade")
        // 上一层还在淡出（连按换集）：立刻收掉，别让两层的半透明叠在一起发灰
        freezeFading?.let { old ->
            old.animate().cancel()
            if(old.parent===shell) shell.removeView(old)
            if(freezeFading===old) freezeFading=null
        }
        if(!fade || !layer.isAttachedToWindow || alive<120) { shell.removeView(layer); return }
        freezeFading=layer
        layer.animate().setDuration(FREEZE_FADE_MS).setInterpolator(android.view.animation.DecelerateInterpolator())
            .alpha(0f)
            .withEndAction {
                if(freezeFading===layer) freezeFading=null
                if(layer.parent===shell) shell.removeView(layer)
            }
            .start()
    }
    /** 播放器单例构建：融合构建挂 vc2 渲染器工厂（bvc2 软解进同一个 ExoPlayer），否则用默认工厂。 */
    private fun createPlayer(): ExoPlayer {
        val load=DefaultLoadControl.Builder().setBufferDurationsMs(20000,50000,1000,5000).setTargetBufferBytes(32*1024*1024).build()
        val rf0=Vc2.api.renderersFactory(this)
        val renderers=(rf0 ?: androidx.media3.exoplayer.DefaultRenderersFactory(this)).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
            player=it; playerView?.player=it
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            it.setHandleAudioBecomingNoisy(true)
        }
    }
    /** 过渡层转圈下的小字：只写「正在载入中...」，剧名/集数不重复报（用户 2026-11-20）。 */
    private val loadingTip="正在载入中..."
    /** 换剧专用文案（用户 2026-11-23）：上下切剧与换集区分开，让用户知道是在换一部新剧。 */
    private val seriesLoadingTip="新剧载入中..."
    /** 统一的 MediaSource 构建：融合开关打开时挂 Vc2ExtractorsFactory（嗅探式接管 bvc2），
     *  关闭时与改造前完全一致（单参 Factory + 默认 Mp4Extractor）。 */
    private fun mediaSourceFor(remote: RemoteVideo,mediaId: String): MediaSource {
        val ds=androidx.media3.datasource.DataSource.Factory { VideoDataSource(remote) }
        val ex=Vc2.api.extractorsFactory()
        val factory=if(ex!=null) ProgressiveMediaSource.Factory(ds,ex)
            else ProgressiveMediaSource.Factory(ds)
        return factory.createMediaSource(
            MediaItem.Builder().setMediaId(mediaId).setUri("hongguotv://episode/$mediaId")
                .setMimeType(MimeTypes.VIDEO_MP4).build())
    }

    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,transition: Boolean=false,preservePlayer: Boolean=false) {
        val data=detail ?: return
        // 播放页里换集要重建播放器：先冻住当前这一帧再切，等新一集首帧渲染出来才撤
        //（用户 2026-11-19：不要「黑屏 + loading」）。transition 表示这次进来是「冻完后的重入」，
        // 靠参数而不是成员标志——抓帧是异步的，成员标志在回调返回前就复位了，会无限自冻。
        if(!transition && !recovering && screen=="player" && player!=null && playerView!=null) {
            freezeFrame(loadingTip) {
                playEpisode(index,position,autoplay,recovering,transition=true)
            }
            return
        }
        if(transition) transitionFrom=android.os.SystemClock.elapsedRealtime()
        updater.interrupt(); cancelWarm(); cancelSkipUnplayable()
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        // 从详情页重新进播放器时丢弃上一轮的临时设置；自动连播保持在同一条降档链上。
        if(screen!="player") { resetPlaybackSession(); downgradeEpisode=""; downgradeCount=0 }
        // 换剧（preservePlayer=true）：openDetail 特意没拆播放页，PlayerView 还挂在 root 上，
        // 这里直接沿用同一个 ExoPlayer——省掉 release + 重建 + 硬解初始化（真机实测 600~900ms）。
        val reuse=(screen=="player" || preservePlayer) && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val requestedId=data.episodes[selectedIndex]
        // 换集即重置本集状态（自动降档重放同集时保留 probeQuality，靠 downgradeEpisode 挡住）
        if(requestedId!=downgradeEpisode) { downgradeEpisode=requestedId; downgradeCount=0; probeQuality=null; failedTiers.clear(); decodeRetried=false }
        val seriesId=data.series.id
        var maxQuality=startQuality()
        // 每 5 集向上探一档：剧内记忆会把起播档锁在「曾经播通」的档位上，但片源编码档位逐集不同
        // （有的集 1080 解不了、有的集其实能解）。偶发抬高一级试探，失败由自动降档拉回。
        if(sessionQuality==null && probeQuality==null && !recovering) {
            val verified=verifiedQuality[seriesId]
            if(verified!=null && verified>0 && verified<library.maxQuality) {
                val count=(probeUp[seriesId]?:0)+1
                if(count>=5) {
                    probeUp[seriesId]=0
                    val up=repository.cachedLadder(requestedId)
                        ?.filter { it.codec!="bytevc2" && it.qNum>verified && it.qNum<=library.maxQuality }
                        ?.minOfOrNull { it.qNum }
                    if(up!=null && up>verified) {
                        maxQuality=up
                        android.util.Log.i("HongguoTV","Probe up episode=${selectedIndex+1} from ${verified}P to ${up}P")
                    }
                } else probeUp[seriesId]=count
            }
        }
        val prefetched=if(position==0L && !recovering) preparedNext.take("$requestedId:$maxQuality") else null
        // 换剧专用：预取阶段已经把下一部首集连首块都拉好了（recommendVideos，10 分钟 TTL）
        val readyVideo=recommendVideos.remove(requestedId)?.let { cached ->
            if(android.os.SystemClock.elapsedRealtime()-cached.first<10*60_000L) cached.second
            else { runCatching { cached.second.close() }; null }
        }
        // 队列附着：把备好的流直接挂进现有播放器队列再 seek 过去，解码器不重建。
        // 实测（TCL，2026-11-23）：stop+clear 重建换剧首帧 2.9 秒；剧内无缝切集（同为队列切换）
        // 只要 155ms——差距全在解码器重建上。挂载路径对齐 seamlessNext。
        val queueAttach=readyVideo!=null && reuse && player!=null && !vc2Active && !recovering
        if(!queueAttach && prefetched!=null && readyVideo!=null) {
            // 下一集预挂的流优先级更高：把换剧预取的连接就地还回去，别白占一个
            runCatching { readyVideo.close() }
        }
        if(queueAttach && prefetched!=null) {
            // 换剧以换剧预取为准：预取的下一集就地回收
            runCatching { prefetched.close() }
            closeTransport(prefetchHttp); prefetchHttp=null
        }
        val claimedHttp=if(prefetched!=null && !queueAttach) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews(); stopDanmaku()
        // 旧集的流先记下来：队列附着路径要等新集挂上、旧 item 摘掉之后再关（见挂载处）
        val outgoingVideo=if(reuse) video else null
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            // 自由刷剧用纯色底（用户 2026-11-18：不要海报当背景），换剧时不必换底
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            // 队列附着时不 stop/clear（解码器保住），旧集的流也还托着屏幕上这帧：
            // 先不关，等新集挂上、旧 item 摘掉之后再收（见下方 queueAttach 挂载处）。
            if(!queueAttach && !preservePlayer) { player?.stop(); player?.clearMediaItems() }
            video?.let { android.util.Log.w("HongguoTV","Prefetch episode=${episodeIndex+1} ${it.stats()}") }
            if(!queueAttach) outgoingVideo?.close()
            video=null; closeTransport(playbackHttp); playbackHttp=null
            teardownVc2(); vc2Failed=false
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true; var rebufferStart=0L
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        val skipMark=library.skipMark(data.series.id)
        outroSkipSeconds=skipMark?.outroSeconds ?: 0; outroTriggered=false; ffActive=false
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        // 播放集变了：详情页选集网格下次重建时重新跟随当前集（-1=按 episodeIndex 重算页码），
        // 否则从播放页返回详情时网格还停在旧页，当前集高亮不在屏上（跳转/连播/遥控切集同理）。
        detailEpisodePage=-1
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        playerScreenOn=true; refreshScreenOn()
        remotePublish(true)
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
            // 标题行是「剧名 · 第 N 集」：剧名原样保留，只有集数要按语言改写（第 2 集 → EP 2）
            playbackTitle=LocalizedTextView(this).apply { textSize=23f; includeFontPadding=false; setTextColor(white); maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }
            // 标题行右侧留一个鼠标能点的退出：遥控用户按返回键，插鼠标的用户不该只剩键盘快捷键。
            // 默认收起，等鼠标真的动了/点了才放出来——遥控用户看到一颗点不到的按钮只会更困惑。
            val titleRow=row().apply { gravity=Gravity.CENTER_VERTICAL }
            titleRow.addView(playbackTitle,lp(0,-2).apply { weight=1f })
            val exitPlay=text("✕ 退出",13f,muted).apply {
                isFocusable=false; isClickable=true
                setPadding(dp(12),dp(5),dp(12),dp(5))
                background=rounded(Color.argb(96,0,0,0),TvStyle.outline)
                setOnClickListener { returnToDetail() }
                visibility=View.GONE
            }
            playerExit=exitPlay
            titleRow.addView(exitPlay,lp(-2,-2))
            hud.addView(titleRow)
            // 清晰度/倍速/弹幕状态行：同样靠控件层兜底翻译（`1080P · 1× · 弹幕 开`）
            playbackStatus=LocalizedTextView(this).apply {
                textSize=14f; includeFontPadding=false; setTextColor(gold); setPadding(0,dp(6),0,0); maxLines=1
            }
            hud.addView(playbackStatus)
            // 状态行文案几乎全是运行时拼出来的（失败原因、跳过计数、限流倒计时），出口几十处，
            // 逐个包 L10n.tr 必漏；用 LocalizedTextView 在控件层兜一次，新增文案自动生效。
            playbackText=LocalizedTextView(this).apply {
                textSize=15f; setTextColor(muted); includeFontPadding=false
                setPadding(0,dp(7),0,dp(9))
            }; hud.addView(playbackText)
            // 进度条自己画轨道与滑块：轨道 4dp、可点区域 26dp，鼠标/手指按住就能拖
            progressBar=PlaybackSeekBar(this).apply { max=1000 }
            progressBar.onScrubPreview=preview@{ ratio ->
                val p=player ?: return@preview
                val duration=p.duration
                if(duration<=0 || screen!="player") return@preview
                main.removeCallbacks(hideHud)
                playbackText.text="定位到 ${formatTime((duration*ratio).toLong())} / ${formatTime(duration)}"
            }
            progressBar.onScrub=scrub@{ ratio,final ->
                val p=player ?: return@scrub
                val duration=p.duration
                if(duration<=0 || !playbackReady) return@scrub
                if(final) seekTo((duration*ratio).toLong())
            }
            progressBar.onScrollStep={ delta -> seekTo(currentPosition()+delta) }
            hud.addView(progressBar,lp(-1,dp(26)))
            keyHints=text(keyHintText(),13f,muted).apply { setPadding(0,dp(12),0,0) }; hud.addView(keyHints)
            // 鼠标一动就把退出按钮与鼠标手势提示放出来；遥控一按（见 dispatchKeyEvent）再收回去
            TvPointer.onHover(root) { if(!mouseSeen) { mouseSeen=true; refreshPointerUi() } }
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            // 转圈单独挂在根布局正中央，不占底部 HUD 空间；播放失败自动重试时才可见。
            recoverySpinner=ProgressBar(this,null,android.R.attr.progressBarStyle).apply { indeterminateTintList=android.content.res.ColorStateList.valueOf(accent); visibility=View.GONE }
            root.addView(recoverySpinner,FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER))
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
            // 鼠标快捷按钮（issue #25）：左下「选集」、右上「收藏」，样式同「✕ 退出」；
            // 只在控制条展开时一起出现，鼠标单纯划过不再弹出，点一下画面才三样一起出来。
            val cornerLayer=FrameLayout(this); pointerLayer=cornerLayer
            // 快捷按钮统一跟「✕ 退出」一个样式：小字、深色半透明底、细描边
            fun cornerButton(label: String,onClick: ()->Unit)=text(label,13f,muted).apply {
                isFocusable=false; isClickable=true
                setPadding(dp(12),dp(5),dp(12),dp(5))
                background=rounded(Color.argb(96,0,0,0),TvStyle.outline)
                setOnClickListener { onClick() }
            }
            // 播放/暂停交给画面正中的按钮；左下「选集 · 收藏」排一行，与控制条左边距对齐
            lateinit var cornerEpisodes: TextView
            cornerEpisodes=cornerButton("选集") { showEpisodePanel(cornerEpisodes); updatePointerLayer() }
            pointerFav=cornerButton("☆ 收藏") {
                val d=detail ?: return@cornerButton
                favoriteMonitor.stop()
                val saved=library.toggle(d.series)
                if(saved) library.observeFavorite(d.series.id,d.episodes.size,System.currentTimeMillis(),acknowledge=true)
                updatePointerLayer()
            }
            val cornerRow=row().apply { gravity=Gravity.CENTER_VERTICAL }
            cornerRow.addView(cornerEpisodes,lp(-2,-2))
            cornerRow.addView(pointerFav,lp(-2,-2).apply { leftMargin=dp(12) })
            cornerLayer.addView(cornerRow,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply {
                leftMargin=dp(widthDp()*.05f); bottomMargin=dp(196)
            })
            // 暂停时画面正中的播放按钮：图标带半透明矩形底，跟着控制条一起出现
            pointerCenter=text("▶",42f,Color.argb(225,255,255,255)).apply {
                isFocusable=false; isClickable=true
                setPadding(dp(30),dp(22),dp(30),dp(22))
                // 纯色圆角矩形、无描边（方案 B）：55% 黑底，和 HUD 同一套色
                background=rounded(Color.argb(140,0,0,0))
                setOnClickListener {
                    if(playError) return@setOnClickListener
                    togglePlayback()
                    // 续播后控制条照常 4.5 秒自动收起，别一直压在画面上
                    if(player?.playWhenReady==true) main.postDelayed(hideHud,4500)
                    updatePointerLayer()
                }
            }
            cornerLayer.addView(pointerCenter,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            cornerLayer.visibility=View.GONE
            root.addView(cornerLayer,FrameLayout.LayoutParams(-1,-1))
        }
        applyClock()
        applyNetSpeedOverlay()
        playbackTitle.text=playbackHeading(data.series,episodeIndex)
        playbackText.text=L10n.tr("正在获取播放地址…"); progressBar.progress=0; progressBar.visibility=View.VISIBLE
        // 换集一律不主动弹控制条（用户 2026-11-19：每次都弹太吵）；但用户本来就开着的话别抽走，
        // 只把展开的面板收起来，底部那一条留着继续显示集数与进度。
        if(reuse && hud.visibility==View.VISIBLE) { panel=false; controls.visibility=View.GONE; controls.clearFocus() } else hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; recoverySpinner?.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val ticket=generation
        // 推荐 feed 预取过首集地址就直接用（10 分钟 TTL，地址带时效）
        val warmStream=recommendStreams[requestedId]
            ?.takeIf { android.os.SystemClock.elapsedRealtime()-it.first<10*60*1000L }?.second
        // readyVideo 已在函数开头取好（queueAttach 判定要用）；这里只合并两条预取路径
        val preparedSource=prefetched ?: readyVideo
        work({
            preparedSource ?: run {
                // 推荐流预热的直链先试一次，打不开就走完整阶梯（普通档高→低 + bvc2 垫底）。
                val warm=warmStream
                if(warm!=null) {
                    try { return@run RemoteVideo(repository.http,warm).prepare() }
                    catch(_: java.io.IOException) { /* 掉到阶梯流程 */ }
                }
                // 清晰度阶梯（core 缓存 ~1h）：服务端一次下发全档，普通档从最高（≤用户所选）逐档
                // prepare，打不开自动换下一档；普通档耗尽再落 bytevc2 垫底档——纯本地换直链，
                // 不再触发「降级重试」接口闪屏（2026-11-21 真机 403 混发问题由此根治）。
                try { prepareFromLadder(repository,requestedId,maxQuality,skipFailed=true) }
                catch(e: UnplayableVideoException) { throw e }
                catch(e: java.io.IOException) {
                    // 阶梯全失败：退回旧的 stream() 多轮重试路径兜底（含风控/降级模型场景）。
                    val stream=repository.stream(requestedId,maxQuality)
                    if(stream.codec=="bytevc2") vc2Episodes+=requestedId
                    RemoteVideo(repository.http,stream).prepare()
                }
            }
        }, { remote ->
            android.util.Log.w("HongguoTV","Playback timing episode=${episodeIndex+1} toRemote=${android.os.SystemClock.elapsedRealtime()-playbackStarted}ms preloaded=${prefetched!=null} warmVideo=${readyVideo!=null} reused=$reuse")
            video=remote; quality=remote.info.quality
            // 预取/复用路径没经过 prepareFromLadder：这里统一把档位状态对齐，失败降档才有的放矢。
            if(activeTier==null) activeTier=remote.info
            if(activeTiers.isEmpty()) activeTiers=repository.cachedLadder(requestedId) ?: listOf(remote.info)
            val p=player ?: createPlayer()
            // 软解性能兜底（老电视）：bvc2 软解跟不上会卡顿但永不报错，降档链收不到信号；
            // 渲染器看门狗持续落后时回调这里，把当前档位记入死档走既有降档链（用户 2026-11-21）。
            Vc2.api.onVideoTooSlow = { w,h ->
                android.util.Log.w("HongguoTV","Bvc2 soft decode too slow ${w}x${h}, auto downgrade")
                main.post {
                    if(screen=="player" && playbackReady && !playError) {
                        activeTier?.let { failedTiers+=tierKey(it) }
                        val lower=nextDescentTarget()
                        if(lower!=null) applySessionDowngrade(lower)
                        else android.util.Log.w("HongguoTV","Slow bvc2 decode but no lower tier, keep best effort")
                    }
                }
            }
            p.setPlaybackSpeed(effectiveSpeed())
            var episodeStartedAt=playbackStarted
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket
                private fun markReady() {
                    // vc2 原生软解模式：视频轨本来就不走 ExoPlayer（原生解码器画），音轨就绪即算就绪。
                    if(!vc2Active && !p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                        playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                        return
                    }
                    playbackReady=true; incompatibleSkips=0
                    if(firstReady) {
                        firstReady=false; readySince=android.os.SystemClock.elapsedRealtime()
                        android.util.Log.w("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-episodeStartedAt}")
                        lastThrottleWait=0; decodeRetried=false
                        // 记录剧内已验证可播档（仅普通档）：同剧后续集直接从这档起播，不必重复撞硬解失败
                        activeTier?.takeIf { it.codec!="bytevc2" && it.qNum>0 }?.let { verifiedQuality[seriesId]=it.qNum }
                        startDanmaku()
                        warmEpisodeWindow()
                        // 起播成功后再预取后两部：此刻网络与解码器都已就绪，抢跑不会拖慢当前这部
                        if(recommendMode) {
                            recommendFailStreak=0
                            prefetchRecommend(recommendCursor)
                            // feed 里没有「点进来」这个动作，键位提示得主动亮一次；但只亮一次——
                            // 自动连播/自动下滑换集换剧（用户 2026-11-18：HUD 不必唤起）都不再打扰
                            if(!feedHintShown) { feedHintShown=true; showHud() }
                        }
                    } else if(rebufferStart>0) {
                        android.util.Log.w("HongguoTV","Rebuffer end episode=${episodeIndex+1} stalledMs=${android.os.SystemClock.elapsedRealtime()-rebufferStart}")
                        rebufferStart=0
                    }
                    if(hud.visibility==View.VISIBLE) updatePlaybackText()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) markReady()
                    if(state==Player.STATE_BUFFERING && !firstReady && rebufferStart==0L) { rebufferStart=android.os.SystemClock.elapsedRealtime(); android.util.Log.w("HongguoTV","Rebuffer start episode=${episodeIndex+1} positionMs=${p.currentPosition}") }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        // 自由刷剧节奏（用户 2026-11-18）：默认本剧连播（一集播完接下一集）；
                        // 「自由刷剧自动下滑」开着才一集播完就换新剧；按过 ←/→ 手动换集的剧始终连播。
                        if(recommendMode) {
                            val binge=feedSeriesBinge || !library.feedAutoSwipe
                            if(binge && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1,savedPosition(episodeIndex+1))
                            else nextRecommend()
                        }
                        else if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1,savedPosition(episodeIndex+1))
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && continueToNextSeason()) { /* season jump in progress */ }
                        else {
                            requestedAutoplay=false; p.pause()
                            // 最后一集自然播完且没跳成下一季：自动打「整剧已看完」标记。
                            // 首页「接着看」与手机端继续观看本就过滤 watched；历史记录保留，
                            // 在「我的 - 观看历史」里仍能看到，徽标显示「整剧已看完」。
                            if(WatchCompletion.finished(episodeIndex,data.episodes.size)) markSeriesWatched(data.series)
                            showEndedPanel()
                        }
                    }
                }
                /** 播放器自动切到预挂的下一集：原地交接状态，不再 stop/clearMediaItems，避免重新解析片头与重建解码器。 */
                override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
                    // AUTO=片尾自动续播；SEEK=用户按 → 时我们主动 seek 到预挂的下一集。
                    // 两条路径共用同一套「原地交接」逻辑，都不重建解码器。
                    if(!active() || (reason!=Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason!=Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)) return
                    val nextId=mediaItem?.mediaId ?: return
                    if(queuedNextId.isEmpty() || nextId!=queuedNextId) return
                    val fromIndex=queuedFromIndex; val fromDuration=queuedFromDuration
                    if(fromIndex in data.episodes.indices) {
                        library.save(WatchProgress(data.series,data.episodes[fromIndex],fromIndex,fromDuration,fromDuration,true,System.currentTimeMillis()))
                    }
                    val nextVideo=queuedVideo; val nextHttp=queuedHttp
                    val oldVideo=video; val oldHttp=playbackHttp
                    episodeIndex=queuedIndex.coerceIn(0,data.episodes.lastIndex); group=episodeIndex/20
                    detailEpisodePage=-1 // 片尾自动续播/SEEK 换集：返回详情页时选集网格跟随新集所在页
                    video=nextVideo; playbackHttp=nextHttp; quality=nextVideo?.info?.quality.orEmpty()
                    // 档位阶梯状态跟着交接：失败自动降档在预挂的下一集上同样有效。
                    activeTier=nextVideo?.info
                    activeTiers=repository.cachedLadder(nextId) ?: listOfNotNull(nextVideo?.info)
                    queuedNextId=""; queuedIndex=-1; queuedVideo=null; queuedHttp=null; queuedFromIndex=-1; queuedFromDuration=0
                    if(p.currentMediaItemIndex>0) runCatching { p.removeMediaItem(0) }
                    stopDanmaku()
                    // 为新一集重置所有逐集状态；解码器与播放器实例保持不变。
                    firstReady=true; firstFrame=true; rebufferStart=0; episodeStartedAt=android.os.SystemClock.elapsedRealtime(); readySince=0
                    playbackReady=false; playError=false; incompatibleSkips=0
                    recovery.reset(); autoRecovery=false; cancelRecovery()
                    downgradeEpisode=nextId; downgradeCount=0
                    startPosition=0; pendingSeek=null; outroTriggered=false; ffActive=false
                    // 无缝切换时新一集的视频编码/分辨率可能变化，ExoPlayer 重建视频解码器后
                    // 部分设备（极米 Z6X 等）不会继承 PlaybackParameters，出现视频回到 1x、
                    // 音频维持原倍速的音画不同步。这里显式重设倍速，同时把快进态恢复到正常倍速。
                    p.setPlaybackSpeed(effectiveSpeed())
                    outroSkipSeconds=library.skipMark(data.series.id)?.outroSeconds ?: 0
                    prefetchAttempts=0; prefetchAttemptAt=0
                    requestedAutoplay=true; pausedForLifecycle=false
                    library.setWatched(data.series.id,false)
                    playbackTitle.text=playbackHeading(data.series,episodeIndex)
                    progressBar.progress=0
                    oldVideo?.close(); closeTransport(oldHttp)
                    android.util.Log.w("HongguoTV","Playback seamless switch episode=${fromIndex+1}->${episodeIndex+1} previousDurationMs=$fromDuration")
                    val intro=library.skipMark(data.series.id)?.introSeconds ?: 0
                    if(intro>0) {
                        p.seekTo(intro*1000L)
                        Toast.makeText(this@MainActivity, L10n.tr("已跳过片头 $intro 秒"),Toast.LENGTH_SHORT).show()
                    }
                    if(p.playbackState==Player.STATE_READY) markReady() else if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    syncMediaSession()
                    warmEpisodeWindow()
                    if(sleepTimer.episodeEnded()) { stopForSleep(false); return }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        // 新一集的画面已经真的画出来了：让上一帧淡出而不是硬切（用户 2026-11-20：一闪太生硬）
                        releaseFreeze(fade=true); transitionFrom=0
                        android.util.Log.w("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-episodeStartedAt}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) { syncMediaSession(); danmakuView?.seekTo(newPosition.positionMs) } }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=mediaSourceFor(remote,requestedId)
            val introSkip=if(position<=0 && (skipMark?.introSeconds ?: 0)>0) skipMark!!.introSeconds*1000L else position.coerceAtLeast(0)
            if(queueAttach) {
                // 队列附着（对齐 seamlessNext）：新集挂到现有播放器尾部再 seek 过去，
                // 解码器不重建——换剧首帧从 2.9 秒降到百毫秒级。旧集的 item 摘掉后再回收。
                val at=p.mediaItemCount
                p.addMediaSource(source); p.seekTo(at,introSkip)
                if(at>0) p.removeMediaItem(0)
                main.post { runCatching { outgoingVideo?.close() } }
                android.util.Log.w("HongguoTV","Playback queue-attach episode=${episodeIndex+1} oldItems=$at")
            } else {
                p.setMediaSource(source); p.seekTo(introSkip)
            }
            android.util.Log.w("HongguoTV","Playback timing episode=${episodeIndex+1} toPrepare=${android.os.SystemClock.elapsedRealtime()-playbackStarted}ms attached=$queueAttach")
            if(!queueAttach || p.playbackState==Player.STATE_IDLE || p.playbackState==Player.STATE_ENDED) p.prepare()
            p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            if(introSkip>0 && position<=0) Toast.makeText(this@MainActivity, L10n.tr("已跳过片头 ${skipMark!!.introSeconds} 秒"),Toast.LENGTH_SHORT).show()
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        cancelNextPrefetch()
        cancelWarm()
        dropQueuedNext()
    }
    /** 只收掉「下一集」这条预取，不动整窗预热。
     *  2026-11-23：maybePrefetch() 原来调的是 cancelPrefetch()，而它内部会 cancelWarm()，
     *  于是每次预取下一集都把 warmWindow 清空 → 下一次 warmEpisodeWindow() 又整窗重打一遍批量请求。
     *  电视日志里 `window warmed 1/15` 每 5 秒出现一次就是这么来的（整窗预热被反复重启）。 */
    private fun cancelNextPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }
    /** 播放就绪后预热当前集前后的一窗（最多 15 集）：之后切集直接命中缓存，不再走「取址失败→自动重试」。 */
    private fun warmEpisodeWindow() {
        val data=detail ?: return
        val count=data.episodes.size
        if(count==0 || !foreground || screen!="player" || playError) return
        if(!networkAvailable() || connectivity.isActiveNetworkMetered) return
        val from=(episodeIndex-3).coerceAtLeast(0)
        val to=(from+14).coerceAtMost(count-1)
        val key="${data.series.id}:$from:$to"
        if(key==warmWindow) return
        warmWindow=key
        val index=episodeIndex
        val ids=data.episodes.subList(from,to+1)
        val scope=RequestScope(); warmScope=scope
        warmJob=prefetchWorker.submit {
            try {
                val report=scope.run { repository.warm(ids) }
                android.util.Log.i("HongguoTV","Playback window warmed episode=${index+1} ${report.cached}/${report.requested} batch=${report.batch} route=${report.route}")
            } catch(_: Exception) { }
        }
    }
    private fun cancelWarm() {
        warmScope?.cancel(); warmScope=null; warmJob?.cancel(true); warmJob=null; warmWindow=""
    }
    /** 放弃已经挂到播放列表的下一集：先从播放列表移除，再释放它独占的取流会话。 */
    private fun dropQueuedNext() {
        val p=player
        if(queuedNextId.isNotEmpty() && p!=null) {
            for(i in 0 until p.mediaItemCount) {
                if(p.getMediaItemAt(i).mediaId==queuedNextId) {
                    if(i>0) p.removeMediaItem(i)
                    break
                }
            }
        }
        queuedVideo?.close(); queuedVideo=null
        closeTransport(queuedHttp); queuedHttp=null
        queuedNextId=""; queuedIndex=-1; queuedFromIndex=-1; queuedFromDuration=0
    }
    /** 预取完成后把下一集 MediaSource 提前挂进 ExoPlayer，让播放器在片尾自动续播。 */
    private fun handOffPrefetched(key: String,id: String,quality: Int) {
        if(isDestroyed || screen!="player" || playError || queuedNextId.isNotEmpty()) return
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || sleepStopped || pausedForLifecycle || episodePanel!=null) return
        if(episodeIndex>=data.episodes.lastIndex) return
        if(data.episodes[episodeIndex]!=p.currentMediaItem?.mediaId || data.episodes[episodeIndex+1]!=id) return
        if(startQuality()!=quality) return
        val state=p.playbackState
        if(state==Player.STATE_IDLE || state==Player.STATE_ENDED) return
        // 预取一就绪就挂进播放列表（不再等片尾 30 秒）：
        // ExoPlayer 默认只加载当前 item，挂在后面的 item 不会主动读数据，所以不抢带宽；
        // 挂好之后用户按 → 就能让播放器内部切过去，省掉一次解码器重建（实测 1.1 秒）。
        val remote=preparedNext.take(key) ?: return
        // 提前预取只做了 prepare（解析头部 + 首块），真正挂队列前补上读前预读，片尾续播才不吃亏。
        runCatching { remote.warm() }
        val http=prefetchHttp.also { prefetchHttp=null } ?: run { remote.close(); return }
        prefetchedFor=""
        val mediaId=id
        val source=mediaSourceFor(remote,mediaId)
        p.addMediaSource(source)
        queuedNextId=mediaId; queuedIndex=episodeIndex+1; queuedVideo=remote; queuedHttp=http
        queuedFromIndex=episodeIndex; queuedFromDuration=p.duration.coerceAtLeast(0)
        android.util.Log.i("HongguoTV","Next episode queued episode=${queuedIndex+1} quality=$quality")
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(queuedNextId.isNotEmpty()) return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        // 预取时机：不再等片尾 30 秒（用户 2026-11-20：手动换集/上下换剧基本都发生在片子中段，
        // 原来那个窗口等于永远不预取，日志里 preloaded 全是 false）。现在起播稳定 3 秒就开始备下一集，
        // 用户随时按 →/↓ 都能直接接手已经建好连接、解好头部的流。
        val id=data.episodes[episodeIndex+1]; val quality=startQuality(); val key="$id:$quality"
        if(preparedNext.isFresh(key)) { handOffPrefetched(key,id,quality); return }
        if(prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        // 缓冲余量门槛从 5 秒提高到 10 秒：提前预取会拉走一块带宽，慢网上别把当前集的口粮抢走。
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<10_000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelNextPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        // 记住播放身份的匿名 Cookie 罐必须继承，否则预加载与正式播放不是同一个会话。
        val http=repository.http.newBuilder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        // 与正式播放共用同一份取址缓存：窗口已预热时，这里的取址是 0 次网络。
        val source=repository.childTransport(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var prepared: RemoteVideo?=null
            try {
                prepared=scope.run {
                    // 空响应不 trip 全局冷却（quiet）：预取只是优化，不能替用户把冷却等级抬上去
                    //（2026-11-23 实测：刷剧时下一集预取被拒，冷却 20→60→120 连跳，看片中途被锁）。
                    // 失败就让 maybePrefetch 的退避机制兜底，用户真点到下一集时再正式取址。
                    // 预取与正式播放共用同一套清晰度阶梯（core 缓存 ~1h，取址通常 0 次），
                    // 普通 403 档会自动换下一档；track=false 不写降级状态，别污染正在播的这集。
                    prepareFromLadder(source,id,quality,http=http,track=false,quiet=true)
                    // 只 prepare（解析头部 + 常驻首块），不 warm 整个 8MB 前读窗口：
                    // 提前预取发生在正片还在播的时候，一口气抢 8MB 会让慢网直接卡顿。
                    // 真的到片尾要续播时，handOffPrefetched 再补 warm。
                }
                if(preparedNext.complete(ticket,key,prepared)) {
                    android.util.Log.i("HongguoTV","Prefetch ready episode=${episodeIndex+2} waited=${android.os.SystemClock.elapsedRealtime()-prefetchAttemptAt}ms")
                    main.post { handOffPrefetched(key,id,quality) }
                } else runCatching { prepared?.close() }   // 挂载失败：prepared 无人接手，就地回收
            } catch(_: Exception) { runCatching { prepared?.close() } }
        }
    }
    private fun networkAvailable(): Boolean {
        if(Build.VERSION.SDK_INT>=23) return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
        @Suppress("DEPRECATION") return connectivity.activeNetworkInfo?.isConnected==true
    }
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        // 限流等待不走普通重试预算：预算耗尽也不弹手动面板，按冷却剩余时间等到点再试。
        if(throttledRemainingMs>0 && !recovery.remaining) {
            val delay=throttledRemainingMs+2000L
            playbackText.text="官方接口限流中，${(delay/1000f).toInt().coerceAtLeast(1)} 秒后自动重试 · ${formatTime(retryPosition)}"
            retryPending=true; main.postDelayed(retryRunnable,delay)
            return
        }
        // 预算耗尽：自动重试彻底走不通，把手动面板摆出来让用户决定。
        if(!recovery.remaining) {
            autoRecovery=false
            playbackText.text=L10n.tr("已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。")
            showManualRecoveryPanel(incompatible=false,decoding=false)
            return
        }
        // 网络断开：保持转圈等联网，联网回调会重新调度，不弹按钮打扰用户。
        if(!networkAvailable()) {
            playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"
            return
        }
        // 限流：等官方冷却到期再试一次（+2 秒缓冲防时钟毛刺），等待期不烧普通重试预算。
        val delay=if(throttledRemainingMs>0) throttledRemainingMs+2000L else recovery.delayMillis ?: return
        playbackText.text=if(throttledRemainingMs>0) {
            "官方接口限流中，${(delay/1000f).toInt().coerceAtLeast(1)} 秒后自动重试 · ${formatTime(retryPosition)}"
        } else "${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    /** 播放取流用的档位：本会话降过档就用降档值，否则用用户设置值。 */
    private fun effectiveQuality(): Int = sessionQuality ?: library.maxQuality
    /** 播放中实际生效的倍速与画面：本会话改过就用会话值，否则回落到全局默认。 */
    private fun effectiveSpeed(): Float = sessionSpeed ?: library.playbackSpeed
    private fun effectiveFrame(): VideoFrameMode = sessionFrame ?: library.frameMode
    /** 退出播放页时把「只对当前这部剧生效」的临时设置全部丢掉，回到全局默认。 */
    private fun resetPlaybackSession() { sessionQuality=null; probeQuality=null; failedTiers.clear(); decodeRetried=false; sessionSpeed=null; sessionFrame=null }
    /** 临时降档只影响本集重放（probeQuality），不写回用户设置、也不带进下一集——
     *  下一集起播档由 startQuality() 重新决定（剧内已验证档优先，用户 2026-11-21）。 */
    private fun applySessionDowngrade(target: Int) {
        probeQuality=target; downgradeCount++
        android.util.Log.w("HongguoTV","Auto downgrade to ${if(target==0) "bvc2" else "${target}P"} episode=${episodeIndex+1} attempt=$downgradeCount actual=$quality")
        playEpisode(episodeIndex,retryPosition,retryAutoplay)
    }

    /** 按清晰度阶梯建流：普通档（≤cap）从高到低逐档 prepare，某一档打不开（403/失效）自动换
     *  下一档，普通档耗尽再落 bytevc2 垫底档（融合路径由 Extractor 嗅探接管）。
     *  五档全空/解密全失败时 core.ladder 抛 UnplayableVideoException，这里原样上抛。
     *  track=false 用于预取线程：不写 activeTier/activeTiers，别污染正在播的这集的降级状态。 */
    private fun prepareFromLadder(repo: ContentRepository,id: String,cap: Int,
                                  http: okhttp3.OkHttpClient=repository.http,track: Boolean=true,
                                  skipFailed: Boolean=false,quiet: Boolean=false): RemoteVideo {
        val tiers=repo.ladder(id,quiet)
        // skipFailed：同集降档/上爬重放时跳过硬解已失败的档（level 标错类死档），别重复撞；
        // 预取走 track=false 且默认 false——预取的是下一集，死档名单是本集的，不能跨集套用。
        val dead={ t: StreamInfo -> skipFailed && tierKey(t) in failedTiers }
        val normal=tiers.filter { it.codec!="bytevc2" }.sortedByDescending { it.qNum }
        val selectable=normal.filter { it.qNum in 1..cap && !dead(it) }
        val normalHead=if(selectable.isEmpty()) normal.filter { !dead(it) }.take(1) else selectable
        // bvc2 软解档位：受「设置 → 软解清晰度」约束（默认 360P，老电视友好；用户 2026-11-21）。
        // 在上限内取最高一档（分辨率优先）；上限内全死（性能看门狗标记/URL 失效）时落最低档兜底。
        val softCap=library.softQuality
        val bvc=tiers.filter { it.codec=="bytevc2" }.sortedByDescending { it.qNum }
        val bvcAlive=bvc.filter { !dead(it) }
        val bvcPick=bvcAlive.filter { it.qNum in 1..softCap }.ifEmpty { bvcAlive.takeLast(1) }
        val candidates=normalHead+bvcPick
        if(candidates.isEmpty()) throw UnplayableVideoException("本集片源暂不兼容，请播放下一集","ladder empty id=$id",null)
        var lastError: java.io.IOException?=null
        for(tier in candidates) {
            val remote=RemoteVideo(http,tier)
            try {
                if(tier.codec=="bytevc2" && !Vc2.api.fused && !enterVc2Mode(tier))
                    throw java.io.IOException("vc2 原生解码器不可用")
                remote.prepare()
                if(tier.codec=="bytevc2") vc2Episodes+=id
                if(track) { activeTier=tier; activeTiers=candidates }
                return remote
            } catch(e: java.io.IOException) {
                runCatching { remote.close() }   // prepare 失败的连接必须还回去，别占住缓存块
                lastError=e
                android.util.Log.w("HongguoTV","Stream tier failed ${tier.quality}/${tier.codec} ep=$id: ${e.message}")
            }
        }
        throw lastError ?: java.io.IOException("本集无可用片源")
    }
    /**
     * bytevc2 原生软解模式入口（同屏复用主播放页，用户 2026-11-20 拍板）：
     * - 音轨：ExoPlayer 继续播同一条 bytevc2 流（容器里音频是标准编码），进度/seek/倍速/弹幕时钟全沿用；
     * - 画面：PlayerView 让位（GONE+摘 player），解码器把视频画到叠加 SurfaceView（mediaOutput 层级盖住空视频面）；
     * - 下载整集到缓存后打开解码器，视频线程按音频时钟出帧；
     * - 失败（下载/打开/解码）：置 vc2Failed 后回落「暂不兼容」提示，绝不无限重播。
     * 返回 false 表示本机不具备原生解码条件，调用方原样抛出走旧逻辑。
     */
    private fun enterVc2Mode(vc2: com.hongguotv.core.StreamInfo): Boolean {
        if(vc2Failed || vc2.url.isEmpty() || vc2.key==null || !Vc2.api.decoderAvailable) return false
        val url=vc2.url
        val keyHex=vc2.key!!.joinToString("") { "%02x".format(it) }
        runOnUiThread {
            if(screen!="player") return@runOnUiThread
            teardownVc2()
            vc2Active=true
            playerView?.let { it.player=null; it.visibility=View.GONE }
            val surface=SurfaceView(this).apply {
                holder.setFormat(PixelFormat.RGBA_8888); setZOrderMediaOverlay(true)
                holder.addCallback(object: SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { vc2Decoder?.setSurface(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { vc2Decoder?.setSurface(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { vc2Decoder?.setSurface(null) } }
                })
            }
            vc2Surface=surface
            root.addView(surface,FrameLayout.LayoutParams(-1,-1))
            updatePlaybackText()
            Thread {
                val file=java.io.File(cacheDir,"bvc2_episode.mdl")
                vc2Cache=file
                try {
                    if(file.exists()) file.delete()
                    val client=okhttp3.OkHttpClient.Builder()
                        .connectTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60,java.util.concurrent.TimeUnit.SECONDS).build()
                    client.newCall(okhttp3.Request.Builder().url(url)
                        .header("User-Agent",VendorConstants.VIDEO_UA)
                        .header("Referer","https://novel.snssdk.com/")
                        .header("Accept-Encoding","identity").build()).execute().use { resp ->
                        if(!resp.isSuccessful) throw java.io.IOException("片源下载失败 HTTP ${resp.code}")
                        java.io.FileOutputStream(file).use { out -> resp.body!!.byteStream().copyTo(out) }
                    }
                    android.util.Log.i("HongguoTV","Vc2Mode: downloaded ${file.length()} bytes, opening decoder")
                    val dec=Vc2.api.openDecoder(file.absolutePath,keyHex) ?: throw java.io.IOException("解码器打开失败")
                    // 下载期间用户可能已退出播放页（teardownVc2 已跑）：此时解码器没人接手，必须就地释放防泄漏
                    if(!vc2Active) { runCatching { dec.close() }; return@Thread }
                    vc2Decoder=dec
                    main.post {
                        surface.holder.surface?.takeIf { it.isValid }?.let { dec.setSurface(it) }
                        startVc2Video(dec)
                    }
                } catch(t: Throwable) {
                    android.util.Log.e("HongguoTV","Vc2Mode: init failed",t)
                    if(vc2Active) {
                        vc2Failed=true
                        main.post {
                            teardownVc2()
                            if(screen=="player") playerFailure(com.hongguotv.core.UnplayableVideoException("本集片源暂不兼容，请播放下一集"))
                        }
                    }
                }
            }.apply { name="bvc2-download" }.start()
        }
        return true
    }

    /** 视频线程：解码出帧后按音频时钟（含倍速）出画；大幅跳转/落后直接吸附到当前位置。 */
    private fun startVc2Video(dec: Vc2NativeDecoder) {
        vc2Thread=Thread {
            while(vc2Active) {
                val pts=try { dec.decodeVideo() } catch(t: Throwable) {
                    android.util.Log.e("HongguoTV","bvc2 video decode",t); -1L
                }
                if(pts<0) break
                while(vc2Active) {
                    val ahead=pts-currentPosition()*1000
                    if(ahead<=40_000) break
                    if(ahead>1_500_000) { runCatching { dec.seek(currentPosition().coerceAtLeast(0)*1000) }; break }
                    try { Thread.sleep(10) } catch(e: InterruptedException) { return@Thread }
                }
                if(!vc2Active) break
                try { dec.renderFrame() } catch(t: Throwable) { android.util.Log.e("HongguoTV","bvc2 render",t) }
            }
        }.apply { name="bvc2-video"; start() }
    }

    /** 拆掉原生软解模式：线程、解码器、叠加画面、缓存文件一并回收，PlayerView 恢复原位。 */
    private fun teardownVc2() {
        if(!vc2Active && vc2Thread==null && vc2Decoder==null && vc2Surface==null) return
        vc2Active=false
        vc2Thread?.join(1200); vc2Thread=null
        runCatching { vc2Decoder?.close() }; vc2Decoder=null
        vc2Surface?.let { runCatching { root.removeView(it) } }; vc2Surface=null
        runCatching { vc2Cache?.delete() }; vc2Cache=null
        playerView?.let { it.visibility=View.VISIBLE; it.player=player }
    }

    /** 每集最多自动降两档：解码失败立即降，其余错误等同档重试预算用尽再降。 */
    private fun autoDowngradeTarget(decoding: Boolean,retryable: Boolean): Int? {
        // 上限 5：阶梯最多 5 档普通 + bvc2 垫底，4 次会在最坏情况挡住最后一次 bvc2 软解兜底；
        // failedTiers 已保证同一档不会重复试，多一次预算不会造成循环空转。
        if(downgradeCount>=5) return null
        if(!decoding && !(retryable && !recovery.remaining)) return null
        return nextDescentTarget()
    }
    /** 沿活动阶梯找下一个可试档位：优先向下（未试过的更低普通档 → bytevc2 垫底返回 0）；
     *  已到底但上面还有没试过的档时向上爬（用户 2026-11-21）——服务端部分档位 level 标错
     *  （hvc1.1.6.L186 超硬解能力）不代表整集不能解，高档可能反而是好档。向上封顶
     *  effectiveQuality()，不突破用户手动选择的档位。返回 null 表示阶梯全试完。 */
    private fun nextDescentTarget(): Int? {
        val idx=activeTiers.indexOfFirst { it===activeTier }.takeIf { it>=0 }
            ?: activeTiers.indexOfFirst { it.quality==activeTier?.quality && it.codec==activeTier?.codec }
        if(idx<0) return QualityDowngrade.next(QualityDowngrade.parse(quality),effectiveQuality())
        for(i in idx+1 until activeTiers.size) {
            val next=activeTiers[i]
            if(next.codec=="bytevc2") return 0
            if(tierKey(next) in failedTiers) continue
            return next.qNum
        }
        val ceiling=effectiveQuality()
        for(i in idx-1 downTo 0) {
            val up=activeTiers[i]
            if(tierKey(up) in failedTiers || up.codec=="bytevc2") continue
            if(up.qNum>ceiling) continue
            return up.qNum
        }
        return null
    }
    private fun playerFailure(problem: Throwable) {
        // 整集只有 bytevc2（服务端无兼容档）：重播本集，playEpisode 的取流回退会转原生软解模式。
        // 解码器不可用或本集已失败过就不重试，落回「暂不兼容」提示，防止无限循环。
        val vc2=(problem as? com.hongguotv.core.UnplayableVideoException)?.bvc2Stream
        if(vc2!=null && !vc2Failed && vc2.url.isNotEmpty() && vc2.key!=null && Vc2.api.decoderAvailable) {
            cancelRecovery(); cancelPrefetch(); player?.pause()
            playEpisode(episodeIndex,currentPosition().coerceAtLeast(0),requestedAutoplay && !sleepStopped,recovering=true)
            return
        }
        teardownVc2()
        // 换集时主动 stop()/close 数据源会让 ExoPlayer 抛出 IOException: Canceled，
        // 这是切换动作本身的副作用，不是片源问题：必须放行，否则过渡层刚盖上去就被撤掉
        //（用户 2026-11-19 真机：过渡层只活了 46ms，画面照样黑）。
        val canceled=problem is java.io.IOException && problem.message?.contains("Canceled")==true
        if(canceled && transitionFrom>0 && android.os.SystemClock.elapsedRealtime()-transitionFrom<5000) {
            android.util.Log.w("HongguoTV","Playback failure ignored (episode transition canceled)")
            return
        }
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        // 官方限流（空响应冷却）：记住剩余等待时间，重试改走专用等待路径（见 scheduleRecovery/retryRunnable）。
        // 连续被拒时官方会逐档加罚且实际封禁窗口长于宣称值，本地等待按上一轮 2 倍退避（2026-11-21 实测）。
        val throttleRemaining=(problem as? PlaybackThrottledException)?.remainingMs ?: 0L
        if(throttleRemaining>0) {
            throttledRemainingMs=maxOf(throttleRemaining,lastThrottleWait*2)
            lastThrottleWait=throttledRemainingMs
        } else { throttledRemainingMs=0L; lastThrottleWait=0L }
        val incompatible=problem is UnplayableVideoException
        // 自由刷剧里「片源不兼容」会 2.5 秒后自动跳下一部。这段时间过渡层不要撤：
        // 撤了就是黑底错误页一闪一闪（真机实测就是这样），改成留画面 + 换文案。
        val keepFreeze=incompatible && recommendMode && recommendFailStreak+1<3 && freezeLayer!=null
        if(keepFreeze) {
            freezeTip?.text=L10n.tr("本集片源不可用，正在跳到下一部…")
            freezeFade?.let { main.removeCallbacks(it) }
            val guard=Runnable { releaseFreeze() }; freezeFade=guard; main.postDelayed(guard,20000)
        } else releaseFreeze()
        // 失败时默认先把按钮都藏起来，只留转圈；只有「自动重试彻底走不通」才把手动面板摆出来。
        playError=true; player?.pause(); progressBar.visibility=View.GONE
        if(!keepFreeze) hud.visibility=View.VISIBLE
        controls.removeAllViews(); controls.visibility=View.GONE; recoverySpinner?.visibility=View.GONE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        // 硬解失败的档位记入本集失败名单：降档/上爬时跳过，避免重复撞同一个死档（level 标错类）。
        if(decoding) activeTier?.let { failedTiers+=tierKey(it) }
        val retryable=!incompatible && (if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException)
        playbackText.text=when {
            incompatible -> "本集片源暂不兼容：平台未提供电视可解码的清晰度，可播放下一集。"
            decoding -> "电视无法解码当前视频，可尝试更低清晰度。"
            else -> "播放失败，可重试或切换清晰度。"
        }
        // 解码失败同档重试没有意义，同档重试预算又已用尽时也不该再空转：两种情况都自动退一档。
        // 限流不是片源问题，换清晰度再打一次取址照样被拒，不能进降档。
        val lower=if(incompatible || sleepStopped || throttledRemainingMs>0) null else autoDowngradeTarget(decoding,retryable)
        when {
            lower!=null -> {
                // 自动降档：600ms 后直接换清晰度重放，期间用转圈提示，不打扰用户。
                playbackText.text=if(lower==0) "当前清晰度无法播放，正在切换到 bvc2 软解画面"
                    else "当前清晰度无法播放，正在自动切换到 ${lower}P"
                recoverySpinner?.visibility=View.VISIBLE
                autoRecovery=false
                main.postDelayed({ if(foreground && screen=="player" && playError) applySessionDowngrade(lower) },600)
            }
            decoding && !decodeRetried -> {
                // 阶梯耗尽的解码失败：自动重试 1 次赌 CDN 换编码变体（ep39 实测同档不同时刻编码不同）。
                // 重试必须清死档名单，否则 prepareFromLadder 会跳过原档直接抛「暂不兼容」，碰不到原档；
                // 重试全程不打取址接口（阶梯缓存内零请求，不撞风控），失败面板照常回来，最坏多花几秒。
                decodeRetried=true; failedTiers.clear()
                playbackText.text=L10n.tr("正在重试…")
                recoverySpinner?.visibility=View.VISIBLE
                autoRecovery=false
                main.postDelayed({ if(foreground && screen=="player" && playError) playEpisode(episodeIndex,retryPosition,retryAutoplay) },600)
            }
            retryable && !sleepStopped -> {
                // 可重试：静默自动重试，界面只显示转圈 + 倒计时，不弹按钮。
                // 用户按确认键可随时取消并切到手动面板（见 dispatchKeyEvent）。
                playbackText.text=if(throttledRemainingMs>0) "官方接口限流中…" else "准备恢复播放…"
                recoverySpinner?.visibility=View.VISIBLE
                autoRecovery=true; scheduleRecovery()
            }
            else -> {
                showManualRecoveryPanel(incompatible,decoding)
                // 推荐 feed 是自动起播，不能停在一屏失败面板上等人按；跳过这一部继续刷。
                if(recommendMode) {
                    recommendFailStreak++
                    if(recommendFailStreak>=3) {
                        recommendFailStreak=0
                        playbackText.text=L10n.tr("连续几部都放不出来，先回首页看看")
                        main.postDelayed({ if(foreground && screen=="player" && playError) { playError=false; returnToRecommendPage() } },2500)
                    } else {
                        playbackText.text="这部暂时放不出来，2.5 秒后自动换下一部 · 已跳过 $recommendFailStreak 部"
                        main.postDelayed({ if(foreground && screen=="player" && playError && recommendMode) switchRecommend(1) },2500)
                    }
                }
            }
        }
        android.util.Log.e("HongguoTV","Playback failure: ${problem.javaClass.simpleName} msg=${problem.message}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else "")+(if(problem is com.hongguotv.core.UnplayableVideoException && problem.diagnostic.isNotEmpty()) " "+problem.diagnostic else ""), problem)
    }
    /** 自动重试走不通（预算耗尽 / 网络异常 / 不兼容 / 解码失败）时，才把手动操作按钮摆出来。 */
    private fun showManualRecoveryPanel(incompatible: Boolean,decoding: Boolean) {
        recoverySpinner?.visibility=View.GONE
        controls.removeAllViews(); controls.visibility=View.VISIBLE
        val actions=row(); controls.addView(actions)
        if(incompatible) {
            val hasNext=episodeIndex<detail?.episodes.orEmpty().lastIndex
            val hasPrev=episodeIndex>0
            // 本集放不出来时用户最想做的往往是退回上一集，只给「下一集」等于把人往前逼。
            // 上一集按钮放在最前：点错了也能原路退回去，自动跳集期间同样可用来打断。
            val prev=if(hasPrev) addButton(actions,"播放上一集") { playEpisode(episodeIndex-1,savedPosition(episodeIndex-1)) } else null
            if(hasNext && extras.autoSkipUnplayable && incompatibleSkips<5) {
                // 整批集数可能都是不可解码片源：最多连跳 5 集，避免无限空转
                incompatibleSkips++
                playbackText.text="本集片源暂不兼容，自动跳过 · 已跳过 $incompatibleSkips 集"
                addButton(actions,"返回选集") { returnToDetail(true) }
                val job=Runnable { skipUnplayableJob=null; if(foreground && screen=="player" && playError) playEpisode(episodeIndex+1) }
                skipUnplayableJob=job
                main.postDelayed(job,1200)
            } else {
                val next=if(hasNext) addButton(actions,"播放下一集") { playEpisode(episodeIndex+1) } else null
                val back=addButton(actions,"返回选集") { returnToDetail(true) }
                // 焦点优先仍给「下一集」；没有下一集时才退到上一集，最后才是返回选集。
                (next ?: prev ?: back).requestFocus()
            }
        } else {
            addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
            // 手动降一档：沿活动阶梯找当前实际播放档位的下一位，普通档耗尽给 bvc2 兜底入口。
            val lower=nextDescentTarget()
            if(lower!=null) addButton(actions,if(lower==0) "切换到 bvc2 软解" else "切换到 ${lower}P") {
                // 只改本次播放的档位，不动用户的全局默认；用户主动换档=新的尝试，重试预算也复位
                sessionQuality=lower; downgradeCount=0; decodeRetried=false
                playEpisode(episodeIndex,retryPosition,retryAutoplay)
            }
            // 已经是 480P（最低档）时上面那个按钮不会出现，但恰恰是这种片源，
            // 用户最需要一个「换一档再试」的入口——所以固定摆一个能选全部档位的按钮。
            lateinit var pick: TextView
            pick=addButton(actions,"切换清晰度") { showQualityPicker(pick,true) }
            if(lower==null) pick.requestFocus()
            addButton(actions,"返回选集") { returnToDetail(true) }
        }
    }
    /** 用户在自动重试转圈时主动打断：停掉定时器，切到手动面板让用户决定下一步。 */
    private fun stopAutoRecoveryAndShowPanel() {
        cancelRecovery()
        playbackText.text=L10n.tr("已取消自动重试，观看位置已保留。")
        showManualRecoveryPanel(incompatible=false,decoding=false)
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
        updatePointerLayer()
    }
    /** 播放屏鼠标手势：垂直滑动换集（仿红果原版，不弹选集面板），单击呼出/收起控制条。挂在根布局上，非播放屏直接放行。 */
    private fun setupMouseGestures() {
        val gestures=object: GestureDetector.SimpleOnGestureListener() {
            private var locked=false; private var total=0f
            fun beginStroke() { locked=false; total=0f }
            override fun onDown(e: MotionEvent)=true
            override fun onScroll(e1: MotionEvent?,e2: MotionEvent,dx: Float,dy: Float): Boolean {
                if(screen!="player" || locked) return true
                total+=dy
                // 累计位移超过屏幕高 20% 触发一次，本手势内不再重复触发（防慢慢拖动连切）。
                // 注意 onScroll 的 dy 是「上次坐标−当前坐标」（AOSP 反号约定）：指针上移 dy>0，
                // 所以 total>0 才是上滑=下一集；onFling 用的是速度原始符号（上滑为负），两条路不要混。
                if(Math.abs(total)>resources.displayMetrics.heightPixels*0.2f) {
                    locked=true
                    // 自由刷剧里上下滑＝换剧（对齐遥控 ↑↓ 语义），点播页仍是换集（用户 2026-11-18）
                    if(recommendMode) switchRecommend(if(total>0) 1 else -1)
                    else skipEpisode(if(total>0) 1 else -1,reveal=false)
                }
                return true
            }
            override fun onFling(e1: MotionEvent?,e2: MotionEvent,vx: Float,vy: Float): Boolean {
                if(screen!="player" || locked) return false
                // 快速轻扫位移可能不够 20%，用速度兜底；要求纵向明显占优，避免误伤横向拖动
                if(Math.abs(vy)>Math.abs(vx)*1.4f && Math.abs(vy)>2000f) {
                    locked=true
                    if(recommendMode) switchRecommend(if(vy<0) 1 else -1)
                    else skipEpisode(if(vy<0) 1 else -1,reveal=false)
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if(screen!="player" || playError) return false
                if(!playbackReady) { showHud(); return true }
                // 鼠标左键单击＝暂停/续播：暂停时把控制条和画面中间的播放按钮一起放出来
                if(player?.playWhenReady==true) { togglePlayback(); showHud() }
                else { togglePlayback(); hidePlaybackOverlay() }
                return true
            }
        }
        val detector=GestureDetector(this,gestures)
        root.setOnTouchListener { _,e ->
            if(screen!="player") return@setOnTouchListener false
            // 鼠标点一下就记住：控制条上的退出按钮与鼠标手势提示跟着出现
            if(TvPointer.isPointer(e)) { if(!mouseSeen) { mouseSeen=true; refreshPointerUi() } }
            // 鼠标右键的行为可在「遥控器按键」里改，默认退出播放。TV 上很多遥控器把右键映射成
            // BACK（那条走 dispatchKeyEvent），这里接的是真正的鼠标次级键。
            if(e.actionMasked==MotionEvent.ACTION_DOWN && (e.buttonState and MotionEvent.BUTTON_SECONDARY)!=0) {
                when(extras.mouseRightAction) {
                    PlaybackExtras.MOUSE_PLAY -> if(player?.playWhenReady==true) { togglePlayback(); showHud() } else { togglePlayback(); hidePlaybackOverlay() }
                    PlaybackExtras.MOUSE_EPISODES -> { showHud(); showEpisodePanel(hud) }
                    PlaybackExtras.MOUSE_HUD -> if(controls.visibility==View.VISIBLE) hidePlaybackOverlay() else showHud()
                    // 播放设置：打开时收起左下「选集/收藏」与中间播放键；再点一次右键关掉面板并收起 HUD
                    PlaybackExtras.MOUSE_PANEL -> if(panel) hidePlaybackOverlay() else { showHud(); showPanel() }
                    else -> returnToDetail()
                }
                return@setOnTouchListener true
            }
            when(e.actionMasked) {
                // 只在按下时重置：抬起后 onFling 才回调，若在 UP 重置会解锁导致 fling 再触发一次（一次滑动切两集）
                MotionEvent.ACTION_DOWN -> gestures.beginStroke()
            }
            detector.onTouchEvent(e)
        }
    }

    /** 播放页标题：自由刷剧不再加前缀（用户 2026-11-18），与点播一致只显示剧名与集数。 */
    private fun playbackHeading(series: Series, episode: Int)="${series.title}  ·  第 ${episode+1} 集"

    private fun keyHintText(): String {
        // 推荐 feed 用另一套键位：上下是换剧；左右短按换集/长按快进；确认短按暂停/长按进详情
        // 每一段都是独立词条，逐段 L10n.tr：整串（多段用 4 空格拼接）在字典里查不到。
        if(recommendMode) {
            val prefix=when {
                feedSeriesBinge -> "本剧连播中 · "
                library.feedAutoSwipe -> "自动下滑已开 · "
                else -> "本剧连播 · "
            }
            val base=L10n.tr(prefix)+L10n.tr("左右 换集 · 长按 快进/快退    上下 换剧    确认 暂停 · 长按 进详情    菜单 控制条 · 长按 快捷面板    返回 回上页")
            return if(mouseSeen) base+"    "+L10n.tr("鼠标 单击 暂停/播放 · 上下滑 换剧 · 拖进度条/滚轮 快进快退 · 右键 退出") else base
        }
        val upDown=if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) L10n.tr("↓ 下一集    ↑ 上一集") else L10n.tr("↓ 选集    ↑ 更多")
        val parts=mutableListOf(L10n.tr("确认 暂停/播放"),L10n.tr("左右 快退/快进"),L10n.tr("长按右键 3× 快进"),upDown,L10n.tr("长按菜单 快捷面板"))
        // 没在用鼠标就不提鼠标手势：屏幕上写着「拖进度条」，人伸手去摸遥控器只会更迷糊
        if(mouseSeen) {
            val right=if(extras.mouseRightAction==PlaybackExtras.MOUSE_EXIT) L10n.tr("右键退出")
                else L10n.tr("右键")+L10n.tr(mouseRightLabel())+" · "+L10n.tr("右上角退出")
            parts+=L10n.tr("鼠标 单击暂停/播放 · 拖进度条定位 · 条上滚轮 ±10 秒 · 上下滑换集")+" · "+right
        }
        parts+=L10n.tr("返回 收起 / 退出")
        return parts.joinToString("    ")
    }

    /** 鼠标右键当前映射，用于底部手势提示与设置项文案。 */
    private fun mouseRightLabel()=when(extras.mouseRightAction) {
        PlaybackExtras.MOUSE_PLAY -> "暂停/播放"
        PlaybackExtras.MOUSE_EPISODES -> "打开选集"
        PlaybackExtras.MOUSE_HUD -> "显隐控制条"
        PlaybackExtras.MOUSE_PANEL -> "播放设置"
        else -> "退出"
    }

    /** 鼠标 / 遥控切换后刷新「只属于鼠标」的那部分界面：右上角退出与手势提示。 */
    private fun refreshPointerUi() {
        playerExit?.visibility=if(mouseSeen) View.VISIBLE else View.GONE
        keyHints?.text=keyHintText()
        updatePointerLayer()
    }
    /** 鼠标快捷按钮：只在「播放页 + 用过鼠标 + 控制条展开」时出现，跟着控制条一起进出。 */
    private fun updatePointerLayer() {
        val layer=pointerLayer ?: return
        if(screen!="player") { layer.visibility=View.GONE; return }
        val show=mouseSeen && hud.visibility==View.VISIBLE && episodePanel==null && !panel && !playError
        layer.visibility=if(show) View.VISIBLE else View.GONE
        pointerCenter?.visibility=if(show && playbackReady && player?.playWhenReady==false) View.VISIBLE else View.GONE
        if(show) {
            val d=detail
            pointerFav?.text=if(d!=null && library.favorite(d.series.id)) "★ 已收藏" else "☆ 收藏"
        }
    }

    private fun showHud() {
        if(screen!="player") return
        refreshPointerUi()
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        updatePointerLayer()
        if(!panel && !ffActive && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        remotePublish()
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        // 拖动进度条时别用播放位置盖掉「定位到 12:34」：手指还按着，文字每秒跳回去像坏了
        if(progressBar.scrubbing) return
        val danmakuOn=library.danmaku && detail?.series?.id?.let { extras.danmakuEnabled(it,true) }!=false
        // bvc2 软解集在清晰度后面标 (软解)，提示用户当前画面来自融合软解（用户 2026-11-21）。
        // 判断依据是在播档位的 codec（融合路径 vc2Active 恒为 false，原实现拿旧模式标志导致标签永不显示）。
        val hudQuality=if((vc2Active || activeTier?.codec=="bytevc2") && quality.isNotBlank()) "$quality(软解)" else quality
        playbackStatus.text=listOfNotNull(hudQuality.ifBlank { null },PlaybackSpeed.label(player?.playbackParameters?.speed ?: effectiveSpeed()),"弹幕 "+(if(danmakuOn) "开" else "关")).joinToString("   ·   ")
        if(sleepStopped) { playbackText.text=L10n.tr("定时停止已生效 · 观看位置已保存 · 按播放可继续"); return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        // vc2 模式下解码器还没就绪：音频先行，画面短暂黑屏，状态行给个预期
        if(vc2Active && vc2Decoder==null) { playbackText.text=L10n.tr("正在加载原生解码…"); return }
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { ffActive -> "3× 快进中"; p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        playerScreenOn=play; refreshScreenOn()
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    /** 返回是否真的切换了集数（越界返回 false）。
     *  同一部剧里换集不再自动弹控制条（用户 2026-11-19：每换一集弹一次 HUD 太吵），
     *  集数交给换集过渡层上的小字；控制条本来就开着的话保持开着，别把用户手里的操作条抽走。 */
    /**
     * 下一集已经挂在播放列表里时，让播放器内部切过去，而不是重建整条播放链路。
     * 重建那 1.2 秒里约 1.1 秒是硬解初始化（真机日志证实），这条路径只 flush 解码器。
     * 返回 false 表示条件不满足，调用方回退到 playEpisode。
     */
    private fun seamlessNext(silent: Boolean=false): Boolean {
        if(!seamlessNextOnSkip) return false
        // vc2 模式：下一集走播放器内部切轨会让原生解码的画面与新集音轨脱节，强制走完整换集路径
        if(vc2Active) return false
        val p=player ?: return false
        val data=detail ?: return false
        if(screen!="player" || playError || !playbackReady) return false
        if(queuedNextId.isEmpty() || queuedIndex!=episodeIndex+1) return false
        if(queuedIndex>data.episodes.lastIndex) return false
        var at=-1
        for(i in 0 until p.mediaItemCount) if(p.getMediaItemAt(i).mediaId==queuedNextId) { at=i; break }
        if(at<0) return false
        // 解码器 flush 瞬间会黑一下，仍然拿当前这一帧盖住再切
        freezeFrame(loadingTip,silent) { runCatching { player?.seekTo(at,0L) } }
        return true
    }
    private fun skipEpisode(direction: Int,reveal: Boolean=false): Boolean {
        if(!foreground || screen!="player") return false
        val count=detail?.episodes?.size ?: return false
        if(count<=0) return false
        val target=episodeIndex+direction
        // 上下键换集、媒体键、手势、语音/遥控 play.next 都走这里；到头了必须给反馈，
        // 否则用户只看到「按了没反应」。方向对称，第一集往上同样提示。
        if(target !in 0 until count) {
            Toast.makeText(this@MainActivity, L10n.tr(if(direction>0) "已经是最后一集了 · 播完会自动标记已看完" else "已经是第一集了"),
                Toast.LENGTH_SHORT).show()
            return false
        }
        val hudWasVisible=hud.visibility==View.VISIBLE
        // 下一集已经预挂好就原地切（快得多）；没挂好才退回重建播放器那条路。
        if(direction>0 && seamlessNext()) { if(reveal || hudWasVisible) showHud(); return true }
        playEpisode(target,savedPosition(target)); if(reveal || hudWasVisible) showHud(); return true
    }
    private fun syncMediaSession() {
        remotePublish()
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
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),effectiveSpeed(),state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep(saveCurrent: Boolean=true) {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); if(saveCurrent) saveProgress()
        playerScreenOn=false; refreshScreenOn()
        playbackText.text=L10n.tr("定时停止已生效 · 观看位置已保存 · 按播放可继续")
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,L10n.tr("定时停止已生效"),Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: View) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.setValueLabel("定时 ${sleepTimer.label()}"); updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(effectiveFrame()) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: View,inPlayer: Boolean) {
        tvTools.choose(if(inPlayer) "本剧画面模式" else "全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            // 播放中只改这一部剧，全局默认留给设置页
            if(inPlayer) sessionFrame=VideoFrameMode.entries[index] else library.frameMode=VideoFrameMode.entries[index]
            playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.setValueLabel((if(inPlayer) "画面 " else "画面模式  ·  ")+effectiveFrame().label)
        })
    }
    private fun applyDanmakuPrefs()=danmakuView?.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
    private fun resetDanmakuPrefs() {
        library.danmakuOpacity=DanmakuPrefs.OPACITY_DEFAULT; library.danmakuFont=DanmakuPrefs.FONT_DEFAULT
        library.danmakuSpeed=DanmakuPrefs.SPEED_DEFAULT; library.danmakuRows=DanmakuPrefs.ROWS_DEFAULT
        applyDanmakuPrefs()
    }
    private fun showDanmakuOpacityPicker(anchor: View,done: (()->Unit)?=null) {
        val options=listOf(100,90,80,70,60,50,40,30,21)
        val current=options.indexOf(library.danmakuOpacity).coerceAtLeast(0)
        // 与清晰度选择器同一样式：当前值行内标注「· 当前」，开窗焦点直接落在它上面
        tvTools.choose("弹幕不透明度",options.map { "$it%"+(if(it==library.danmakuOpacity) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuOpacity=options[choice]; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.setValueLabel("弹幕不透明度  ·  ${library.danmakuOpacity}%")
            done?.invoke()
        },focusIndex=current)
    }
    private fun showDanmakuFontPicker(anchor: View,done: (()->Unit)?=null) {
        val current=library.danmakuFont.coerceIn(0,DanmakuPrefs.FONT_LABELS.lastIndex)
        tvTools.choose("弹幕字体大小",DanmakuPrefs.FONT_LABELS.mapIndexed { index,label -> label+(if(index==current) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuFont=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.setValueLabel("弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}")
            done?.invoke()
        },focusIndex=current)
    }
    private fun showDanmakuSpeedPicker(anchor: View,done: (()->Unit)?=null) {
        val current=library.danmakuSpeed.coerceIn(0,DanmakuPrefs.SPEED_LABELS.lastIndex)
        tvTools.choose("弹幕移动速度",DanmakuPrefs.SPEED_LABELS.mapIndexed { index,label -> label+(if(index==current) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuSpeed=choice; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.setValueLabel("弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}")
            done?.invoke()
        },focusIndex=current)
    }
    private fun showDanmakuRowsPicker(anchor: View,done: (()->Unit)?=null) {
        val rows=(DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).toList()
        val current=(library.danmakuRows-DanmakuPrefs.ROWS_MIN).coerceIn(0,rows.lastIndex)
        tvTools.choose("弹幕展示行数",rows.map { "$it 行"+(if(it==library.danmakuRows) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuRows=choice+DanmakuPrefs.ROWS_MIN; applyDanmakuPrefs()
            if(anchor.tag!="playback-settings") anchor.setValueLabel("弹幕展示行数  ·  ${library.danmakuRows} 行")
            done?.invoke()
        },focusIndex=current)
    }
    /** In-player danmaku preferences; reopens itself after each change so several can be tuned in one visit. */
    private fun showDanmakuPrefsDialog(anchor: View) {
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
    private fun showBlockedWordDialog(anchor: View) {
        val presets=listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手")
        val current=extras.blockedWords
        val words=(presets+current.filterNot { it in presets }).distinct()
        val checked=BooleanArray(words.size) { words[it] in current }
        val content=column()
        content.addView(TvDialog.title(this,"弹幕屏蔽词"))
        // 选项用可聚焦的按钮行：多选 ListView 的 performClick 触发不了 OnItemClickListener，
        // 手机遥控确认键点不动；这里和清晰度选择器同一套做法，行内自绘勾选态。
        fun paint(row: Button,index: Int,focus: Boolean) {
            row.text=(if(checked[index]) "✓ " else "　")+words[index]
            row.background=GradientDrawable().apply {
                setColor(when { checked[index]&&focus -> TvStyle.accent; focus -> TvStyle.raised; checked[index] -> TvStyle.selectedSurface; else -> TvStyle.surface })
                cornerRadius=dp(14).toFloat()
                setStroke(dp(1),if(focus||checked[index]) TvStyle.accent else TvStyle.outline)
            }
            row.setTextColor(when { checked[index]&&focus -> TvStyle.background; focus -> TvStyle.text; checked[index] -> TvStyle.accent; else -> TvStyle.muted })
            row.setTypeface(null,if(focus||checked[index]) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        val dialog=AlertDialog.Builder(this).create()
        val rows=words.mapIndexed { index,word ->
            Button(this).apply {
                id=View.generateViewId()
                textSize=16f
                minHeight=dp(46)
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(20),0,dp(20),0)
                paint(this,index,false)
                setOnFocusChangeListener { _,has -> paint(this,index,has) }
                setOnClickListener { checked[index]=!checked[index]; paint(this,index,isFocused) }
            }
        }
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }
        val list=column()
        rows.forEachIndexed { index,row ->
            row.nextFocusUpId=rows.getOrNull(index-1)?.id ?: row.id
            row.nextFocusDownId=rows.getOrNull(index+1)?.id ?: row.id
            list.addView(row,lp(-1,-2).apply { bottomMargin=dp(8) })
        }
        scroll.addView(list,lp(-1,-2))
        TvDialog.capHeight(this,scroll)
        content.addView(scroll,lp(-1,-2))
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val clear=TvDialog.capsuleButton(this,"清空") {
            extras.blockedWords=emptySet(); applyBlockedWords(); anchor.setValueLabel("弹幕屏蔽词  ·  0 个")
            for(i in checked.indices) checked[i]=false
            rows.forEachIndexed { i,r -> paint(r,i,r.isFocused) }
        }
        val save=TvDialog.capsuleButton(this,"保存") {
            extras.blockedWords=words.filterIndexed { index,_ -> checked[index] }.toSet()
            applyBlockedWords(); anchor.setValueLabel("弹幕屏蔽词  ·  ${extras.blockedWords.size} 个")
            Toast.makeText(this@MainActivity,L10n.tr("屏蔽词已保存，立即生效"),Toast.LENGTH_SHORT).show()
        }
        buttons.addView(clear,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(save,lp(-2,-2))
        content.addView(buttons)
        dialog.setView(TvDialog.card(this,content)) // setView 必须在 styleWindow 前：自然测宽要读到卡片
        TvDialog.styleWindow(this,dialog) { rows.firstOrNull()?.requestFocus() }
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }
    private fun applyBlockedWords() = danmakuView?.setBlockedFilter { text -> extras.blocks(text) }

    /** 本机观看统计：直接按最近观看记录汇总，不额外存储、不产生任何网络请求。 */
    private fun showWatchStats(anchor: View) {
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
            if(accountManager.signedIn) {
                val cloudCount=accountManager.cloudRows().size
                if(cloudCount>0) append("\n云端另有 $cloudCount 部观看记录（只读展示，不合并统计）")
            }
            append("\n统计来自本机观看记录，不上传、不联网。")
        }
        tvTools.info("观看统计 · 本机",body,anchor)
    }

    /** 取址协议档案：看当前生效的那份、手动拉一次、或者一键回到内置默认。
     *  站方改字段集时改远端档案即可生效；这里给的是「出问题时能立刻退回」的把手。 */
    private fun showProfileSheet(row: SettingRow) {
        fun refreshRow() { row.value = L10n.tr(profileUpdater.statusLine()) }
        TvDialog.choose(this, L10n.tr("取址协议档案"),
            listOf(L10n.tr("查看当前档案"), L10n.tr("立即检查更新"), L10n.tr("恢复内置默认")),
            { index ->
                when (index) {
                    0 -> {
                        val error = profileUpdater.sanityError()
                        val body = profileUpdater.summary() + if (error == null) "" else "\n\n自检异常：$error"
                        TvDialog.info(this, L10n.tr("取址协议档案"), body).show()
                    }
                    1 -> {
                        Toast.makeText(this, L10n.tr("正在检查协议档案…"), Toast.LENGTH_SHORT).show()
                        profileUpdater.refresh { _, message ->
                            refreshRow()
                            Toast.makeText(this, L10n.tr(message), Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> TvDialog.confirm(this, L10n.tr("恢复内置默认"),
                        L10n.tr("清掉已下发的协议档案，播放取址回到随包默认值。下次启动会重新检查远端档案。"),
                        confirmText = L10n.tr("恢复"), danger = true) {
                        profileUpdater.resetToBuiltin()
                        refreshRow()
                        Toast.makeText(this, L10n.tr("已恢复内置默认档案"), Toast.LENGTH_SHORT).show()
                    }.show()
                }
            }, extra = L10n.tr("当前：") + L10n.tr(profileUpdater.statusLine()))
    }

    /** 接口自检：用户逐项点击，单次串行请求，失败只提示不重试，避免给站点增加并发压力。
     *  状态圆点内嵌在按钮文字前：灰=未检测，橙=检测中，绿=通，红=不通；3 秒内重复点击直接忽略。 */
    private fun showInterfaceCheck(anchor: View) {
        val content=column()
        val status=textLive("选择一项开始检测",14f,muted).apply { setPadding(dp(8),0,dp(8),dp(10)) }
        content.addView(status)
        lateinit var dialog: AlertDialog
        // 6 项拆两行各 3 个：弹窗收窄后单行 6 个 weight 均分会把按钮压到逐字竖排换行
        val rows=List(2) { row() }
        val buttons=mutableListOf<TextView>()
        val dotIdle=TvStyle.outline
        val dotOk=Color.rgb(86,196,122)
        val dotFail=Color.rgb(235,95,95)
        // 每个按钮独立的冷却与在检状态，互不影响
        val lastAt=mutableMapOf<String,Long>()
        val busy=mutableSetOf<String>()
        fun dotLabel(name: String,color: Int): CharSequence {
            val span=android.text.SpannableString("●  ${L10n.tr(name)}")
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
            if(name in busy || now-(lastAt[name] ?: 0L)<3_000) { status.text="${L10n.tr(name)} 请稍候再试"; return }
            lastAt[name]=now
            busy+=name; status.text="正在检测 ${L10n.tr(name)}…"
            btn.text=dotLabel(name,accent)
            val started=android.os.SystemClock.elapsedRealtime()
            io.execute {
                val result=runCatching(probe)
                main.post {
                    if(!dialog.isShowing) return@post
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    result.fold(
                        { detail -> status.text="${L10n.tr(name)} · 正常 · $detail · ${elapsed}ms"; btn.text=dotLabel(name,dotOk) },
                        { problem -> status.text="${L10n.tr(name)} · 失败 · ${(problem.message ?: problem.javaClass.simpleName).take(80)}"; btn.text=dotLabel(name,dotFail) })
                    busy-=name
                }
            }
        }
        fun add(name: String,probe: ()->String): TextView {
            lateinit var btn: TextView
            btn=button(name) { check(name,btn,probe) }
            btn.text=dotLabel(name,dotIdle)
            buttons+=btn
            rows[(buttons.size-1)/3].addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
            return btn
        }
        add("首页") { val page=repository.home(1,library.contentType); L10n.trFmt("返回 {0} 条",page.items.size) }
        add("搜索") { val page=repository.search("修仙",1,library.contentType); L10n.trFmt("返回 {0} 条",page.items.size) }
        add("播放") {
            val progress=library.history().firstOrNull() ?: throw IllegalStateException("暂无观看记录，先播放一集")
            val info=repository.stream(progress.episodeId,library.maxQuality); "获取成功 · ${info.quality}"
        }
        add("联想") { val words=repository.suggest("修仙"); L10n.trFmt("联想 {0} 个",words.size) }
        add("账号") { accountManager.checkAccountHealth() }
        add("云端") { accountManager.checkCloudHistory() }
        content.addView(rows[0])
        content.addView(rows[1],lp(-1,-2).apply { topMargin=dp(8) })
        content.addView(text("说明：每项只发一次请求；账号与云端检测需要先登录，未登录会跳过。",12f,muted).apply { setPadding(dp(8),dp(10),dp(8),0) })
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog,widthDp=340)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); buttons.first().requestFocus()
    }

    /** 遥控器按键：就地循环切换取值，避免多层弹窗来回跳焦点。 */
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
        addButton(transport,"上一集") { skipEpisode(-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { skipEpisode(1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        lateinit var episodes: TextView
        episodes=addButton(transport,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(transport,"播放设置") {
            val seriesId=detail?.series?.id.orEmpty()
            val mark=library.skipMark(seriesId)
            val danmakuOn=extras.danmakuEnabled(seriesId,library.danmaku)
            fun skipLabel(seconds: Int)=if(seconds>0) "$seconds 秒" else "关闭"
            val options=buildList {
                add("倍速 ${PlaybackSpeed.label(effectiveSpeed())}（仅本剧）"); add("清晰度 ${if(effectiveQuality()>0) "${effectiveQuality()}P" else "bvc2 软解"}（仅本剧）"); add("画面 ${effectiveFrame().label}（仅本剧）"); add("定时 ${sleepTimer.label()}")
                add("跳过片头 ${skipLabel(mark?.introSeconds ?: 0)}"); add("跳过片尾 ${skipLabel(mark?.outroSeconds ?: 0)}"); add("从头播放"); add("弹幕 ${if(danmakuOn) "开启" else "关闭"}（仅本剧）")
                if(danmakuOn) add("弹幕设置")
            }
            tvTools.choose("播放设置",options,more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) {
                        when(choice) {
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
                }
            })
        }.apply { tag="playback-settings" }
        play.requestFocus()
    }
    /** 播放快捷面板：长按菜单键呼出。清晰度/倍速/弹幕/更多四列，列内上下循环，改动立即生效，播放不中断。 */
    private fun showQuickPanel() {
        if(screen!="player" || playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val data=detail
        val seriesId=data?.series?.id.orEmpty()
        controls.addView(text("播放快捷面板",16f).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
        controls.addView(text("上下选择 · 确认更改 · 返回或菜单键关闭 · 短按菜单仍执行你设置的行为",12f,muted).apply { setPadding(0,dp(2),0,dp(10)) })
        val columnsRow=row().apply { gravity=Gravity.TOP }
        controls.addView(columnsRow)
        val columns=mutableListOf<List<TextView>>()
        fun optionColumn(title: String,options: List<String>,current: Int,onPick: (Int,TextView)->Unit): List<TextView> {
            val col=column()
            col.addView(text(title,13f,gold).apply { setPadding(dp(10),0,0,dp(6)) })
            val items=options.mapIndexed { index,label ->
                TextView(this).apply {
                    text=if(index==current) "$label · 当前" else label; textSize=14f
                    setTextColor(if(index==current) accent else white); includeFontPadding=false
                    gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(7),dp(12),dp(7)); minHeight=dp(36); minWidth=dp(150)
                    focusStyle(this,index==current)
                    setOnClickListener { onPick(index,this) }
                    col.addView(this,lp(-2,-2).apply { bottomMargin=dp(4) })
                }
            }
            columnsRow.addView(col,lp(-2,-2).apply { rightMargin=dp(22) })
            return items
        }
        // 清晰度：切换后按当前进度重新取流，面板随之关闭（playEpisode 会清空 controls）
        columns+=optionColumn("清晰度",PlaybackQuality.options.map { "${it}P" },PlaybackQuality.options.indexOf(effectiveQuality())) { index,_ ->
            val target=PlaybackQuality.options[index]
            if(effectiveQuality()!=target) {
                // 只改本次播放的档位，不写回全局默认；用户主动换档=新的尝试，重试预算也复位
                sessionQuality=target; probeQuality=null; downgradeEpisode=""; downgradeCount=0; decodeRetried=false
                playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            } else showQuickPanel()
        }
        // 倍速：立即生效并停留在面板
        columns+=optionColumn("倍速",PlaybackSpeed.options.map(PlaybackSpeed::label),PlaybackSpeed.options.indexOf(effectiveSpeed())) { index,_ ->
            sessionSpeed=PlaybackSpeed.options[index]; player?.setPlaybackSpeed(PlaybackSpeed.options[index])
            updatePlaybackText(); showQuickPanel()
        }
        // 弹幕：本剧开关即时切换；其余进入既有弹幕设置
        val danmakuOn=library.danmaku && extras.danmakuEnabled(seriesId,true)
        columns+=optionColumn("弹幕",listOf(if(danmakuOn) "关闭弹幕（仅本剧）" else "开启弹幕（仅本剧）","弹幕设置"),-1) { index,anchor ->
            if(index==0) { toggleSeriesDanmaku(seriesId); showQuickPanel() } else showDanmakuPrefsDialog(anchor)
        }
        // 更多：跳过片头片尾 / 画面 / 定时 / 从头播放
        val mark=data?.series?.id?.let { library.skipMark(it) }
        fun skipLabel(seconds: Int)=if(seconds>0) "$seconds 秒" else "关闭"
        columns+=optionColumn("更多",listOf("跳过片头 ${skipLabel(mark?.introSeconds ?: 0)}","跳过片尾 ${skipLabel(mark?.outroSeconds ?: 0)}","画面 ${effectiveFrame().label}","定时 ${sleepTimer.label()}","从头播放本集"),-1) { index,anchor ->
            when(index) {
                0 -> showSkipPicker(anchor,true)
                1 -> showSkipPicker(anchor,false)
                2 -> showFramePicker(anchor,true)
                3 -> showSleepPicker(anchor)
                4 -> { seekTo(0); hidePanel() }
            }
        }
        // 列内上下循环，列间左右按行号对齐（越界夹到末项），遥控器不会跳出面板的网格
        val maxRows=columns.maxOf { it.size }
        columns.forEachIndexed { c,items ->
            items.forEachIndexed { i,item ->
                item.nextFocusUpId=items[(i-1+items.size)%items.size].id
                item.nextFocusDownId=items[(i+1)%items.size].id
                if(c>0) item.nextFocusLeftId=columns[c-1][i.coerceAtMost(columns[c-1].lastIndex)].id
                if(c<columns.lastIndex) item.nextFocusRightId=columns[c+1][i.coerceAtMost(columns[c+1].lastIndex)].id
            }
        }
        if(maxRows>0) columns[1].getOrNull(0)?.requestFocus() ?: columns[0].firstOrNull()?.requestFocus()
    }
    /** Season finale auto-continues into the next season only when both the master autoplay and the season switch are on. */
    private fun continueToNextSeason(): Boolean {
        val data=detail ?: return false
        if(!library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) return false
        val next=data.nextSeason ?: return false
        Toast.makeText(this@MainActivity, L10n.tr("即将播放《${next.title}》"),Toast.LENGTH_SHORT).show()
        openDetail(next,resume=true,autoplay=true)
        return true
    }
    /** 整剧播完自动标记：不重复提示，也不删历史——「我的 - 观看历史」要留着这一条。 */
    private fun markSeriesWatched(series: Series) {
        if(library.watched(series.id)) return
        library.setWatched(series.id,true)
        Toast.makeText(this@MainActivity, L10n.tr("《${series.title}》已看完 · 已移出「接着看」，可在「我的 - 观看历史」查看"),Toast.LENGTH_LONG).show()
    }
    private fun showEndedPanel() {
        val data=detail
        if(playError || data==null || !library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) {
            // 最后一集播完但没开自动连播/跨季时，也要明确告诉用户「整部剧看完了」
            playbackText.text=if(!playError && data!=null && WatchCompletion.finished(episodeIndex,data.episodes.size))
                "全剧已播完 · 已标记看完" else "本集已结束"
            showPanel(); return
        }
        val cached=library.cachedHome(library.contentType)?.items.orEmpty().filter { it.id!=data.series.id }
        if(cached.isNotEmpty()) { showRecommendationPanel(cached); return }
        playbackText.text=L10n.tr("本集已结束"); showPanel()
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
    private fun showSkipPicker(anchor: View,intro: Boolean) {
        val data=detail ?: return
        val options=listOf(0,15,30,45,60,90,120)
        val current=library.skipMark(data.series.id)?.let { if(intro) it.introSeconds else it.outroSeconds } ?: 0
        tvTools.choose((if(intro) "跳过片头" else "跳过片尾")+" · 仅对本剧生效",options.map { if(it==0) "关闭" else "$it 秒" },anchor,{ index ->
            val old=library.skipMark(data.series.id) ?: SkipMark(0,0)
            val next=if(intro) old.copy(introSeconds=options[index]) else old.copy(outroSeconds=options[index])
            library.setSkipMark(data.series.id,next)
            if(!intro) { outroSkipSeconds=next.outroSeconds; outroTriggered=false }
            Toast.makeText(this@MainActivity, L10n.tr("已保存，仅对《${data.series.title}》生效"),Toast.LENGTH_SHORT).show()
            showHud()
        },"当前 ${if(current>0) "$current 秒" else "关闭"}",onExtra={})
    }
    private fun showSpeedPicker(anchor: View,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val current=if(inPlayer) effectiveSpeed() else library.playbackSpeed
        val content=column()
        content.addView(TvDialog.title(this,if(inPlayer) "本剧倍速" else "全局默认倍速"))
        if(inPlayer) content.addView(text("只对当前这部剧生效，不改「设置 → 播放」里的默认倍速",12f,muted).apply { setPadding(dp(8),0,dp(8),dp(8)) })
        lateinit var dialog: AlertDialog
        // 同清晰度选择器：按钮比 ListView 更可靠，电视上不会出现「按了没反应」
        val items=mutableListOf<TextView>()
        speeds.forEach { speed ->
            val isCurrent=speed==current
            val item=button(PlaybackSpeed.label(speed)+(if(isCurrent) "   ·   当前" else ""),isCurrent) {
                // 播放中就只改本次会话：用户临时调个倍速，不该改掉明天看别的剧的默认值
                if(inPlayer) sessionSpeed=speed else library.playbackSpeed=speed
                if(inPlayer) player?.setPlaybackSpeed(speed)
                if(anchor.tag!="playback-settings") anchor.setValueLabel((if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(speed))
                if(inPlayer) updatePlaybackText()
                dialog.dismiss()
            }
            items+=item
            // 选项只是文字行，居中排版既费横向空间又难扫读；左对齐后弹窗可以收得很窄
            item.gravity=Gravity.CENTER_VERTICAL
            item.setPadding(dp(16),dp(10),dp(16),dp(10))
            content.addView(item,lp(-1,dp(46)).apply { bottomMargin=dp(8) })
        }
        items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: view.id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        }
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        // 播放中靠左小窗：只是换个倍速，没必要居中盖住大半个画面；
        // 设置页没有播放画面要护，全局默认值是正式决定，居中更像「对话框」
        TvDialog.styleWindow(this,dialog,maxWidthDp=380,
            gravity=if(inPlayer) Gravity.START else Gravity.CENTER,marginDp=48)
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show()
        (items.getOrNull(speeds.indexOf(current)) ?: items.firstOrNull())?.requestFocus()
    }
    /** 软解（bvc2）清晰度选择（用户 2026-11-21）：默认 360P 老电视友好。
     *  只影响 bvc2 垫底档的选择；调高后软解跟不上，性能看门狗仍会自动降回来。 */
    private fun showSoftQualityPicker(anchor: View) {
        val options=PlaybackQuality.softOptions
        val current=library.softQuality
        val content=column()
        content.addView(TvDialog.title(this,"软解清晰度"))
        content.addView(text("bvc2 软解专用档位，普通剧集不受影响。老电视建议 360P；调高画质更好，但软解跟不上会自动降回来。",12f,muted).apply { setPadding(dp(8),0,dp(8),dp(8)) })
        lateinit var dialog: AlertDialog
        val items=mutableListOf<TextView>()
        options.forEach { q ->
            val isCurrent=q==current
            val item=button("${q}P"+(if(isCurrent) "   ·   当前" else ""),isCurrent) {
                library.softQuality=q
                anchor.setValueLabel("软解清晰度  ·  ${q}P")
                dialog.dismiss()
            }
            items+=item
            item.gravity=Gravity.CENTER_VERTICAL
            item.setPadding(dp(16),dp(10),dp(16),dp(10))
            content.addView(item,lp(-1,dp(46)).apply { bottomMargin=dp(8) })
        }
        items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: view.id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        }
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog,maxWidthDp=380,gravity=Gravity.CENTER,marginDp=48)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }

    private fun showQualityPicker(anchor: View,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val current=if(inPlayer) effectiveQuality() else library.maxQuality
        val content=column()
        content.addView(TvDialog.title(this,if(inPlayer) "本剧清晰度" else "全局默认清晰度"))
        if(inPlayer) content.addView(text("只对当前这部剧生效，不改「设置 → 播放」里的默认清晰度",12f,muted).apply { setPadding(dp(8),0,dp(8),dp(8)) })
        lateinit var dialog: AlertDialog
        // 用按钮而不是 ListView：弹窗里的 ListView 在部分电视上收不到遥控确认键，
        // 用户按了没反应却看不出原因。按钮的点击路径最稳。
        val items=mutableListOf<TextView>()
        options.forEach { quality ->
            val isCurrent=quality==current
            val item=button("${quality}P"+(if(isCurrent) "   ·   当前" else ""),isCurrent) {
                if(inPlayer) {
                    // 播放中只切这一部剧的档位，不动用户设置的全局默认
                    sessionQuality=quality; probeQuality=null
                } else {
                    library.maxQuality=quality; sessionQuality=null
                }
                // 用户手动选定档位即视为放弃本轮自动降档，后续失败重新从选定档位起算。
                downgradeEpisode=""; downgradeCount=0; decodeRetried=false
                if(anchor.tag!="playback-settings") anchor.setValueLabel((if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${quality}P")
                // 选完就收窗：播放中它会挡住画面，而用户下一步要看的是「到底能不能播」。
                dialog.dismiss()
                if(inPlayer && !isCurrent) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }
            items+=item
            // 与倍速选择器一致：行左对齐、弹窗靠左收窄，不遮播放画面
            item.gravity=Gravity.CENTER_VERTICAL
            item.setPadding(dp(16),dp(10),dp(16),dp(10))
            content.addView(item,lp(-1,dp(46)).apply { bottomMargin=dp(8) })
        }
        items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: view.id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        }
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        // 与倍速选择器同一规则：播放中靠左小窗，设置页居中
        TvDialog.styleWindow(this,dialog,maxWidthDp=380,
            gravity=if(inPlayer) Gravity.START else Gravity.CENTER,marginDp=48)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
        (items.getOrNull(options.indexOf(current)) ?: items.firstOrNull())?.requestFocus()
    }
    /** 顶部导航栏模块：与实际顶栏对齐——搜索/排行榜/本地库/片源/我的；收藏与最近观看是"我的"的子 tab，不单独列。 */
    private fun navModuleNames()=listOf("search" to "搜索","rank" to "排行榜","nas" to "本地库","source" to "片源","mine" to "我的")
    private fun navModuleHidden(key: String): Boolean = when(key) {
        "nas" -> library.navHidden("nas")
        "source" -> library.sourceHidden
        "mine" -> library.navHidden("favorite") && library.navHidden("history")
        else -> library.navHidden(key)
    }
    private fun navModulesLabel(): String {
        val names=navModuleNames()
        return "导航模块  ·  ${names.count { !navModuleHidden(it.first) }}/${names.size} 显示"
    }
    /** 导航栏模块显隐：逐项开关，关闭后立即从顶部导航栏移除；首页与设置始终显示，不在此列。 */
    private fun showNavModules(anchor: View) {
        val content=column()
        content.addView(TvDialog.title(this,"导航模块"))
        content.addView(text("首页与设置始终显示；关闭后会从顶部导航栏移除，收藏与观看记录不会删除。",13f,muted)
            .apply { setPadding(0,0,0,dp(10)) })
        lateinit var dialog: AlertDialog
        navModuleNames().forEach { (key,label) ->
            fun rowText()="$label  ·  ${if(navModuleHidden(key)) "已隐藏" else "显示中"}"
            val row=TvDialog.capsuleButton(this,rowText()) { }
            row.gravity=Gravity.START or Gravity.CENTER_VERTICAL
            row.setPadding(dp(16),0,dp(16),0)
            row.setOnClickListener {
                val hide=!navModuleHidden(key)
                when(key) {
                    "source" -> library.sourceHidden=hide
                    "mine" -> { library.setNavHidden("favorite",hide); library.setNavHidden("history",hide) }
                    else -> library.setNavHidden(key,hide)
                }
                row.text=rowText(); anchor.setValueLabel(navModulesLabel())
            }
            content.addView(row,lp(-1,dp(46)).apply { bottomMargin=dp(8) })
        }
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(6),0,0) }
        val done=TvDialog.capsuleButton(this,"完成") { dialog.dismiss() }
        buttons.addView(done,lp(-2,-2))
        content.addView(buttons)
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener {
            // 关闭后重建页面，让顶部导航立刻反映新的显隐，并把焦点送回本项
            settingsFocusKey="导航模块"
            showCatalog()
        }
        dialog.show()
        content.getChildAt(2)?.requestFocus()
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
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected,savedPosition(selected)) }
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
        // 界面缩放（UiScale）时背景内容同倍率放大、超出部分裁边：背景跟 UI 一起变大，
        // 而不是其他元素都放大了背景还停在原尺寸；切档 recreate 后 loadBackdrop 重解码即生效
        val scale=maxOf(width.toFloat()/raw.width,height.toFloat()/raw.height)*UiScale.factor(this)
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
    private fun saveBackdrop(bytes: ByteArray,extension: String,onDone: ()->Unit={}) {
        io.execute {
            val bitmap=runCatching {
                filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() }
                java.io.File(filesDir,"backdrop.$extension").writeBytes(bytes)
                decodeBackdrop(bytes)
            }.getOrNull()
            main.post {
                if(isDestroyed) return@post
                if(bitmap==null) { Toast.makeText(this@MainActivity,L10n.tr("图片无法使用，请换一张试试"),Toast.LENGTH_LONG).show(); return@post }
                backdropBitmap=bitmap
                backdropView?.let {
                    if(it.parent==null && screen!="player") root.addView(it,0,FrameLayout.LayoutParams(-1,-1))
                    it.setImageBitmap(bitmap)
                }
                Toast.makeText(this@MainActivity,L10n.tr("背景已更新"),Toast.LENGTH_SHORT).show()
                onDone()
            }
        }
    }
    /** Drop any uploaded picture and switch back to one of the built-in styles. */
    private fun resetBackdrop(style: String) {
        io.execute { filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() } }
        getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).edit().putString("backdropStyle",style).apply()
        loadBackdrop()
        Toast.makeText(this@MainActivity, L10n.tr(if(style=="forced") "已恢复为强制欣赏" else "已恢复默认背景"),Toast.LENGTH_SHORT).show()
    }
    /**
     * 语音「打开快传文件」：与设置页里的入口同一条路，不另写一份列表。
     *
     * 列表里的确认键按类型分流到安装 / 预览 / 播放，那是「人已经选中了这一行」的确定意图，
     * 所以 direct=true；列表为空时给的按钮是重新打开手机快传，也不另做一套。
     */
    private fun openQuickFiles(anchor: View?) {
        tvTools.quickFiles(anchor,{ file,kind -> handleQuickTransfer(file,kind,true,anchor) },
            { tvTools.quickTransfer(anchor,{ file,kind -> handleQuickTransfer(file,kind) },{},{ openQuickFiles(anchor) }) })
    }
    /** 快传落盘后的分流：APK 引导安装，图片可预览并设为背景，视频可直接播放。
     *  [direct] 用于「快传文件」列表：用户已明确选中该行，直接进入预览/播放。 */
    private fun handleQuickTransfer(file: java.io.File,kind: TransferKind,direct: Boolean=false,anchor: View?=null) {
        when(kind) {
            TransferKind.APK -> {
                pendingQuickApk=file
                TvDialog.confirm(this,"安装手机传来的应用","已收到 ${file.name}。\n确认后打开电视的系统安装界面，按提示完成安装。") { installQuickApk(file) }.show()
            }
            TransferKind.IMAGE -> if(direct) tvTools.previewImage(anchor,file) { setQuickBackdrop(file) }
                else TvDialog.confirm(this,"设为电视背景","已收到图片 ${file.name}，是否用作电视背景？") { setQuickBackdrop(file) }.show()
            TransferKind.VIDEO -> if(direct) tvTools.playVideo(anchor,file)
                else Toast.makeText(this@MainActivity, L10n.tr("视频已保存到电视：${file.name}"),Toast.LENGTH_LONG).show()
            TransferKind.OTHER -> Toast.makeText(this@MainActivity, L10n.tr("文件已保存到电视：${file.name}"),Toast.LENGTH_LONG).show()
        }
    }
    /** 图片直接生效（可逆：设置页可恢复默认背景），后台读图，完成后刷新设置页文案。 */
    private fun setQuickBackdrop(file: java.io.File) {
        io.execute {
            val bytes=runCatching { file.readBytes() }.getOrNull()
            if(bytes==null) main.post { Toast.makeText(this,L10n.tr("图片无法读取，请换一张试试"),Toast.LENGTH_SHORT).show() }
            else saveBackdrop(bytes,TransferRules.extensionOf(file.name) ?: "jpg") { refreshBackdropSetting?.invoke() }
        }
    }
    private fun installQuickApk(apk: java.io.File) {
        when(ApkInstaller.install(this,apk)) {
            ApkInstaller.Result.LAUNCHED -> { pendingQuickApk=null; Toast.makeText(this,L10n.tr("已打开安装界面"),Toast.LENGTH_SHORT).show() }
            ApkInstaller.Result.NEEDS_PERMISSION -> Toast.makeText(this,L10n.tr("请允许本应用安装应用，返回后会自动继续安装"),Toast.LENGTH_LONG).show()
            ApkInstaller.Result.FAILED -> { pendingQuickApk=null; tvTools.info("无法安装","电视没有可用的安装入口，请用系统文件管理器安装。",null) }
        }
    }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        val ended=completed || p.playbackState==Player.STATE_ENDED
        val position=p.currentPosition.coerceAtLeast(0)
        // 逐集记录：换集后再回来能接着播；看完的集不留记录，回来时从头开始。
        episodeKey(episodeIndex)?.let { key -> if(ended) episodePositions.remove(key) else episodePositions[key]=position }
        val progress=WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,position,p.duration.coerceAtLeast(0),ended,System.currentTimeMillis())
        library.save(progress)
        // 顺带上报官方推荐画像：saveProgress 覆盖了换集/暂停/退出/播完四类时机（60s 节流在内部）
        if(accountManager.signedIn) accountManager.reportProgress(
            data.series,data.episodes[episodeIndex],episodeIndex,position,p.duration.coerceAtLeast(0),data.episodes.size)
    }
    private fun episodeKey(index: Int): String? {
        val data=detail ?: return null
        val episode=data.episodes.getOrNull(index) ?: return null
        return "${data.series.id}:$episode"
    }
    /** 切集时该集上次看到的坐标；没有记录（没看过或已看完）返回 0，即从头播。 */
    private fun savedPosition(index: Int): Long = episodeKey(index)?.let { episodePositions[it] } ?: 0L
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
            Toast.makeText(this,L10n.tr("弹幕总开关已关闭，请先在设置或播放设置中开启"),Toast.LENGTH_SHORT).show(); return
        }
        val on=!extras.danmakuEnabled(seriesId,true)
        extras.setDanmakuEnabled(seriesId,on)
        if(on) startDanmaku() else stopDanmaku()
        Toast.makeText(this@MainActivity, L10n.tr("已为《${detail?.series?.title.orEmpty()}》${if(on) "开启" else "关闭"}弹幕"),Toast.LENGTH_SHORT).show()
    }
    private fun stopDanmaku() {
        danmakuLoader=null
        danmakuView?.unbind()
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        teardownVc2()
        main.removeCallbacks(menuLongRunnable); menuLongFired=false
        ffActive=false; outroSkipSeconds=0; outroTriggered=false
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.let { android.util.Log.w("HongguoTV","Prefetch end ${it.stats()}") }
        video?.close(); video=null; playerView?.player=null; playerView=null; keyHints=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        stopDanmaku(); danmakuView=null
        closeTransport(playbackHttp); playbackHttp=null
        playerScreenOn=false; refreshScreenOn()
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        // 过渡层只服务于「播放页里换集」：真要离开播放页就撤掉，别拿冻结帧盖住下一页
        releaseFreeze()
        // 自由刷剧里的退出（✕ 按钮 / 鼠标右键 / media 键）：回进入页而不是详情（用户 2026-11-18）
        if(recommendMode && !feedDetailBrowsing) { returnToRecommendPage(); return }
        // 真正离开播放页才算一次播放会话结束：「只对当前这部剧生效」的档位、倍速、
        // 画面到这里全部作废。不能放进 releasePlayer()——换清晰度重播也会走那条路，
        // 会把用户刚选的档位当场清掉。
        resetPlaybackSession()
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    // ================= 待机屏保 =================

    /** 待机屏保（用户 2026-11-19）：非播放页无操作超过设定时长后盖全屏时钟；
     *  播放中 / 手机遥控占用 / 三方播放器时不触发（refreshScreenOn 的常亮条件保持一致）；
     *  任意按键/鼠标事件只撤屏保不透传，防止唤醒时误触换剧。 */
    private var screensaverView: FrameLayout?=null
    private var screensaverClock: TextView?=null
    private var screensaverDate: TextView?=null
    private var screensaverCustom: ScreensaverCustom?=null
    /** 底部提示条：告诉用户「怎么退出去」。待机模式 6 秒淡出，预览模式改做倒计时。 */
    private var screensaverHint: TextView?=null
    /** 是不是「设置里的立即预览」盖起来的：预览要自动收，待机屏保只在有人操作时收。 */
    private var screensaverPreview=false
    private var screensaverLeft=0
    /** 盖屏前的焦点：撤屏后还回去，遥控用户醒来接着按方向键，不用从左上角重新找。 */
    private var screensaverFocus: View?=null
    /** 撤屏后要接着做的事：素材面板里「长按预览这一张」用它把面板重新弹回来。
     *  弹窗是独立窗口、必须关掉才能盖屏，所以预览完不重开用户就回不到刚才那一屏。 */
    private var screensaverOnDismiss: (()->Unit)?=null
    /** 素材面板的勾选草稿：面板里勾了几张、还没按「用选中的轮播」时，配置不动。
     *  收快传素材会关窗重开，靠它把已勾的带过去（saverDraftKeep）；用户主动关窗就丢掉。 */
    private var saverDraft: MutableSet<String>?=null
    private var saverDraftKeep=false
    /** 刚盖屏的静默期截止时间：长按预览松手的那次 UP 会晚于盖屏落进来，不吞掉就会把预览立刻关掉。 */
    private var screensaverIgnoreUntil=0L
    private var lastInteractionAt=android.os.SystemClock.elapsedRealtime()
    private val screensaverCheck: Runnable = Runnable { checkScreensaver(); scheduleScreensaverCheck() }
    private val screensaverTick: Runnable = Runnable {
        updateScreensaverClock()
        if(screensaverPreview) countdownPreview()
        main.postDelayed(screensaverTick,1000)
    }
    /** 预览停留时长：够看清画面，又不至于让人等（用户 2026-11-19 要求「过几秒自动退出」）。 */
    private val previewSeconds=8

    private fun resetIdleTimer() {
        lastInteractionAt=android.os.SystemClock.elapsedRealtime()
        if(screensaverView!=null) dismissScreensaver()
    }
    private fun scheduleScreensaverCheck() {
        main.removeCallbacks(screensaverCheck)
        main.postDelayed(screensaverCheck,5000)
    }
    private fun checkScreensaver() {
        if(screensaverView!=null) return
        if(library.screensaverMinutes<=0) return
        if(!foreground || screen=="player" || remoteHolding || thirdWantsScreenOn) return
        val idleLimit=library.screensaverMinutes*60_000L
        if(android.os.SystemClock.elapsedRealtime()-lastInteractionAt < idleLimit) return
        showScreensaver()
    }
    /** 立即预览屏保（设置 → 屏保预览）：跳过空闲计时按当前风格直接盖全屏；待机屏保档位为「关闭」时也允许预览。
     *  先清掉所有弹窗再盖屏：弹窗是独立窗口，会浮在屏保层上面（用户 2026-11-19 看到的「预览时弹窗还在」就是这个）。
     *  延迟一拍再盖：等弹窗窗口真的移除、本次按键的 ACTION_UP 走完——UP 也会命中唤醒守卫，不延后会立刻撤掉预览。 */
    private fun previewScreensaver(only: java.io.File?=null) {
        if(screensaverView!=null) return
        TvDialog.dismissAll()
        main.postDelayed({ if(screensaverView==null && !isDestroyed) showScreensaver(preview=true,onlyFile=only) },450)
    }
    private fun showScreensaver(preview: Boolean=false,onlyFile: java.io.File?=null) {
        if(screensaverView!=null) return
        screensaverFocus=currentFocus
        screensaverPreview=preview
        screensaverLeft=previewSeconds
        // 盖屏瞬间留 1.2 秒静默期：长按预览的松手事件会晚到（见 consumeScreensaverInput）。
        // 长按 0.5 秒触发 + 0.45 秒盖屏延迟 ⇒ 盖屏后再留 1.2 秒，够覆盖 2 秒以内的长按
        screensaverIgnoreUntil=android.os.SystemClock.elapsedRealtime()+1200
        val layer=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // 传了 onlyFile 就是在面板里长按预览单张：无视当前风格，只放这一张（单张=常驻/循环，不轮播）
        when(if(onlyFile!=null) "custom" else library.screensaverStyle) {
            "neon" -> { layer.addView(NeonBackdrop(this),FrameLayout.LayoutParams(-1,-1)); addClockOverlay(layer,Color.WHITE,Color.argb(210,180,220,255)) }
            "stars" -> { layer.addView(StarfieldView(this),FrameLayout.LayoutParams(-1,-1)); addClockOverlay(layer,Color.WHITE,Color.argb(210,255,255,255)) }
            "breath" -> { layer.addView(BreathBackdrop(this),FrameLayout.LayoutParams(-1,-1)); addClockOverlay(layer,Color.WHITE,Color.argb(210,255,255,255)) }
            // 仿真时钟两档：表盘自己就是时钟（指针实时走动），不再叠数字角标，「时钟位置」设置不参与
            "clockgold" -> layer.addView(AnalogClockView(this,true),FrameLayout.LayoutParams(-1,-1))
            "clockdawn" -> layer.addView(AnalogClockView(this,false),FrameLayout.LayoutParams(-1,-1))
            // 选了「我的素材」但本机一个素材都没有（从别的设备恢复了备份/素材被外部清掉）：
            // 当次直接回落经典墨夜盖屏，非预览时把风格持久退回 classic，
            // 不让用户每次空闲都对着黑屏提示干瞪眼（用户 2026-11-25）
            "custom" -> {
                if(onlyFile==null && saverMediaFiles().isEmpty() && quickTransferMedia().isEmpty()) {
                    if(!preview) library.screensaverStyle="classic"
                    addClockOverlay(layer,Color.WHITE,muted)
                } else screensaverCustom=ScreensaverCustom(layer,onlyFile).also { it.start() }
            }
            else -> addClockOverlay(layer,Color.WHITE,muted)
        }
        root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        screensaverView=layer
        updateScreensaverClock()
        addScreensaverHint(layer,preview)
        main.postDelayed(screensaverTick,1000)
        android.util.Log.i("HongguoTV","Screensaver shown style=${if(onlyFile!=null) "custom(single)" else library.screensaverStyle} preview=$preview")
    }
    /** 底部提示条：屏保以前「怎么退出去」全靠猜（用户 2026-11-19 问的就是这个）。
     *  待机屏保只用来说明一次，6 秒后淡出，免得长期挂在电视上烧屏；预览模式常驻并逐秒倒计时。 */
    private fun addScreensaverHint(layer: FrameLayout,preview: Boolean) {
        val hint=text("",13f,Color.argb(235,255,255,255)).apply {
            gravity=Gravity.CENTER
            background=rounded(Color.argb(120,0,0,0))
            setPadding(dp(18),dp(9),dp(18),dp(9))
            // 自定义素材的图片/视频是后加的，会盖住提示条（和角标时钟同一个坑）；
            // 用 Z 轴抬高最稳，不用在每次 addView 后记得再 bringToFront 一次
            translationZ=dp(24).toFloat()
        }
        layer.addView(hint,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(30) })
        screensaverHint=hint
        refreshScreensaverHint()
        // 自定义素材的画面是后加的，会盖在提示条上面（和角标时钟同一个坑）
        screensaverCustom?.let { hint.bringToFront() }
        if(!preview) {
            hint.alpha=0f
            hint.animate().alpha(1f).setDuration(300).withEndAction {
                hint.animate().alpha(0f).setStartDelay(5500).setDuration(600).withEndAction {
                    if(screensaverHint===hint) hint.visibility=View.GONE
                }.start()
            }.start()
        }
    }
    private fun refreshScreensaverHint() {
        screensaverHint?.text=if(screensaverPreview)
            "正在预览  ·  ${screensaverLeft.coerceAtLeast(1)} 秒后返回  ·  按任意键或点击屏幕立即返回"
        else "按任意键 / 移动鼠标返回"
    }
    private fun countdownPreview() {
        screensaverLeft--
        if(screensaverLeft<=0) { dismissScreensaver(); return }
        refreshScreensaverHint()
    }
    /** 屏保时钟：位置完全跟随设置（居中/右上/左下/右下/不显示），内置风格与自定义素材共用一套。
     *  2026-11-23 修复两点：内置风格以前固定居中、根本不读 screensaverClockPos（设置里换位置预览没变化）；
     *  位置选项补上「居中」档。返回时钟容器（自定义素材换图后要 bringToFront 顶回最上层），关闭时返回 null。 */
    private fun addClockOverlay(layer: FrameLayout,clockColor: Int,dateColor: Int,hint: String?=null): View? {
        val pos=library.screensaverClockPos
        var box: LinearLayout?=null
        if(pos!="off") {
            val corner=pos!="mid"
            val clock=text("",if(corner) 26f else 54f,clockColor).apply {
                setTypeface(null,Typeface.BOLD)
                gravity=if(corner) Gravity.START else Gravity.CENTER
                includeFontPadding=false
            }
            val date=text("",if(corner) 12f else 16f,dateColor).apply {
                gravity=if(corner) Gravity.START else Gravity.CENTER
                if(!corner) setPadding(0,dp(14),0,0)
            }
            box=column().apply {
                if(corner) {
                    gravity=if(pos=="bl") Gravity.START else Gravity.END
                    background=rounded(Color.argb(64,0,0,0))
                    setPadding(dp(14),dp(8),dp(14),dp(10))
                } else gravity=Gravity.CENTER
                // 自定义素材的图/视频是后加的会盖住时钟，直接抬 Z 轴（与提示条同一个坑）
                translationZ=dp(20).toFloat()
            }
            box.addView(clock,lp(-1,-2)); box.addView(date,lp(-1,-2))
            val (gravity,wide)=when(pos) {
                "bl" -> (Gravity.BOTTOM or Gravity.START) to false
                "br" -> (Gravity.BOTTOM or Gravity.END) to false
                "tr" -> (Gravity.TOP or Gravity.END) to false
                else -> Gravity.CENTER to true
            }
            layer.addView(box,FrameLayout.LayoutParams(if(wide) -1 else -2,if(wide) -1 else -2,gravity).apply {
                when(pos) {
                    "tr" -> setMargins(0,dp(28),dp(36),0)
                    "bl" -> setMargins(dp(36),0,0,dp(40))
                    "br" -> setMargins(0,0,dp(36),dp(40))
                }
            })
            screensaverClock=clock; screensaverDate=date
        }
        if(hint!=null) {
            val tip=text(hint,13f,dateColor).apply { gravity=Gravity.CENTER }
            // 居中时钟时提示挂到时钟下面，避免叠字；其余位置提示居中摆放
            if(pos=="mid" && box!=null) box.addView(tip,lp(-1,-2))
            else layer.addView(tip,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        }
        return box
    }
    private fun updateScreensaverClock() {
        val now=java.util.Calendar.getInstance()
        screensaverClock?.text=String.format(java.util.Locale.CHINA,"%02d:%02d",now.get(java.util.Calendar.HOUR_OF_DAY),now.get(java.util.Calendar.MINUTE))
        val week=when(now.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "周一"; java.util.Calendar.TUESDAY -> "周二"; java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"; java.util.Calendar.FRIDAY -> "周五"; java.util.Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
        screensaverDate?.text=String.format(java.util.Locale.CHINA,"%d月%d日  ·  %s",now.get(java.util.Calendar.MONTH)+1,now.get(java.util.Calendar.DAY_OF_MONTH),week)
    }
    private fun dismissScreensaver() {
        val layer=screensaverView ?: return
        screensaverView=null; screensaverClock=null; screensaverDate=null; screensaverHint=null
        screensaverPreview=false; screensaverLeft=0
        main.removeCallbacks(screensaverTick)
        screensaverCustom?.stop(); screensaverCustom=null
        runCatching { root.removeView(layer) }
        // 撤屏后把焦点还回盖屏前那个控件：遥控用户醒来接着按方向键，不会掉到左上角重新找
        val back=screensaverFocus
        screensaverFocus=null
        val after=screensaverOnDismiss
        screensaverOnDismiss=null
        if(after!=null) main.postDelayed({ if(!isDestroyed && screensaverView==null) after() },260)
        else back?.takeIf { v -> v.isAttachedToWindow }?.post { if(screensaverView==null && currentFocus==null) back.requestFocus() }
        android.util.Log.i("HongguoTV","Screensaver dismissed")
    }

    // ---- 屏保素材（自定义媒体） ----

    /** 自定义屏保素材目录：手机快传收到的图片/动图/视频都存这里。 */
    private fun saverMediaDir()=java.io.File(filesDir,"screensaver")
    private fun saverMediaFiles()=saverMediaDir().listFiles()?.filter { !it.name.endsWith(TransferRules.PART_SUFFIX) }?.sortedBy { it.name } ?: emptyList()
    /** 快传收到的素材挪进屏保目录（同分区 rename，失败再 copy），已存在的同名文件跳过。 */
    private fun storeSaverMedia(file: java.io.File,onDone: ()->Unit={}) {
        io.execute {
            val dir=saverMediaDir(); runCatching { dir.mkdirs() }
            val target=java.io.File(dir,file.name)
            if(!target.exists()) runCatching {
                if(!file.renameTo(target)) file.copyTo(target,overwrite=true)
            }
            main.post {
                if(isDestroyed) return@post
                Toast.makeText(this@MainActivity, L10n.tr("已加入屏保素材：${shortName(file.name)}"),Toast.LENGTH_SHORT).show()
                refreshSaverMediaSetting?.invoke()
                onDone()
            }
        }
    }
    /** 已勾选参与轮播的文件名（按磁盘顺序，且必须仍然存在）：空=轮播全部。 */
    private fun saverPickedNames(files: List<java.io.File> = saverMediaFiles()): List<String> {
        val picked=library.screensaverMedia.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return files.map { it.name }.filter { it in picked }
    }
    /** 长文件名收口：设置行、toast、样例卡这类「一行放不下就崩版」的地方统一截断。
     *  屏保素材都是手机传的，名字常常是 IMG_20261119_213045_edit.jpg 这种长度。
     *  素材卡本身不截断，走单行省略 + 聚焦跑马灯，能看全。 */
    private fun shortName(name: String,max: Int=16)=if(name.length<=max) name else name.take(max-1)+"…"
    /** 手机快传收到的图片/视频（VIDEO 与 IMAGE 都落在 transfers 目录），可直接选来当屏保。 */
    private fun quickTransferMedia(): List<java.io.File> {
        val dir=java.io.File(filesDir,TransferRules.directoryName(TransferKind.IMAGE))
        val exts=setOf("jpg","jpeg","png","webp","gif","mp4","mkv","ts","mov","webm","avi")
        return dir.listFiles()?.filter {
            it.isFile && !it.name.endsWith(TransferRules.PART_SUFFIX) && TransferRules.extensionOf(it.name) in exts
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    /** 我的屏保素材（用户 2026-11-19 重做成缩略图面板，当晚补「复选几张轮播」）。
     *  顶部两张入口卡（手机传新素材 / 轮播全部），中间素材缩略图网格（图片按目标尺寸采样、动图取首帧、视频取第一帧），
     *  底部「已勾选 N 个」+「用选中的 N 个轮播」（点了立刻全屏预览）+ 红色描边的「清空全部素材」。
     *  勾选语义：确认键 = 勾上 / 取消，勾一个就是只播这一张，勾多个就是这几个轮流播；
     *  「轮播全部素材」= 全选并启用，配置写成空串（以后新传的素材自动进轮播，不用再回来勾一次）。
     *  文件名可能很长：卡片上单行中间省略，聚焦后跑马灯；其它地方（设置行、toast、样例卡）一律截断。 */
    private fun showSaverMediaManager(anchor: View) {
        val own=saverMediaFiles()
        val transfers=quickTransferMedia().filter { f -> own.none { it.name==f.name } }
        if(own.isEmpty() && transfers.isEmpty()) {
            Toast.makeText(this,L10n.tr("还没有素材，先用手机传一张图片或一段视频"),Toast.LENGTH_LONG).show()
            startSaverUpload(anchor); return
        }
        val stored=saverPickedNames(own)
        // 勾选草稿：按当前配置初始化（配置为空 = 全部轮播 ⇒ 全选）。
        // 收快传素材要关窗重开，靠 saverDraft 把已勾的带过去；用户主动关掉面板就丢弃——没按「轮播」不写配置。
        val draft=saverDraft ?: (if(stored.isEmpty()) own.map { it.name } else stored).toMutableSet()
        saverDraft=draft
        var dialogRef: AlertDialog?=null
        lateinit var scroll: ScrollView
        /** 所有素材卡，按网格顺序：网格是自己用行拼的，FocusFinder 在这种「行内 weight + 滚动容器」
         *  结构里经常搜不到下一行（实测按下键焦点直接丢失），所以最后统一手写四向焦点链。 */
        val allCards=mutableListOf<LinearLayout>()
        /** 勾选变化后要重画的东西：每张卡的角标/描边 + 底部计数与按钮文案 */
        val repainters=mutableListOf<()->Unit>()
        var applyBtn: TextView?=null
        lateinit var countLabel: TextView
        fun checkedCount()=own.count { it.name in draft }
        /** 一个都没勾时按钮退成灰底「先勾选素材」：点了只弹提示，别让它看起来能用。 */
        fun paintApply() {
            val b=applyBtn ?: return
            val n=checkedCount()
            b.text=if(n==0) "先勾选素材" else "用选中的 $n 个轮播"
            b.background=if(n==0) rounded(Color.rgb(52,58,70)) else rounded(accent)
            b.setTextColor(if(n==0) muted else TvStyle.background)
        }
        fun syncFooter() {
            countLabel.text=if(own.isEmpty()) "素材库还是空的 · 上面快传里的点一下就能收进来"
                else "共 ${own.size} 个 · ${TransferRules.humanBytes(own.sumOf { it.length() })} · 已勾选 ${checkedCount()} 个"
            paintApply()
        }

        /** 鼠标右键 = 关闭面板，补在面板内的可点控件上（弹窗级的那份在 TvDialog.track，
         *  只对空白区域生效：子控件消费 ACTION_DOWN 后事件不会回到 decor）。 */
        fun rightClickCloses(v: View) {
            v.setOnTouchListener { _,ev ->
                val secondary=(ev.buttonState and android.view.MotionEvent.BUTTON_SECONDARY)!=0
                if(ev.actionMasked==android.view.MotionEvent.ACTION_DOWN && secondary) { dialogRef?.dismiss(); true } else false
            }
        }

        /** 入口卡：左侧大符号 + 标题/副标题，焦点 accent 描边。 */
        fun entryCard(icon: String,title: String,sub: String,onClick: ()->Unit): LinearLayout {
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                isFocusable=true; isFocusableInTouchMode=true; isClickable=true
                setPadding(dp(14),dp(11),dp(14),dp(11))
            }
            card.addView(text(icon,20f,accent).apply { gravity=Gravity.CENTER; setPadding(dp(6),0,dp(14),0) },lp(-2,-2))
            val col=column()
            col.addView(text(title,15f,white))
            col.addView(text(sub,12f,muted).apply { setPadding(0,dp(3),0,0); maxLines=1; ellipsize=TextUtils.TruncateAt.END },lp(-1,-2))
            card.addView(col,lp(0,-2).apply { weight=1f })
            fun restyle(f: Boolean) { card.background=rounded(if(f) TvStyle.raised else surface,if(f) accent else TvStyle.outline) }
            restyle(false)
            card.setOnFocusChangeListener { _,f -> restyle(f) }
            card.setOnClickListener { onClick() }
            rightClickCloses(card)
            return card
        }
        fun tag(label: String,bg: Int,fg: Int)=text(label,10f,fg).apply {
            setPadding(dp(7),dp(2),dp(7),dp(3)); background=rounded(bg)
        }
        val videoExts=setOf("MP4","MKV","WEBM","MOV","TS","AVI")
        /** 素材卡：缩略图 + 文件名 + 大小；左上角是勾选角标（本面板的主操作），焦点只改描边和底色。 */
        fun mediaCard(file: java.io.File,fromTransfer: Boolean): LinearLayout {
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                id=View.generateViewId()
                isFocusable=true; isFocusableInTouchMode=true; isClickable=true
                setPadding(dp(7),dp(7),dp(7),dp(8))
            }
            val box=FrameLayout(this)
            val thumb=ImageView(this).apply {
                scaleType=ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.argb(255,12,16,26))
            }
            box.addView(thumb,FrameLayout.LayoutParams(-1,-1))
            // 占位：解码要跑 io 线程，先显示扩展名，别让用户对着四块空板子等
            val ext=(TransferRules.extensionOf(file.name) ?: "").uppercase(Locale.ROOT)
            val placeholder=text(ext,12f,Color.argb(105,255,255,255)).apply { gravity=Gravity.CENTER }
            box.addView(placeholder,FrameLayout.LayoutParams(-1,-1))
            // 勾选角标放左上：确认键勾上/取消，这是这个面板唯一的操作
            val check=text("",11f,TvStyle.background).apply {
                gravity=Gravity.CENTER; setTypeface(null,Typeface.BOLD)
                minWidth=dp(22); minHeight=dp(20); setPadding(dp(6),0,dp(6),0)
            }
            box.addView(check,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply { setMargins(dp(6),dp(6),0,0) })
            // 快传角标放右上：这条目录里的文件还没进素材库
            if(fromTransfer) box.addView(tag("快传",Color.argb(215,10,14,22),Color.argb(235,255,255,255)),
                FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { setMargins(0,dp(6),dp(6),0) })
            if(ext in videoExts) box.addView(tag("▶",Color.argb(185,0,0,0),Color.WHITE),
                FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6),0,0,dp(6)) })
            card.addView(box,LinearLayout.LayoutParams(-1,dp(96)))
            // 文件名可能很长：宽度必须约束成 MATCH_PARENT（原来是 wrap_content，长名字会直接把卡片撑破），
            // 平时中间省略，聚焦后跑马灯滚一遍，不用点进去才知道是哪张
            val nameView=text(file.name,11.5f,white).apply {
                setSingleLine(true); ellipsize=TextUtils.TruncateAt.MIDDLE; marqueeRepeatLimit=-1
                setPadding(0,dp(7),0,0)
            }
            card.addView(nameView,lp(-1,-2))
            card.addView(text(TransferRules.humanBytes(file.length()),10.5f,muted).apply { setPadding(0,dp(2),0,0) },lp(-1,-2))
            var focused=false
            fun isChecked()=file.name in draft
            fun paint() {
                // 不做放大动画：卡片在 ScrollView 里，scale 出来的溢出会被裁掉（第一行左上角实测被切）
                card.background=rounded(if(focused) TvStyle.raised else surface,if(focused||isChecked()) accent else TvStyle.outline)
                if(isChecked()) { check.text="✓"; check.background=rounded(accent); check.setTextColor(TvStyle.background) }
                else { check.text=""; check.background=rounded(Color.argb(120,0,0,0),Color.argb(190,255,255,255)) }
            }
            paint()
            repainters+=::paint
            card.setOnFocusChangeListener { _,f ->
                focused=f; paint()
                nameView.isSelected=f
                nameView.ellipsize=if(f) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.MIDDLE
                // 网格是自绘行拼的，ScrollView 不会跟着焦点自动滚，得手动把卡带到可视区。
                // 按「整块（区块标题 + 整行）」对齐：card.top 是相对所在行的（行内每张都是 0），
                // 必须换算成滚动内容坐标，否则第二行永远算成在顶部、滚不过去，表现为「按下键焦点消失」。
                // 只滚到「行顶」会把「已加入 · N 个」挤出视口、只剩半截（实测），所以整块放得下就带上标题。
                // 必须等 scroll 自己 post 一次再算：styleWindow 会在 show 前把滚动区压到 80% 屏高，
                // 在 card.post 里读到的 scroll.height 还是压缩前/为 0 的旧值，会误判「整块放不下」而把标题剔掉（实测）。
                if(f) scroll.postDelayed({
                    val rowView=(card.parent as? View) ?: card
                    val base=IntArray(2).also { scroll.getLocationInWindow(it) }
                    fun contentTop(v: View)=IntArray(2).also { v.getLocationInWindow(it) }[1]-base[1]+scroll.scrollY
                    val rowTop=contentTop(rowView)
                    val rowBottom=rowTop+rowView.height
                    val pg=rowView.parent as? android.view.ViewGroup
                    val idx=pg?.indexOfChild(rowView) ?: -1
                    // 上一兄弟如果是 TextView，那就是这一区的标题，跟行一起带进视口
                    val title=if(idx>0) pg?.getChildAt(idx-1)?.takeIf { it !is LinearLayout } else null
                    val blockTop=title?.let { contentTop(it) } ?: rowTop
                    // 一屏只放得下一行，所以焦点到哪一行就对齐到哪一行的「区块标题顶」：
                    // 不要按 scroll.height 判断能不能放下（压缩后的高度时机拿不到，会误判成只保行、把标题挤出去）
                    scroll.scrollTo(0,blockTop.coerceAtLeast(0))
                },50)
            }
            card.setOnClickListener {
                if(fromTransfer) {
                    // 快传目录里的先收进素材库再勾上：收完关窗重开（草稿保留），网格里就多出这一张
                    saverDraftKeep=true
                    storeSaverMedia(file) {
                        draft.add(file.name)
                        dialogRef?.dismiss()
                        main.postDelayed({ saverDraftKeep=false; if(!isDestroyed) showSaverMediaManager(anchor) },350)
                    }
                } else {
                    if(!draft.remove(file.name)) draft.add(file.name)
                    repainters.forEach { it() }
                    syncFooter()
                }
            }
            // 长按 = 只看这一张（不改勾选、不写配置）：遥控器按住确认键、鼠标按住左键都能触发。
            // 屏保层在 Activity 根视图上、弹窗是独立窗口，必须先关面板才看得到预览，所以预览结束再把面板弹回来。
            card.setOnLongClickListener {
                saverDraftKeep=true
                screensaverOnDismiss={ saverDraftKeep=false; if(!isDestroyed) showSaverMediaManager(anchor) }
                previewScreensaver(file)
                true
            }
            // 鼠标右键在卡片上也要能关面板：可点击的卡片自己消费了 ACTION_DOWN 之后，
            // decor 上那个「右键=关闭」的监听就收不到事件（ViewGroup 命中 child 后不再走自身 onTouch），
            // 不补这一段右键点卡片会被当成勾选
            rightClickCloses(card)
            loadSaverThumb(file,dp(170),dp(96),thumb,placeholder)
            allCards+=card
            return card
        }

        /** 按勾选结果落盘并立刻预览：全选写空串（= 全部轮播，以后新传的自动包含）。 */
        fun applySelection() {
            val names=own.map { it.name }.filter { it in draft }
            if(names.isEmpty()) { Toast.makeText(this,L10n.tr("至少勾选一个素材"),Toast.LENGTH_SHORT).show(); return }
            library.screensaverStyle="custom"
            library.screensaverMedia=if(names.size==own.size) "" else names.joinToString(",")
            refreshSaverStyleSetting?.invoke(); refreshSaverMediaSetting?.invoke()
            saverDraft=null
            dialogRef?.dismiss()
            previewScreensaver()
        }

        fun clearAll() {
            // 不可逆操作：数量/体积写进正文，确认按钮单独叫「清空全部」并标红，默认焦点留在「取消」
            val total=TransferRules.humanBytes(own.sumOf { it.length() })
            TvDialog.confirm(
                activity=this,
                title="清空屏保素材？",
                message="将删除已加入屏保的 ${own.size} 个素材（共 $total），删除后无法恢复。收藏、观看记录和快传文件不受影响。",
                confirmText="清空全部",
                danger=true,
                action={
                    // 删除和刷新必须在同一个后台任务里排队：先 io 删、删完再回主线程刷新，
                    // 否则主线程的刷新会抢在删除之前跑，标签永远停在「5 个」（实测踩到）
                    io.execute {
                        own.forEach { it.delete() }
                        main.post {
                            if(isDestroyed) return@post
                            library.screensaverMedia=""
                            saverDraft=null
                            refreshSaverMediaSetting?.invoke()
                            // 素材清光后「我的素材」没东西可放，自动退回经典墨夜，别让用户对着一行提示干瞪眼
                            if(saverMediaFiles().isEmpty()) {
                                library.screensaverStyle="classic"
                                refreshSaverStyleSetting?.invoke()
                                Toast.makeText(this,L10n.tr("屏保素材已清空，屏保画面已切回「经典墨夜」"),Toast.LENGTH_LONG).show()
                            } else Toast.makeText(this,L10n.tr("屏保素材已清空"),Toast.LENGTH_SHORT).show()
                            dialogRef?.dismiss()
                        }
                    }
                }
            ).show()
        }

        val content=column()
        // 标题行右侧的「关闭」：鼠标用户没有返回键，面板里必须有个点得到的出口
        val head=row()
        head.addView(TvDialog.title(this,"我的屏保素材").apply { setPadding(0,0,0,0) },lp(0,-2).apply { weight=1f })
        head.addView(TvDialog.capsuleButton(this,"关闭",compact=true,radiusDp=16) { dialogRef?.dismiss() })
        content.addView(head,lp(-1,-2).apply { bottomMargin=dp(8) })
        content.addView(text("确认键勾选 / 取消：勾几个就轮播几个。长按确认键 = 全屏只看这一张、不改设置（鼠标按住左键同样有效）。选好后按下方按钮轮播并预览，${previewSeconds} 秒自动返回",13f,muted).apply { setPadding(0,0,0,dp(14)) })

        var uploadCard: LinearLayout?=null
        var selectAllCard: LinearLayout?=null
        if(own.isNotEmpty() || transfers.isNotEmpty()) {
            val entries=row()
            uploadCard=entryCard("＋","用手机传新素材","图片 / 动图 / 视频 · 手机扫码上传") { startSaverUpload(anchor) }
            entries.addView(uploadCard,lp(0,-2).apply { weight=1f })
            if(own.isNotEmpty()) {
                val sub=when {
                    stored.isEmpty() || stored.size>=own.size -> "当前 · 全部 ${own.size} 个"
                    stored.size==1 -> "当前只播 1 个 · 点这里全选"
                    else -> "当前 ${stored.size}/${own.size} 个 · 点这里全选"
                }
                selectAllCard=entryCard("↻","轮播全部素材",sub) {
                    draft.clear(); draft.addAll(own.map { it.name })
                    repainters.forEach { it() }; syncFooter()
                    applySelection()
                }
                entries.addView(selectAllCard,lp(0,-2).apply { weight=1f; leftMargin=dp(12) })
            }
            content.addView(entries,lp(-1,-2).apply { bottomMargin=dp(16) })
        }

        scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false; setPadding(0,dp(4),0,dp(2)) }
        // 底部留出余量：一屏只放得下一行，滚到第二行时如果没有这段余量，最大滚动距离不够，
        // 第二行顶不齐、上面会挂着第一行被切一半的「784 KB」（实测）
        val gridBox=column().apply { setPadding(0,0,0,dp(30)) }
        var line: LinearLayout?=null
        fun section(title: String) { gridBox.addView(text(title,12f,accent).apply { setPadding(dp(2),dp(2),0,dp(9)) }) }
        fun addCards(files: List<java.io.File>,fromTransfer: Boolean) {
            line=null
            files.forEachIndexed { i,f ->
                if(i%4==0) { line=row(); gridBox.addView(line!!,lp(-1,-2).apply { bottomMargin=dp(10) }) }
                line!!.addView(mediaCard(f,fromTransfer),lp(0,-2).apply {
                    weight=1f; if(i%4!=3) rightMargin=dp(10)
                })
            }
            // 最后一行不满 4 个：用等宽空位补齐，卡片宽度才和上面的行对齐
            repeat((4-files.size%4)%4) { line!!.addView(View(this),lp(0,1).apply { weight=1f }) }
        }
        // 一屏只放得下一行（4 张），多于 4 个时标题里点明「可滚动」，不然用户以为只有这 4 个
        if(own.isNotEmpty()) { section("已加入 · ${own.size} 个"+(if(own.size>4) " · 上下键滚动查看" else "")); addCards(own,false) }
        if(transfers.isNotEmpty()) { section("快传 · ${transfers.size} 个 · 点一下收进素材库"); addCards(transfers,true) }
        scroll.addView(gridBox,lp(-1,-2))
        content.addView(scroll,lp(-1,-2))

        val footer=row().apply { setPadding(0,dp(14),0,0) }
        countLabel=text("",12f,muted)
        footer.addView(countLabel,lp(0,-2).apply { weight=1f })
        var clearBtn: TextView?=null
        if(own.isNotEmpty()) {
            // 主操作：用选中的这几个轮播（顺便立刻预览，不满意再回来改勾选）
            applyBtn=text("",13f,TvStyle.background).apply {
                id=View.generateViewId()
                gravity=Gravity.CENTER; isFocusable=true; isFocusableInTouchMode=true; isClickable=true
                setPadding(dp(18),dp(9),dp(18),dp(9))
                background=rounded(accent)
                setOnFocusChangeListener { _,f ->
                    background=if(checkedCount()==0) rounded(Color.rgb(52,58,70))
                        else if(f) rounded(accent,Color.WHITE) else rounded(accent)
                }
                setOnClickListener { applySelection() }
                rightClickCloses(this)
            }
            footer.addView(applyBtn,lp(-2,-2).apply { rightMargin=dp(10) })
            // 清空是不可逆操作：单独放右下角、红色描边，跟上面的素材卡明显不是一类东西
            val red=Color.rgb(232,86,86)
            clearBtn=text("清空全部素材",13f,red).apply {
                id=View.generateViewId()
                gravity=Gravity.CENTER; isFocusable=true; isFocusableInTouchMode=true; isClickable=true
                setPadding(dp(16),dp(9),dp(16),dp(9))
                background=rounded(Color.TRANSPARENT,Color.argb(150,232,86,86))
                setOnFocusChangeListener { _,f ->
                    background=rounded(if(f) red else Color.TRANSPARENT,red)
                    setTextColor(if(f) Color.WHITE else red)
                }
                setOnClickListener { clearAll() }
                // 右键落在「清空」上应当等于关面板，而不是被当成左键弹出清空确认框
                rightClickCloses(this)
            }
            footer.addView(clearBtn,lp(-2,-2))
        }
        content.addView(footer)
        syncFooter()

        // 手写四向焦点链：网格是「行内 weight + ScrollView」拼的，FocusFinder 在这个结构下
        // 从入口卡按下键会直接搜不到候选、焦点凭空丢失（实测踩到）。按网格坐标显式连：
        // 上=上一行同列（首行回入口卡）、下=下一行同列（末行回底部按钮）、左右=同行相邻。
        run {
            val ids=allCards.map { it.id }
            allCards.forEachIndexed { i,c ->
                val col=i%4
                c.nextFocusLeftId=if(col==0) c.id else ids[i-1]
                c.nextFocusRightId=if(col==3 || i==ids.lastIndex) c.id else ids[i+1]
                c.nextFocusUpId=if(i-4>=0) ids[i-4] else uploadCard?.id ?: c.id
                c.nextFocusDownId=if(i+4<=ids.lastIndex) ids[i+4] else (applyBtn?.id ?: clearBtn?.id ?: c.id)
            }
            uploadCard?.let { c -> c.nextFocusDownId=ids.firstOrNull() ?: c.id; c.nextFocusRightId=selectAllCard?.id ?: c.id }
            selectAllCard?.let { c -> c.nextFocusLeftId=uploadCard?.id ?: c.id; c.nextFocusDownId=ids.firstOrNull() ?: c.id }
            applyBtn?.let { b ->
                b.nextFocusUpId=ids.lastOrNull() ?: b.id
                b.nextFocusLeftId=ids.lastOrNull() ?: b.id
                b.nextFocusRightId=clearBtn?.id ?: b.id
            }
            clearBtn?.let { b ->
                b.nextFocusUpId=ids.lastOrNull() ?: b.id
                b.nextFocusRightId=b.id
                b.nextFocusLeftId=applyBtn?.id ?: b.id
            }
        }

        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        dialogRef=dialog
        // 素材多时整块超出屏幕：styleWindow 会在 show 前把滚动区压到 80% 屏高，首帧就到位
        TvDialog.styleWindow(this,dialog,widthDp=880,capPx=(resources.displayMetrics.heightPixels*0.8f).toInt()) {
            // 面板可能是「长按预览完自动重开」的第二次实例，ScrollView 位置别沿用上次的余量，
            // 否则区块标题「已加入 · N 个」会挂在顶部被切一半（实测）
            scroll.scrollTo(0,0)
            // 开窗焦点给「用手机传新素材」：新用户第一次进来最需要的就是这个入口
            uploadCard?.requestFocus()
        }
        dialog.setOnDismissListener {
            runCatching { anchor.requestFocus() }
            // 只有真正关掉面板才丢草稿；收快传素材引起的重开要保留（saverDraftKeep）
            if(!saverDraftKeep) saverDraft=null
        }
        dialog.show()
    }
    /** 素材缩略图：图片按目标尺寸采样解码、动图/视频取首帧，全在 io 线程做完再回主线程贴图。 */
    private fun loadSaverThumb(file: java.io.File,w: Int,h: Int,into: ImageView,placeholder: View?) {
        io.execute {
            val bmp=runCatching {
                val video=(TransferRules.extensionOf(file.name) ?: "").uppercase(Locale.ROOT) in setOf("MP4","MKV","WEBM","MOV","TS","AVI")
                val raw=if(video) {
                    val r=android.media.MediaMetadataRetriever()
                    try { r.setDataSource(file.absolutePath); r.getFrameAtTime(0) } finally { runCatching { r.release() } }
                } else {
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(file.absolutePath,bounds)
                    if(bounds.outWidth<=0) null else {
                        var sample=1
                        while(bounds.outWidth/(sample*2)>=w && bounds.outHeight/(sample*2)>=h) sample*=2
                        BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample })
                    }
                }
                // 视频帧是原始分辨率（可能 4K），先缩到卡片量级再交给 ImageView，别把几十 MB 的位图留在内存里
                if(raw!=null && (raw.width>w*2 || raw.height>h*2)) {
                    Bitmap.createScaledBitmap(raw,(w*3/2).coerceAtLeast(1),(h*3/2).coerceAtLeast(1),true).also { raw.recycle() }
                } else raw
            }.getOrNull()
            main.post {
                if(isDestroyed) return@post
                if(bmp!=null) { into.setImageBitmap(bmp); placeholder?.visibility=View.GONE }
            }
        }
    }
    private fun startSaverUpload(anchor: View) {
        tvTools.quickTransfer(anchor,{ file,kind ->
            if(kind==TransferKind.IMAGE || kind==TransferKind.VIDEO) storeSaverMedia(file) { refreshSaverMediaSetting?.invoke() }
        })
    }
    /** 跑马灯样例卡的迷你预览：内置风格直接渲染实时背景，自定义展示素材数量。 */
    private fun saverPreview(key: String): View {
        val frame=FrameLayout(this)
        val clockLabel=text("12:34",16f,Color.WHITE).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER }
        when(key) {
            "neon" -> { frame.addView(NeonBackdrop(this),FrameLayout.LayoutParams(-1,-1)); frame.addView(clockLabel,FrameLayout.LayoutParams(-1,-1)) }
            "stars" -> { frame.addView(StarfieldView(this),FrameLayout.LayoutParams(-1,-1)); frame.addView(clockLabel,FrameLayout.LayoutParams(-1,-1)) }
            "breath" -> { frame.addView(BreathBackdrop(this),FrameLayout.LayoutParams(-1,-1)); frame.addView(clockLabel,FrameLayout.LayoutParams(-1,-1)) }
            "clockgold" -> frame.addView(AnalogClockView(this,true),FrameLayout.LayoutParams(-1,-1))
            "clockdawn" -> frame.addView(AnalogClockView(this,false),FrameLayout.LayoutParams(-1,-1))
            "custom" -> {
                frame.setBackgroundColor(Color.argb(255,16,20,30))
                val all=saverMediaFiles()
                val picked=saverPickedNames(all)
                val note=when {
                    all.isEmpty() -> "手机传自己的\n图片 / 动图 / 视频"
                    picked.isEmpty() -> "我的素材\n共 ${all.size} 个 · 全部轮播"
                    picked.size==1 -> "我的素材\n只播 ${shortName(picked[0],12)}"
                    else -> "我的素材\n轮播 ${picked.size}/${all.size} 个"
                }
                frame.addView(text(note,12f,muted).apply {
                    gravity=Gravity.CENTER; maxLines=3; ellipsize=TextUtils.TruncateAt.END
                    setPadding(dp(8),0,dp(8),0)
                },FrameLayout.LayoutParams(-1,-1))
            }
            else -> { frame.setBackgroundColor(Color.BLACK); frame.addView(clockLabel,FrameLayout.LayoutParams(-1,-1)) }
        }
        return frame
    }
    /** 屏保风格跑马灯：横向样例卡聚焦滚动居中，左右键浏览、确认键选用（用户 2026-11-19）。 */
    private fun showSaverStylePicker(anchor: View) {
        val content=column()
        content.addView(TvDialog.title(this,"选择屏保画面"))
        content.addView(text("左右键浏览，确认键选用。前六种是自带画面（含两款指针走动的仿真时钟）；「我的素材」放你自己传的图片和视频。",13f,muted).apply { setPadding(0,dp(4),0,dp(10)) })
        val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFillViewport=false }
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(8),dp(4)) }
        val index=ScreenSaverPrefs.STYLES.indexOfFirst { it.first==library.screensaverStyle }.coerceAtLeast(0)
        val cards=ScreenSaverPrefs.STYLES.map { (key,name) ->
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                isFocusable=true; isClickable=true
                setPadding(dp(10),dp(10),dp(10),dp(12))
                addView(saverPreview(key),LinearLayout.LayoutParams(dp(176),dp(92)).apply { bottomMargin=dp(8) })
                addView(text(name,14f,white))
                fun restyle(focused: Boolean) {
                    // 不做 1.05 放大：卡片在 HorizontalScrollView 里，放大后上下溢出会被滚动作裁掉，
                    // 聚焦那张的描边上下就是平切的（2026-11-19 用户截图指出）。靠描边加粗 + 提亮底色表达焦点。
                    background=GradientDrawable().apply {
                        cornerRadius=dp(12).toFloat(); setColor(if(focused) TvStyle.raised else Color.argb(255,22,26,38))
                        setStroke(dp(if(focused) 3 else 2),if(focused) accent else TvStyle.outline)
                    }
                }
                restyle(false)
                setOnFocusChangeListener { v,has ->
                    restyle(has)
                    if(has) post { val target=((v.left-(scroll.width-v.width)/2)).coerceAtLeast(0); scroll.smoothScrollTo(target,0) }
                }
                setOnClickListener {
                    library.screensaverStyle=key
                    refreshSaverStyleSetting?.invoke()
                    (it.tag as? AlertDialog)?.dismiss()
                    Toast.makeText(this@MainActivity, L10n.tr("屏保画面：$name"),Toast.LENGTH_SHORT).show()
                    // 选了「我的素材」却一个素材都没有：直接把上传入口摆出来，别让用户对着提示干瞪眼
                    if(key=="custom" && saverMediaFiles().isEmpty() && quickTransferMedia().isEmpty()) {
                        main.postDelayed({ if(!isDestroyed) showSaverMediaManager(anchor) },450)
                    }
                }
                tag=null
            }
            row.addView(card,LinearLayout.LayoutParams(dp(196),-2).apply { rightMargin=dp(10) })
            card
        }
        scroll.addView(row)
        content.addView(scroll)
        val picker=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        cards.forEach { it.tag=picker }
        TvDialog.styleWindow(this,picker,widthDp=780)
        picker.setOnDismissListener { runCatching { anchor.requestFocus() } }
        picker.show()
        picker.window?.decorView?.post { cards.getOrNull(index)?.requestFocus() }
    }

    // ---- 屏保内置风格背景（自绘 View：onDraw 末尾 postInvalidateDelayed 驱动，分离即自停） ----

    /** 霓虹都市：色相缓转的暗色渐变，时钟高亮可读。 */
    private class NeonBackdrop(ctx: Context): View(ctx) {
        private val paint=Paint()
        override fun onDraw(canvas: Canvas) {
            val hue=(System.currentTimeMillis()/1000f*10f)%360f
            val colors=intArrayOf(
                Color.HSVToColor(floatArrayOf(hue,0.72f,0.20f)),
                Color.HSVToColor(floatArrayOf((hue+110f)%360f,0.68f,0.30f)),
                Color.HSVToColor(floatArrayOf((hue+230f)%360f,0.62f,0.14f)),
                Color.HSVToColor(floatArrayOf(hue,0.72f,0.20f)))
            paint.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat()*1.25f,colors,
                floatArrayOf(0f,0.36f,0.72f,1f),android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            postInvalidateDelayed(66)
        }
    }
    /** 星河流光：星点匀速下坠 + 明暗闪烁。 */
    private class StarfieldView(ctx: Context): View(ctx) {
        private class Star(val x: Float,val y: Float,val r: Float,val speed: Float,val phase: Float)
        private var stars: List<Star>?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private fun init() {
            val rnd=java.util.Random(20261119L)
            stars=(1..110).map { Star(rnd.nextFloat(),rnd.nextFloat(),rnd.nextFloat()*2.0f+0.7f,
                rnd.nextFloat()*0.016f+0.004f,rnd.nextFloat()*6.283f) }
        }
        override fun onDraw(canvas: Canvas) {
            if(stars==null) init()
            val t=System.nanoTime()/1_000_000_000f
            val w=width.toFloat(); val h=height.toFloat(); val d=resources.displayMetrics.density
            for(s in stars!!) {
                val yy=((s.y+t*s.speed)%1f)*h
                val tw=(0.55f+0.45f*Math.sin((t*1.7+s.phase).toDouble()).toFloat())
                paint.color=Color.argb((238*tw).toInt().coerceIn(0,255),255,255,255)
                canvas.drawCircle(s.x*w,yy,s.r*d,paint)
            }
            postInvalidateDelayed(33)
        }
    }
    /** 晨雾呼吸：中央光晕缓慢胀缩。 */
    private class BreathBackdrop(ctx: Context): View(ctx) {
        private val paint=Paint()
        override fun onDraw(canvas: Canvas) {
            val pulse=0.5f+0.5f*Math.sin(System.nanoTime()/1_000_000_000f*0.9).toFloat()
            val w=width.toFloat(); val h=height.toFloat()
            val radius=Math.max(w,h)*(0.34f+0.26f*pulse)
            paint.shader=RadialGradient(w/2f,h/2f,radius,
                intArrayOf(Color.argb((78+46*pulse).toInt(),52,90,132),Color.argb(30,26,58,96),Color.TRANSPARENT),
                floatArrayOf(0f,0.55f,1f),android.graphics.Shader.TileMode.CLAMP)
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(0f,0f,w,h,paint)
            postInvalidateDelayed(66)
        }
    }

    /** 仿真时钟（用户 2026-11-23）：指针实时走动的自绘表盘，整屏做底、表盘居中，日期画在表盘下方。
     *  两档配色：gold=墨金（暗底 + 金圈金针），否则晨白（晨光底 + 素白表盘 + 深色指针）。
     *  表盘本身就是时钟，这两档不再叠数字时钟角标（「时钟位置」设置对它们不生效）。 */
    private class AnalogClockView(ctx: Context,private val gold: Boolean): View(ctx) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val calendar=java.util.Calendar.getInstance()
        override fun onDraw(canvas: Canvas) {
            val w=width.toFloat(); val h=height.toFloat(); val d=resources.displayMetrics.density
            // 整屏底：中央微亮、四周收暗的径向渐变，避免大黑屏中间孤零零一个表盘的呆板
            paint.style=Paint.Style.FILL
            paint.shader=RadialGradient(w/2f,h/2f,Math.max(w,h)*0.72f,
                intArrayOf(if(gold) Color.argb(255,40,46,62) else Color.argb(255,70,80,108), if(gold) Color.BLACK else Color.argb(255,20,23,32)),
                floatArrayOf(0f,1f),android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint)
            paint.shader=null
            val cx=w/2f; val cy=h/2f
            val r=Math.min(w,h)*0.32f
            val goldLine=Color.argb(255,214,182,108)
            val goldBright=Color.argb(255,236,210,148)
            val darkInk=Color.argb(255,44,50,66)
            // 表盘面 + 外圈
            paint.color=if(gold) Color.argb(255,32,38,52) else Color.argb(255,242,238,229)
            canvas.drawCircle(cx,cy,r,paint)
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=3.5f*d
            paint.color=if(gold) goldLine else Color.argb(255,124,132,152)
            canvas.drawCircle(cx,cy,r,paint)
            paint.strokeWidth=1f*d
            paint.color=if(gold) Color.argb(150,214,182,108) else Color.argb(150,124,132,152)
            canvas.drawCircle(cx,cy,r*0.90f,paint)
            // 刻度：12 个主刻度粗而亮，分钟刻度细而淡
            for(i in 0 until 60) {
                val major=i%5==0
                val angle=Math.toRadians((i*6-90).toDouble())
                val sin=Math.sin(angle).toFloat(); val cos=Math.cos(angle).toFloat()
                paint.strokeWidth=(if(major) 3.5f else 1.2f)*d
                paint.color=when { gold&&major -> goldBright; gold -> Color.argb(130,180,160,110); major -> darkInk; else -> Color.argb(120,100,108,130) }
                val inner=r*(if(major) 0.74f else 0.82f); val outer=r*0.86f
                canvas.drawLine(cx+cos*inner,cy+sin*inner,cx+cos*outer,cy+sin*outer,paint)
            }
            // 数字 12 / 3 / 6 / 9
            paint.style=Paint.Style.FILL
            paint.textSize=r*0.16f
            paint.textAlign=Paint.Align.CENTER
            paint.typeface=Typeface.create("sans-serif-light",Typeface.BOLD)
            paint.color=if(gold) goldBright else darkInk
            val nr=r*0.60f
            val asc=paint.fontMetrics.ascent; val desc=paint.fontMetrics.descent
            fun vText(value: String,x: Float,y: Float)=canvas.drawText(value,x,y-(asc+desc)/2f,paint)
            vText("12",cx,cy-nr)
            vText("6",cx,cy+nr)
            paint.textAlign=Paint.Align.LEFT
            vText("3",cx+nr-paint.measureText("3")/2f,cy)
            paint.textAlign=Paint.Align.RIGHT
            vText("9",cx-nr+paint.measureText("9")/2f,cy)
            // 指针：时针 / 分针常亮，秒针细一档、配色做区分
            calendar.timeInMillis=System.currentTimeMillis()
            val hour=calendar.get(java.util.Calendar.HOUR)+calendar.get(java.util.Calendar.MINUTE)/60f
            val minute=calendar.get(java.util.Calendar.MINUTE)+calendar.get(java.util.Calendar.SECOND)/60f
            val second=calendar.get(java.util.Calendar.SECOND)+calendar.get(java.util.Calendar.MILLISECOND)/1000f
            fun hand(angleDeg: Float,length: Float,width: Float,color: Int) {
                val angle=Math.toRadians((angleDeg-90).toDouble())
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=width*d
                paint.strokeCap=Paint.Cap.ROUND
                paint.color=color
                canvas.drawLine(cx,cy,cx+Math.sin(angle).toFloat()*length,cy-Math.cos(angle).toFloat()*length,paint)
            }
            hand(hour*30f,r*0.48f,6f,if(gold) goldBright else darkInk)
            hand(minute*6f,r*0.70f,4f,if(gold) goldBright else darkInk)
            hand(second*6f,r*0.78f,1.6f,if(gold) Color.argb(255,220,128,104) else Color.argb(255,198,94,86))
            paint.style=Paint.Style.FILL
            paint.color=if(gold) goldBright else darkInk
            canvas.drawCircle(cx,cy,4f*d,paint)
            // 表盘下方：日期与星期，两档共用
            paint.textSize=r*0.11f
            paint.textAlign=Paint.Align.CENTER
            paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            paint.color=if(gold) Color.argb(210,222,198,140) else Color.argb(210,226,232,248)
            val week=when(calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> "周一"; java.util.Calendar.TUESDAY -> "周二"; java.util.Calendar.WEDNESDAY -> "周三"
                java.util.Calendar.THURSDAY -> "周四"; java.util.Calendar.FRIDAY -> "周五"; java.util.Calendar.SATURDAY -> "周六"
                else -> "周日"
            }
            canvas.drawText("${calendar.get(java.util.Calendar.MONTH)+1}月${calendar.get(java.util.Calendar.DAY_OF_MONTH)}日  ·  $week",cx,cy+r*1.22f,paint)
            // 250ms 一帧：秒针连续扫动不跳格，CPU 占用也远低于逐帧
            postInvalidateDelayed(250)
        }
    }

    /** 自定义媒体屏保：轮播勾选的图片/动图/视频，间隔与声音、填充方式、角标位置都跟随设置。 */
    private inner class ScreensaverCustom(private val layer: FrameLayout,private val only: java.io.File?=null) {
        private var player: MediaPlayer?=null
        private var mediaView: View?=null
        private var cornerView: View?=null
        private var index=0
        private var advance: Runnable?=null
        private var stopped=false
        /** 当前轮播池：勾选的素材（按磁盘顺序）；没勾或全被删时=全部素材。 */
        private var pool: List<java.io.File> = emptyList()
        /** 单选模式：只勾了一个 → 图常驻、动图/视频循环，不轮播。 */
        private var single=false
        private val isVideo={ f: java.io.File -> f.extension.lowercase(Locale.ROOT) in setOf("mp4","mkv","webm","mov","ts","avi") }
        private val isStatic={ f: java.io.File -> f.extension.lowercase(Locale.ROOT) in setOf("jpg","jpeg","png") }
        /** 轮播换图间隔：设置里的 10/30/60 秒（视频例外，播完才换）。 */
        private val intervalMs get() = library.screensaverInterval*1000L
        /** 图片缩放方式：fit 适应 / fill·tile 铺满裁切（动图不支持平铺，回落填充；静态图的平铺在 showStatic 单独做）
         *  / stretch 拉伸（无视比例强行铺满，用户 2026-11-25 加）。 */
        private val scaleType get() = when(library.screensaverFit) {
            "fill","tile" -> ImageView.ScaleType.CENTER_CROP
            "stretch" -> ImageView.ScaleType.FIT_XY
            else -> ImageView.ScaleType.FIT_CENTER
        }

        fun start() {
            val singleFile=only
            if(singleFile!=null && singleFile.exists()) {
                // 面板里长按预览单张：只放这一个，常驻/循环，跟正式屏保的单选行为一致
                pool=listOf(singleFile); single=true
                addCornerClock()
                playAt(pool,0)
                return
            }
            val all=saverMediaFiles()
            val picked=saverPickedNames(all)
            pool=if(picked.isEmpty()) all else all.filter { it.name in picked }
            if(pool.isEmpty()) pool=all
            if(pool.isEmpty()) { addClockOverlay(layer,Color.WHITE,muted,"尚未上传素材 · 到 设置 → 待机屏保 → 屏保素材 用手机快传上传"); return }
            // 池里只剩一个就没必要轮播了：图常驻、动图/视频循环，避免每 10 秒重载同一张图闪一下
            single=pool.size==1
            addCornerClock()
            playAt(pool,index)
        }
        /** 时钟：位置跟随设置（居中/右上/左下/右下/不显示），与内置风格共用 addClockOverlay。 */
        private fun addCornerClock() {
            cornerView=addClockOverlay(layer,Color.argb(235,255,255,255),Color.argb(190,255,255,255))
        }
        /** 换下一个媒体前先撤掉上一个的 view：否则 GIF/图片/视频互相叠加，角标时钟也会被盖住。 */
        private fun removeMedia() {
            mediaView?.let { runCatching { layer.removeView(it) } }
            mediaView=null
            runCatching { player?.stop() }; runCatching { player?.release() }
            player=null
        }
        private fun playAt(all: List<java.io.File>,position: Int) {
            if(stopped || isDestroyed) return
            val file=all.getOrNull(position%all.size) ?: return
            index=position%all.size
            cancelAdvance()
            removeMedia()
            when {
                isVideo(file) -> playVideo(file)
                // 单选静态图=壁纸常驻，不轮播；轮播模式按设定间隔换下一张
                isStatic(file) -> { showStatic(file); if(!single) scheduleAdvance(intervalMs) }
                // 单选动图循环播即可；轮播模式按设定间隔换下一个
                else -> { showAnimated(file); if(!single) scheduleAdvance(intervalMs) }
            }
            cornerView?.bringToFront()
        }
        private fun next() { if(!stopped) main.post { playAt(pool,index+1) } }
        private fun scheduleAdvance(delay: Long) {
            val r=Runnable { next() }
            advance=r; main.postDelayed(r,delay)
        }
        private fun cancelAdvance() { advance?.let { main.removeCallbacks(it) }; advance=null }
        private fun playVideo(file: java.io.File) {
            val tv=TextureView(this@MainActivity)
            mediaView=tv
            layer.addView(tv,FrameLayout.LayoutParams(-1,-1))
            tv.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture,width: Int,height: Int) {
                    try {
                        val p=MediaPlayer()
                        p.setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        p.setDataSource(file.absolutePath)
                        p.setSurface(android.view.Surface(st))
                        val volume=if(library.screensaverMuted) 0f else 1f
                        p.setVolume(volume,volume)
                        // 单选模式视频循环播（壁纸）；轮播模式播完切下一个
                        p.isLooping=single
                        p.setOnCompletionListener { releasePlayer(); if(!single) next() }
                        p.setOnErrorListener { _,_,_ -> releasePlayer(); next(); true }
                        // prepare 是异步的：start 必须等 onPrepared，提前调会报 -38（state 4 调 start）
                        p.setOnPreparedListener { mp ->
                            if(stopped) return@setOnPreparedListener
                            mp.start()
                            // 视频此前是恒定满屏拉伸：现在跟随「画面填充」设置做适应/填充/拉伸（平铺回落填充，用户 2026-11-25）
                            applyVideoTransform(tv,mp.videoWidth,mp.videoHeight)
                        }
                        player=p
                        p.prepareAsync()
                    } catch(_: Exception) { releasePlayer(); next() }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture,width: Int,height: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture)=true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        private fun releasePlayer() {
            runCatching { player?.stop() }; runCatching { player?.release() }
            player=null
        }
        /** 视频「画面填充」：fit 适应（居中留黑边）/ fill·tile 填充（铺满裁切）/ stretch 拉伸满屏（恒等映射）。
         *  TextureView 默认把整块 buffer 拉到视图大小，不设置时永远是拉伸，fit/fill 需要显式变换矩阵。 */
        private fun applyVideoTransform(tv: TextureView,vw: Int,vh: Int) {
            if(vw<=0 || vh<=0) return
            tv.post {
                if(stopped || isDestroyed) return@post
                val w=tv.width.toFloat(); val h=tv.height.toFloat()
                if(w<=0f || h<=0f) return@post
                val sx=w/vw; val sy=h/vh
                val m=android.graphics.Matrix()
                when(library.screensaverFit) {
                    "fit" -> { val s=minOf(sx,sy); m.setScale(s,s); m.postTranslate((w-vw*s)/2f,(h-vh*s)/2f) }
                    "fill","tile" -> { val s=maxOf(sx,sy); m.setScale(s,s); m.postTranslate((w-vw*s)/2f,(h-vh*s)/2f) }
                    else -> m.setScale(sx,sy)
                }
                tv.setTransform(m)
            }
        }
        /** 静态图：按屏幕宽高采样解码（io 线程），避免大图卡主线程。 */
        private fun showStatic(file: java.io.File) {
            io.execute {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(file.absolutePath,bounds)
                if(bounds.outWidth<=0) { main.post { next() }; return@execute }
                var sample=1
                val w=resources.displayMetrics.widthPixels; val h=resources.displayMetrics.heightPixels
                while(bounds.outWidth/(sample*2)>=w && bounds.outHeight/(sample*2)>=h) sample*=2
                val bitmap=runCatching { BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample }) }.getOrNull()
                main.post {
                    if(stopped || isDestroyed) return@post
                    if(bitmap==null) { next(); return@post }
                    val iv=ImageView(this@MainActivity)
                    if(library.screensaverFit=="tile") {
                        // 平铺（用户 2026-11-25）：图片按原尺寸重复铺满屏幕（壁纸语义），不走缩放
                        iv.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources,bitmap).apply { setTileModeXY(android.graphics.Shader.TileMode.REPEAT,android.graphics.Shader.TileMode.REPEAT) })
                    } else { iv.setImageBitmap(bitmap); iv.scaleType=this@ScreensaverCustom.scaleType }
                    mediaView=iv
                    layer.addView(iv,FrameLayout.LayoutParams(-1,-1))
                    // 解码是异步的：图加进来时已经压在角标时钟上面，必须再抬一次时钟，否则满幅壁纸会把时间完全盖住
                    cornerView?.bringToFront()
                }
            }
        }
        /** 动图（gif/动图webp）：API 28+ 用 ImageDecoder 原生动画；低版本 gif 回退 Movie。 */
        private fun showAnimated(file: java.io.File) {
            val ext=file.extension.lowercase(Locale.ROOT)
            if(Build.VERSION.SDK_INT>=28) {
                val drawable=runCatching {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                }.getOrNull()
                if(drawable!=null) {
                    val iv=ImageView(this@MainActivity).apply { setImageDrawable(drawable); scaleType=this@ScreensaverCustom.scaleType }
                    mediaView=iv
                    layer.addView(iv,FrameLayout.LayoutParams(-1,-1))
                    cornerView?.bringToFront()
                    if(drawable is AnimatedImageDrawable) drawable.start()
                    if(!single) scheduleAdvance(intervalMs)
                    return
                }
            }
            if(ext=="gif") {
                val movie=runCatching { file.inputStream().use { Movie.decodeStream(it) } }.getOrNull()
                if(movie!=null && movie.width()>0 && movie.height()>0) {
                    val view=object: View(this@MainActivity) {
                        override fun onDraw(canvas: Canvas) {
                            if(stopped) return
                            val w=width.toFloat(); val h=height.toFloat()
                            val sx=w/movie.width(); val sy=h/movie.height()
                            // 拉伸=横竖各按屏幕比例强拉（会变形）；填充/平铺=铺满裁切（取较大比例，动图不支持平铺回落填充）；
                            // 适应=整图可见（取较小比例）（用户 2026-11-25 加拉伸）
                            val scaleX: Float; val scaleY: Float
                            when(library.screensaverFit) {
                                "stretch" -> { scaleX=sx; scaleY=sy }
                                "fill","tile" -> { val s=Math.max(sx,sy); scaleX=s; scaleY=s }
                                else -> { val s=minOf(sx,sy); scaleX=s; scaleY=s }
                            }
                            val dx=(w-movie.width()*scaleX)/2f; val dy=(h-movie.height()*scaleY)/2f
                            movie.setTime(((System.currentTimeMillis()%movie.duration().coerceAtLeast(1)).toInt()))
                            canvas.save(); canvas.translate(dx,dy); canvas.scale(scaleX,scaleY)
                            movie.draw(canvas,0f,0f)
                            canvas.restore()
                            postInvalidateDelayed(33)
                        }
                    }
                    mediaView=view
                    layer.addView(view,FrameLayout.LayoutParams(-1,-1))
                    cornerView?.bringToFront()
                    if(!single) scheduleAdvance(intervalMs)
                    return
                }
            }
            // 解不动（异常动图/低版本动图webp）：取首帧当静态图
            showStatic(file); if(!single) scheduleAdvance(intervalMs)
        }
        fun stop() {
            stopped=true
            cancelAdvance()
            removeMedia()
        }
    }

    // ================= 手机遥控 =================

    /**
     * 屏幕常亮的唯一决策点。原先 add/clear 散在起播、requestPlayback、releasePlayer、
     * stopForSleep 和三方播放器里各写一遍，语义互相覆盖；遥控要求「电视暂停但手机上还有人连着」
     * 时依然常亮，两处各自开关会打架，所以先收敛成一个函数。
     */
    private fun refreshScreenOn() {
        if(remoteHolding || thirdWantsScreenOn || playerScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 三方播放器不再自己开关这个 flag，统一交给 [refreshScreenOn]。 */
    fun onThirdWantsScreenOn(on: Boolean) {
        if(thirdWantsScreenOn==on) return
        thirdWantsScreenOn=on; refreshScreenOn()
    }

    private fun openRemote(anchor: View?) {
        // 已经开过就从原会话再打开窗口：token 不变，手机上不用重新扫码
        remote?.let { syncRemoteEntry(); it.show(anchor); return }
        autoStartRemote()
        val session=remote
        if(session==null) { tvTools.info("手机遥控","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        session.show(anchor)
        syncRemoteEntry()
        remotePublish(true)
    }

    /** 网络未就绪时的静默重试次数上限；超过就等用户手动点「手机遥控」再报错。 */
    private var remoteStartTries=0

    /**
     * 永久遥控：应用一起来就把局域网服务挂上，不弹任何窗口。
     * 手机里存着旧地址的用户随时打开都能直连，电视端不用先开二维码窗。
     * onCreate 时网络可能还没就绪（DHCP 未完成），拿不到地址就隔几秒静默重试。
     */
    private fun autoStartRemote() {
        if(remote != null) return
        if(remoteStartTries >= 5) return
        remoteStartTries++
        val address=tvTools.lanAddress()
        if(address==null) { main.postDelayed({ if(!isDestroyed) autoStartRemote() }, 3000); return }
        runCatching {
            PhoneRemoteSession(this,address,{ remoteTarget() },remoteController,{ remoteResumeList() },{ remoteResumeAll() },
                cover={ artwork.bytes(it) },
                upload={ name,length,stream -> tvTools.receiveRemoteUpload(name,length,stream) },
                batchUpload={ items,stream -> tvTools.receiveRemoteBatchUpload(items,stream) },
                onClients={ syncRemoteEntry() }) {
                remote=null; syncRemoteEntry()
            }.also { session -> remote=session; remoteStartTries=0 }
        }.onFailure { android.util.Log.w("HongguoTV","autoStartRemote failed: ${it.message}") }
        syncRemoteEntry()
    }

    /**
     * 顶栏与详情页标题行共用的遥控入口按钮。样式跟「手机快传」「设置」一致，
     * 建立后立刻把当前会话状态刷上去，避免重建页面后文案回退成「手机遥控」。
     */
    private fun remoteEntryButton(): TextView {
        val view=button("手机遥控",false) { }
        view.setOnClickListener { openRemote(view) }
        view.textSize=13f; view.minHeight=dp(if(navTight) 36 else 38)
        val pad=dp(if(navTight) 10 else 12); view.setPadding(pad,dp(7),pad,dp(7))
        remoteEntryView=view
        syncRemoteEntry()
        return view
    }

    /**
     * 顶栏入口文案。三个状态要能一眼区分：
     * 没开会话就是纯入口；开了没人连就是「待连接」；连上了写台数。
     * 一律不显示 IP：服务是应用启动就静默挂着的，绝大多数时间没人连，
     * 顶栏常驻一串局域网地址既没用又占地方，要看地址点开弹窗，二维码下方写着。
     * 顶栏空间吃紧（navTight）时再压成两个字。
     */
    private fun syncRemoteEntry() {
        val view=remoteEntryView ?: return
        val session=remote
        val clients=session?.hub?.clientCount ?: 0
        val short=navTight
        // 顶栏键位文案是随会话状态变的，必须每次 setText 都过一遍字典（构建时翻不到）
        view.text=L10n.tr(when {
            session==null -> "手机遥控"
            // 现在允许多台手机同时控制，写出台数比笼统的「已连接」更有用
            clients>1 -> if(short) "遥控中 · ${clients} 台" else "遥控中 · ${clients} 台设备"
            clients==1 -> if(short) "遥控中" else "遥控中 · 手机已连接"
            else -> if(short) "遥控" else "遥控 · 待连接"
        })
    }

    private fun closeRemote() {
        val session=remote ?: return
        remote=null
        session.close()
        remoteHolding=false; refreshScreenOn()
        syncRemoteEntry()
    }

    /** 主线程调用。快照只在这里采集，socket 线程只读，绝不跨线程碰 ExoPlayer。 */
    private fun remotePublish(force: Boolean=false) {
        val session=remote ?: return
        val now=android.os.SystemClock.elapsedRealtime()
        if(!force && now-remotePublishedAt<500) return
        remotePublishedAt=now
        // 缓冲中拖进度条时 seekTo 会静默失败：先存下来，READY 之后再补一次
        remotePendingSeek?.let { target ->
            if(screen=="player" && playbackReady) { remotePendingSeek=null; seekTo(target) }
        }
        // 电视一直停在前台时 onResume 不会再来，缓存的意图不能只靠它消费：
        // 每次发布顺带试一次，等到播放器真的出现（比如 TV 上手动开播）就补上。
        val pending=session.hub.peekPending()
        if(!remoteApplying && pending!=null && (!pending.isPlayback || remoteTarget()!=null)) {
            session.hub.takePending()
            remoteApplying=true
            runCatching { if(pending.isPlayback) remoteTarget()?.apply(pending) else remoteController.navigate(pending) }
            remoteApplying=false
        }
        session.hub.snapshot=remoteSnapshotNow()
    }

    /** 官方与三方共用一份快照结构，手机端不需要知道自己在控哪种播放器。 */
    private fun remoteSnapshotNow(): PlaybackSnapshot {
        val current=when {
            tab==6 -> third?.remoteSnapshot()
            screen=="player" -> officialSnapshot()
            else -> null
        }
        return (current ?: PlaybackSnapshot()).copy(
            foreground=foreground,
            screen=if(tab==6) "third" else screen
        )
    }

    private fun officialSnapshot(): PlaybackSnapshot? {
        val data=detail ?: return null
        val p=player
        return PlaybackSnapshot(
            source=PlaybackSource.OFFICIAL,
            title=data.series.title,
            groups=listOf(EpisodeGroup("正片",data.episodes.size)),
            group=0, episode=episodeIndex,
            positionMs=currentPosition(),
            durationMs=p?.duration?.coerceAtLeast(0) ?: 0,
            playing=p?.playWhenReady==true && !pausedForLifecycle,
            buffering=p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING,
            speed=effectiveSpeed(),
            muted=(p?.volume ?: 1f)<=0f,
            danmaku=library.danmaku && extras.danmakuEnabled(data.series.id,true)!=false,
            danmakuAvailable=library.danmaku,
            error=if(playError) "播放出错，可在电视上重试或换集" else null,
            screen="player"
        )
    }

    private fun remoteTarget(): RemoteTarget? {
        if(tab==6 && third?.remoteSnapshot()!=null) {
            return LambdaTarget(PlaybackSource.THIRD,{ cmd->third?.remoteApply(cmd)==true },{ third?.remoteEpisodes() ?: emptyList() })
        }
        if(screen=="player" && detail!=null) {
            return LambdaTarget(PlaybackSource.OFFICIAL,::applyOfficialCommand,::officialEpisodes)
        }
        return null
    }

    private fun applyOfficialCommand(cmd: RemoteCommand): Boolean {
        if(screen!="player") return false
        return when(cmd) {
            is RemoteCommand.Play -> { requestPlayback(cmd.on); true }
            is RemoteCommand.Seek -> {
                if(!playbackReady || player==null) { remotePendingSeek=cmd.toMs } else seekTo(cmd.toMs)
                true
            }
            is RemoteCommand.Step -> {
                // 缓冲中 seekTo 会被丢掉，和拖动一样先记下来，READY 后补一次
                val to=(currentPosition()+cmd.byMs).coerceAtLeast(0)
                if(!playbackReady || player==null) remotePendingSeek=to else seekTo(to)
                true
            }
            is RemoteCommand.Episode -> {
                val count=detail?.episodes?.size ?: return false
                if(cmd.index !in 0 until count) return false
                playEpisode(cmd.index,savedPosition(cmd.index)); true
            }
            is RemoteCommand.Speed -> {
                // 手机上调倍速同样只对当前这部剧生效，不写回全局默认
                sessionSpeed=cmd.value; player?.setPlaybackSpeed(cmd.value)
                showHud(); updatePlaybackText(); true
            }
            is RemoteCommand.Muted -> { player?.volume=if(cmd.on) 0f else 1f; updatePlaybackText(); true }
            is RemoteCommand.Danmaku -> {
                // 电视上是「切换」语义，但手机发的是「设成 X」。先比再切，避免已经开着时反被关掉。
                val id=detail?.series?.id ?: return false
                val current=library.danmaku && extras.danmakuEnabled(id,true)!=false
                if(current!=cmd.on) toggleSeriesDanmaku(id)
                true
            }
            else -> false
        }
    }

    /** 官方播放器是一维的，对遥控汇报成「只有一个正片组」，避免遥控代码里出现分支。 */
    private fun officialEpisodes(): List<EpisodeGroupDetail> {
        val data=detail ?: return emptyList()
        return listOf(EpisodeGroupDetail("正片",data.episodes.mapIndexed { index,_ -> EpisodeEntry(index,"第 ${index+1} 集") }))
    }

    /** 片源是独立模块：电视停在片源页时，「继续观看」列的是片源自己的观看记录。
     *  主站页则本机记录在前，登录后的红果云端记录补在后（同剧只留本机，云端角标「· 云端」）。 */
    private fun remoteResumeList(): List<ResumeItem> {
        // 首屏与电视「接着看」一栏对齐（8 个）：云端与更多记录收进手机端「更多」弹层
        if(tab==6) return thirdStore.history().filter { !it.completed && it.durationMs>0 }.take(12).map {
            ResumeItem(it.sourceApi+"|"+it.videoId,it.title,
                it.episodeLabel.ifBlank { "第 ${it.episodeIndex+1} 集" }+" · "+formatTime(it.positionMs),
                it.positionMs,it.durationMs,it.cover)
        }
        // watched = 整剧已看完（末集播完自动标，或长按手动标）：继续观看不该再翻出来，
        // 只在电视「我的 - 观看历史」里留着
        return library.history().filter { it.duration>0 && !it.completed && !library.watched(it.series.id) }.take(8).map {
            ResumeItem(it.series.id,it.series.title,"第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}",it.position,it.duration,it.series.cover)
        }
    }

    /** 「更多」弹层的全量记录：本机全部在前、云端补后（同剧去重），下滑分批由手机端渲染。 */
    private fun remoteResumeAll(): List<ResumeItem> {
        if(tab==6) return thirdStore.history().filter { !it.completed && it.durationMs>0 }.map {
            ResumeItem(it.sourceApi+"|"+it.videoId,it.title,
                it.episodeLabel.ifBlank { "第 ${it.episodeIndex+1} 集" }+" · "+formatTime(it.positionMs),
                it.positionMs,it.durationMs,it.cover)
        }
        val local=library.history().filter { it.duration>0 && !it.completed && !library.watched(it.series.id) }
        val localIds=local.map { it.series.id }.toSet()
        val cloud=if(!accountManager.signedIn) emptyList() else {
            // 云端历史没拉过就顺手踢一次（异步、只踢一次），下次打开「更多」就有
            if(!accountManager.cloudHistoryLoaded && !remoteCloudKicked) {
                remoteCloudKicked=true; accountManager.refreshHistory()
            }
            // 本机已标看完的剧，别让云端记录又把它补回「继续观看」
            accountManager.cloudRows().filter { it.series.id !in localIds && !library.watched(it.series.id) }
        }
        return local.map {
            ResumeItem(it.series.id,it.series.title,"第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}",it.position,it.duration,it.series.cover)
        }+cloud.map {
            // id 加 cloud: 前缀：续播时据此走云端进度，不与本机记录的 seriesId 混淆；
            // 手机页面也靠这个前缀在海报上挂「云端」角标
            ResumeItem("cloud:"+it.series.id,it.series.title,
                "第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}",it.position,it.duration,it.series.cover)
        }
    }

    private fun remoteResumePlay(id: String): Boolean {
        if(tab==6) {
            val entry=thirdStore.history().firstOrNull { it.sourceApi+"|"+it.videoId==id } ?: return false
            return third?.remoteResume(entry)==true
        }
        if(id.startsWith("cloud:")) {
            // 云端续播：复用最近观看页云端卡片同一条 openDetail(cloud=) 路径，
            // episodeId 对不上时自动退回集号，且不写本机记录
            val row=accountManager.cloudRows().firstOrNull { "cloud:"+it.series.id==id } ?: return false
            openDetail(row.series,resume=true,cloud=row)
            return true
        }
        val progress=library.history().firstOrNull { it.series.id==id } ?: return false
        openDetail(progress.series,resume=true)
        return true
    }

    /**
     * 遥控地址（含门禁 token）常驻入口。
     *
     * 手机扫码窗口一旦有手机连上就会自动关掉，想抄地址去配自动化就没地方看；
     * 这里固定展示一次，并列出外部可直接用的命令，省得去翻源码或抓包。
     */
    private fun proxyLabel()="网络代理  ·  ${AppProxy.current().display}"

    /**
     * 代理帮助：用户大多不熟悉 Clash 的局域网代理配置，把文档要点浓缩成三段——
     * 电脑端怎么开、电视端怎么填、连不上时按什么顺序排查。在设置对话框点「帮助」弹出。
     */
    private fun proxyHelpText()="""
        什么时候需要开：部分片源域名被运营商上游 DNS 污染或 SNI 阻断（换 DNS、DoH 均无效），开启代理后全部网络流量走局域网代理即可恢复访问。快传 / 遥控 / WebDAV 等局域网功能始终直连，不受影响。

        一、电脑端准备（以 Clash / mihomo 为例）
        1. 打开「允许局域网连接 / Allow LAN」
        2. 记下代理端口（Clash 默认混合端口 7890，其他软件以实际为准）
        3. Windows 防火墙首次弹窗选「允许访问」；电视一直连不上时，在防火墙高级设置里给代理软件加一条入站规则
        4. 查电脑 IP：命令行运行 ipconfig，看 IPv4 地址（192.168.x.x）

        二、电视端填写
        1. 填电脑 IP 和端口；遥控输入不便就点「扫码填写」，用手机回填
        2. 点「测试连接」，提示连通成功后「启用并保存」
        3. 不想用了回来点「停用」即可

        三、常见问题
        · 测试连接失败：按顺序排查——电脑代理已启动、端口与这里填的一致、电视和电脑在同一网络、防火墙已放行
        · 用 FlClash：软件里的代理开关必须打开，否则核心会静默拒绝所有代理流量（无任何报错）
        · 电脑改过端口：电视这里要同步改
        · 代理正常但某源仍打不开：多半是该源域名本身失效，与代理无关
        · 手机遥控页也能开关：对输入框说「打开网络代理」「关闭网络代理」「代理状态」
    """.trimIndent().replace(Regex("^[ ]+",RegexOption.MULTILINE),"")

    /**
     * 网络代理设置：部分片源域名被上游 DNS 污染或 SNI 阻断（换 DNS/DoH 无效），
     * 让全部流量走局域网 HTTP 代理是唯一应用侧出路。详见 [AppProxy]。
     */
    private fun showProxyDialog(anchor: View) {
        val current=AppProxy.current()
        val form=column().apply { setPadding(dp(8),dp(4),dp(8),dp(8)) }
        val hostEdit=EditText(this).apply {
            hint="代理地址，如 192.168.31.23"; textSize=15f; setTextColor(white); setHintTextColor(muted)
            isSingleLine=true; maxLines=1
            background=rounded(bg); setPadding(dp(10),dp(10),dp(10),dp(10))
            setText(current.host)
            setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
        }
        val portEdit=EditText(this).apply {
            hint="端口，如 7890"; textSize=15f; setTextColor(white); setHintTextColor(muted)
            isSingleLine=true; maxLines=1
            inputType=android.text.InputType.TYPE_CLASS_NUMBER
            background=rounded(bg); setPadding(dp(10),dp(10),dp(10),dp(10))
            if(current.port>0) setText(current.port.toString())
            setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
        }
        val errorText=text("",13f,accent).apply { setPadding(0,dp(6),0,0) }
        form.addView(text("部分片源域名被运营商上游 DNS 污染或 SNI 阻断（换 DNS / DoH 均无效），开启代理后全部网络流量走局域网代理，可恢复访问。局域网地址（快传 / 遥控 / WebDAV）始终直连。",12f,muted).apply { setPadding(0,0,0,dp(10)) })
        form.addView(text("代理地址",13f,muted)); form.addView(hostEdit)
        form.addView(text("端口",13f,muted).apply { setPadding(0,dp(8),0,0) }); form.addView(portEdit)
        form.addView(errorText)
        form.addView(text("电脑端以 Clash 为例：开启「允许局域网连接 Allow LAN」，这里填电脑的局域网 IP 和混合代理端口。",12f,muted).apply { setPadding(0,dp(6),0,0) })
        val actions=row().apply { setPadding(0,dp(12),0,0) }
        lateinit var dialog: AlertDialog
        val enable=TvDialog.capsuleButton(this,"启用并保存",compact=true) { }
        val test=TvDialog.capsuleButton(this,"测试连接",compact=true) { }
        val scan=TvDialog.capsuleButton(this,"扫码填写",compact=true) { }
        lateinit var help: Button
        help=TvDialog.capsuleButton(this,"帮助",compact=true) { tvTools.info("网络代理 · 帮助",proxyHelpText(),help) }
        val disable=TvDialog.capsuleButton(this,"停用",compact=true) { }
        val cancel=TvDialog.capsuleButton(this,"取消",compact=true) { dialog.dismiss() }
        // 六个按钮用 compact 胶囊（13sp/内边距12）一行放下；常规尺寸会裁掉末尾按钮
        listOf(enable to 8,test to 8,scan to 8,help to 8,disable to 8,cancel to 0).forEach { (view,margin) ->
            actions.addView(view,lp(-2,-2).apply { rightMargin=dp(margin) })
        }
        form.addView(actions)
        val content=column()
        content.addView(TvDialog.title(this,"网络代理"))
        content.addView(ScrollView(this).apply { addView(form) },lp(-1,0).apply { weight=1f })
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)

        fun readConfig(enabled: Boolean): AppProxy.Config {
            val host=hostEdit.text.toString().trim()
            val port=portEdit.text.toString().trim().toIntOrNull() ?: 0
            return AppProxy.Config(enabled,host,port)
        }
        fun persist(cfg: AppProxy.Config) {
            AppProxy.save(this,cfg)
            anchor.setValueLabel(proxyLabel())
            dialog.dismiss()
            Toast.makeText(this@MainActivity, L10n.tr(if(cfg.enabled) "网络代理已启用 · ${cfg.host}:${cfg.port}" else "网络代理已停用"),Toast.LENGTH_SHORT).show()
        }
        enable.setOnClickListener {
            val cfg=readConfig(true)
            when {
                cfg.host.isEmpty() -> errorText.text=L10n.tr("请填写代理地址")
                cfg.port !in 1..65535 -> errorText.text=L10n.tr("端口需为 1-65535 的数字")
                else -> persist(cfg)
            }
        }
        disable.setOnClickListener { persist(readConfig(false)) }
        test.setOnClickListener {
            val cfg=readConfig(true)
            if(cfg.host.isEmpty() || cfg.port !in 1..65535) { errorText.text=L10n.tr("请先填写有效的地址与端口"); return@setOnClickListener }
            errorText.text=L10n.tr("测试中…"); test.isFocusable=false; test.isEnabled=false
            io.execute {
                val started=System.currentTimeMillis()
                val result=runCatching {
                    val proxy=java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress(cfg.host,cfg.port))
                    val client=okhttp3.OkHttpClient.Builder()
                        .proxy(proxy)
                        .connectTimeout(5,java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5,java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    // 显式 .proxy() 绕开全局 selector，确保测的就是用户填的那个代理
                    client.newCall(okhttp3.Request.Builder().url("https://www.baidu.com/").head().build()).execute().use { it.code }
                }
                main.post {
                    test.isFocusable=true; test.isEnabled=true
                    result.onSuccess { code -> errorText.text="连通成功 · ${System.currentTimeMillis()-started} ms（HTTP $code）" }
                        .onFailure { errorText.text="连接失败：${it.message?.take(80) ?: "未知错误"}" }
                }
            }
        }
        scan.setOnClickListener {
            // 电视键盘输入 IP/端口太费劲，走手机表单回填（与片源导入同一通道）
            tvTools.phoneForm(enable,PhoneForm(
                heading="填写网络代理",
                description="填写局域网内代理服务的 IP 与端口（如电脑 Clash 的局域网 IP 和 7890），提交后自动回填到电视。",
                fields=listOf(
                    PhoneField("host","代理地址","例如：192.168.31.23",60),
                    PhoneField("port","端口","例如：7890",5)),
                doneHint="请回到电视点「启用并保存」。",
                invalidMessage="请填写有效的地址与端口"
            )) { values ->
                val host=values["host"].orEmpty().trim()
                val port=values["port"].orEmpty().trim()
                if(host.isNotEmpty() && port.toIntOrNull() in 1..65535) { hostEdit.setText(host); portEdit.setText(port); enable.requestFocus() }
                else errorText.text=L10n.tr("请填写有效的地址与端口")
            }
        }
        dialog.show()
        // 与片源导入对话框同一规则：不自动聚焦输入框，避免弹软键盘挡住操作按钮
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        enable.requestFocus()
    }

    private fun showRemoteAddress(anchor: View) {
        autoStartRemote()
        val session=remote ?: run {
            tvTools.info("遥控地址","电视还没拿到局域网地址，请确认已连 Wi-Fi 或有线网络后重试。",anchor)
            return
        }
        val base=session.hub.url
        val message=buildString {
            appendLine("同一局域网里的脚本、Home Assistant、语音桥都可以直接调。")
            appendLine()
            appendLine("① 地址由「IP + 端口 + 门禁 token」拼成：")
            appendLine(base)
            appendLine("换网络只有 IP 会变，token 不变。")
            appendLine()
            appendLine("② 在地址末尾加 /cmd，用 POST 发一段 JSON：")
            appendLine("""{"cmd":"open","module":"source"}   跳到片源页""")
            appendLine("""{"cmd":"watch","query":"黄石"}   搜出来摆好，自己点""")
            appendLine("""{"cmd":"watch","query":"文盲修仙","episode":7}   直接看第 7 集""")
            appendLine("""{"cmd":"say","text":"快进30秒"}   整句话交给电视理解""")
            appendLine()
            appendLine("③ 完整示例（bash / Git Bash 可直接跑）：")
            appendLine("curl -X POST \"$base/cmd\" \\")
            appendLine("  -H \"Content-Type: application/json\" \\")
            appendLine("""  -d '{"cmd":"say","text":"给我随机推荐一部漫剧"}'""")
            appendLine()
            appendLine("④ say 能听懂的说法（节选）：")
            appendLine("剧名 / 文盲修仙第七集 / 快进30秒 / 快退一分钟 / 返回上一级")
            appendLine("1.5倍速 / 静音 / 声音大一点 / 半小时后关掉")
            appendLine("随机推荐一部漫剧 / 切换短剧 / 登录红果账户 / 检查更新")
            appendLine("打开网络代理 / 关闭网络代理 / 代理状态")
            appendLine()
            append("module 可选：home / search / rank / favorite / history / settings / source / library")
        }
        tvTools.info("遥控地址",message,anchor)
    }

    /** 一级模块键 → 页签下标。索引语义见顶部导航构建处的注释。 */
    private fun remoteTabOf(module: String): Int? = when(module) {
        "home" -> 0
        "search" -> 1
        "rank" -> 2
        "favorite" -> 3
        "history" -> 4
        "settings" -> 5
        "source" -> 6
        "library" -> 7
        else -> null
    }

    /**
     * 语音/自动化「跳到某个模块」。
     *
     * 正在播的那部必须先停：只切页面不停播放器，会出现画面走了声音还在后台放。
     * 模块被用户在设置里隐藏时直接返回 false，不要把焦点送进一个不可见的页签。
     */
    /**
     * 语音与外部指令的动作分发（`act` 命令）。
     *
     * 每个分支都等价于「用户在界面上点了那一项」，区别只是触发源换成了嘴。
     * 返回 false 表示**当前状态做不了**（没在播放却说「下一集」），上层据此提示，
     * 不假装成功——静默失败比多问一句更让人困惑。
     */
    private fun remoteAct(cmd: RemoteCommand.Act): Boolean {
        val anchor=voiceAnchor
        return when(cmd.id) {
            "theme.toggle" -> {
                library.theme=TvStyle.toggle().name
                voiceSay("主题 · ${TvStyle.theme.label}")
                recreateKeepingPage("系统风格")
                true
            }
            "theme.set" -> {
                val target=if(cmd.arg=="light") TvStyle.Theme.PORCELAIN else TvStyle.Theme.NIGHT
                if(TvStyle.theme==target) { voiceSay("已经是 ${target.label}"); return true }
                library.theme=target.name
                voiceSay("主题 · ${target.label}")
                recreateKeepingPage("系统风格")
                true
            }
            "clock.toggle" -> {
                library.showClock=!library.showClock
                applyClock()
                voiceSay(if(library.showClock) "已显示系统时间" else "已隐藏系统时间")
                true
            }
            "netspeed.toggle" -> {
                library.netSpeed=!library.netSpeed
                // 所有界面常显（含播放页），挂在 root 上随页面重建自动恢复
                applyNetSpeedOverlay(show=library.netSpeed)
                voiceSay(if(library.netSpeed) "网速显示已打开" else "网速显示已关闭")
                true
            }
            // 显式开 / 关：说「打开网速」时本来开着，就该保持开着，而不是被切成关
            "netspeed.set" -> {
                val on=cmd.arg=="on"
                if(library.netSpeed==on) { voiceSay(if(on) "网速显示本来就是开着的" else "网速显示本来就是关着的"); return true }
                library.netSpeed=on
                applyNetSpeedOverlay(show=on)
                voiceSay(if(on) "网速显示已打开" else "网速显示已关闭")
                true
            }
            "backdrop.reset" -> {
                resetBackdrop("plain")
                refreshBackdropSetting?.invoke()
                voiceSay("已恢复默认背景")
                true
            }
            // 预览浮层里的「设为电视背景」按钮，语音一句话直接对最近一张快传图片生效
            "backdrop.set" -> {
                tvTools.recentQuickFile(TransferKind.IMAGE) { hit ->
                    if(hit==null) voiceSay("还没有快传图片，先用手机快传传一张")
                    else { setQuickBackdrop(hit.file); voiceSay("已把 ${hit.name} 设为电视背景") }
                }
                true
            }
            // 上传整批接收 / 安装 APK / 设为背景这类确认框：没有确认按钮时不执行
            "dialog.confirm" -> if(TvDialog.confirmTopmost()) { voiceSay("已确认"); true } else false
            // 只关弹窗，不代替「返回」：没有弹窗时什么都不做，免得一句话把人从当前页退走。
            "dialog.close" -> {
                val dialog=TvDialog.topmostDialog() ?: return false
                dialog.dismiss()
                voiceSay("已关闭弹窗")
                true
            }
            "autonext.toggle" -> {
                library.autoNext=!library.autoNext
                voiceSay(if(library.autoNext) "自动连播已开启" else "自动连播已关闭")
                true
            }
            "autoseason.toggle" -> {
                library.autoSeason=!library.autoSeason
                voiceSay(if(library.autoSeason) "系列剧连播已开启" else "系列剧连播已关闭")
                true
            }
            "autoskip.toggle" -> {
                extras.autoSkipUnplayable=!extras.autoSkipUnplayable
                voiceSay(if(extras.autoSkipUnplayable) "自动跳过已开启" else "自动跳过已关闭")
                true
            }
            // 播放中说「关弹幕」多半是嫌当前这一部挡画面，而不是想改全局开关：
            // 有播放器就只作用于这一部，没播放器才是设置页里的总开关。
            "danmaku.toggle" -> {
                if(screen=="player") {
                    val id=detail?.series?.id ?: return false
                    toggleSeriesDanmaku(id); true
                } else {
                    library.danmaku=!library.danmaku
                    voiceSay(if(library.danmaku) "弹幕已开启" else "弹幕已关闭")
                    true
                }
            }
            "play.toggle" -> if(screen=="player") { togglePlayback(); true } else false
            "play.next" -> skipEpisode(1)
            "play.prev" -> skipEpisode(-1)
            // 从头播放：只影响当前这一集，不改全局「续播」记忆
            "play.replay" -> if(screen=="player") { seekTo(0); requestPlayback(true); true } else false
            "player.close" -> when {
                screen=="player" -> { returnToDetail(); true }
                tab==6 -> third?.onBack()==true
                else -> false
            }
            "panel.episodes" -> when {
                screen=="player" -> { showEpisodePanel(hud); true }
                tab==6 -> third?.remoteMenu()==true
                else -> false
            }
            "skip.intro" -> {
                if(screen!="player") return false
                val minutes=detail?.series?.id?.let { library.skipMark(it)?.introSeconds } ?: 0
                if(minutes<=0) { voiceSay("这部剧还没设片头"); return true }
                seekTo(minutes*1000L)
                voiceSay("已跳过片头 ${minutes} 秒")
                true
            }
            "speed.up" -> adjustSpeed(1)
            "speed.down" -> adjustSpeed(-1)
            "sleep.set" -> if(cmd.arg=="off") {
                sleepTimer.cancel()
                voiceSay("已取消定时停止")
                updatePlaybackText()
                true
            } else sleepTarget(cmd.arg)?.let { (kind,value) ->
                // 界面上的定时只有 1..180 分钟 / 1..10 集，越界会被 SleepTimer 直接拒掉
                if(kind=="min") sleepTimer.afterMinutes(value.coerceIn(1,180))
                else sleepTimer.afterEpisodes(value.coerceIn(1,10))
                voiceSay("定时 · ${sleepTimer.label()}")
                updatePlaybackText()
                true
            } ?: false
            // 播放快捷面板：清晰度/倍速/弹幕/更多四列，只在播放页有意义
            "panel.quick" -> if(screen=="player") { showQuickPanel(); voiceSay("播放快捷面板"); true } else false
            "volume.up" -> adjustVolume(1)
            "volume.down" -> adjustVolume(-1)
            "volume.mute" -> adjustMute(cmd.arg)
            "type.set" -> {
                val type=ContentType.fromStored(cmd.arg)
                switchContentType(type)
                voiceSay("已切到${type.label}")
                true
            }
            "random.pick" -> remoteRandomPick(cmd.arg)
            // 自由刷剧：已在 feed 里就只回执不再重进（enterFreeFeed 自己有防重入，但回执要区分）
            "feed.open" -> if(screen=="player" && recommendMode) { voiceSay("已经在自由刷剧了"); true } else { enterFreeFeed(); true }
            // 屏保预览：立即盖屏看一眼，8 秒自动退出；播放页不响应（会盖住正在看的画面）
            "saver.preview" -> if(screen=="player") false else { previewScreensaver(); true }
            "resume.latest" -> remoteResumeList().firstOrNull()?.let { remoteResumePlay(it.id) } ?: false
            "list.play" -> listTarget(cmd.arg)?.let { (kind,index) -> remoteListPlay(kind,index) } ?: false
            "favorite.toggle" -> {
                val series=detail?.series ?: return false
                val added=library.toggle(series)
                voiceSay(if(added) "已收藏" else "已取消收藏")
                refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates"))
                if(added) favoriteMonitor.check()
                true
            }
            // 主动查一次收藏更新：有新集时收藏页角标会亮，语音查完去收藏页就能看到
            "favorite.check" -> { favoriteMonitor.check(); if(accountManager.signedIn) accountManager.refreshFavorites(); voiceSay("正在检查收藏更新"); true }
            "update.check" -> { updater.checkNow(anchor); true }
            "check.interface" -> { showInterfaceCheck(anchor); true }
            "cache.clear" -> {
                io.execute {
                    listOfNotNull(cacheDir,externalCacheDir).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                    main.post { if(!isDestroyed) voiceSay("缓存已清理") }
                }
                true
            }
            "stats.show" -> { showWatchStats(anchor); true }
            "backup.show" -> { showLocalBackupDialog(anchor); true }
            "quick.send" -> {
                tvTools.quickTransfer(anchor,{ file,kind -> handleQuickTransfer(file,kind) },{},{ openQuickFiles(anchor) })
                true
            }
            // 「刷新二维码」在没开面板时就是打开面板：新码本来就要重新生成一次，
            // 两条路合并成一条，行为对人是同一个结果（屏幕上出现一张能用的新码）。
            "transfer.refresh" -> {
                if(tvTools.lanAddress()==null) { voiceSay("电视还没连上局域网，先连 Wi-Fi 再试"); return true }
                tvTools.quickTransfer(anchor,{ file,kind -> handleQuickTransfer(file,kind) },{},{ openQuickFiles(anchor) })
                voiceSay("二维码已刷新")
                true
            }
            "transfer.files" -> { openQuickFiles(anchor); true }
            // 全屏预览 / 播放最近收到的文件，与在「快传文件」列表里点一行完全同一条路径
            "transfer.preview" -> {
                tvTools.recentQuickFile(null) { hit ->
                    if(hit==null) voiceSay("还没有快传文件")
                    else { voiceSay("正在打开 ${hit.name}"); handleQuickTransfer(hit.file,hit.kind,true,anchor) }
                }
                true
            }
            "transfer.clear" -> {
                tvTools.clearQuickFiles { count,bytes ->
                    voiceSay(if(count<=0) "快传文件本来就是空的"
                        else "已清空 $count 个快传文件，腾出 ${TransferRules.humanBytes(bytes)}")
                }
                true
            }
            "history.clear" -> {
                // 主站与片源各一份历史，一句话两边一起清：人只记得「我搜过」，不记得在哪搜的。
                library.clearSearches()
                third?.remoteClearSearches()
                // 只有停在搜索页才重建：那是左栏历史真正可见的地方，别处重建会白跑一次请求
                if(tab==1) showCatalog()
                voiceSay("已清空搜索历史")
                true
            }
            "remote.address" -> { showRemoteAddress(anchor); true }
            "xiaoai.show" -> { showXiaoAiDialog(anchor); true }
            "account.show" -> { accountManager.showLoginDialog(this); true }
            "hidden.restore" -> {
                library.unhideAll()
                voiceSay("已恢复隐藏推荐")
                true
            }
            // 网络代理开关：arg = on | off（空=切换）。语音说「开代理」最常见的就是
            // 被拦截的片源打不开时；地址没配过开不了，提示去设置里配，不静默失败。
            "proxy.set", "proxy.status" -> {
                val cur=AppProxy.current()
                if(cmd.id=="proxy.status") {
                    voiceSay(when {
                        !cur.enabled -> "网络代理未启用"
                        cur.valid -> "网络代理已启用 · ${cur.host}:${cur.port}"
                        else -> "网络代理配置不完整"
                    })
                    return true
                }
                val target=when(cmd.arg) { "on" -> true; "off" -> false; else -> !cur.enabled }
                if(target==cur.enabled) { voiceSay(if(target) "代理本来就是开着的" else "代理本来就是关着的"); return true }
                if(target && !cur.valid) { voiceSay("还没配置代理地址，先到设置里填一次"); return true }
                AppProxy.save(this,cur.copy(enabled=target))
                voiceSay(if(target) "网络代理已开启 · ${cur.host}:${cur.port}" else "网络代理已关闭")
                true
            }
            else -> false
        }
    }

    /**
     * 「列表第 N 个」的落点。序号从 1 起，与口头表达一致（说「第二个」就取下标 1）。
     *
     * `history` 走 [remoteResumeList]，所以它自动跟着当前模块走：停在片源页就是片源的历史。
     * 越界一律返回 false，让人知道这句话没落实，而不是默默播了一个别的东西。
     */
    private fun remoteListPlay(kind: String,index: Int): Boolean {
        val position=index-1
        if(position<0) return false
        return when(kind) {
            "history" -> remoteResumeList().getOrNull(position)?.let { remoteResumePlay(it.id) } ?: false
            "favorite" -> {
                val series=library.favorites().getOrNull(position) ?: return false
                openDetail(series,resume=true)
                true
            }
            "episodes" -> {
                val count=detail?.episodes?.size ?: return false
                if(position>=count) return false
                playEpisode(position,savedPosition(position))
                true
            }
            else -> {
                val series=catalog.getOrNull(position) ?: return false
                openDetail(series,resume=true)
                true
            }
        }
    }

    /** 语音动作的回执：短提示，不打断画面，也不抢焦点。 */
    private fun voiceSay(message: String) {
        Toast.makeText(this@MainActivity, L10n.tr(message),Toast.LENGTH_SHORT).show()
    }

    /**
     * 一条命令落到界面。
     *
     * 遥控页输入框、小爱语音、局域网脚本三个入口共用这里：谁都不用再各自 when 一遍命令类型。
     * 播放类命令交给当前播放器适配器（官方/三方各一份），其余交给界面。
     */
    private fun remoteDo(cmd: RemoteCommand): Boolean = when(cmd) {
        is RemoteCommand.Act -> remoteAct(cmd)
        is RemoteCommand.Open -> remoteOpenModule(cmd.module)
        is RemoteCommand.Watch -> remoteWatch(cmd.query,cmd.episode)
        is RemoteCommand.Say -> remoteSay(cmd.text)
        else -> if(cmd.isPlayback) remoteTarget()?.apply(cmd)==true else remoteController.navigate(cmd)
    }

    /** 倍速加减一档。播放中说就只改当前这一部，别处改的是「默认倍速」。 */
    private fun adjustSpeed(direction: Int): Boolean {
        val options=PlaybackSpeed.options
        fun shift(current: Float): Float {
            val index=options.indexOfFirst { it>=current-0.001f }.let { if(it<0) options.lastIndex else it }
            return options[(index+direction).coerceIn(0,options.lastIndex)]
        }
        if(screen=="player") {
            val next=shift(effectiveSpeed())
            sessionSpeed=next; player?.setPlaybackSpeed(next)
            showHud(); updatePlaybackText()
            voiceSay("倍速 ${PlaybackSpeed.label(next)}")
            return true
        }
        val snap=if(tab==6) third?.remoteSnapshot() else null
        if(snap!=null) {
            val next=shift(snap.speed)
            third?.remoteApply(RemoteCommand.Speed(next))
            voiceSay("倍速 ${PlaybackSpeed.label(next)}")
            return true
        }
        val next=shift(library.playbackSpeed)
        library.playbackSpeed=next
        voiceSay("默认倍速 ${PlaybackSpeed.label(next)}")
        return true
    }

    /**
     * 音量走系统媒体音量，不走播放器内部音量：没在播放时也能调，
     * 而且调完退出去看别的 App 也是这个音量，符合「电视音量」的直觉。
     */
    private fun audioManager(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun adjustVolume(direction: Int): Boolean {
        val manager=audioManager()
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if(direction>0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI)
        val max=manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        voiceSay("音量 ${now*100/max}%")
        return true
    }

    /**
     * 「静音 / 取消静音」。
     *
     * 播放器在跑就走播放器内部静音：遥控快照里的 muted 字段会同步变化，
     * 手机端能看见「静音」亮起来，跟摇控器上按静音键的行为一致。
     * 没在播放才落到系统媒体音量——那时「静音」指的是整台电视。
     */
    private fun adjustMute(mode: String): Boolean {
        val wanted=when(mode) {
            "on" -> true
            "off" -> false
            else -> null
        }
        val snapshot=if(tab==6) third?.remoteSnapshot() else if(screen=="player") officialSnapshot() else null
        if(snapshot!=null && snapshot.controllable) {
            val target=wanted ?: !snapshot.muted
            if(remoteTarget()?.apply(RemoteCommand.Muted(target))!=true) return false
            voiceSay(if(target) "已静音" else "已取消静音")
            return true
        }
        val manager=audioManager()
        // isStreamMute 是 API 23 才有的；更低版本读不到静音状态，按「未静音」处理（mode 明确给了 on/off 时不影响结果）
        val muted=if(Build.VERSION.SDK_INT>=23)
            runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        else false
        val target=wanted ?: !muted
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if(target) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,0)
        voiceSay(if(target) "已静音" else "已取消静音")
        return true
    }

    /**
     * 随机推荐一部：从该内容类型的首页推荐里随机取一部，**打开详情页**而不是直接播。
     *
     * 推荐的意义是「看看有什么」，直接起播反而把人架在那儿；详情页有简介和选集，
     * 不合口味退回来再喊一次就行。也顺手对齐了「搜索只出结果」的原则。
     */
    private fun remoteRandomPick(arg: String): Boolean {
        val type=if(arg.isBlank()) library.contentType else ContentType.fromStored(arg)
        voiceSay("正在挑一部${type.label}…")
        io.execute {
            val items=runCatching { repository.home(1,type) }.getOrNull()?.items.orEmpty()
                .filterNot { it.id in library.hidden() }
            if(items.isEmpty()) {
                main.post { if(!isDestroyed) voiceSay("没取到${type.label}推荐，稍后再试") }
                return@execute
            }
            val pick=items.random()
            main.post {
                if(isDestroyed) return@post
                // 片源页盖在主界面上，先回首页再开详情，否则详情被片源视图挡住
                if(tab==6) remoteOpenModule("home")
                if(library.contentType!=type) {
                    library.contentType=type
                    for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
                }
                openDetail(pick,resume=false,autoplay=false)
                voiceSay("随机推荐《${pick.title}》")
            }
        }
        return true
    }

    private fun remoteOpenModule(module: String): Boolean {
        val target=remoteTabOf(module) ?: return false
        if(navHiddenAt(target)) return false
        TvDialog.topmostDialog()?.dismiss()
        if(screen=="player") {
            resetPlaybackSession(); sleepTimer.cancel(); sleepStopped=false
            saveProgress(); library.flush(); releasePlayer()
        }
        if(tab!=target) switchTab(target) else showCatalog(focusSegment=(target==3||target==4))
        // 我的模块（最近观看/收藏）焦点由 focusSegment 落分段按钮，不强推顶栏
        if(!(target==3||target==4)) nav.getOrNull(target)?.requestFocus()
        return true
    }

    /**
     * 遥控页输入框里那句话：先当语音指令理解，认不出动作就当剧名开播。
     *
     * 和 [runVoiceAction] 同源不同壳——那边是给小爱的，回执要带「小爱同学：」前缀；
     * 这里是人自己在遥控页上打的字，做没做成屏幕上本来就看得见（切了页/开始播了），
     * 只在**做不到**的时候补一句，免得多一层"手机关掉了没"的怀疑。
     */
    private fun remoteSay(text: String): Boolean {
        val plan=VoiceIntent.parse(text) ?: return remoteWatch(text)
        val ok=remoteDo(plan.command)
        // 做不到就补一句：否则屏幕上什么都没变，人只会怀疑手机掉线了
        if(!ok) Toast.makeText(this@MainActivity, L10n.tr("${plan.echo}：当前页面做不到"),Toast.LENGTH_SHORT).show()
        return ok
    }

    /**
     * 「按名字找剧」。
     *
     * 默认**只把搜索结果摆出来，不替用户挑**：结果里混着预告、空壳和同名不同内容，
     * 猜错一次的代价是「喊了 A 结果播了 B」，比让人多点一下烦得多。所以停在结果页，
     * 由人自己选——搜索页面本来就把「搜的是什么、有哪些结果」放在屏幕上。
     *
     * 三个例外才允许自动落到播放器：
     * - 嘴上已经说清集号（`文盲修仙第七集`）——意图足够明确；
     * - [autoplay] 为 true（小爱主题昵称那条路，一个主题就是一个动作）；
     * - 停在片源页 —— 交给片源模块自己的搜索与聚合，那边有独立的源列表。
     */
    private fun remoteWatch(query: String, episode: Int = 0, autoplay: Boolean = false): Boolean {
        // 停在片源模块时交给片源自己的搜索：那一侧有自己的源列表和聚合结果页，
        // 用主站搜索会搜出「主站有、当前片源没有」的片子，点开还是放不出来。
        if(tab==6) {
            val module=third
            if(module==null) {
                Toast.makeText(this,L10n.tr("片源模块还没准备好，稍后再试"),Toast.LENGTH_SHORT).show()
                return false
            }
            return if(episode>0 || autoplay) module.remotePlayTitle(query,episode) else module.remoteSearchTitle(query)
        }
        if(screen=="player") {
            resetPlaybackSession(); sleepTimer.cancel(); sleepStopped=false
            saveProgress(); library.flush(); releasePlayer()
        }
        // 先切到搜索结果页：结果本身就是要看的东西
        runSearch(query,fromPhone=true)
        if(episode<=0 && !autoplay) {
            // 当前内容库搜不到时换一个库再搜一次（漫剧/短剧分库），别把人晾在空列表上。
            // 只换库，不替人挑剧——挑还是让人自己点。
            searchOtherLibrary(query)
            return true
        }
        // AI 剧没有独立搜索入口（searchTab=null），放进候选只会白等一次超时
        val types=(listOf(library.contentType)+ContentType.entries).distinct().filter { it.searchTab!=null }
        // 主站搜索按基名索引：喊「黄石第五季」常常一条都搜不到，去掉季号再试一次就够了。
        // 原名优先，所以正常说法不会多花时间。
        val words=listOf(query,ThirdCms.seriesBase(query)).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        io.execute {
            val hit=words.firstNotNullOfOrNull { word ->
                types.firstNotNullOfOrNull { type ->
                    val items=runCatching { repository.search(word,1,type) }.getOrNull()?.items.orEmpty()
                    val index=TitleMatch.best(word,items)
                    if(index<0) null else type to items[index]
                }
            }
            // 标题对上只是第一步：搜索结果里混着预告、空壳和同名不同内容，
            // 没有剧集就进播放器等于把人扔进一个黑屏。这里把详情先取回来验一次，
            // 验不过就停在搜索页——结果已经摆在屏幕上了，点一下比猜可靠。
            val ready=hit?.let { (type,series) ->
                runCatching { repository.detail(series.id) }.getOrNull()
                    ?.takeIf { it.episodes.isNotEmpty() }
                    ?.let { Triple(type,series,it) }
            }
            main.post {
                if(isDestroyed) return@post
                if(ready==null) {
                    val why=if(hit==null) "没搜到「$query」，可以换个说法再试"
                        else "《${hit.second.title}》暂时没有可播放的剧集，请在结果里选一个"
                    Toast.makeText(this@MainActivity, L10n.tr(why),Toast.LENGTH_SHORT).show()
                    return@post
                }
                val (type,series,loaded)=ready
                if(library.contentType!=type) { library.contentType=type; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
                if(episode>0) {
                    if(episode>loaded.episodes.size) {
                        openDetail(series,resume=false,autoplay=false,preloaded=loaded)
                        Toast.makeText(this@MainActivity, L10n.tr("《${series.title}》只有 ${loaded.episodes.size} 集"),Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    // 说了集号就落到那一集：先摆详情页，再从那一集起播（不续播旧进度）
                    openDetail(series,resume=false,autoplay=false,preloaded=loaded)
                    playEpisode(episode-1,savedPosition(episode-1))
                } else openDetail(series,resume=true,preloaded=loaded)
            }
        }
        return true
    }

    /**
     * 「这个库里没有，就换个库看一眼」。
     *
     * 漫剧与短剧是两个独立搜索池，人在漫剧模式下喊一部短剧的名字，结果页会是一片空白——
     * 屏幕上看不出「是没这部」还是「搜错库了」。这里只负责换库并重搜，
     * 命中的结果照样由人自己点。
     */
    private fun searchOtherLibrary(query: String) {
        val current=library.contentType
        val types=(listOf(current)+ContentType.entries).distinct().filter { it.searchTab!=null }
        io.execute {
            val found=types.firstNotNullOfOrNull { type ->
                val items=runCatching { repository.search(query,1,type) }.getOrNull()?.items.orEmpty()
                if(items.isEmpty()) null else type to items.size
            } ?: return@execute
            main.post {
                if(isDestroyed || found.first==library.contentType) return@post
                runSearch(query,found.first,fromPhone=true)
                Toast.makeText(this@MainActivity, L10n.tr("${found.first.label}里找到 ${found.second} 条「$query」"),Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 小爱语音入口（巴法云中转）。没配私钥时为 null。 */
    private var xiaoAi: XiaoAiLink? = null
    private var xiaoAiState: String = ""

    private fun xiaoAiLabel(): String = if(XiaoAiAccess.uid.isBlank()) "小爱同学  ·  未连接"
        else "小爱同学  ·  已配置 ${XiaoAiAccess.masked()}"

    /**
     * 只在电视前台轮询巴法云。
     *
     * 后台常驻既费流量又会被系统的省电策略掐断，而语音入口的实际场景就是
     * 「电视开着、人懒得找遥控器」，前台够用。
     */
    private fun startXiaoAi() {
        if(XiaoAiAccess.uid.isBlank()) return
        val link=xiaoAi ?: XiaoAiLink(
            { action -> runVoiceAction(action) },
            { state -> xiaoAiState=state }
        ).also { xiaoAi=it }
        link.start()
    }

    private fun stopXiaoAi() { xiaoAi?.stop() }

    /**
     * 换私钥后重开：基线要按新账号重算，否则旧主题的时间戳会挡住第一次触发。
     */
    private fun restartXiaoAi() {
        xiaoAi?.destroy(); xiaoAi=null; xiaoAiState=""
        startXiaoAi()
    }

    /** 语音动作落到界面：与手机遥控同一套入口，行为保持一致。 */
    private fun runVoiceAction(action: XiaoAiVoice.Action) {
        // 通用指令自己会出回执（「已切换弹幕」），只有做不到时才需要在这里补一句
        if(action is XiaoAiVoice.Action.Do) {
            if(!remoteDo(action.command))
                Toast.makeText(this@MainActivity, L10n.tr("小爱同学：${action.echo}，但当前页面做不到"),Toast.LENGTH_SHORT).show()
            return
        }
        val ok=when(action) {
            is XiaoAiVoice.Action.Open -> remoteOpenModule(action.module)
            // 小爱这条路的约定是「一个主题 = 一个动作」，喊剧名就是开播，所以照旧自动起播
            is XiaoAiVoice.Action.Watch -> remoteWatch(action.query,0,autoplay=true)
            is XiaoAiVoice.Action.Do -> false
        }
        val what=when(action) {
            is XiaoAiVoice.Action.Open -> "打开「${moduleLabel(action.module)}」"
            is XiaoAiVoice.Action.Watch -> "播放「${action.query}」"
            is XiaoAiVoice.Action.Do -> action.echo
        }
        Toast.makeText(this@MainActivity, L10n.tr(if(ok) "小爱同学：$what" else "小爱同学：$what 没生效（当前页面不支持）"),Toast.LENGTH_SHORT).show()
    }

    private fun moduleLabel(module: String): String = when(module) {
        "home" -> "首页"; "search" -> "搜索"; "rank" -> "排行榜"; "favorite" -> "收藏"
        "history" -> "最近观看"; "settings" -> "设置"; "source" -> "片源"; "library" -> "本地库"
        else -> module
    }

    /**
     * 小爱同学配置。
     *
     * 私钥是 32 位十六进制，用遥控器在电视上敲基本不可能，所以主入口是手机扫码填写
     * （与「添加片源」共用 PhoneInputServer）。
     */
    private fun showXiaoAiDialog(anchor: View) {
        val content=column()
        content.addView(TvDialog.title(this,"小爱同学  ·  语音开播"))
        content.addView(TextView(this).apply {
            textSize=14f; setTextColor(TvStyle.muted); setLineSpacing(dp(5).toFloat(),1f)
            text=buildString {
                appendLine("小爱只会说「打开 / 关闭某个设备」，不会把整句传出来，所以约定：")
                appendLine("巴法云里一个主题就是一个动作，主题昵称就是小爱要喊的名字。")
                appendLine()
                appendLine("① cloud.bemfa.com 新建主题，如 hghs001、hgmod1001")
                appendLine("    主题名只能字母数字，末三位是设备类型（001 插座），否则不会同步到米家")
                appendLine("② 进主题「更多设置」把昵称改成要喊的话")
                appendLine("    剧名直接写「黄石第五季」；跳模块写「红果片源」「红果首页」")
                appendLine("③ 米家 → 我的 → 其他平台设备 → 添加 → 巴法，登录同一个账号")
                appendLine("④ 把控制台首页那串私钥填进来（扫码最省事），电视在前台时生效")
            }
        })
        val status=LocalizedTextView(this).apply {
            textSize=14f; setTextColor(TvStyle.text); setPadding(0,dp(10),0,0)
            text=xiaoAiStatusText()
        }
        content.addView(status)
        val actions=row().apply { setPadding(0,dp(12),0,0) }
        lateinit var dialog: AlertDialog
        val scan=TvDialog.capsuleButton(this,"扫码填写") { }
        val test=TvDialog.capsuleButton(this,"立即检查") {
            if(XiaoAiAccess.uid.isBlank()) status.text="私钥  ${XiaoAiAccess.masked()}\n状态  先填私钥"
            else { restartXiaoAi(); status.text="私钥  ${XiaoAiAccess.masked()}\n状态  正在连接…" }
        }
        val clear=TvDialog.capsuleButton(this,"清除") {
            XiaoAiAccess.setUid(""); xiaoAi?.destroy(); xiaoAi=null; xiaoAiState=""
            status.text=xiaoAiStatusText()
            Toast.makeText(this,L10n.tr("已清除小爱配置"),Toast.LENGTH_SHORT).show()
        }
        val close=TvDialog.capsuleButton(this,"关闭") { dialog.dismiss() }
        actions.addView(scan,lp(-2,-2).apply { rightMargin=dp(10) })
        actions.addView(test,lp(-2,-2).apply { rightMargin=dp(10) })
        actions.addView(clear,lp(-2,-2).apply { rightMargin=dp(10) })
        actions.addView(close,lp(-2,-2))
        content.addView(actions)
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog,widthDp=520)
        scan.setOnClickListener {
            tvTools.phoneForm(scan,PhoneForm(
                heading="小爱同学 · 巴法云私钥",
                description="巴法云控制台首页那串 32 位私钥。它既能读主题列表也能代表账号，只存在电视本机。",
                fields=listOf(PhoneField("uid","巴法云私钥","控制台首页的 32 位十六进制",64)),
                doneHint="回到电视即可看到连接状态。",
                invalidMessage="请填写巴法云控制台首页那串私钥（20 位以上字母数字）"
            )) { values ->
                val uid=values["uid"].orEmpty().trim()
                if(uid.length>=20) {
                    XiaoAiAccess.setUid(uid); restartXiaoAi()
                    status.text=xiaoAiStatusText()
                    Toast.makeText(this,L10n.tr("已保存，正在连巴法云"),Toast.LENGTH_SHORT).show()
                } else status.text=L10n.tr("私钥短了，巴法云的是 32 位；请重新扫码填写")
            }
        }
        dialog.show()
        test.requestFocus()
    }

    private fun xiaoAiStatusText(): String {
        val live=xiaoAi?.state() ?: xiaoAiState
        val state=when {
            XiaoAiAccess.uid.isBlank() -> "未填写私钥"
            live.isNotBlank() -> live
            else -> "等电视开始轮询"
        }
        return "私钥  ${XiaoAiAccess.masked()}\n状态  $state"
    }

    /** 方向键走焦点搜索，不做 KeyEvent 注入：注入会撞上播放屏的专用语义（↓选集、↑更多）。 */
    private fun remoteDpad(key: String): Boolean {
        // 弹窗是独立窗口，盖上来以后 Activity.currentFocus 恒为 null，下面的 focusSearch 全部落空。
        // 有弹窗就先按真实按键交给它，焦点导航和确认点击都走弹窗自己的规则。
        TvDialog.topmostDialog()?.let { return TvDialog.dispatchRemoteKey(it,key) }
        // 播放屏没有可聚焦的 View（surface + HUD），焦点搜索必然落空，手机上表现成「按了没反应」。
        // 控制条收起时直接对齐实体遥控器的播放屏语义：上呼出设置、下选集、左右快退快进、确认暂停。
        if(screen=="player" && !panel) {
            // 自由刷剧（用户 2026-11-21）：手机遥控方向键此前硬用点播语义（上设置/下选集/左右快进），
            // 与实体遥控在 feed 里的键位（↑↓换剧、←→换集、确认暂停）对不上。对齐 handleRecommendKey。
            if(recommendMode) return when(key) {
                "center" -> if(playError) true else { togglePlayback(); if(player?.playWhenReady==true) hidePlaybackOverlay() else showHud(); true }
                "up" -> { switchRecommend(-1); true }
                "down" -> { switchRecommend(1); true }
                "left" -> { feedSkipEpisode(-1); true }
                "right" -> { feedSkipEpisode(1); true }
                else -> false
            }
            return when(key) {
                "center" -> { togglePlayback(); true }
                "up" -> { showPanel(); true }
                "down" -> { showEpisodePanel(hud); true }
                "left" -> { seek(-1,0); true }
                "right" -> { seek(1,0); true }
                else -> false
            }
        }
        val focus=currentFocus
        if(key=="center") {
            if(screen=="player" && !panel) { togglePlayback(); return true }
            return focus?.performClick()==true
        }
        val direction=when(key) {
            "up"->View.FOCUS_UP; "down"->View.FOCUS_DOWN
            "left"->View.FOCUS_LEFT; "right"->View.FOCUS_RIGHT
            else->return false
        }
        val next=focus?.focusSearch(direction) ?: root.focusSearch(direction)
        return next!=null && next.requestFocus()
    }

    /** 与电视菜单键的抬起分支完全一致：短按按用户配置分流，长按走快捷面板。片源页则交给片源自己。 */
    private fun remoteMenuShort(): Boolean {
        if(tab==6) return third?.remoteMenu()==true
        if(screen!="player") return false
        if(panel) { hidePlaybackOverlay(); return true }
        if(playError) return true
        when(extras.menuKeyAction) {
            PlaybackExtras.MENU_EPISODES -> showEpisodePanel(hud)
            PlaybackExtras.MENU_DANMAKU -> detail?.series?.id?.let { toggleSeriesDanmaku(it) }
            else -> showPanel()
        }
        return true
    }

    private val remoteController=object: RemoteController {
        override fun navigate(cmd: RemoteCommand): Boolean = when(cmd) {
            is RemoteCommand.Dpad -> remoteDpad(cmd.key)
            // 有弹窗时返回键先关弹窗：直接走 goBack() 会绕过它，人就被卡在弹窗里出不来。
            RemoteCommand.Back -> {
                val overlay=TvDialog.topmostDialog()
                if(overlay!=null && TvDialog.dispatchRemoteKey(overlay,"back")) true else { goBack(); true }
            }
            // 与实体遥控长按菜单键一致：呼出四列快捷面板（menuLongRunnable 也走 showQuickPanel）；
            // 片源播放器没有快捷面板，长按短按都呼出它自己的底部操作条
            is RemoteCommand.Menu -> if(cmd.long) {
                if(screen=="player") { showQuickPanel(); true } else remoteMenuShort()
            } else remoteMenuShort()
            is RemoteCommand.Search -> {
                // 片源是独立模块：电视停在片源页时，搜索就该搜片源自己的内容，
                // 不能把人拽回主站搜索页。其余页面维持原行为。
                if(tab==6) third?.remoteSearch(cmd.query)==true else {
                    // 手机搜索会直接把电视切到搜索结果页；正在播的那部若不先停掉，
                    // 画面走了声音还在后台放。离开播放页的收尾与 returnToDetail 一致。
                    if(screen=="player") { resetPlaybackSession(); sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); releasePlayer() }
                    runSearch(cmd.query,fromPhone=true); true
                }
            }
            is RemoteCommand.Resume -> remoteResumePlay(cmd.id)
            is RemoteCommand.Open -> remoteOpenModule(cmd.module)
            is RemoteCommand.Watch -> remoteWatch(cmd.query,cmd.episode)
            is RemoteCommand.Say -> remoteSay(cmd.text)
            is RemoteCommand.Act -> remoteAct(cmd)
            else -> false
        }
        override fun onClientsChanged(clients: Int) {
            remoteHolding=clients>0
            refreshScreenOn()
            remote?.setClients(clients)
        }
        override fun onSessionEnded() {
            remote=null; remoteHolding=false; remoteCloudKicked=false; refreshScreenOn(); syncRemoteEntry()
        }
    }

    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(recommendMode) { saveProgress(); returnToRecommendPage() } else if(player?.playbackState==Player.STATE_ENDED) returnToDetail() else if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> {
                // 自由刷剧确认进来的详情：返回＝续播当前剧，从哪进回哪（用户 2026-11-18）
                if(feedDetailBrowsing && recommendItems.isNotEmpty()) { feedDetailBrowsing=false; playRecommend(recommendCursor.coerceIn(0,recommendItems.lastIndex)); return }
                feedDetailBrowsing=false
                showCatalog()
            }
            else -> if(tab==1 && advancedSearch) { switchAdvanced(false); filterToggle?.post { filterToggle?.requestFocus() } } else if(tab==6 && third?.onBack()==true) {} else if(tab==3 && favoriteManage) { favoriteManage=false; favoriteSelected.clear(); showCatalog() } else if(tab==2 && newRelease) { newRelease=false; page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; discoveryCursor=null; discoverySelection=DiscoverySelection(); tabState.remove(2); tabFocus.remove(2); pendingNewReleaseFocus="back"; showCatalog(true) } else if(tab==2 && aiCatalog) { aiCatalog=false; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true); aiEntry?.post { if(aiEntry?.isAttachedToWindow==true) aiEntry?.requestFocus() } } else if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else showExitConfirm() } else if(nav.getOrNull(tab)?.requestFocus()!=true) showExitConfirm()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    /** 屏保期间的输入统一走这里：刚盖屏那一下要吞掉。
     *  面板里「长按预览」的松手事件比盖屏晚到（长按 500ms 触发 + 450ms 盖屏延迟 ≈ 松手时刻），
     *  直接当唤醒处理会让预览一松手就关掉（实测），所以盖屏后留 700ms 静默期。 */
    private fun consumeScreensaverInput(): Boolean {
        if(android.os.SystemClock.elapsedRealtime()>=screensaverIgnoreUntil) resetIdleTimer()
        return true
    }
    /** 鼠标移动/滚轮都走 generic motion：据此把输入方式切到指针，网格不再为滚轮补焦点。 */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if(screensaverView!=null) return consumeScreensaverInput()
        lastInteractionAt=android.os.SystemClock.elapsedRealtime()
        if(TvPointer.isPointer(ev)) pointerInput=true
        return super.dispatchGenericMotionEvent(ev)
    }
    /** 触摸/鼠标点击：
     *  1) 屏保期间任何点击只用来唤醒，不透传给下层——否则「想唤醒却点开了一张卡片」（用户 2026-11-19）；
     *  2) 鼠标右键补一个「返回」：Android 不把次级键映射成 BACK，接了鼠标的用户否则在非播放页没有返回手段。
     *     播放页右键有自己的映射（设置里可改），这里放行给 super，别抢它的。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if(screensaverView!=null) return consumeScreensaverInput()
        if(ev.actionMasked==MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY)!=0 && screen!="player") {
            lastInteractionAt=android.os.SystemClock.elapsedRealtime()
            goBack()
            return true
        }
        // 鼠标左键点按走 touch 通道而非 generic motion：按住不动只点击时系统只发 touch 事件，
        // 之前只在右键分支续命，导致「一直用鼠标点着也会进屏保」（用户 2026-11-21）。
        lastInteractionAt=android.os.SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }
    /** 鼠标滚轮：RecyclerView 原生不吃 GenericMotion 滚轮事件，目录网格此前只能靠遥控方向键
     *  滚（issue #35.2「鼠标下拉不下去」的一半根因）。滚轮按屏高 1/5 步进滚动结果网格。 */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(screen=="catalog" && event.action==MotionEvent.ACTION_SCROLL
            && event.source and InputDevice.SOURCE_CLASS_POINTER!=0) {
            val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val recycler=catalogRecycler
            if(delta!=0f && recycler!=null && recycler.height>0) {
                pointerInput=true
                recycler.scrollBy(0,(delta*recycler.height/5f).toInt())
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 屏保唤醒键只撤屏保不透传：防止「按一下想唤醒却把焦点卡片点开了」（用户 2026-11-19）
        if(screensaverView!=null) return consumeScreensaverInput()
        // 设置左栏排序模式：上下移动被抓起的分类，左右不动，确认/返回放下并保存（用户 2026-11-25）
        if(railReorderPos>=0 && tab==5 && screen=="catalog") {
            if(event.action==KeyEvent.ACTION_DOWN) when(event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { railReorderMove?.invoke(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { railReorderMove?.invoke(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> {
                    // 抓起本身就是长按：松手前系统还在灌 repeat 键，必须只有全新一按（repeat==0）
                    // 才算放下，否则抓起 50ms 后就被残留重复键自动放下＝功能无效（用户 2026-11-25）
                    if(event.repeatCount==0) railReorderEnd?.invoke(true)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { railReorderEnd?.invoke(true); return true }
                else -> {}
            }
            // 抓起那一按的抬起事件（可能带 FLAG_LONG_PRESS）：吞掉，别让它落到按钮上再触发一次点击
            if(event.action==KeyEvent.ACTION_UP &&
                (event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode==KeyEvent.KEYCODE_ENTER)) return true
        }
        lastInteractionAt=android.os.SystemClock.elapsedRealtime()
        if(event.action==KeyEvent.ACTION_DOWN) pointerInput=false
        // 遥控一按就把鼠标专属的东西收起来：最近一次操作已经换人了
        if(mouseSeen && screen=="player") { mouseSeen=false; refreshPointerUi() }
        if(tab==6 && third?.onKey(event)==true) return true
        // 首页长按菜单键：快捷进入自由刷剧（用户 2026-11-18）；短按仍交给卡片的快捷操作。
        // 两条路都收：真实遥控按住靠 550ms 计时器，注入的 FLAG_LONG_PRESS（adb/某些遥控）直接触发。
        // 必须放在 handleCatalogDown/Up 之前：那两个函数对非方向键也会直接走
        // super.dispatchKeyEvent（view 树当场消费），放在后面永远轮不到。
        if(screen=="catalog" && event.keyCode==KeyEvent.KEYCODE_MENU) {
            // 收藏页：菜单键循环切换排序（卡片上的快捷菜单改用长按确认键，与设计稿按键表一致）
            if(tab==3 && collection==null) {
                if(event.action==KeyEvent.ACTION_UP && !favoriteManage) {
                    val order=listOf("update","watch","name")
                    library.favoriteSort=order[(order.indexOf(library.favoriteSort)+1)%order.size]
                    showCatalog()
                    favoriteSortButtons.getOrNull(order.indexOf(library.favoriteSort))?.let { btn -> btn.post { if(btn.isAttachedToWindow) btn.requestFocus() } }
                }
                return true
            }
            // 首页：长按进自由刷剧，短按交给卡片快捷菜单
            if(tab==0 && collection==null) {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    if(event.flags and KeyEvent.FLAG_LONG_PRESS!=0) { homeMenuFired=true; main.removeCallbacks(homeMenuRunnable); enterFreeFeed() }
                    else if(event.repeatCount==0) { homeMenuFired=false; main.postDelayed(homeMenuRunnable,550) }
                    return true
                }
                main.removeCallbacks(homeMenuRunnable)
                if(homeMenuFired) { homeMenuFired=false; return true }
                return false
            }
        }
        // 推荐 feed 的键位语义与点播播放页不同：↑↓ 换剧、←→ 换集、确认进详情。
        // 必须放在 handleCatalogDown/Up 之前：那两个函数末尾会 super.dispatchKeyEvent 把事件
        // 先交给视图树（HUD 的暂停按钮正持有焦点），放在后面永远轮不到——同 MENU 键的坑。
        if(screen=="player" && recommendMode && handleRecommendKey(event)) return true
        if(handleCatalogDown(event)) return true
        if(handleCatalogUp(event)) return true
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            // 菜单键：短按执行「菜单键行为」设置的动作（抬起时触发），长按 500ms 呼出播放快捷面板
            if(key==KeyEvent.KEYCODE_MENU) {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    if(event.repeatCount==0 && !playError) { menuLongFired=false; main.postDelayed(menuLongRunnable,500) }
                } else if(event.action==KeyEvent.ACTION_UP) {
                    main.removeCallbacks(menuLongRunnable)
                    if(menuLongFired) { menuLongFired=false; return true }
                    if(playError) return true
                    if(panel) { hidePlaybackOverlay(); return true }
                    when(extras.menuKeyAction) {
                        PlaybackExtras.MENU_EPISODES -> showEpisodePanel(hud)
                        PlaybackExtras.MENU_DANMAKU -> detail?.series?.id?.let { toggleSeriesDanmaku(it) }
                        else -> showPanel()
                    }
                }
                return true
            }
            // 自动重试转圈时：确认键打断并切到手动面板；其余键忽略，避免误触切集。
            if(playError && autoRecovery) {
                if((key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) stopAutoRecoveryAndShowPanel()
                return true
            }
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
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_UP) { stopFastForward(); return true }
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if(event.repeatCount==0) seek(1,0) else startFastForward()
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) { if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) skipEpisode(1) else showEpisodePanel(hud) }
                        KeyEvent.KEYCODE_DPAD_UP -> if(event.repeatCount==0) { if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) skipEpisode(-1) else showPanel() }
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
        // 待机屏保计时：回前台重新计时，5 秒一查（用户 2026-11-19）
        lastInteractionAt=android.os.SystemClock.elapsedRealtime()
        scheduleScreensaverCheck()
        // 开机进首页 6 秒后还没进刷剧就静默预热推荐流（一次），点进来自由刷剧时基本 0 等待
        main.postDelayed(feedPrewarmRunnable,6000)
        updater.resume()
        autoStartRemote()
        startXiaoAi()
        // 从「安装未知应用」授权页返回：同一个包继续安装，不用重新扫码
        pendingQuickApk?.let { apk -> if(Build.VERSION.SDK_INT<26 || packageManager.canRequestPackageInstalls()) { pendingQuickApk=null; installQuickApk(apk) } }
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
        // 回到前台：先把状态推给手机（页面从「电视已暂停」恢复），缓存意图由 remotePublish 内部补上
        remotePublish(true)
    }
    override fun onPause() {
        updater.pause()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        main.removeCallbacks(screensaverCheck); dismissScreensaver()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); third?.pause(); saveProgress(); library.flush()
        // 会话保留，但要让手机知道「电视已暂停」，否则页面会一直显示成还能操作
        remotePublish(true)
        super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        stopXiaoAi()
        third?.stop()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush()
        remotePublish(true)
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); profileUpdater.destroy(); saveProgress(); releasePlayer(); accountManager.destroy(); library.close()
        closeRemote()
        xiaoAi?.destroy()
        third?.release()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        recommendVideos.values.forEach { runCatching { it.second.close() } }; recommendVideos.clear()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
        if(killOnDestroy) android.os.Process.killProcess(android.os.Process.myPid())
    }
}
