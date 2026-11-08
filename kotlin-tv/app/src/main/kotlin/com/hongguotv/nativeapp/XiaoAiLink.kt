// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.RemoteCommand
import com.hongguotv.core.VoiceIntent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 小爱同学 → 电视的语音入口，中间借巴法云（bemfa）转一手。
 *
 * 为什么要绕巴法云：米家「我的 → 其他平台设备」原生支持绑定巴法账号，绑完以后巴法里的
 * 主题会以「设备」的身份出现在米家，小爱就能直接喊它的名字。平台不允许第三方 App 直接把
 * 自己塞进米家，这是唯一既不需要企业资质、也不用添硬件的一条路。
 *
 * 小爱只会说「打开 X / 关闭 X」，喊不出自由句子，所以约定：**一个主题 = 一个动作，
 * 主题昵称 = 小爱要喊的名字**。昵称可以随便写中文（主题名本身只能字母数字），于是
 * 「红果片源」是跳模块，「黄石第五季」就是按名字开播。
 */
object XiaoAiVoice {

    /** 主题名前缀：只认自己建的主题，免得把用户别的巴法设备当成指令。 */
    const val TOPIC_PREFIX = "hg"

    /** 一次语音指令。 */
    sealed class Action {
        /** 按片名开播（有进度就续播）。 */
        data class Watch(val query: String) : Action()

        /** 直接跳到一级模块，键见 [com.hongguotv.core.REMOTE_MODULES]。 */
        data class Open(val module: String) : Action()

        /**
         * 一句通用指令：检查更新、切主题、打开列表第 N 个……
         *
         * 和 [Watch]/[Open] 分开是因为它们的来源不同：那两个是「喊昵称」时代的产物，
         * 这个来自 [com.hongguotv.core.VoiceIntent] 对整句话的解析。
         * [echo] 是解析时给出的回执文案，执行失败时用来告诉用户「听懂了但做不到」。
         */
        data class Do(val command: RemoteCommand, val echo: String) : Action()
    }

    data class Topic(val topic: String, val name: String, val msg: String, val unix: Long)

    /** 昵称 → 模块。只认全名匹配，不做包含匹配：「我的收藏夹」不该被当成「收藏」。 */
    private val MODULES = linkedMapOf(
        "首页" to "home",
        "搜索" to "search",
        "排行榜" to "rank",
        "排行" to "rank",
        "收藏" to "favorite",
        "最近观看" to "history",
        "观看历史" to "history",
        "历史" to "history",
        "片源" to "source",
        "三方源" to "source",
        "本地库" to "library",
        "本地" to "library",
        "设置" to "settings"
    )

    /** 一次语音指令的关键词长度上限，与遥控命令的搜索框一致。 */
    const val QUERY_MAX = 80

    /** 开关语义的载荷：这些不是剧名，仍按「昵称 = 剧名/模块」的老规矩解释。 */
    private val SWITCH_WORDS = setOf("on", "off", "toggle", "true", "false", "1", "0", "开", "关")

    /** 昵称里常见的装饰：念出来没意义，去掉以后才好匹配剧名。 */
    private val SEPARATORS = charArrayOf(' ', '\u3000', '·', '-', '_', '：', ':', '|', '/', '、')

    /** 让昵称可以直接写剧名：「红果黄石」和「黄石」都该看懂。长的放前面，先剥长的。 */
    private val PREFIXES = listOf("hongguotv", "红果tv", "hongguo", "红果")

    /**
     * 昵称 → 动作。
     *
     * 先剥「红果」这类前缀，再看是不是模块名，否则整体当剧名去搜。
     * 单字直接放弃：一个字的昵称（「看」）八成是随手起的，猜错比不响应更烦人。
     */
    fun actionOf(name: String): Action? {
        val text = bare(name)
        if(text.isEmpty()) return null
        MODULES[text]?.let { return Action.Open(it) }
        if(text.length < 2) return null
        return Action.Watch(text)
    }

    private fun bare(name: String): String {
        var text = name.trim().trim(*SEPARATORS)
        if(text.isEmpty()) return ""
        for(prefix in PREFIXES) {
            if(text.equals(prefix, ignoreCase = true)) return ""
            if(text.length > prefix.length && text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim(*SEPARATORS)
                break
            }
        }
        return text
    }

    /**
     * 消息载荷里的剧名。
     *
     * 巴法云的 `msg` 实测能带任意文本：米家只会发 `on`/`off`（所以老的「昵称 = 剧名」还得留着），
     * 但小爱自定义技能可以把用户说的剧名原样写进来，于是同一个主题既能当开关、也能当输入框。
     *
     * 只做「像不像一句话」的判断，不做任何猜测：猜错会直接播别的片，比不响应更糟。
     * 返回 null 表示这不是文本指令，交给开关语义那条老路。
     */
    fun queryOf(payload: String): String? {
        val text = payload.trim()
        if(text.length < 2 || text.length > QUERY_MAX) return null
        if(text.lowercase() in SWITCH_WORDS) return null
        if(text.any { it.isISOControl() }) return null
        return text
    }

    /**
     * 一句话 → 动作。
     *
     * 与 [actionOf] 的分工：[actionOf] 解释的是**主题昵称**（「黄石第五季」这种名字），
     * 这里解释的是**用户说的整句话**（「检查有没有更新」「看历史记录第二个」）。
     *
     * 解析器认不出来就返回 null，调用方什么都不做——不要退化成「拿整句话去搜」，
     * 那会把一句闲聊变成一次莫名其妙的搜索。
     */
    fun textAction(text: String): Action? {
        val plan = VoiceIntent.parse(text) ?: return null
        return when(val cmd = plan.command) {
            is RemoteCommand.Open -> Action.Open(cmd.module)
            // 整句话一律走通用分发（[Action.Do]）：影射到界面上的行为和遥控页一模一样，
            // 包括「搜索只出结果、说清集数才落到那一集」这条规矩。
            // 只有「主题昵称就是剧名」那条老路（[actionOf]）才默认开播。
            else -> Action.Do(cmd, plan.echo)
        }
    }

    /**
     * 解析 `allTopic` 响应。
     *
     * 真实响应把主题数组又包了一层（`data.data`），跟文档里写的 `data` 直接是数组不一样：
     *
     * ```json
     * {"code":0,"msg":"success","data":{"data":[{"topic":"hgplay1001","name":"黄石第五季"}]}}
     * ```
     *
     * 两种都读，免得哪天巴法再改回去又白屏一次。
     *
     * 只保留 [TOPIC_PREFIX] 开头的主题：巴法账号里可能还有别的设备，那些不该被这里当作指令。
     */
    fun parse(body: String): List<Topic> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("data")
            ?: return emptyList()
        val out = ArrayList<Topic>(data.length())
        for(index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val topic = item.optString("topic").trim()
            if(topic.isEmpty() || !topic.startsWith(TOPIC_PREFIX, ignoreCase = true)) continue
            out += Topic(
                topic = topic,
                name = item.optString("name").trim(),
                msg = item.optString("msg").trim(),
                unix = item.optLong("unix", 0L)
            )
        }
        return out
    }

    /** 接口错误码 → 人话。正常返回 null。 */
    fun problem(body: String): String? {
        val code = runCatching { JSONObject(body).optInt("code", -1) }.getOrDefault(-1)
        return when(code) {
            0 -> null
            // 私钥格式对但服务端不认（或账号下一个主题都没有），实测返回的就是 -1
            -1 -> "巴法云没认出这把私钥"
            40003 -> "巴法云私钥不对"
            10002 -> "巴法云参数有误"
            else -> "巴法云返回 code=$code"
        }
    }
}

/**
 * 巴法云私钥的持久持有者。
 *
 * 私钥（控制台首页那串 32 位）既是 MQTT 的 clientID，也是这套 HTTP 接口的鉴权，等于
 * 半把账号钥匙：只存本机、不上传、界面里打码显示。
 */
object XiaoAiAccess {
    private const val PREFS_FILE = "xiaoai_link"
    private var prefs: android.content.SharedPreferences? = null

    @Volatile var uid: String = ""
        private set

    fun init(context: Context) {
        if(prefs != null) return
        val store = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs = store
        uid = store.getString("uid", "").orEmpty().trim()
    }

    fun setUid(value: String) {
        uid = value.trim()
        prefs?.edit()?.putString("uid", uid)?.apply()
    }

    /** 界面上展示用：只留前 4 后 4，中间打码。 */
    fun masked(): String = when {
        uid.isEmpty() -> "未填写"
        uid.length <= 10 -> "已填写"
        else -> uid.take(4) + "…" + uid.takeLast(4)
    }
}

/**
 * 小爱语音的调度：MQTT 常驻订阅为主，HTTP 轮询做兜底。
 *
 * **为什么必须有 MQTT**：米家下发指令前会先看设备在不在线，而巴法云的「在线」= 有客户端
 * 订阅了这个主题。只轮询 HTTP 的电视永远算离线，小爱会直接回「设备不在线」，指令根本发不出来。
 *
 * 两条路的职责：
 * - MQTT：维持在线状态 + 实时收指令（延迟约 0）
 * - 轮询：MQTT 没连上时照样能收（最多 2 秒延迟）；顺带刷新「主题 → 昵称」映射，
 *   MQTT 的消息里只有主题名，没有昵称
 *
 * 只在电视前台跑：语音入口的实际场景就是「电视开着、人懒得找遥控器」，后台常驻会被系统掐。
 */
class XiaoAiLink(
    private val onAction: (XiaoAiVoice.Action) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        /** HTTPS：私钥在查询串里，不走明文。 */
        private const val ENDPOINT = "https://apis.bemfa.com/vb/api/v2/allTopic"

        /** 离线时 2 秒一轮：再快对巴法不礼貌，再慢用户会以为没反应。 */
        private const val POLL_OFFLINE_MS = 2_000L

        /** 在线时只是刷新映射（新加的主题），慢一点没关系。 */
        private const val POLL_ONLINE_MS = 60_000L

        /** MQTT 刚处理过的主题，轮询在这么久内不再重复触发。 */
        private const val MQTT_GRACE_MS = 15_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hongguotv-xiaoai").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** 主题 → 已处理到的消息时间戳。 */
    private val seen = ConcurrentHashMap<String, Long>()

    /** 主题 → 昵称。MQTT 的 PUBLISH 只给主题名，昵称得靠轮询拉回来。 */
    private val names = ConcurrentHashMap<String, String>()

    /** 主题 → MQTT 最近一次处理的时间，用来抑制轮询重复触发。 */
    private val handled = ConcurrentHashMap<String, Long>()

    private var session: BemfaSession? = null
    private var primed = false
    private var baseline = false
    private var running = false
    private var count = 0
    private var lastError: String? = null
    private var reported = ""

    @Volatile private var online = false

    private val tick = object : Runnable {
        override fun run() {
            if(!running) return
            worker.execute {
                val result = runCatching { fetch() }
                main.post {
                    if(!running) return@post
                    result.onSuccess { topics ->
                        lastError = null
                        applyTopics(topics)
                        consume(topics)
                    }.onFailure { error ->
                        lastError = error.message?.takeIf { it.isNotBlank() } ?: "网络错误"
                    }
                    publish()
                    main.postDelayed(this, if(online) POLL_ONLINE_MS else POLL_OFFLINE_MS)
                }
            }
        }
    }

    fun start() {
        if(running) return
        if(XiaoAiAccess.uid.isBlank()) { publish("未填写巴法云私钥"); return }
        running = true
        primed = false
        baseline = false
        online = false
        lastError = null
        seen.clear()
        names.clear()
        handled.clear()
        count = 0
        connect()
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        session?.stop()
        session = null
        online = false
    }

    fun destroy() {
        stop()
        worker.shutdownNow()
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    fun state(): String = when {
        XiaoAiAccess.uid.isBlank() -> "未填写巴法云私钥"
        lastError != null && !online -> "连不上巴法云（$lastError）"
        !primed -> "正在连接…"
        count == 0 -> "已连接，但没有以 ${XiaoAiVoice.TOPIC_PREFIX} 开头的主题"
        online -> "已连接 · $count 个设备"
        else -> "$count 个主题，云端未在线（正在重连，此时小爱会说设备不在线）"
    }

    private fun connect() {
        val uid = XiaoAiAccess.uid
        if(uid.isBlank()) return
        session?.stop()
        session = BemfaSession(
            clientId = uid,
            onOpen = { worker.execute { refresh() } },
            onMessage = { topic, payload -> onMqtt(topic, payload) },
            onState = { up ->
                online = up
                main.post { publish() }
            }
        ).also { it.start() }
    }

    /**
     * MQTT 收到的指令。
     *
     * 载荷有两种来源，靠内容区分：小爱技能送来的是剧名原文（任意文本），米家送来的是
     * `on`/`off`。后者要靠映射表查昵称，映射还没拉到就先记着，等轮询补上——这种极端
     * 情况用户再喊一次即可。
     */
    private fun onMqtt(topic: String, payload: String) {
        XiaoAiVoice.queryOf(payload)?.let { query ->
            handled[topic] = System.currentTimeMillis()
            val action = XiaoAiVoice.textAction(query) ?: return
            main.post { if(running) onAction(action) }
            return
        }
        if(!payload.equals("on", ignoreCase = true)) return
        val name = names[topic] ?: return
        val action = XiaoAiVoice.actionOf(name) ?: return
        handled[topic] = System.currentTimeMillis()
        main.post { if(running) onAction(action) }
    }

    /** 只拉列表、不发动作：用于 MQTT 连上时确认要订阅哪些主题。 */
    private fun refresh() {
        val topics = runCatching { fetch() }.getOrNull() ?: return
        applyTopics(topics)
        if(topics.isNotEmpty()) primed = true
        main.post { publish() }
    }

    /** 更新主题与昵称的映射，主题有增减就重新订阅。 */
    private fun applyTopics(topics: List<XiaoAiVoice.Topic>) {
        topics.forEach { names[it.topic] = it.name }
        count = topics.size
        primed = true
        session?.subscribe(topics.map { it.topic })
    }

    private fun publish(forced: String? = null) {
        val text = forced ?: state()
        if(text == reported) return
        reported = text
        onState(text)
    }

    /**
     * 轮询兜底：只在 MQTT 没连上时触发动作。
     *
     * 第一轮只记基线不执行：进程刚起来时列表里全是历史消息，照单全收会莫名其妙开始播。
     */
    private fun consume(topics: List<XiaoAiVoice.Topic>) {
        if(!baseline) {
            topics.forEach { seen[it.topic] = it.unix }
            baseline = true
            return
        }
        if(online) {
            // MQTT 在线时它已经实时处理过了，这里只更新基线，免得重连后又补播一遍
            topics.forEach { seen[it.topic] = it.unix }
            return
        }
        val now = System.currentTimeMillis()
        for(topic in topics) {
            if((seen[topic.topic] ?: 0L) >= topic.unix) continue
            seen[topic.topic] = topic.unix
            val recent = handled[topic.topic] ?: 0L
            if(now - recent < MQTT_GRACE_MS) continue
            if(topic.msg.equals("on", ignoreCase = true)) {
                val action = XiaoAiVoice.actionOf(topic.name) ?: continue
                onAction(action)
                continue
            }
            // 非开关载荷 = 技能写进来的原话。轮询是兜底通道，这条也得认，否则 MQTT 一断
            // 就只能喊固定昵称了。
            val query = XiaoAiVoice.queryOf(topic.msg) ?: continue
            val action = XiaoAiVoice.textAction(query) ?: continue
            onAction(action)
        }
    }

    private fun fetch(): List<XiaoAiVoice.Topic> {
        val uid = XiaoAiAccess.uid
        require(uid.isNotBlank()) { "未填写巴法云私钥" }
        val url = ENDPOINT + "?openID=" + URLEncoder.encode(uid, "UTF-8") + "&type=1"
        val request = Request.Builder().url(url).header("User-Agent", "HongguoTV").build()
        http.newCall(request).execute().use { response ->
            if(!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            XiaoAiVoice.problem(body)?.let { throw IllegalStateException(it) }
            return XiaoAiVoice.parse(body)
        }
    }
}
