// SPDX-License-Identifier: GPL-3.0-only
// Account endpoint shapes referenced from charlie901208/HGTV feat/phone-login, 2026-10-21.
package com.hongguotv.core

// 账户云端数据访问：封装账户相关请求与返回数据。

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AccountCredentials(
    val cookie: String,
    val deviceId: String = "",
    val installId: String = "",
    val device: Map<String, String> = emptyMap(),
) {
    fun toJson() =
        JSONObject()
            .put("cookie", cookie)
            .put("deviceId", deviceId)
            .put("installId", installId)
            .put("device", JSONObject(device))
            .toString()

    override fun toString() = "AccountCredentials([redacted])"

    companion object {
        fun fromJson(value: String): AccountCredentials {
            val json = JSONObject(value)
            val device = json.optJSONObject("device") ?: JSONObject()
            return AccountCredentials(
                json.getString("cookie"),
                json.str("deviceId"),
                json.str("installId"),
                device.keys().asSequence().associateWith { device.getString(it) },
            )
        }
    }
}

internal object AccountCredentialsParser {
    fun parseHeader(value: String) = AccountCredentials(value)
}

data class CloudProfile(val id: String, val nickname: String)

data class CloudProgress(
    val series: Series,
    val episodeId: String,
    val episodeIndex: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val episodeCount: Int = 0,
)

internal fun cloudResponse(response: okhttp3.Response): String {
    if (!response.isSuccessful) throw IOException("账号请求失败（HTTP ${response.code}）")
    val body = response.body ?: throw IOException("账号接口响应为空")
    val limit = 2 * 1024 * 1024L
    if (body.contentLength() > limit) throw IOException("账号接口响应过大")
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val input = body.byteStream()
    while (out.size() <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    val bytes = out.toByteArray()
    if (bytes.size > limit) throw IOException("账号接口响应过大")
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) throw IOException("账号接口返回空响应，请稍后重试")
    return text
}

data class CloudFavorite(val series: Series, val collectedAt: Long)

data class CloudReservation(
    val series: Series,
    val online: Boolean,
    val publishAt: Long,
    val publishText: String = "",
)

class CloudAccountClient(private val http: OkHttpClient) {
    fun registerDevice(): DeviceRegister.Registered? {
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (k, v) ->
            request.header(k, v)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        return http.newCall(request.build()).execute().use {
            DeviceRegister.parse(cloudResponse(it), it.headers("Set-Cookie"))
        }
    }

    private fun json(
        credentials: AccountCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        gzip: Boolean = false,
    ): JSONObject {
        val device =
            VendorConstants.device.toMutableMap().apply {
                putAll(credentials.device)
                put("device_id", credentials.deviceId)
                put("iid", credentials.installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, if (body != null) "73932" else "72232")
                put("version_name", if (body != null) "7.3.9.32" else "7.2.2.32")
            }
        val wireBody =
            if (gzip && body != null)
                java.io
                    .ByteArrayOutputStream()
                    .apply { java.util.zip.GZIPOutputStream(this).use { it.write(body) } }
                    .toByteArray()
            else body
        val signed =
            Signer()
                .sign("https://api5-normal-sinfonlinea.fqnovel.com$path", device + params, wireBody)
        val request = Request.Builder().url(signed.url).header("Cookie", credentials.cookie)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        if (wireBody != null)
            request.post(wireBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (gzip) request.header("Content-Encoding", "gzip")
        val transport =
            if (path == "/reading/bookapi/bookshelf/video/update/v1/")
                http.newBuilder().retryOnConnectionFailure(false).build()
            else http
        return transport.newCall(request.build()).execute().use { response ->
            val json = JSONObject(cloudResponse(response))
            val code = json.optInt("code", -1)
            if (code != 0) throw IOException("账号接口不可用（错误码 $code），请重新登录或稍后重试")
            json
        }
    }

    fun profile(credentials: AccountCredentials): CloudProfile {
        val data =
            json(credentials, "/reading/user/info/v1/").optJSONObject("data")
                ?: throw IOException("账号资料为空")
        val id = data.str("biz_user_id")
        if (id.isBlank()) throw IOException("登录态未通过账号验证，请重新登录")
        return CloudProfile(id, data.str("user_name").ifBlank { "红果用户" })
    }

    fun history(credentials: AccountCredentials): List<CloudProgress> {
        val data =
            json(
                credentials,
                "/reading/bookapi/read_progress/list/v1/",
                mapOf("book_types" to "2", "limit" to "50"),
            )
        val rows =
            data.optJSONArray("video_data")
                ?: data.optJSONObject("data")?.optJSONArray("video_data")
                ?: throw IOException("云端历史数据格式已变化")
        val progress = parseProgress(rows)
        if (progress.isEmpty()) return emptyList()
        val details =
            json(
                    credentials,
                    "/reading/bookapi/multi-detail/v1/",
                    mapOf(
                        "book_id" to progress.map { it.series.id }.joinToString(","),
                        "book_type" to "2",
                    ),
                )
                .optJSONArray("data") ?: throw IOException("云端剧集资料格式已变化")
        val series =
            details.objects().associate { row ->
                row.str("book_id") to
                    Series(
                        row.str("book_id"),
                        row.str("book_name"),
                        cleanUrl(row.str("thumb_url")),
                        row.str("abstract"),
                        episodeBadge(row),
                    )
            }
        return progress.map {
            it.copy(series = series[it.series.id] ?: it.series.copy(title = "剧集 ${it.series.id}"))
        }
    }

    /** Read-only video shelf. Protocol reference: charlie901208/HGTV AccountParser.favorites. */
    fun favorites(credentials: AccountCredentials): List<CloudFavorite> =
        parseFavorites(json(credentials, "/reading/bookapi/bookshelf/video/info/v1/"))

    /**
     * Video shelf only (novel bookshelf/add can acknowledge without changing video favorites).
     * Protocol reference: HGTV videoShelf public request model. No automatic retry: callers must
     * read the shelf back before reporting success.
     */
    fun setFavorite(credentials: AccountCredentials, seriesId: String, add: Boolean) {
        require(seriesId.matches(Regex("[0-9]{1,30}"))) { "剧集编号无效" }
        val entry =
            JSONObject()
                .put("book_id", seriesId)
                .put("book_type", 2)
                .put("modify_time", System.currentTimeMillis())
                .put("video_shelf_operate_type", if (add) 0 else 1)
        if (!add) entry.put("group_name", "")
        val body =
            JSONObject()
                .put("is_cancelled", false)
                .put("update_bookshelf_video_list", JSONArray().put(entry))
        if (add)
            body.put(
                "shark_extra",
                JSONObject()
                    .put("enter_from", "MainFragmentActivity")
                    .put("previous_page", "")
                    .put("page_list", "MainFragmentActivity")
                    .put("is_active_behavior", "true")
                    .put("inactive_type", "0"),
            )
        json(
            credentials,
            "/reading/bookapi/bookshelf/video/update/v1/",
            body = body.toString().toByteArray(),
        )
    }

    /**
     * Protocol reference: charlie901208/HGTV feat/phone-login, accountReserves. Keep the two
     * upstream categories separate; an unpublished series is never playable. The upstream
     * pagination contract is unverified, so expose only this requested batch.
     */
    fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation> {
        val raw =
            json(
                credentials,
                "/reading/user/subscribe/list/v0/",
                mapOf(
                    "offset" to "0",
                    "limit" to "20",
                    "is_online" to online.toString(),
                    "tab_type" to "13",
                    "subscribe_order_type" to "0",
                    "swipe_type" to "0",
                    "need_calendar_schema" to "false",
                    "subscribe_offset" to "0",
                ),
            )
        val rows = parseReservations(raw, online)
        if (!online) return rows
        val missing =
            rows.filter { episodeTotal(it.series.badge) == 0 }.map { it.series.id }
        if (missing.isEmpty()) return rows
        // Optional batch metadata: its failure must not hide a valid reservation list.
        val totals =
            runCatching {
                    json(
                            credentials,
                            "/reading/bookapi/multi-detail/v1/",
                            mapOf("book_id" to missing.joinToString(","), "book_type" to "2"),
                        )
                        .optJSONArray("data")
                        ?.objects()
                        .orEmpty()
                        .associate { it.str("book_id") to episodeBadge(it) }
                }
                .getOrDefault(emptyMap())
        return rows.map { row ->
            totals[row.series.id]
                ?.takeIf { it.isNotBlank() }
                ?.let { row.copy(series = row.series.copy(badge = it)) } ?: row
        }
    }

    fun upload(credentials: AccountCredentials, progress: CloudProgress, episodeCount: Int) {
        require(
            progress.series.id.matches(Regex("[0-9]{1,30}")) &&
                progress.episodeId.matches(Regex("[0-9]{1,30}"))
        )
        val entry =
            JSONObject()
                .put("book_id", progress.series.id)
                .put("item_id", progress.episodeId)
                .put("vid_index", progress.episodeIndex + 1)
                .put("current_play_time", progress.position.coerceAtLeast(0))
                .put("current_total_time", progress.duration.coerceAtLeast(0))
                .put("episode_count", episodeCount)
                .put("read_timestamp_ms", progress.updatedAt)
                .put("book_type", 2)
                .put(
                    "progress_rate",
                    if (progress.duration > 0)
                        (progress.position.toDouble() / progress.duration)
                            .coerceIn(0.0, 1.0)
                            .toString()
                    else "0",
                )
                .put("player_cumulative_total_duration", progress.position.coerceAtLeast(0))
        val body =
            JSONObject()
                .put("books", JSONArray().put(entry))
                .put("write_mode", 1)
                .toString()
                .toByteArray()
        json(credentials, "/reading/bookapi/read_progress/upload/v1/", body = body)
    }

    companion object {
        /** badge（如「全 50 集」「更新至 12 集」）里的总集数，无法解析时按 0 处理。 */
        private fun episodeTotal(badge: String) =
            Regex("\\d+").findAll(badge).lastOrNull()?.value?.toIntOrNull() ?: 0

        private fun episodeBadge(row: JSONObject): String {
            val count =
                listOf("episode_cnt", "episode_count", "drama_chapter_number")
                    .map { row.optInt(it) }
                    .firstOrNull { it > 0 } ?: 0
            return if (count > 0) "全 $count 集" else ""
        }

        fun parseFavorites(raw: JSONObject): List<CloudFavorite> {
            val data = raw.optJSONObject("data") ?: throw IOException("账户收藏数据格式已变化")
            if (!data.has("video_shelf_info")) throw IOException("账户收藏列表格式已变化")
            val rows =
                if (data.isNull("video_shelf_info")) JSONArray()
                else data.optJSONArray("video_shelf_info") ?: throw IOException("账户收藏列表格式已变化")
            val result =
                rows.objects().mapNotNull { row ->
                    val video = row.optJSONObject("video_data") ?: row
                    val id =
                        video
                            .str("series_id")
                            .ifBlank { row.str("series_id") }
                            .ifBlank { row.str("book_id") }
                    val title =
                        row.str("series_title")
                            .ifBlank { video.str("title") }
                            .ifBlank { row.str("book_name") }
                    if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                    val cover =
                        row.str("series_cover_url")
                            .ifBlank { video.str("cover") }
                            .ifBlank { video.str("cover_url") }
                            .ifBlank { video.str("thumb_url") }
                            .ifBlank { row.str("thumb_url") }
                    val count =
                        video.optInt(
                            "episode_cnt",
                            video.optInt("episode_count", row.optInt("episode_cnt")),
                        )
                    val tags =
                        runCatching {
                                JSONArray(row.str("category_schema"))
                                    .objects()
                                    .map { it.str("name") }
                                    .filter(String::isNotBlank)
                                    .take(5)
                                    .joinToString(" · ")
                            }
                            .getOrDefault("")
                            .ifBlank { video.str("category") }
                    val stamp =
                        row.optLong("collect_time").coerceAtLeast(0).let {
                            if (it in 1..9999999999L) it * 1000 else it
                        }
                    CloudFavorite(
                        Series(
                            id,
                            title,
                            cleanUrl(cover),
                            row.str("series_intro")
                                .ifBlank { video.str("video_desc") }
                                .ifBlank { row.str("abstract") },
                            if (count > 0) "全 $count 集" else "",
                            tags,
                        ),
                        stamp,
                    )
                }
            if (rows.length() > 0 && result.isEmpty()) throw IOException("账户收藏条目格式已变化")
            return result.sortedByDescending { it.collectedAt }.distinctBy { it.series.id }
        }

        fun parseReservations(raw: JSONObject, online: Boolean): List<CloudReservation> {
            val data = raw.optJSONObject("data") ?: throw IOException("预约数据格式已变化，请稍后重试")
            // A null list is the upstream's empty category; missing/non-array data is an error.
            if (!data.has("subscribe_items")) throw IOException("预约列表格式已变化，请稍后重试")
            val rows =
                if (data.isNull("subscribe_items")) JSONArray()
                else data.optJSONArray("subscribe_items") ?: throw IOException("预约列表格式已变化，请稍后重试")
            val result =
                rows
                    .objects()
                    .mapNotNull { row ->
                        val video =
                            row.optJSONObject("subscribe_data")
                                ?: row.optJSONObject("video_data")
                                ?: row
                        val seriesId =
                            video
                                .str("series_id")
                                .ifBlank { row.str("series_id") }
                                .ifBlank { row.str("book_id") }
                        // Unpublished entries use a flat reservation item, without a playable
                        // series ID.
                        // Namespace that key so it can never be mistaken for a playback ID.
                        val id =
                            if (seriesId.matches(Regex("[0-9]{1,30}"))) seriesId
                            else if (
                                !online &&
                                    video === row &&
                                    row.str("item_id").matches(Regex("[0-9]{1,30}"))
                            )
                                "reservation:" + row.str("item_id")
                            else return@mapNotNull null
                        val title =
                            row.str("series_title")
                                .ifBlank { video.str("title") }
                                .ifBlank { video.str("book_name") }
                                .ifBlank { row.str("name") }
                        if (title.isBlank()) return@mapNotNull null
                        if (row.has("is_online") && row.optBoolean("is_online") != online)
                            return@mapNotNull null
                        val cover =
                            row.str("series_cover_url")
                                .ifBlank { video.str("cover") }
                                .ifBlank { video.str("cover_url") }
                                .ifBlank { video.str("thumb_url") }
                        val stamp =
                            row.optLong("schedule_publish_time").coerceAtLeast(0).let {
                                if (it in 1..9999999999L) it * 1000 else it
                            }
                        val series =
                            Series(
                                id,
                                title,
                                cleanUrl(cover),
                                video
                                    .str("video_desc")
                                    .ifBlank { row.str("abstract") }
                                    .ifBlank { row.str("item_desc") },
                                if (online)
                                    episodeBadge(video)
                                        .ifBlank { episodeBadge(row) }
                                        .ifBlank { "已上线" }
                                else "待上线",
                                video.str("category"),
                            )
                        CloudReservation(series, online, stamp, row.str("expected_rec_text"))
                    }
                    .distinctBy { it.series.id }
            if (rows.length() > 0 && result.isEmpty()) throw IOException("预约条目格式已变化，请稍后重试")
            return result
        }

        fun parseProgress(rows: JSONArray): List<CloudProgress> =
            rows
                .objects()
                .mapNotNull { row ->
                    val id = row.str("series_id").ifEmpty { row.str("book_id") }
                    val episode = row.str("vid")
                    if (!id.matches(Regex("[0-9]{1,30}")) || !episode.matches(Regex("[0-9]{1,30}")))
                        return@mapNotNull null
                    val stamp =
                        row.optLong("last_watch_time").let {
                            if (it in 1..9999999999L) it * 1000 else it
                        }
                    CloudProgress(
                        Series(id, ""),
                        episode,
                        (row.optInt("vid_index", 1) - 1).coerceAtLeast(0),
                        row.optLong("current_play_time").coerceAtLeast(0),
                        row.optLong("current_total_time").coerceAtLeast(0),
                        stamp,
                        row.optInt("episode_count").coerceAtLeast(0),
                    )
                }
                .distinctBy { it.series.id }
                .sortedByDescending { it.updatedAt }
    }
}
