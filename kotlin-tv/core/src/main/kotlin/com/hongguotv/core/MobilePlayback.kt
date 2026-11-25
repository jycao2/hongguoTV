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
    const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    /** 7.4.5.32 现行 App 协议（上游 0.16.14 实网验收）：默认落在 `reading.snssdk.com`，
     *  端点 `preload/v1`，`dr_scene=preload`、`device_level=2`。 */
    const val APP_HOST = "https://reading.snssdk.com"
    const val PRELOAD_PATH = "/novel/player/multi_video_model/preload/v1"
    /** 7.4.3.32 抓包口径（openhongguo `PathPlaybackV5`）：`player/v1`。 */
    const val PATH = "/novel/player/multi_video_model/player/v1"
    /** 平铺路径（上游 `FlatPath`）：与 [PATH] 互为轮换，服务端反向切换时靠它兜住。 */
    const val FLAT_PATH = "/novel/player/multi_video_model/v1"
    /** 单集现行模型接口（上游 tsukiyo-zero/hongguoTV 0.1.40 d859244 移植）：
     *  官方 7.4.5.32 客户端真实调用的取址端点（APK `RpcOperation("$POST /novel/player/video_model/v1/")` 确认），
     *  2026-11-24 起重新放行，实测取到 1080P；请求体是单集 `video_id` + biz_param，**不带 mixed_video_id_map**。 */
    const val CURRENT_MODEL_PATH = "/novel/player/video_model/v1/"
    /** 备用取址 GET（官方 `GetVideoModelRequest` 形态）。2026-11-22 上游 0.16.12 实网确认：
     *  主 POST 返空时，同一身份用 **v5 签名** 打这条 GET 能拿到模型（4 集跨 2 部剧）。 */
    private const val TAB_PATH = "/reading/bookapi/video_tab/multi_video_model/v1/"
    /** 单发批量最多带多少集：站方上限 20，留出余量取 15（与 [ContentRepository] 的窗口一致）。 */
    private const val MAX_BATCH = 15
    /** video_tab 是 GET + Cronet 传输，UA 与主 POST 的 tt-ok 形态不同。
     *  2026-11-25 对齐上游 0.1.37（ff4d1d2 实测表）：只有 74532 UA 能通过——版本指纹不齐会被静默丢弃。 */
    private const val TAB_UA = "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
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

    /** 现行 App（7.4.5.32）的精简 query：上游 0.16.14 实网通过的那一套。
     *  与老 28 项相比去掉了 resolution/dpi/dragon_device_type/pv_player/compliance_status/
     *  need_personal_recommend/player_so_load/is_android_pad_screen；设备字段仍取注册画像，
     *  保证 query 与 Medusa 里的设备串是同一条指纹。 */
    private fun appQuery(identity: PlaybackDeviceSession.Profile): Map<String, String> {
        val v = identity.values
        fun value(key: String, fallback: String) = v[key]?.takeIf { it.isNotBlank() } ?: fallback
        return linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74532", "version_name" to "7.4.5.32",
            "update_version_code" to "74532", "manifest_version_code" to "74532",
            "os" to "android", "ssmix" to "a",
            "channel" to value("channel", "update_64"),
            "device_platform" to "android",
            "device_type" to value("device_type", "25053RT47C"),
            "device_brand" to value("device_brand", "Redmi"),
            "os_version" to value("os_version", "16"), "os_api" to value("os_api", "36"),
            "ac" to "wifi", "language" to "zh",
            "host_abi" to value("host_abi", "arm64-v8a"),
            "iid" to v.getValue("iid"), "cdid" to v.getValue("cdid"),
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
        path: String = PATH,
        /** 账号登录 Cookie（`sessionid=…; sid_tt=…`）。默认空 = 匿名取址。
         *  2026-11-23 实验入口：官方 APK 是登录态取址，匿名请求没有计次主体，服务端最省事的处理就是给空。 */
        cookie: String = "",
        /** 账号登录 token（`X-Tt-Token` 头）。官方客户端取址同时带 Cookie 与该头，缺一都可能被判匿名。 */
        token: String = "",
        /** true = 走 7.4.5.32 现行 App 协议：`reading.snssdk.com` + `preload/v1` + `dr_scene=preload`
         *  + `device_level=2` + 平台默认 UA（上游 0.16.14 验收形态）。 */
        currentApp: Boolean = false,
        /** true = 兼容模型重取（2026-11-24，参考 tsukiyo-zero/hongguoTV issue #56）：biz_param 附加
         *  `codec_type:0 + force_codec:h264`，让全档 bytevc2 的剧集换发可硬解的 H.265（hvc1）档位。
         *  仅在「模型所有档位均为 bytevc2/bvc2」时由上层触发一次，失败静默回落 vc2 软解，不循环。 */
        compatCodec: Boolean = false,
    ): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        // 7.4.3.32 形态：完整 biz_param + mixed_video_id_map。
        // 2026-11-23 按同行 0.27.4/0.27.6 实测配方修正两处（此前我们正好写反）：
        //  1) device_level 固定 4（我们曾填 1）；4 空响应时由上层升到 5 再试同一身份。
        //  2) PLAYER 直连**不带 dr_scene**——只有 PRELOAD 请求才带 dr_scene="preload"，
        //     之前照抄 openhongguo 抓包加的 dr_scene="player" 会被判成无效请求。
        val level = if (currentApp) 2 else deviceLevel
        val scene = if (currentApp) ""","dr_scene":"preload"""" else ""
        val compat = if (compatCodec) ""","codec_type":0,"force_codec":"h264"""" else ""
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024$compat},"mixed_video_id_map":{"1004":["$id"]}$scene}"""
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val endpoint = if (currentApp) APP_HOST + PRELOAD_PATH else HOST + path
        val signed = signer.signPlaybackV5(endpoint, if (currentApp) appQuery(identity) else query(identity), body)
        // 2026-11-23 22:10 关键修正：V5 播放就是「Medusa v5 那一代」，头集合是三/四头
        //   x-ss-stub + x-khronos + x-helios + x-medusa（GET video_tab 时无 stub）。
        // 此前这里调 realSignedHeaders 把它们全当「占位头」过滤掉，换成了上一代 551 的
        //   x-gorgon/x-ladon/x-argus/x-ss-req-ticket —— 服务端要的签名我们根本没发出去，
        // 这是 200+0 的直接原因（PhoenixReadApi 2026-11-23 9ba235c 实测口径）。
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", if (currentApp) System.getProperty("http.agent", "okhttp/4.12.0") else UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .apply { if (!currentApp) header("X-XS-From-Web", "0") }
            .apply { if (!currentApp) header("x-ss-dp", "8662") }
            .apply { if (!currentApp) header("lc", "101") }
            .header("Content-Encoding", "gzip")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .apply { if (token.isNotBlank()) header("X-Tt-Token", token) }
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
        return parse(text, id)
    }

    /** 单集现行模型接口（上游 tsukiyo-zero/hongguoTV 0.1.40 d859244 移植，2026-11-25）：
     *  `POST /novel/player/video_model/v1/` 是官方 7.4.5.32 客户端真实调用的取址端点，11-24 起重新放行
     * （上游线上复测取到 1080P/720P）。与批量 preload 的差异：请求体是**单集 `video_id` + biz_param**，
     *  不带 mixed_video_id_map 也不带 dr_scene；query 与 preload 共用同一套 74532 公共参数；
     *  v5 签名（x-ss-stub 取 gzip body 的 MD5，由签名器内部处理）。
     *  响应为 `data.video_model`（单集直挂，可能序列化成字符串），由 [parse] 的 directModel 兼容。
     *  空响应照旧抛 [EmptyPlaybackResponseException]，其余失败返回 null。 */
    fun videoModelCurrent(
        id: String,
        identity: PlaybackDeviceSession.Profile,
        http: OkHttpClient,
        signer: Signer,
        now: Long,
    ): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        val payload = """{"video_id":"$id","biz_param":{"detail_page_version":0,"device_level":2,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}}"""
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val signed = signer.signPlaybackV5(APP_HOST + CURRENT_MODEL_PATH, appQuery(identity), body)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", System.getProperty("http.agent", "okhttp/4.12.0"))
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Content-Encoding", "gzip")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val text = try {
            val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            RequestScope.execute(transport.newCall(request)) { response ->
                if (response.code == 429) throw PlaybackRateLimitedException(ContentRepository.retryAfterMs(response))
                if (!response.isSuccessful) throw IOException("单集模型请求失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
        } catch (limited: PlaybackRateLimitedException) {
            throw limited
        } catch (problem: java.io.InterruptedIOException) {
            throw problem
        } catch (problem: IOException) {
            ContentRepository.probeLog?.invoke("video_model/v1 请求失败 ${problem.message}")
            return null
        } catch (problem: RuntimeException) {
            ContentRepository.probeLog?.invoke("video_model/v1 请求异常 ${problem.message}")
            return null
        }
        if (text.isBlank()) {
            ContentRepository.probeLog?.invoke("video_model/v1 空响应（HTTP 200 / 0 字节）")
            throw EmptyPlaybackResponseException()
        }
        return parse(text, id)
    }

    /** 一窗剧集的批量取址：同一发 `preload/v1`，`mixed_video_id_map["1004"]` 放多个 id。
     *
     *  2026-11-23 深夜定位「上下集加载慢」：`ContentRepository.warm()` 的批量路径还在用
     *  `playerRequest`（v5 占位头被 `realSignedHeaders` 换成了 551 的 gorgon/ladon/argus），
     *  电视日志每轮都是 `window warmed 1/15 batch=0`——预取从来没成功过，等于只有「下一集」
     *  靠单集预取兜着，「上一集」永远是冷启动现取一次。
     *  批量走和主取址同一条已验收协议（preload + Medusa F21=312 + v5 四头），一次请求喂满一窗。
     *
     *  返回 id → `video_model` 映射；空响应抛 [EmptyPlaybackResponseException]（由上层决定退避），
     *  其余失败返回空表（批量只是优化，绝不因此 trip 全局冷却）。 */
    fun batch(
        ids: List<String>,
        identity: PlaybackDeviceSession.Profile,
        http: OkHttpClient,
        signer: Signer,
    ): Map<String, JSONObject> {
        val clean = ids.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct().take(MAX_BATCH)
        if (clean.isEmpty()) return emptyMap()
        val array = org.json.JSONArray().also { target -> clean.forEach(target::put) }
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":2,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":$array},"dr_scene":"preload"}"""
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val signed = signer.signPlaybackV5(APP_HOST + PRELOAD_PATH, appQuery(identity), body)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", System.getProperty("http.agent", "okhttp/4.12.0"))
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Content-Encoding", "gzip")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val text = try {
            val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            RequestScope.execute(transport.newCall(request)) { response ->
                if (response.code == 429) throw PlaybackRateLimitedException(ContentRepository.retryAfterMs(response))
                if (!response.isSuccessful) throw IOException("批量取址请求失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
        } catch (limited: PlaybackRateLimitedException) {
            throw limited
        } catch (problem: java.io.InterruptedIOException) {
            throw problem
        } catch (problem: IOException) {
            ContentRepository.probeLog?.invoke("批量取址请求失败 ${problem.message}")
            return emptyMap()
        } catch (problem: RuntimeException) {
            ContentRepository.probeLog?.invoke("批量取址请求异常 ${problem.message}")
            return emptyMap()
        }
        if (text.isBlank()) {
            ContentRepository.probeLog?.invoke("批量取址空响应（HTTP 200 / 0 字节）")
            throw EmptyPlaybackResponseException()
        }
        return parseAll(text).also { models ->
            ContentRepository.probeLog?.invoke("批量取址 ${clean.size} 集 → ${models.size} 个模型")
        }
    }

    /** 批量响应可能是 `data.video_model_data[vid]` / `data.1004[vid]` / 直接以 vid 为键。 */
    private fun parseAll(text: String): Map<String, JSONObject> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        if (root.optInt("code", root.optInt("Code", -1)) != 0) {
            ContentRepository.probeLog?.invoke("批量取址 code=${root.optInt("code", -1)} body=${text.take(160)}")
            return emptyMap()
        }
        val data = root.optJSONObject("data") ?: return emptyMap()
        val out = LinkedHashMap<String, JSONObject>()
        fun harvest(container: JSONObject) {
            for (key in container.keys()) {
                val row = container.optJSONObject(key) ?: continue
                val model = when (val value = row.opt("video_model")) {
                    is JSONObject -> value
                    is String -> runCatching { JSONObject(value) }.getOrNull()
                    else -> row.takeIf { it.has("video_list") || it.has("fallback_api") }
                } ?: continue
                out[key] = model
            }
        }
        data.optJSONObject("video_model_data")?.let(::harvest)
        data.optJSONObject("1004")?.let(::harvest)
        if (out.isEmpty()) harvest(data)
        return out
    }

    /**
     * 备用取址：`GET /reading/bookapi/video_tab/multi_video_model/v1/`，**必须用 v5 签名**。
     *
     * 2026-11-22 上游 0.16.12（`SIGNING_V5.md` Issue #83）实网验证：主 POST 持续 200+0 时，
     * 同一匿名身份用 v5 签这条 GET 能连续拿到 4 集模型并解出 MP4 头。此前这条 GET 用的是
     * 旧 v3 签名/profile，所以也拿空正文——正是我们 11-20 以来「tab 兜底从来没成功过」的原因。
     *
     * 两个细节不能省：
     *  1) 签名输入是 `ByteArray(0)`（MD5 = d41d8cd9…），**不是 null**——null 会被算成 16 字节零摘要，不等价；
     *  2) GET 不带 body/Content-Encoding/Content-Type/x-ss-stub，也不带账号 Cookie。
     */
    fun videoTab(
        id: String,
        identity: PlaybackDeviceSession.Profile,
        http: OkHttpClient,
        signer: Signer,
        now: Long,
        host: String = HOST,
        /** true = 兼容模型重取（2026-11-24）：query 附加 `codec_type=0 + force_codec=h264`。
         *  preload 通道对全 bvc2 剧的兼容重取可能整请求被空响应掉，而 video_tab 是空响应环境下
         *  唯一确认活着的通道——兼容重取失败后自动落到这里再试一次。 */
        compatCodec: Boolean = false,
    ): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        val v = identity.values
        fun value(key: String, fallback: String) = v[key]?.takeIf { it.isNotBlank() } ?: fallback
        // 设备字段取注册画像（同一台设备不能出现两套指纹），其余对齐官方 GetVideoModelRequest。
        // 2026-11-25 版本固定 74532（上游 0.1.37 ff4d1d2 实测）：74332/7.3.9.32 一族会被静默丢弃。
        val version = "74532"
        val values = linkedMapOf(
            "aid" to value("aid", "8662"),
            "app_name" to value("app_name", "novelread"),
            "version_code" to version,
            "version_name" to "7.4.5.32",
            "channel" to value("channel", "vivo_8662_64"),
            "device_platform" to "android",
            "device_type" to value("device_type", "PGT-AN10"),
            "os_version" to value("os_version", "12"),
            "update_version_code" to "74532",
            "iid" to v.getValue("iid"),
            "cdid" to v.getValue("cdid"),
            "device_id" to v.getValue("device_id"),
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
        if (compatCodec) { values["codec_type"] = "0"; values["force_codec"] = "h264" }
        val signed = signer.signPlaybackV5("$host$TAB_PATH", values, ByteArray(0))
        val request = Request.Builder().url(signed.url).get()
            .header("User-Agent", TAB_UA)
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (key, value) -> header(key, value) }
                // 空字节只是签名输入：GET 没有 body，也不该带 POST 那三个头。
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
            }
            .build()
        val text = try {
            val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            RequestScope.execute(transport.newCall(request)) { response ->
                if (response.code == 429) throw PlaybackRateLimitedException(ContentRepository.retryAfterMs(response))
                if (!response.isSuccessful) throw IOException("video_tab 请求失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
        } catch (limited: PlaybackRateLimitedException) {
            throw limited
        } catch (problem: java.io.InterruptedIOException) {
            throw problem
        } catch (problem: IOException) {
            ContentRepository.probeLog?.invoke("video_tab 请求失败 ${problem.message}")
            return null
        } catch (problem: RuntimeException) {
            ContentRepository.probeLog?.invoke("video_tab 请求异常 ${problem.message}")
            return null
        }
        if (text.isBlank()) {
            ContentRepository.probeLog?.invoke("video_tab 空响应（HTTP 200 / 0 字节）")
            throw EmptyPlaybackResponseException()
        }
        return parse(text, id)
    }

    /** 两种响应形态共用：扁平 `data.video_model`、`data.1004[vid]`、`data.video_model_data[vid]`。 */
    private fun parse(text: String, id: String): JSONObject? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: run {
            ContentRepository.probeLog?.invoke("播放模型非 JSON body=${text.take(120)}")
            return null
        }
        val code = root.optInt("code", root.optInt("Code", -1))
        if (code != 0) {
            ContentRepository.probeLog?.invoke("播放模型 code=$code body=${text.take(200)}")
            return null
        }
        val data = root.optJSONObject("data") ?: run {
            ContentRepository.probeLog?.invoke("播放模型无 data len=${text.length} body=${text.take(240)}")
            return null
        }
        // 旧端点把模型直接挂在 `data.video_model`（可能是字符串），没有分组这一层。
        directModel(data)?.let { return it }
        val entries = data.optJSONObject("1004") ?: data.optJSONObject("video_model_data") ?: data
        val row = entries.optJSONObject(id) ?: entries.keys().asSequence()
            .mapNotNull { entries.optJSONObject(it) }.firstOrNull() ?: run {
            ContentRepository.probeLog?.invoke("播放模型无剧集行 id=$id entries=${entries.keys().asSequence().toList().take(8)}")
            return null
        }
        val model = when (val value = row.opt("video_model")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: run {
            ContentRepository.probeLog?.invoke("播放模型无 video_model rowKeys=${row.keys().asSequence().toList().take(10)}")
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
