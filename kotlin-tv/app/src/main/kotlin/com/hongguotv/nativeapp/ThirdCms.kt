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
    val video: ThirdVideo, val typeName: String?, val year: String?, val area: String?,
    val synopsis: String?, val groups: List<Pair<String, List<ThirdEpisode>>>
)
data class ThirdProgress(val episode: Int, val positionMs: Long, val group: Int)
data class ThirdListPage(
    val categories: List<ThirdCategory>, val videos: List<ThirdVideo>,
    val page: Int, val pageCount: Int, val hasMore: Boolean, val total: Int
)
/** 搜索结果分页：条目带封面与播放地址，page/hasMore/total 用于「继续下滑加载更多」。 */
data class ThirdSearchPage(
    val details: List<ThirdDetail>, val page: Int, val hasMore: Boolean, val total: Int
)
/** 搜索请求结果：page 为空时 error 给出可读原因（该片源未开启搜索 / 网络失败）。 */
data class ThirdSearchResult(val page: ThirdSearchPage?, val error: String?)
data class ThirdProbe(val ok: Boolean, val total: Int, val reason: String?)
data class ThirdTvboxSite(val name: String, val api: String)
data class ThirdTvboxParse(val candidates: List<ThirdTvboxSite>, val skipped: Int)

/** CMS v10 协议与三方配置解析（纯函数，可单测）。
 *  播放地址格式实测：vod_play_from=`liangzi$$$lzm3u8`，vod_play_url=`第01集$https://a#第02集$https://b$$$第01集$https://c`。 */
object ThirdCms {
    const val PAGE_LIMIT = 20          // 常规单页条目数，page/pagecount 缺失时按「返回条数 < 此值」判尾页
    const val MAX_SOURCES = 30         // 源列表上限
    const val SUGGEST_LIMIT = 10       // 联想词条数上限
    const val TIMEOUT_SECONDS = 10L

    /** 内置源名单（2026-10-20 实测探活通过；失效源随版本更新在名单中替换）。 */
    val BUILTIN_SOURCES = listOf(
        ThirdSource("liangzi", "量子", "https://cj.lziapi.com/api.php/provide/vod", true),
        ThirdSource("baofeng", "暴风", "https://bfzyapi.com/api.php/provide/vod", true),
        ThirdSource("feifan", "非凡", "https://ffzy5.tv/api.php/provide/vod", true),
        ThirdSource("tianya", "天涯", "https://tyyszy.com/api.php/provide/vod", true)
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
                item.optString("vod_area").trim().ifEmpty { null },
                synopsis.ifEmpty { null },
                parsePlayGroups(item.optString("vod_play_from"), item.optString("vod_play_url"))
            )
        }
        return details
    }

    /** 搜索联想解析（ac=list&wd=）：响应只含片名等轻量字段，去重后最多返回 SUGGEST_LIMIT 条。 */
    fun parseSuggestions(body: String): List<String> {
        val json = JSONObject(body)
        val array = json.optJSONArray("list") ?: return emptyList()
        val names = LinkedHashSet<String>()
        for(index in 0 until array.length()) {
            val name = array.optJSONObject(index)?.optString("vod_name")?.trim().orEmpty()
            if(name.isNotEmpty()) names += name
            if(names.size >= SUGGEST_LIMIT) break
        }
        return names.toList()
    }

    /** 搜索结果分页解析（ac=detail&wd=）：条目带封面与播放地址，分页字段与列表一致。 */
    fun parseDetailPage(body: String): ThirdSearchPage {
        val details = parseDetailList(body)
        val json = JSONObject(body)
        val page = json.optInt("page", 1)
        val pageCount = json.optInt("pagecount", 0)
        val hasMore = if(pageCount > 0) page < pageCount else details.size >= PAGE_LIMIT
        return ThirdSearchPage(details, page, hasMore && details.isNotEmpty(), json.optInt("total", details.size))
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

    // 在途 catalog 请求登记：切换分类/片源时统一取消，避免慢请求占满线程池导致界面长时间空态
    private val catalogCalls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())

    fun cancelCatalog() {
        synchronized(catalogCalls) { catalogCalls.forEach { it.cancel() }; catalogCalls.clear() }
    }

    /** 单请求：区分 HTTP 错误、空响应与网络异常，给出明确原因。catalog=true 时登记可取消。 */
    fun get(url: String, catalog: Boolean = false): ThirdHttp {
        val call = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36")
            .build())
        if(catalog) catalogCalls += call
        return try {
            call.execute().use { response ->
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
                is UnknownHostException -> "域名无法解析"
                else -> "连接失败（响应被中断）"
            })
        } finally {
            if(catalog) catalogCalls -= call
        }
    }

    /** 分类列表（ac=list 首页响应含完整 class[]；videolist 响应不返回分类）。 */
    fun categories(api: String): List<ThirdCategory> {
        val key = "$api|categories"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<ThirdCategory>)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "list", "pg" to "1")), catalog = true)
        val parsed = response.body?.let { body -> try { ThirdCms.parseList(body).categories } catch (_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
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
        val response = get(ThirdCms.apiUrl(api, params), catalog = true)
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

    /** 搜索联想（ac=list&wd=，轻量响应只有片名）；接口不支持搜索或网络失败时静默返回空。 */
    fun suggest(api: String, word: String): List<String> {
        val key = "$api|suggest|$word"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<String>)?.let { return it }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "list", "wd" to word, "pg" to "1")))
        val parsed = response.body?.let { body -> try { ThirdCms.parseSuggestions(body) } catch(_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
    }

    /** 片源内搜索分页（ac=detail&wd=，结果直接含封面与播放地址）。
     *  部分源未开启搜索时会返回「暂不支持搜索」等纯文本，这里给出明确原因而不是笼统的加载失败。 */
    fun searchPage(api: String, word: String, page: Int): ThirdSearchResult {
        val key = "$api|search|$word|$page"
        (cached(key) as? ThirdSearchPage)?.let { return ThirdSearchResult(it, null) }
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to "detail", "wd" to word, "pg" to page.toString())))
        response.body ?: return ThirdSearchResult(null, response.error ?: "连接失败")
        val parsed = try { ThirdCms.parseDetailPage(response.body!!) } catch(_: Exception) { null }
            ?: return ThirdSearchResult(null, "该片源未开启搜索")
        store(key, parsed); return ThirdSearchResult(parsed, null)
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
        response.body ?: return null to "无法获取配置：${response.error ?: "未知错误"}。请先确认该链接能在手机浏览器里直接打开"
        // 部分配置带 UTF-8 BOM，直接交给 JSONObject 会解析失败
        val body = response.body!!.trim().removePrefix("\uFEFF")
        return try {
            ThirdCms.parseTvboxConfig(body) to null
        } catch (_: Exception) {
            null to "该配置不支持导入（仅支持明文 JSON，加密或二进制配置无法解析）"
        }
    }

    /** 释放独立连接池与线程池。evictAll 关闭 SSL socket 会触发网络 IO，必须在后台线程执行，
     *  否则 onDestroy（主线程）调用时 StrictMode 抛 NetworkOnMainThreadException。 */
    fun shutdown() {
        Thread({
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }, "thirdcms-shutdown").start()
    }

    private fun cached(key: String): Any? {
        val hit = cache[key] ?: return null
        return if(System.currentTimeMillis() - hit.first <= cacheTtl) hit.second else { cache.remove(key); null }
    }
    private fun store(key: String, value: Any) { cache[key] = System.currentTimeMillis() to value }
}
