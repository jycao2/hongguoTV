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
 * 官方播放模型接口：现用 `/novel/player/video_model/v1/`（旧参数格式）。
 *
 * 2026-11-16 官方曾把取址改判到 `/novel/player/multi_video_model/player/v1`（v5 签名 + `mixed_video_id_map`）；
 * **2026-11-20 下午该端点对所有身份/版本/Cookie/参数组合一律 HTTP 200 + 0 字节**（源站主动返空），
 * 同族旧端点 `/novel/player/video_model/v1/` 反而恢复正常，但只认旧参数（`video_id` + `video_platform:3`），
 * 用 v5 那套 `mixed_video_id_map` 打它会回 `100001 video_id invalid`。二者签名相同，只需换路径与 body。
 * 旧端点把模型直挂 `data.video_model`（字符串），没有 `1004` 分组这一层。
 */
internal object MobilePlayback {
    private const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    /** 2026-11-20 下午起 `.../multi_video_model/player/v1` 对任何身份/版本/参数组合都回 200+0，
     *  但同族旧端点 `/novel/player/video_model/v1/` 仍在正常下发（要旧参数格式 `video_id` + `video_platform:3`）。
     *  二者签名完全一样，只换路径与 body。 */
    private const val PATH = "/novel/player/video_model/v1/"
    const val UA = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    /** 查询串必须与 v5 签名内嵌的 7.4.3.32 / PGT-AN10 指纹一致，多一个少一个都会落到空响应。 */
    private fun query(identity: PlaybackDeviceSession.Profile) = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "74332", "version_name" to "7.4.3.32",
        "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
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
        val payload = """{"video_id":"$id","content_type":1,"biz_param":{"need_all_video_definition":true,"need_mp4_align":false,"video_platform":3}}"""
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val signed = signer.signPlaybackV5(HOST + PATH, query(identity), body)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
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

    /** 播放请求体（gzip 前）：旧端点要 `video_id`，`video_platform` 固定 3。 */
    private fun directModel(data: JSONObject): JSONObject? = when (val value = data.opt("video_model")) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun gzip(source: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(source) } }.toByteArray()
}
