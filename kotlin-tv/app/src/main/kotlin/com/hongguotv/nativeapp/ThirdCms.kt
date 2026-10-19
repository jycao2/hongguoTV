// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 三方片源数据模型：与红果内容体系完全隔离，不进 Library/收藏/历史/备份。 */
data class ThirdSource(val id: String, val name: String, val api: String, val builtin: Boolean)
data class ThirdCategory(val id: String, val name: String)
data class ThirdVideo(val id: String, val name: String, val cover: String, val remarks: String)
data class ThirdEpisode(val label: String, val url: String)
data class ThirdDetail(
    val video: ThirdVideo, val typeName: String?, val year: String?,
    val synopsis: String?, val groups: List<Pair<String, List<ThirdEpisode>>>
)
data class ThirdProgress(val episode: Int, val positionMs: Long, val group: Int)
data class ThirdListPage(
    val categories: List<ThirdCategory>, val videos: List<ThirdVideo>,
    val page: Int, val pageCount: Int, val hasMore: Boolean, val total: Int
)
data class ThirdProbe(val ok: Boolean, val total: Int, val reason: String?)
data class ThirdTvboxSite(val name: String, val api: String)
data class ThirdTvboxParse(val candidates: List<ThirdTvboxSite>, val skipped: Int)

/** CMS v10 协议与三方配置解析（纯函数，可单测）。
 *  播放地址格式实测：vod_play_from=`liangzi$$$lzm3u8`，vod_play_url=`第01集$https://a#第02集$https://b$$$第01集$https://c`。 */
object ThirdCms {
    const val PAGE_LIMIT = 20          // 常规单页条目数，page/pagecount 缺失时按「返回条数 < 此值」判尾页
    const val MAX_SOURCES = 30         // 源列表上限
    const val TIMEOUT_SECONDS = 10L

    /** 内置源名单（2026-10-20 实测探活通过；失效源随版本更新在名单中替换）。 */
    val BUILTIN_SOURCES = listOf(
        ThirdSource("liangzi", "量子", "https://cj.lziapi.com/api.php/provide/vod", true),
        ThirdSource("baofeng", "暴风", "https://bfzyapi.com/api.php/provide/vod", true),
        ThirdSource("feifan", "非凡", "https://ffzy5.tv/api.php/provide/vod", true),
        ThirdSource("tianya", "天涯", "https://tyyszy.com/api.php/provide/vod", true),
        ThirdSource("suoni", "索尼", "https://suonizy.net/api.php/provide/vod", true)
    )

    /** 次级名单（实测可用，供「恢复内置源」补齐）。 */
    val SPARE_SOURCES = listOf(
        ThirdSource("hongniu", "红牛", "https://hongniuzy3.com/api.php/provide/vod", true),
        ThirdSource("sanliuling", "360", "https://360zy.com/api.php/provide/vod", true),
        ThirdSource("jinying", "金鹰", "https://jinyingzy.com/api.php/provide/vod", true)
    )

    /** 「恢复内置源」：返回当前不存在的内置/次级源。 */
    fun restoreCandidates(existing: List<ThirdSource>): List<ThirdSource> =
        (BUILTIN_SOURCES + SPARE_SOURCES).filter { spare -> existing.none { it.api == spare.api } }

    /** 接口地址拼参数：源地址可能自带 query，按 ?/& 正确衔接。 */
    fun apiUrl(api: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return if(api.contains('?')) "$api&$query" else "$api?$query"
    }

    /** 编码兜底：UTF-8 解出替换符（U+FFFD）说明源站为 GBK，按 GBK 重试。 */
    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if(utf8.contains('\uFFFD')) String(bytes, charset("GBK")) else utf8
    }

    /** 列表解析（ac=videolist）：class[] 分类 + list[] 条目；单条字段缺失跳过不崩溃。 */
    fun parseList(body: String): ThirdListPage {
        val json = JSONObject(body)
        val categories = mutableListOf<ThirdCategory>()
        json.optJSONArray("class")?.let { array ->
            for(index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("type_id").trim(); val name = item.optString("type_name").trim()
                if(id.isNotEmpty() && name.isNotEmpty()) categories += ThirdCategory(id, name)
            }
        }
        val videos = mutableListOf<ThirdVideo>()
        json.optJSONArray("list")?.let { array ->
            for(index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("vod_id").trim(); val name = item.optString("vod_name").trim()
                if(id.isEmpty() || name.isEmpty()) continue
                videos += ThirdVideo(id, name, item.optString("vod_pic").trim(), item.optString("vod_remarks").trim())
            }
        }
        val page = json.optInt("page", 1); val pageCount = json.optInt("pagecount", 0)
        // 分页容错：page/pagecount 缺失（0）时按「返回条数 < 单页上限」判断尾页
        val hasMore = if(pageCount > 0) page < pageCount else videos.size >= PAGE_LIMIT
        return ThirdListPage(categories, videos, page, pageCount, hasMore && videos.isNotEmpty(), json.optInt("total", videos.size))
    }

    /** 详情解析（ac=detail&ids=）：取 list[] 首条完整字段。 */
    fun parseDetail(body: String): ThirdDetail? = parseDetailList(body).firstOrNull()

    /** 详情列表解析（ac=detail&wd= 搜索直接含播放地址，点击即可播）。 */
    fun parseDetailList(body: String): List<ThirdDetail> {
        val json = JSONObject(body)
        val array = json.optJSONArray("list") ?: return emptyList()
        val details = mutableListOf<ThirdDetail>()
        for(index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val video = ThirdVideo(
                item.optString("vod_id").trim(), item.optString("vod_name").trim(),
                item.optString("vod_pic").trim(), item.optString("vod_remarks").trim())
            if(video.id.isEmpty() || video.name.isEmpty()) continue
            // 简介去 HTML 标签并解码常见实体，纯文本展示
            val synopsis = item.optString("vod_content")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .trim()
            details += ThirdDetail(
                video,
                item.optString("type_name").trim().ifEmpty { null },
                item.optString("vod_year").trim().ifEmpty { null },
                synopsis.ifEmpty { null },
                parsePlayGroups(item.optString("vod_play_from"), item.optString("vod_play_url"))
            )
        }
        return details
    }

    /** 播放地址三层解析：$$$ 分组 → # 分集 → 名称$地址。
     *  容错：空段跳过、无 $ 的整段视为地址、组名缺失兜底「线路 N」。 */
    fun parsePlayGroups(from: String, urls: String): List<Pair<String, List<ThirdEpisode>>> {
        val names = from.split("$$$").map { it.trim() }
        val groups = mutableListOf<Pair<String, List<ThirdEpisode>>>()
        urls.split("$$$").forEachIndexed { groupIndex, rawGroup ->
            var counter = 0
            val episodes = rawGroup.split("#").mapNotNull { raw ->
                val segment = raw.trim()
                if(segment.isEmpty()) return@mapNotNull null
                counter += 1
                val sep = segment.indexOf('$')
                when {
                    sep > 0 -> ThirdEpisode(segment.substring(0, sep).trim().ifEmpty { "第${counter}集" }, segment.substring(sep + 1).trim())
                    sep == 0 -> ThirdEpisode("第${counter}集", segment.substring(1).trim())
                    else -> ThirdEpisode("第${counter}集", segment)
                }
            }.filter { it.url.isNotEmpty() }
            if(episodes.isNotEmpty()) {
                val name = names.getOrNull(groupIndex)?.ifEmpty { null } ?: "线路${groupIndex + 1}"
                groups += name to episodes
            }
        }
        return groups
    }

    /** 默认播放组：m3u8 组优先（名字或首集地址含 m3u8），否则取第一组。 */
    fun defaultGroupIndex(groups: List<Pair<String, List<ThirdEpisode>>>): Int =
        groups.indexOfFirst { (_, episodes) -> episodes.any { it.url.contains("m3u8", true) } }
            .let { if(it >= 0) it else 0 }

    /** 探活判定：JSON 含 list 字段即通过；返回条目总数用于源管理展示。 */
    fun probeTotal(body: String): Int? {
        val json = JSONObject(body)
        if(!json.has("list")) return null
        val total = json.optInt("total", -1)
        return if(total >= 0) total else json.optJSONArray("list")?.length() ?: 0
    }

    /** TVBox 明文配置提取：只取 type=1 且 api 为 http(s) 链接的 CMS 源；
     *  spider jar（type=0）与加密 app 源（type=3/4）自动跳过并计数。 */
    fun parseTvboxConfig(body: String): ThirdTvboxParse {
        val sites = JSONObject(body).optJSONArray("sites") ?: return ThirdTvboxParse(emptyList(), 0)
        val candidates = mutableListOf<ThirdTvboxSite>()
        var skipped = 0
        for(index in 0 until sites.length()) {
            val item = sites.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val api = item.optString("api").trim()
            val type = item.optInt("type", -1)
            if(type == 1 && api.startsWith("http")) candidates += ThirdTvboxSite(name.ifEmpty { "未命名源" }, api)
            else skipped += 1
        }
        return ThirdTvboxParse(candidates, skipped)
    }
}

/** 单次请求结果：body 为解码后的响应体，error 为失败原因（两者互斥）。 */
data class ThirdHttp(val body: String?, val error: String?)

/** 三方片源专用 HTTP 客户端：独立连接池与超时（10 秒），不走红果签名/拦截器/UA。 */
class ThirdCmsClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(ThirdCms.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ThirdCms.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // 列表/分类/详情内存缓存：10 分钟 TTL，不落盘
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()
    private val cacheTtl = 10 * 60_000L

    /** 单请求：区分 HTTP 错误、空响应与网络异常，给出明确原因。 */
    fun get(url: String): ThirdHttp = try {
        http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36")
            .build()).execute().use { response ->
            val bytes = response.body?.bytes()
            when {
                !response.isSuccessful -> ThirdHttp(null, "HTTP ${response.code}")
                bytes == null || bytes.isEmpty() -> ThirdHttp(null, "源站返回空响应")
                else -> ThirdHttp(ThirdCms.decode(bytes), null)
            }
        }
    } catch (problem: Exception) {
        ThirdHttp(null, when(problem) {
            is SocketTimeoutException -> "连接超时"
            is UnknownHostException -> "无法连接源站"
            else -> "连接失败"
        })
    }

    /** 分类列表与条目分页（ac=videolist，含封面与角标；ac=list 是轻量响应缺 vod_pic）。 */
    fun list(api: String, categoryId: String?, page: Int): ThirdListPage? {
        val key = "$api|list|$categoryId|$page"
        (cached(key) as? ThirdListPage)?.let { return it }
        val params = buildList {
            add("ac" to "videolist")
            if(!categoryId.isNullOrBlank()) add("t" to categoryId)
            add("pg" to page.toString())
        }
        val response = get(ThirdCms.apiUrl(api, params))
        val parsed = response.body?.let { body -> try { ThirdCms.parseList(body) } catch (_: Exception) { null } } ?: return null
        store(key, parsed); return parsed
    }

    /** 单条详情（ac=detail&ids=）。 */
    fun detail(api: String, id: String): ThirdDetail? {
        val key = "$api|detail|$id"
        (cached(key) as? ThirdDetail)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "detail", "ids" to id)))
        val parsed = response.body?.let { body -> try { ThirdCms.parseDetail(body) } catch (_: Exception) { null } } ?: return null
        store(key, parsed); return parsed
    }

    /** 片源内搜索（ac=detail&wd=，结果直接含播放地址）。 */
    fun search(api: String, word: String): List<ThirdDetail> {
        val key = "$api|search|$word"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<ThirdDetail>)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "detail", "wd" to word)))
        val parsed = response.body?.let { body -> try { ThirdCms.parseDetailList(body) } catch (_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
    }

    /** 探活：HTTP 200 + JSON 含 list 字段即通过，返回条目数；失败带明确原因。 */
    fun probe(api: String): ThirdProbe {
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "list", "pg" to "1")))
        response.body ?: return ThirdProbe(false, 0, response.error ?: "连接失败")
        val total = response.body?.let { body -> try { ThirdCms.probeTotal(body) } catch (_: Exception) { null } }
            ?: return ThirdProbe(false, 0, "非 CMS 接口（缺少 list 字段）")
        return ThirdProbe(true, total, null)
    }

    /** 拉取并提取 TVBox 明文配置；parse 为 null 时 error 给出原因（含加密配置提示）。 */
    fun importTvbox(url: String): Pair<ThirdTvboxParse?, String?> {
        val response = get(url)
        response.body ?: return null to (response.error ?: "无法获取配置")
        return try {
            ThirdCms.parseTvboxConfig(response.body!!) to null
        } catch (_: Exception) {
            null to "该配置不支持导入（仅支持明文 JSON）"
        }
    }

    /** 释放独立连接池与线程池。 */
    fun shutdown() {
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun cached(key: String): Any? {
        val hit = cache[key] ?: return null
        return if(System.currentTimeMillis() - hit.first <= cacheTtl) hit.second else { cache.remove(key); null }
    }
    private fun store(key: String, value: Any) { cache[key] = System.currentTimeMillis() to value }
}
