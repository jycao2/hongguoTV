// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 遥控协议的数据模型与命令白名单。纯数据 + 纯函数，不碰 socket，可单测。 */

enum class PlaybackSource { OFFICIAL, THIRD, NONE }

/** 线路组摘要，只进快照、不含量；完整成员走 /episodes。 */
data class EpisodeGroup(val name: String, val count: Int)

/** /episodes 返回的完整成员。 */
data class EpisodeEntry(val index: Int, val title: String)
data class EpisodeGroupDetail(val name: String, val episodes: List<EpisodeEntry>)

/** 浏览模式的「继续观看」条目。 */
data class ResumeItem(
    val id: String,
    val title: String,
    val caption: String,
    val positionMs: Long,
    val durationMs: Long,
    /** 原始封面地址。手机端不直接用它，而是交给电视代取（见 /cover），免得撞上防盗链。 */
    val cover: String = ""
)

/**
 * 播放快照。只由主线程写、socket 线程读，所以业务字段全部不可变；
 * epoch/seq/clients 这类会话级字段由 Hub 在序列化时补，不存在这里。
 */
data class PlaybackSnapshot(
    val source: PlaybackSource = PlaybackSource.NONE,
    val title: String = "",
    val groups: List<EpisodeGroup> = emptyList(),
    val group: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val muted: Boolean = false,
    val danmaku: Boolean = true,
    /**
     * 当前片源支不支持弹幕。三方片源没有弹幕，「关」和「不支持」必须区分开：
     * 否则手机上的弹幕按钮永远显示「关」，点了也没有任何反应。
     */
    val danmakuAvailable: Boolean = true,
    val error: String? = null,
    val foreground: Boolean = true,
    val screen: String = "home"
) {
    /** 有没有可控制的播放器。false 时手机端禁用遥控面板（面板所有控件都依赖它）。 */
    val controllable: Boolean get() = source != PlaybackSource.NONE
}

/** 上行命令。解析即校验，构造出来的对象一定是合法值。 */
sealed class RemoteCommand {
    data class Play(val on: Boolean) : RemoteCommand()
    data class Seek(val toMs: Long) : RemoteCommand()
    data class Step(val byMs: Long) : RemoteCommand()
    data class Episode(val group: Int, val index: Int) : RemoteCommand()
    data class Speed(val value: Float) : RemoteCommand()
    data class Muted(val on: Boolean) : RemoteCommand()
    data class Danmaku(val on: Boolean) : RemoteCommand()
    data class Dpad(val key: String, val holdMs: Long) : RemoteCommand()
    data object Back : RemoteCommand()
    data class Menu(val long: Boolean) : RemoteCommand()
    data class Search(val query: String) : RemoteCommand()
    data class Resume(val id: String) : RemoteCommand()

    /**
     * 直接跳到一级模块。
     *
     * 参数用稳定英文键而不是中文名：界面文案改名（「片源」→「三方源」）不该让外部桥接失效，
     * 手机页面、语音桥、自动化脚本都只依赖这里的键。合法值见 [REMOTE_MODULES]。
     */
    data class Open(val module: String) : RemoteCommand()

    /**
     * 「按名字找剧」。
     *
     * 默认**只出结果、不起播**：搜索结果里混着预告、同名不同内容、空壳条目，
     * 替用户挑一个的代价是「喊了《X》结果播了《Y》」。停在结果页让人点一下，比猜可靠。
     *
     * [episode] 是显式说的集号（`文盲修仙第七集` → 7）。说了集号意图就够明确了，
     * 此时才允许直接落到那一集；0 表示没指定。
     *
     * 界面侧决定优先搜哪套内容（停在片源页就搜片源，否则搜红果主站）。
     */
    data class Watch(val query: String, val episode: Int = 0) : RemoteCommand()

    /**
     * 把整句话交给电视自己理解（[VoiceIntent.parse]）。
     *
     * 遥控页的输入框走这条：说的可能是剧名，也可能是「检查更新」「历史记录第二个」。
     * 解析器认不出任何动作时本来就退化成「当剧名搜」，所以一个入口两用，
     * 不用让用户在遥控页上先选「这是指令还是片名」。
     */
    data class Say(val text: String) : RemoteCommand()

    /**
     * 通用界面动作：把 App 里每个「点了会发生事」的功能变成一个可外部调用的 ID。
     *
     * 这是语音入口的落点。之前只有 open/watch/search 三条，能做的仅限「跳页」和「搜片名」；
     * 想知道有没有新版、想把主题换成白的、想看历史记录第二条，都没有对应的命令可用。
     * 有了 [Act] 以后，语音能操作的范围 = [REMOTE_ACTIONS] 里登记了什么，加新功能时只要
     * 登记一个键、在界面层接一下，不必再动协议。
     *
     * [arg] 是可选参数，格式由动作自己定义（目前只有列表定位用 `<列表>:<序号>`）。
     * 白名单校验在 [RemoteProtocol.parse]，执行由界面层负责。
     */
    data class Act(val id: String, val arg: String = "") : RemoteCommand()

    data object Detach : RemoteCommand()

    /**
     * 断线重连后是否可以重放。只保留「把状态设成 X」的幂等意图；
     * step/dpad/back/menu/search 有时效性，重连时重放会变成「瞬间跳 5 集」。
     */
    val durable: Boolean
        get() = when(this) {
            is Play, is Seek, is Episode, is Speed, is Muted, is Danmaku, is Resume -> true
            else -> false
        }
}

/** 播放类命令交给适配器；导航类命令只在没有播放器时才有意义，交给 controller。 */
val RemoteCommand.isPlayback: Boolean
    get() = when(this) {
        is RemoteCommand.Play, is RemoteCommand.Seek, is RemoteCommand.Step,
        is RemoteCommand.Episode, is RemoteCommand.Speed, is RemoteCommand.Muted,
        is RemoteCommand.Danmaku -> true
        else -> false
    }

const val REMOTE_SPEED_MIN = 0.5f
const val REMOTE_SPEED_MAX = 2.0f
const val REMOTE_SEEK_MAX_MS = 24L * 60 * 60 * 1000
const val REMOTE_STEP_MAX_MS = 10L * 60 * 1000
private const val SEARCH_MAX = 80
private val DPAD_KEYS = setOf("up", "down", "left", "right", "center")

/**
 * `open` 命令允许的模块键。
 *
 * 白名单而不是自由字符串：命令来自局域网，任何拼错或恶意的键都该在入口被拒，
 * 不要让界面层去猜一个不存在的页签。
 */
val REMOTE_MODULES = setOf(
    "home",     // 首页
    "search",   // 搜索
    "rank",     // 排行榜
    "favorite", // 收藏
    "history",  // 最近观看
    "settings", // 设置
    "source",   // 片源（三方 CMS）
    "library"   // 本地库
)

/**
 * `act` 命令允许的动作键。
 *
 * 和 [REMOTE_MODULES] 同样的原则：白名单而不是自由字符串。命名规则 `<对象>.<动词>`，
 * 键名一旦发布就当接口用——界面文案改了（「系统风格」→「主题」）不许改键。
 *
 * 这些键全部是**幂等或可重复**的操作：执行两次不会把用户带到奇怪的地方。
 * 「下一集」「切主题」这类切换型看着不幂等，但重放只会再切一次，比丢掉指令可接受。
 */
val REMOTE_ACTIONS = setOf(
    // 外观与界面
    "theme.toggle",     // 切换深/浅主题
    "theme.set",        // 指定主题：arg = dark | light
    "clock.toggle",     // 顶栏系统时间
    "netspeed.toggle",  // 右上角实时网速悬浮条（切换）
    "netspeed.set",     // 显式开 / 关网速：arg = on | off
    "backdrop.reset",   // 恢复默认背景
    "backdrop.set",     // 最近一张快传图片设为电视背景
    "dialog.close",     // 关掉当前最上层的弹窗（没有弹窗时不执行）
    "dialog.confirm",   // 点最上层确认框的「确认」（接收上传 / 安装 / 设背景）
    // 播放偏好（设置页里的开关）
    "autonext.toggle",  // 自动连播
    "autoseason.toggle",// 系列剧连播
    "danmaku.toggle",   // 弹幕：在播放页切当前剧，其它页切总开关
    "autoskip.toggle",  // 不兼容片源自动跳过
    // 播放控制（语义化版本，语音端不知道集号，只说「下一集」）
    "play.toggle",      // 暂停 / 继续
    "play.next",        // 下一集
    "play.prev",        // 上一集
    "play.replay",      // 从头播放本集
    "player.close",     // 退出播放器，回到详情页
    "panel.episodes",   // 呼出选集面板（电视播放页 / 片源播放页）
    "panel.quick",      // 呼出播放快捷面板（清晰度/倍速/弹幕/更多，仅播放页）
    "skip.intro",       // 按本剧片头标记跳过片头
    "speed.up",         // 倍速 +1 档（播放页只作用于本剧）
    "speed.down",       // 倍速 -1 档
    "sleep.set",        // 定时停止：arg = min:<分钟> | ep:<集数> | off（取消）
    // 列表定位：arg = "<列表>:<序号>"，序号从 1 开始，见 [REMOTE_LIST_KINDS]
    "list.play",
    // 继续观看
    "resume.latest",    // 接着看最近一条
    // 详情页
    "favorite.toggle",  // 收藏 / 取消收藏当前剧
    "favorite.check",   // 手动检查收藏的剧有没有更新
    // 浏览
    "type.set",         // 切内容类型：arg = comic | short | ai
    "random.pick",      // 随机推荐一部并打开详情：arg = comic | short | ai（空=当前类型）
    // 设备音量（系统媒体音量，不是播放器内部音量）
    "volume.up",
    "volume.down",
    "volume.mute",      // arg = on | off（空=切换）
    // 关于与工具
    "update.check",     // 检查版本更新
    "check.interface",  // 接口自检
    "cache.clear",      // 清理缓存
    "stats.show",       // 观看统计
    "backup.show",      // 本地备份
    "quick.send",       // 手机快传（打开面板并生成新二维码）
    "transfer.refresh", // 刷新手机快传的二维码
    "transfer.files",   // 打开快传文件列表
    "transfer.clear",   // 清空快传收到的全部文件
    "transfer.preview", // 全屏预览 / 播放最近一个快传文件
    "history.clear",    // 清空搜索历史（主站 + 片源）
    "remote.address",   // 遥控地址
    "xiaoai.show",      // 小爱同学设置
    "account.show",     // 账号
    "hidden.restore",   // 恢复隐藏推荐
    // 网络代理（DNS 污染/SNI 阻断场景的开关）
    "proxy.set",        // 开关代理：arg = on | off（空=切换；开启要求配置完整）
    "proxy.status"      // 播报当前代理状态
)

/** `list.play` 的列表键：`history` 最近观看、`favorite` 收藏、`catalog` 当前列表、`episodes` 选集。 */
val REMOTE_LIST_KINDS = setOf("history", "favorite", "catalog", "episodes", "current")

/** `type.set` / `random.pick` 的类型键，直接对应 [ContentType.storedValue]。 */
val REMOTE_TYPE_KEYS = ContentType.entries.map { it.storedValue }.toSet()

/**
 * `sleep.set` 的参数：`min:30` 三十分钟后停，`ep:1` 看完这一集就停。
 *
 * 只开放界面上已有的那几档（15/30/60/90 分钟、1/3/5 集），
 * 语音说「四十分钟」会被归到最近的档，而不是造出一个界面里选不到的状态。
 */
fun sleepTarget(arg: String): Pair<String, Int>? {
    val parts = arg.split(':', limit = 2)
    if(parts.size != 2) return null
    val value = parts[1].toIntOrNull() ?: return null
    return when(parts[0]) {
        "min" -> value.takeIf { it in 1..600 }?.let { "min" to it }
        "ep" -> value.takeIf { it in 1..99 }?.let { "ep" to it }
        else -> null
    }
}

/** 动作参数的长度上限；正常只有 `history:12` 这种。 */
const val REMOTE_ACT_ARG_MAX = 48

/**
 * 动作参数只允许小写字母、数字和 `: . _ -` 三个分隔符。
 *
 * 参数来自语音识别的自由文本，必须在这里收窄成一个几乎没有表达力的字符集：
 * 界面层拿到的东西不该再需要做一次转义或注入检查。
 */
fun actArgValid(arg: String): Boolean =
    arg.length <= REMOTE_ACT_ARG_MAX && arg.all { it in 'a'..'z' || it in '0'..'9' || it == ':' || it == '.' || it == '_' || it == '-' }

/** `list.play` 的参数：`history:2` → (history, 2)。序号从 1 起，格式不符或越界返回 null。 */
fun listTarget(arg: String): Pair<String, Int>? {
    val parts = arg.split(':', limit = 2)
    if(parts.size != 2) return null
    val kind = parts[0]
    if(kind !in REMOTE_LIST_KINDS) return null
    val index = parts[1].toIntOrNull() ?: return null
    return if(index in 1..999) kind to index else null
}

object RemoteProtocol {
    /** 单条命令请求体上限；正常命令都在 200 字节内。 */
    const val MAX_BODY = 2048

    /**
     * 门禁密钥的最短长度（十六进制字符数）。
     *
     * 16 个 hex 字符 = 64 bit，穷举在局域网内不可行；再短就只剩阻挠作用了。
     * 低于这个长度的外部注入值会被 Hub 丢弃并回退到安全随机。
     */
    const val MIN_TOKEN_LENGTH = 16

    /** 生成一个新密钥。16 字节 = 128 bit，128 个 hex 字符。 */
    fun newToken(): String = ByteArray(16)
        .also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** 命令白名单解析。任何越界、缺字段、类型不符都返回 null，不做「尽力猜测」。 */
    fun parse(body: String): RemoteCommand? {
        if(body.length > MAX_BODY) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when(val cmd = json.optString("cmd")) {
            "play" -> RemoteCommand.Play(bool(json, "on") ?: return null)
            "seek" -> RemoteCommand.Seek(long(json, "to")?.takeIf { it in 0..REMOTE_SEEK_MAX_MS } ?: return null)
            "step" -> RemoteCommand.Step(
                long(json, "by")?.takeIf { it != 0L && it in -REMOTE_STEP_MAX_MS..REMOTE_STEP_MAX_MS } ?: return null
            )
            "episode" -> RemoteCommand.Episode(
                group = int(json, "group")?.takeIf { it in 0..999 } ?: return null,
                index = int(json, "index")?.takeIf { it in 0..9999 } ?: return null
            )
            "speed" -> RemoteCommand.Speed(
                double(json, "value")?.toFloat()?.takeIf { it in REMOTE_SPEED_MIN..REMOTE_SPEED_MAX } ?: return null
            )
            "muted" -> RemoteCommand.Muted(bool(json, "on") ?: return null)
            "danmaku" -> RemoteCommand.Danmaku(bool(json, "on") ?: return null)
            "dpad" -> {
                val key = json.optString("key").lowercase()
                if(key !in DPAD_KEYS) null
                else RemoteCommand.Dpad(key, (long(json, "hold") ?: 0L).coerceIn(0, 5000))
            }
            "back" -> RemoteCommand.Back
            "menu" -> RemoteCommand.Menu(json.optBoolean("long", false))
            "search" -> {
                val query = json.optString("query").trim()
                if(query.isEmpty() || query.length > SEARCH_MAX || query.any { it.isISOControl() }) null
                else RemoteCommand.Search(query)
            }
            "resume" -> {
                val id = json.optString("id")
                if(id.isEmpty() || id.length > 128 || id.any { it.isISOControl() }) null
                else RemoteCommand.Resume(id)
            }
            "open" -> json.optString("module").trim().lowercase()
                .takeIf { it in REMOTE_MODULES }?.let { RemoteCommand.Open(it) }
            "watch" -> {
                val query = json.optString("query").trim()
                val episode = if(json.has("episode")) int(json, "episode")?.takeIf { it in 0..9999 } ?: return null else 0
                if(query.isEmpty() || query.length > SEARCH_MAX || query.any { it.isISOControl() }) null
                else RemoteCommand.Watch(query, episode)
            }
            "say" -> {
                val text = json.optString("text").trim()
                if(text.isEmpty() || text.length > SEARCH_MAX || text.any { it.isISOControl() }) null
                else RemoteCommand.Say(text)
            }
            "act" -> {
                val id = json.optString("id").trim().lowercase()
                val arg = json.optString("arg").trim()
                when {
                    id !in REMOTE_ACTIONS -> null
                    !actArgValid(arg) -> null
                    // 列表定位必须带一个合法的列表键：一个不带参数的 list.play 没有意义，
                    // 与其让界面层去猜「你说的是哪个列表」，不如在入口就拒掉。
                    id == "list.play" && listTarget(arg) == null -> null
                    // 带参数的键同样在入口校验：界面层只该收到自己认识的值
                    id == "type.set" && arg !in REMOTE_TYPE_KEYS -> null
                    id == "random.pick" && arg.isNotEmpty() && arg !in REMOTE_TYPE_KEYS -> null
                    id == "volume.mute" && arg !in setOf("", "on", "off") -> null
                    id == "proxy.set" && arg !in setOf("", "on", "off") -> null
                    id == "netspeed.set" && arg !in setOf("on", "off") -> null
                    // 无参数动作：界面层只该收到空 arg，带尾巴的一律在入口拒掉
                    id in setOf("backdrop.set", "dialog.confirm", "transfer.preview") && arg.isNotEmpty() -> null
                    id == "sleep.set" && sleepTarget(arg) == null && arg != "off" -> null
                    else -> RemoteCommand.Act(id, arg)
                }
            }
            "detach" -> RemoteCommand.Detach
            else -> null
        }
    }

    /** 命令序号，用于丢弃乱序与重放；缺失视为无序号（兼容旧页面）。 */
    fun seqOf(body: String): Long? = runCatching { JSONObject(body).optLong("seq", 0L) }.getOrNull()?.takeIf { it > 0 }

    /**
     * 页面会话标识。被顶替的页面如果恰好在发命令，它的高序号会污染新页面的水位线，
     * 让新手机的每一条命令都被判成过期且毫无提示。带上 sid 就能直接丢掉旧会话的命令。
     */
    fun sidOf(body: String): String? = runCatching { JSONObject(body).optString("sid") }
        .getOrNull()?.takeIf { it.length in 6..40 && it.all { ch -> ch.isLetterOrDigit() } }

    private fun bool(json: JSONObject, key: String): Boolean? =
        if(json.has(key) && json.opt(key) is Boolean) json.optBoolean(key) else null
    private fun int(json: JSONObject, key: String): Int? {
        val value = (json.opt(key) as? Number)?.toDouble() ?: return null
        if(value != Math.floor(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
        return value.toInt()
    }
    private fun long(json: JSONObject, key: String): Long? {
        val value = (json.opt(key) as? Number)?.toDouble() ?: return null
        if(value != Math.floor(value) || kotlin.math.abs(value) > 9.0e15) return null
        return value.toLong()
    }
    private fun double(json: JSONObject, key: String): Double? = (json.opt(key) as? Number)?.toDouble()
}

/** 快照序列化。epoch/seq 是会话级信息，clients/evictions 是连接级信息，都由 Hub 注入。 */
fun PlaybackSnapshot.toJson(epoch: Long, seq: Long, ts: Long, clients: Int, evictions: Int): String = JSONObject().apply {
    put("epoch", epoch)
    put("seq", seq)
    put("ts", ts)
    put("source", source.name)
    put("title", title)
    put("groups", JSONArray().also { array -> groups.forEach { array.put(JSONObject().put("name", it.name).put("count", it.count)) } })
    put("group", group)
    put("episode", episode)
    put("positionMs", positionMs)
    put("durationMs", durationMs)
    put("playing", playing)
    put("buffering", buffering)
    put("speed", speed.toDouble())
    put("muted", muted)
    put("danmaku", danmaku)
    put("caps", JSONObject().put("danmaku", danmakuAvailable))
    put("error", error ?: JSONObject.NULL)
    put("tv", JSONObject().put("foreground", foreground).put("screen", screen))
    put("remote", JSONObject().put("clients", clients).put("evictions", evictions))
}.toString()

fun List<EpisodeGroupDetail>.toEpisodesJson(): String = JSONObject().put("groups", JSONArray().also { out ->
    forEach { group ->
        out.put(JSONObject().put("name", group.name).put("episodes", JSONArray().also { items ->
            group.episodes.forEach { items.put(JSONObject().put("index", it.index).put("title", it.title)) }
        }))
    }
}).toString()

fun List<ResumeItem>.toResumeJson(): String = JSONObject().put("items", JSONArray().also { out ->
    forEach {
        out.put(JSONObject().put("id", it.id).put("title", it.title).put("caption", it.caption)
            .put("positionMs", it.positionMs).put("durationMs", it.durationMs).put("cover", it.cover))
    }
}).toString()

/** 播放适配器：官方与三方各实现一份，遥控代码里不出现「是不是三方」的分支。 */
interface RemoteTarget {
    val source: PlaybackSource
    /** 主线程调用，执行一条已校验的播放类命令。 */
    fun apply(cmd: RemoteCommand): Boolean
    /** 主线程调用，按需拉取完整剧集列表。 */
    fun episodes(): List<EpisodeGroupDetail>
}

/** 导航类命令的落点：方向/返回/菜单/搜索/点播，只在 Activity 里才有正确语义。 */
interface RemoteController {
    /** 主线程调用。返回 false 表示当前界面不响应这条命令。 */
    fun navigate(cmd: RemoteCommand): Boolean
    /** 槽位从空变满或从满变空时回调，用于切换屏幕常亮持有位。 */
    fun onClientsChanged(clients: Int) {}
    /** 会话结束（超时或防拉锯销毁），电视端需要关掉遥控入口。 */
    fun onSessionEnded() {}
}
