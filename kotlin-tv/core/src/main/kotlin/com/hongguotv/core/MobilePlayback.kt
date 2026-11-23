// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 官方播放模型接口：对齐官方 7.4.5.32 客户端形态 `/novel/player/multi_video_model/v1`。
 *
 * 2026-11-20 全天官方滚动收紧：`player/v1`（dr_scene 形态）与旧端点 `video_model/v1/` 先后返 200+0。
 * 上游 tsukiyo-zero/hongguoTV@573d75a 反编译 7.4.5.32 确认：新版回到 `$POST /novel/player/multi_video_model/v1`
 * （不带 dr_scene），`version_code=74532` + `mixed_video_id_map` + v5 签名可拿到模型（其 14:09 实测通过）。
 * 旧参数 `data.video_model` 直挂格式仍由 [directModel] 兼容，`1004` 分组格式走通用解析。
 */
internal object MobilePlayback {
    private const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    /** 7.4.5.32 起官方客户端回到 v1 路径；`:dr_scene` 变体（player/default）对匿名请求返回空体。 */
    /** 7.4.3.32 抓包口径（openhongguo `PathPlaybackV5`）：`player/v1` + `dr_scene=player`。 */
    private const val PATH = "/novel/player/multi_video_model/player/v1"
    // V5 播放 POST 用 tt-ok 形态 + PGT-AN10/Android 12（PhoenixReadApi V5PlaybackUserAgent）；
    // video_tab GET 才用 Cronet 形态。此前两者混用，UA 也是错的一环。
    const val UA = PassportClient.V5_PLAYBACK_UA
    /** 完整 28 项 query，缺一项都可能落到空响应；设备字段一律取自注册画像，避免两套指纹。 */
    private fun query(identity: PlaybackDeviceSession.Profile): Map<String, String> {
        val v = identity.values
        fun value(key: String, fallback: String) = v[key]?.takeIf { it.isNotBlank() } ?: fallback
        val version = value("version_code", "74332")
        return linkedMapOf(
            "device_platform" to value("device_platform", "android"),
            "os" to value("os", "android"),
            "ssmix" to "a",
            "cdid" to v.getValue("cdid"),
            "channel" to value("channel", "xiaomi_8662_64"),
            "aid" to value("aid", "8662"),
            "app_name" to value("app_name", "novelread"),
            "version_code" to version,
            "version_name" to value("version_name", "7.4.3.32"),
            "manifest_version_code" to value("manifest_version_code", version),
            "update_version_code" to value("update_version_code", version),
            "resolution" to value("resolution", "1200*2670"),
            "dpi" to value("dpi", "460"),
            "device_type" to value("device_type", "23127PN0CC"),
            "device_brand" to value("device_brand", "Xiaomi"),
            "language" to "zh",
            "os_api" to value("os_api", "34"),
            "os_version" to value("os_version", "14"),
            "ac" to "wifi",
            "host_abi" to value("host_abi", "arm64-v8a"),
            "dragon_device_type" to value("dragon_device_type", "phone"),
            "pv_player" to version,
            "compliance_status" to "0",
            "need_personal_recommend" to "1",
            "player_so_load" to "1",
            "is_android_pad_screen" to "0",
            "iid" to v.getValue("iid"),
            "device_id" to v.getValue("device_id"),
        )
    }

    /** 返回 `video_model` 对象；调用方复用既有的 `modelOf`/`streamFromModel` 解析与解码逻辑。
     *  200 + 0 字节是站方的行为风控信号，原样抛 [EmptyPlaybackResponseException] 让上层触发全局冷却；
     *  其余问题一律返回 null（由上层决定是否走一次 video_tab 兜底），线程被取消时原样抛出。 */
    fun model(
        id: String,
        identity: PlaybackDeviceSession.Profile,
        http: OkHttpClient,
        signer: Signer,
        now: Long,
        deviceLevel: Int = 4,
    ): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        // 7.4.3.32 形态：完整 biz_param + mixed_video_id_map。
        // 2026-11-23 按同行 0.27.4/0.27.6 实测配方修正两处（此前我们正好写反）：
        //  1) device_level 固定 4（我们曾填 1）；4 空响应时由上层升到 5 再试同一身份。
        //  2) PLAYER 直连**不带 dr_scene**——只有 PRELOAD 请求才带 dr_scene="preload"，
        //     之前照抄 openhongguo 抓包加的 dr_scene="player" 会被判成无效请求。
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":$deviceLevel,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}"""
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val signed = signer.signPlaybackV5(HOST + PATH, query(identity), body)
        // 2026-11-23 22:10 关键修正：V5 播放就是「Medusa v5 那一代」，头集合是三/四头
        //   x-ss-stub + x-khronos + x-helios + x-medusa（GET video_tab 时无 stub）。
        // 此前这里调 realSignedHeaders 把它们全当「占位头」过滤掉，换成了上一代 551 的
        //   x-gorgon/x-ladon/x-argus/x-ss-req-ticket —— 服务端要的签名我们根本没发出去，
        // 这是 200+0 的直接原因（PhoenixReadApi 2026-11-23 9ba235c 实测口径）。
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("X-XS-From-Web", "0")
            .header("x-ss-dp", "8662")
            .header("lc", "101")
            .header("Content-Encoding", "gzip")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val text = try {
            // 播放模型请求不带账号 Cookie，也不复用注册身份之外的会话。
            val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            RequestScope.execute(transport.newCall(request)) { response ->
                if (response.code == 429) throw PlaybackRateLimitedException(ContentRepository.retryAfterMs(response))
                if (!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
        } catch (limited: PlaybackRateLimitedException) {
            throw limited
        } catch (problem: java.io.InterruptedIOException) {
            throw problem
        } catch (problem: IOException) {
            ContentRepository.probeLog?.invoke("v5 请求失败 ${problem.message}")
            return null
        } catch (problem: RuntimeException) {
            ContentRepository.probeLog?.invoke("v5 请求异常 ${problem.message}")
            return null
        }
        if (text.isBlank()) {
            // 2026-11-20 抓包：HTTP 200 + content-length:0，网关 51~62ms 直接丢弃或后端 114~176ms 返空。
            // 这不是「签名错了」，是服务端行为风控；上层据此进入冷却，不再补发任何请求。
            ContentRepository.probeLog?.invoke("v5 空响应（HTTP 200 / 0 字节）")
            throw EmptyPlaybackResponseException()
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: run {
            ContentRepository.probeLog?.invoke("v5 非 JSON body=${text.take(120)}")
            return null
        }
        val code = root.optInt("code", root.optInt("Code", -1))
        if (code != 0) {
            ContentRepository.probeLog?.invoke("v5 code=$code body=${text.take(200)}")
            return null
        }
        val data = root.optJSONObject("data") ?: run {
            ContentRepository.probeLog?.invoke("v5 无 data len=${text.length} body=${text.take(240)}")
            return null
        }
        // 旧端点把模型直接挂在 `data.video_model`（可能是字符串），没有 1004 分组这一层。
        directModel(data)?.let { return it }
        val entries = data.optJSONObject("1004") ?: data
        val row = entries.optJSONObject(id) ?: entries.keys().asSequence()
            .mapNotNull { entries.optJSONObject(it) }.firstOrNull() ?: run {
            ContentRepository.probeLog?.invoke("v5 无剧集行 id=$id entries=${entries.keys().asSequence().toList().take(8)}")
            return null
        }
        val model = when (val value = row.opt("video_model")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: run {
            ContentRepository.probeLog?.invoke("v5 无 video_model rowKeys=${row.keys().asSequence().toList().take(10)}")
            return null
        }
        return model
    }

    /** 旧端点 `video_model/v1/` 的响应把模型直挂 `data.video_model`；v1 新形态没有这一层，走 1004 分组。 */
    private fun directModel(data: JSONObject): JSONObject? = when (val value = data.opt("video_model")) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun gzip(source: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(source) } }.toByteArray()
}
