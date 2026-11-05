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
