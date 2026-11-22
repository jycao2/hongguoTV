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
    private const val PATH = "/novel/player/multi_video_model/v1"
    // UA 版本号必须与 query 的 version_code 逐字一致（真果鉴「参数逐字对齐」判据）：
    // 此前 UA 写 73932 而 query 发 74532，是「同一请求两个版本号」的可疑组合。
    const val UA = "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    /** 查询串必须与 v5 签名内嵌的 7.4.5.32 / PGT-AN10 指纹一致，多一个少一个都会落到空响应。 */
    private fun query(identity: PlaybackDeviceSession.Profile) = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "74532", "version_name" to "7.4.5.32",
        "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74532",
        "iid" to identity.values.getValue("iid"),
        "cdid" to identity.values.getValue("cdid"),
        "device_id" to identity.values.getValue("device_id"),
    )

    /** 返回 `video_model` 对象；调用方复用既有的 `modelOf`/`streamFromModel` 解析与解码逻辑。
     *  200 + 0 字节是站方的行为风控信号，原样抛 [EmptyPlaybackResponseException] 让上层触发全局冷却；
     *  其余问题一律返回 null（由上层决定是否走一次 video_tab 兜底），线程被取消时原样抛出。 */
    fun model(
        id: String,
        identity: PlaybackDeviceSession.Profile,
        http: OkHttpClient,
        signer: Signer,
        now: Long,
    ): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        // 7.4.5.32 形态：完整 biz_param + mixed_video_id_map，v1 路径不带 dr_scene。
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}"""
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val signed = signer.signPlaybackV5(HOST + PATH, query(identity), body)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            // 2026-11-20 官方只认真签名：v5 占位三头换成 PlayerSigner 的 argus/ladon（按最终 query 重生成）。
            .apply { ContentRepository.realSignedHeaders(signed.url, signed.headers, body).forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val text = try {
            // 播放模型请求不带账号 Cookie，也不复用注册身份之外的会话。
            val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            RequestScope.execute(transport.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
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
