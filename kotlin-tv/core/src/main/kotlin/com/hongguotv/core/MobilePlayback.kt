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
 * 手机端现行播放模型接口：`POST /novel/player/multi_video_model/player/v1`。
 *
 * 2026-11-16 15:00 起，电视端原先使用的 `/novel/player/multi_video_model/v1/`、
 * `/video_model/v1/`、`/reading/bookapi/video_tab/...` 与官网游客链路之外的所有旧签名组合
 * 一律 HTTP 200 + 0 字节；官方改判 Android `v04.10.03`（v5）签名，并且把路径多插了一段 `player`。
 * 这条链路只带 X-Khronos / X-Helios / X-Medusa 三头、body 必须 gzip，成功后对任意集数都返回
 * `video_model`（不再有官网游客前 3 集限制）。
 */
internal object MobilePlayback {
    private const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    private const val PATH = "/novel/player/multi_video_model/player/v1"
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
     *  任何异常都返回 null（由上层回退旧路由），只有线程被取消时原样抛出。 */
    fun model(
        id: String,
        identity: PlaybackDeviceSession.Profile,
        http: OkHttpClient,
        signer: Signer,
        now: Long,
    ): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""
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
            ContentRepository.probeLog?.invoke("v5 空体 ticket 命中但无内容")
            return null
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: run {
            ContentRepository.probeLog?.invoke("v5 非 JSON body=${text.take(120)}")
            return null
        }
        val code = root.optInt("code", root.optInt("Code", -1))
        if (code != 0) {
            ContentRepository.probeLog?.invoke("v5 code=$code body=${text.take(160)}")
            return null
        }
        val data = root.optJSONObject("data") ?: return null
        val entries = data.optJSONObject("1004") ?: data
        val row = entries.optJSONObject(id) ?: entries.keys().asSequence()
            .mapNotNull { entries.optJSONObject(it) }.firstOrNull() ?: return null
        val model = when (val value = row.opt("video_model")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return null
        return model
    }

    private fun gzip(source: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(source) } }.toByteArray()
}
