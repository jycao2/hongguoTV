// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
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
 *
 * 电视这边不开长连接（Android TV 的后台 socket 太容易被系统掐）：每 2 秒拉一次主题列表，
 * 哪个主题的 msg 变成 on 且时间戳比上次新，就执行一次。代价是最多 2 秒的延迟，换来的是
 * 断网、待机、进程重启都不需要重连状态机。
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
 * 轮询巴法云，把「某个主题刚被打开」翻成动作。
 *
 * 只在电视前台跑：语音入口的绝大多数使用场景就是「电视开着、人不想找遥控器」，
 * 后台常驻轮询既费流量又会被系统的省电策略打断，收益不成正比。
 */
class XiaoAiLink(
    private val onAction: (XiaoAiVoice.Action) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        /** HTTPS：私钥在查询串里，不走明文。 */
        private const val ENDPOINT = "https://apis.bemfa.com/vb/api/v2/allTopic"

        /** 2 秒一轮：再快对巴法不礼貌，再慢用户会以为没反应。 */
        private const val POLL_MS = 2_000L
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
    private val seen = HashMap<String, Long>()
    private var primed = false
    private var running = false
    private var count = 0
    private var lastError: String? = null
    private var reported = ""

    private val tick = object : Runnable {
        override fun run() {
            if(!running) return
            worker.execute {
                val result = runCatching { fetch() }
                main.post {
                    if(!running) return@post
                    result.onSuccess { topics ->
                        lastError = null
                        count = topics.size
                        consume(topics)
                    }.onFailure { error ->
                        lastError = error.message?.takeIf { it.isNotBlank() } ?: "网络错误"
                    }
                    publish()
                    main.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    fun start() {
        if(running) return
        if(XiaoAiAccess.uid.isBlank()) { publish("未填写巴法云私钥"); return }
        running = true
        primed = false
        seen.clear()
        lastError = null
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    fun destroy() {
        stop()
        worker.shutdownNow()
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    fun state(): String = when {
        XiaoAiAccess.uid.isBlank() -> "未填写巴法云私钥"
        lastError != null -> "连不上巴法云（$lastError）"
        !primed -> "正在连接…"
        count == 0 -> "已连接，但没有以 ${XiaoAiVoice.TOPIC_PREFIX} 开头的主题"
        else -> "已连接 · $count 个设备"
    }

    private fun publish(forced: String? = null) {
        val text = forced ?: state()
        if(text == reported) return
        reported = text
        onState(text)
    }

    /**
     * 第一轮只记基线不执行：进程刚起来时列表里全是历史消息，照单全收会莫名其妙开始播。
     */
    private fun consume(topics: List<XiaoAiVoice.Topic>) {
        if(!primed) {
            topics.forEach { seen[it.topic] = it.unix }
            primed = true
            return
        }
        for(topic in topics) {
            if(!topic.msg.equals("on", ignoreCase = true)) continue
            if((seen[topic.topic] ?: 0L) >= topic.unix) continue
            seen[topic.topic] = topic.unix
            val action = XiaoAiVoice.actionOf(topic.name) ?: continue
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
