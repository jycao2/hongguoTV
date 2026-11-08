// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.seasonNumber
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 三方片源数据模型：与红果内容体系完全隔离，不进 Library/收藏/历史/备份。
 *  headers：该源的专用请求头（UA/Referer 等），来自 TVBox 配置 site.header，
 *  API 请求与播放器取流都带上——部分小站校验 UA/防盗链，不带就连不上。
 *  searchable/quickSearch/filterable/categories/timeoutSeconds：TVBox site 元数据——
 *  聚合搜索跳过不可搜源、联想只走 quickSearch 源、categories 只显示指定分类、
 *  timeoutSeconds 覆盖全局超时（0 = 用默认）。 */
data class ThirdSource(
    val id: String, val name: String, val api: String,
    val headers: Map<String, String> = emptyMap(),
    val type: Int = 1,
    val searchable: Boolean = true,
    val quickSearch: Boolean = true,
    val filterable: Boolean = true,
    val categories: List<String> = emptyList(),
    val timeoutSeconds: Int = 0
)
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
data class ThirdTvboxSite(
    val name: String, val api: String,
    val headers: Map<String, String> = emptyMap(),
    val type: Int = 1,
    val searchable: Boolean = true, val quickSearch: Boolean = true, val filterable: Boolean = true,
    val categories: List<String> = emptyList(), val timeoutSeconds: Int = 0
)
data class ThirdTvboxParse(val candidates: List<ThirdTvboxSite>, val skipped: Int)
/** 聚合搜索命中：详情与来源源一起保留，跨源展示、换源与收藏都依赖它。 */
data class ThirdSearchHit(val source: ThirdSource, val detail: ThirdDetail)

/** CMS v10 协议与三方配置解析（纯函数，可单测）。
 *  播放地址格式实测：vod_play_from=`liangzi$$$lzm3u8`，vod_play_url=`第01集$https://a#第02集$https://b$$$第01集$https://c`。 */
object ThirdCms {
    const val PAGE_LIMIT = 20          // 常规单页条目数，page/pagecount 缺失时按「返回条数 < 此值」判尾页
    const val MAX_SOURCES = 200        // 源列表上限
    const val SUGGEST_LIMIT = 10       // 联想词条数上限
    const val TIMEOUT_SECONDS = 10L

    /** 片名归一：去掉《》与空白、统一小写。聚合去重与「一句话开播」挑片共用同一套口径。 */
    fun nameKey(name: String): String =
        name.replace("《", "").replace("》", "").replace(Regex("\\s+"), "").lowercase()

    /** 聚合搜索去重键：片名归一后再拼年份；年份缺失时只按片名。 */
    fun searchKey(name: String, year: String?): String = nameKey(name) + "|" + year?.trim().orEmpty()

    /**
     * 「一句话开播」挑片：先要片名完全一致的，其次要片名包含关键词的，都没有就取第一条
     * （CMS 搜索本身按相关度排序，首条通常就是要的那部）。
     */
    /**
     * 「一句话开播」挑片：先要片名完全一致的，其次要片名包含关键词的，再次要关键词包含片名的
     * （说了「某剧第五季」而库里只有「某剧」）。
     *
     * 都对不上返回 null。原来这里兜底取首条，理由是「CMS 搜索按相关度排序，首条通常就是要的那部」，
     * 但实测搜索词对不上时首条是无关推荐——喊「黄石第五季」能播出别的剧，宁可不播。
     */
    fun pickBest(word: String, details: List<ThirdDetail>): ThirdDetail? {
        if(details.isEmpty()) return null
        val key = nameKey(word)
        if(key.isEmpty()) return null
        return details.firstOrNull { nameKey(it.video.name) == key }
            ?: details.firstOrNull { nameKey(it.video.name).contains(key) }
            ?: details.firstOrNull { nameKey(it.video.name).let { name -> name.length >= 2 && key.contains(name) } }
    }

    /** 季标记：兼容「第二季」「第2季」「第 2 部」，数字与「季/部」之间允许空格。 */
    private val SeasonMark = Regex("第\\s*([0-9零〇一二三四五六七八九十百千]+)\\s*(?:季|部)")

    /** 系列主名：去掉季标记和两端的括号/分隔符，「某剧（第二季）」与「某剧」算同一个系列。 */
    fun seriesBase(name: String): String = name.replace(SeasonMark, "")
        .trim().trim(' ', '\u3000', '：', ':', '-', '—', '_', '·', '（', '）', '(', ')',
            '【', '】', '[', ']', '《', '》')

    /** 季号：复用红果的季号规则，先去掉空格，避免「第 2 季」被漏判。 */
    fun seriesNumber(name: String): Int = seasonNumber(name.replace(Regex("\\s+"), ""))

    /** 系列剧：同一片源里按主名归拢的全部分季（含当前季，按季号排序）。
     *  只认带「第N季/部」的真续季——同名但没有季号的多半是重复上架，不能算系列；
     *  同季号的重复条目只留一条（当前季优先）。不足两季时返回空，详情页据此隐藏区块。 */
    fun series(current: ThirdDetail, candidates: List<ThirdDetail>): List<ThirdDetail> {
        val base = seriesBase(current.video.name)
        if(base.isEmpty()) return emptyList()
        val rows = LinkedHashMap<Int, ThirdDetail>()
        rows[seriesNumber(current.video.name)] = current
        candidates.forEach { item ->
            if(item.video.id == current.video.id) return@forEach
            if(seriesBase(item.video.name) != base) return@forEach
            val number = seriesNumber(item.video.name)
            if(number == Int.MAX_VALUE) return@forEach
            if(!rows.containsKey(number)) rows[number] = item
        }
        if(rows.size < 2) return emptyList()
        return rows.entries.sortedBy { if(it.key == Int.MAX_VALUE) 0 else it.key }.map { it.value }
    }

    fun episodeCount(detail: ThirdDetail): Int = detail.groups.sumOf { it.second.size }

    /** 聚合搜索去重：同键优先保留当前源，其次保留集数更多者；保持首次出现顺序。 */
    fun dedupeHits(hits: List<ThirdSearchHit>, preferredApi: String?): List<ThirdSearchHit> {
        val chosen = LinkedHashMap<String, ThirdSearchHit>()
        hits.forEach { hit ->
            val key = searchKey(hit.detail.video.name, hit.detail.year)
            val prior = chosen[key]
            chosen[key] = when {
                prior == null -> hit
                prior.source.api == preferredApi -> prior
                hit.source.api == preferredApi -> hit
                episodeCount(hit.detail) > episodeCount(prior.detail) -> hit
                else -> prior
            }
        }
        return chosen.values.toList()
    }

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
            // 部分 CMS 在 vod_name 里嵌入 <em> 高亮标签与 HTML 实体，剥离后再展示与搜索
            val name = array.optJSONObject(index)?.optString("vod_name").orEmpty()
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
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

    /** site.header 解析：TVBox 配置里 header 有两种形态——JSON 对象（{"User-Agent":"..."}）
     *  或 "User-Agent=xxx;Referer=yyy" 字符串。键值都 trim，空键/空值丢弃。 */
    fun parseHeaders(raw: Any?): Map<String, String> = when(raw) {
        is JSONObject -> runCatching {
            raw.keys().asSequence().mapNotNull { key ->
                val value = raw.optString(key).trim()
                key.trim().takeIf { it.isNotEmpty() && value.isNotEmpty() }?.let { it to value }
            }.toMap()
        }.getOrDefault(emptyMap())
        is String -> raw.split(';', ',', '\n').mapNotNull { pair ->
            val sep = pair.indexOf('=')
            if(sep <= 0) null else {
                val key = pair.substring(0, sep).trim(); val value = pair.substring(sep + 1).trim()
                if(key.isEmpty() || value.isEmpty()) null else key to value
            }
        }.toMap()
        else -> emptyMap()
    }

    // ---------- CMS v1（type 0）XML 协议 ----------
    // 结构：rss > class > ty(id=1 文本)；rss > list(page/pagecount/recordcount 属性) > video >
    //       id/name/type/pic/note/year/area/des 文本节点 + dl > dd(flag=线路名) 播放地址。
    // 播放地址内容与 JSON 版 vod_play_url 同格式（第01集$url#第02集$url，$$$ 分线路），复用 parsePlayGroups。

    private class XmlVideo {
        var id = ""; var name = ""; var pic = ""; var note = ""
        var typeName = ""; var year = ""; var area = ""; var synopsis = ""
        val playEntries = mutableListOf<Pair<String?, String>>()
    }

    private class XmlRss(
        val categories: List<ThirdCategory>, val videos: List<XmlVideo>,
        val page: Int, val pageCount: Int, val total: Int
    )

    /** CMS v1 XML 全量解析：分类、条目（含播放地址）、分页属性一次读出；坏 XML 抛异常由调用方兜底。 */
    private fun parseXmlRss(body: String): XmlRss {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        // XML 声明前不允许空白/BOM（kxml 会把 <?xml 当处理指令拒掉），源响应常带，先剥掉
        parser.setInput(java.io.StringReader(body.trimStart().removePrefix("\uFEFF")))
        val categories = mutableListOf<ThirdCategory>()
        val videos = mutableListOf<XmlVideo>()
        var page = 1; var pageCount = 0; var total = -1
        var video: XmlVideo? = null
        var textTag: String? = null; var categoryId: String? = null; var flag: String? = null
        val text = StringBuilder()
        var sawList = false
        while(parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when(parser.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when(parser.name.lowercase()) {
                    "list" -> { sawList = true
                        page = parser.attrInt("page", 1); pageCount = parser.attrInt("pagecount", 0)
                        total = parser.attrInt("recordcount", -1) }
                    "video" -> video = XmlVideo()
                    "ty" -> { categoryId = parser.attrOrNull("id"); textTag = "ty"; text.setLength(0) }
                    "dd", "dt" -> { flag = parser.attrOrNull("flag"); textTag = "dd"; text.setLength(0) }
                    "id" -> if(video != null) { textTag = "id"; text.setLength(0) }
                    "name" -> if(video != null) { textTag = "name"; text.setLength(0) }
                    "pic" -> if(video != null) { textTag = "pic"; text.setLength(0) }
                    "note" -> if(video != null) { textTag = "note"; text.setLength(0) }
                    "type" -> if(video != null) { textTag = "type"; text.setLength(0) }
                    "year" -> if(video != null) { textTag = "year"; text.setLength(0) }
                    "area" -> if(video != null) { textTag = "area"; text.setLength(0) }
                    "des" -> if(video != null) { textTag = "des"; text.setLength(0) }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if(textTag != null) text.append(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> when(parser.name.lowercase()) {
                    "ty" -> { val name = text.toString().trim(); val id = categoryId.orEmpty().trim()
                        if(id.isNotEmpty() && name.isNotEmpty()) categories += ThirdCategory(id, name)
                        textTag = null; categoryId = null }
                    "dd", "dt" -> { video?.playEntries += flag?.trim()?.ifEmpty { null } to text.toString().trim()
                        textTag = null; flag = null }
                    "video" -> { video?.let { videos += it }; video = null }
                    "id" -> { video?.id = text.toString().trim(); textTag = null }
                    "name" -> { video?.name = text.toString().trim(); textTag = null }
                    "pic" -> { video?.pic = text.toString().trim(); textTag = null }
                    "note" -> { video?.note = text.toString().trim(); textTag = null }
                    "type" -> { video?.typeName = text.toString().trim(); textTag = null }
                    "year" -> { video?.year = text.toString().trim(); textTag = null }
                    "area" -> { video?.area = text.toString().trim(); textTag = null }
                    "des" -> { video?.synopsis = text.toString(); textTag = null }
                }
            }
            parser.next()
        }
        if(total < 0) total = videos.size
        return XmlRss(categories, videos, page, pageCount, total)
    }

    private fun org.xmlpull.v1.XmlPullParser.attrOrNull(name: String): String? {
        for(index in 0 until attributeCount) if(getAttributeName(index).equals(name, ignoreCase = true)) return getAttributeValue(index)
        return null
    }
    private fun org.xmlpull.v1.XmlPullParser.attrInt(name: String, fallback: Int): Int =
        attrOrNull(name)?.trim()?.toIntOrNull() ?: fallback

    /** XML 分类（class > ty）。 */
    fun parseXmlCategories(body: String): List<ThirdCategory> = parseXmlRss(body).categories

    /** XML 列表页（ac=videolist 响应）。 */
    fun parseXmlList(body: String): ThirdListPage {
        val rss = parseXmlRss(body)
        val videos = rss.videos.mapNotNull { video ->
            if(video.id.isEmpty() || video.name.isEmpty()) null
            else ThirdVideo(video.id, video.name, video.pic, video.note)
        }
        val hasMore = if(rss.pageCount > 0) rss.page < rss.pageCount else videos.size >= PAGE_LIMIT
        return ThirdListPage(rss.categories, videos, rss.page, rss.pageCount, hasMore && videos.isNotEmpty(), rss.total)
    }

    /** XML 详情/搜索列表（条目直接带播放地址）。 */
    fun parseXmlDetailList(body: String): List<ThirdDetail> = parseXmlRss(body).videos.mapNotNull { video ->
        if(video.id.isEmpty() || video.name.isEmpty()) return@mapNotNull null
        // 简介与 JSON 版同口径：剥 HTML 标签、解常见实体
        val synopsis = video.synopsis
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .trim()
        ThirdDetail(
            ThirdVideo(video.id, video.name, video.pic, video.note),
            video.typeName.ifEmpty { null }, video.year.ifEmpty { null }, video.area.ifEmpty { null },
            synopsis.ifEmpty { null },
            video.playEntries.mapIndexed { index, (flag, content) ->
                parsePlayGroups(flag.orEmpty(), content).firstOrNull()
                    ?.takeIf { it.second.isNotEmpty() }
                    ?: ("线路${index + 1}" to emptyList())
            }.filter { it.second.isNotEmpty() }
        )
    }

    /** XML 单条详情。 */
    fun parseXmlDetail(body: String): ThirdDetail? = parseXmlDetailList(body).firstOrNull()

    /** XML 搜索分页。 */
    fun parseXmlDetailPage(body: String): ThirdSearchPage {
        val rss = parseXmlRss(body)
        val details = parseXmlDetailList(body)
        val hasMore = if(rss.pageCount > 0) rss.page < rss.pageCount else details.size >= PAGE_LIMIT
        return ThirdSearchPage(details, rss.page, hasMore && details.isNotEmpty(), rss.total)
    }

    /** XML 搜索联想（只取片名，去重限条数）。 */
    fun parseXmlSuggestions(body: String): List<String> = parseXmlRss(body).videos
        .map { it.name }.filter { it.isNotEmpty() }
        .let { LinkedHashSet(it).toList() }
        .take(SUGGEST_LIMIT)

    /** XML 探活：能解出 rss/list 结构即通过；返回条目总数，非 CMS XML 返回 null。 */
    fun xmlProbeTotal(body: String): Int? {
        val rss = parseXmlRss(body)
        return if(rss.categories.isNotEmpty() || rss.videos.isNotEmpty() || rss.pageCount > 0) rss.total else null
    }

    /** TVBox 明文配置提取：取 type=1（CMS v10 JSON）与 type=0（CMS v1 XML）且 api 为 http(s) 的源，
     *  携带 site.header 专用请求头与 searchable/quickSearch/filterable/categories/timeout 元数据；
     *  spider jar（type=3）与加密 app 源（type=4）自动跳过并计数。 */
    fun parseTvboxConfig(body: String): ThirdTvboxParse {
        val sites = JSONObject(body).optJSONArray("sites") ?: return ThirdTvboxParse(emptyList(), 0)
        val candidates = mutableListOf<ThirdTvboxSite>()
        var skipped = 0
        for(index in 0 until sites.length()) {
            val item = sites.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val api = item.optString("api").trim()
            val type = item.optInt("type", -1)
            if((type == 0 || type == 1) && api.startsWith("http")) {
                val categoryIds = item.optJSONArray("categories")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        when(val v = array.opt(i)) { is Number -> v.toString(); is String -> v.trim().ifEmpty { null }; else -> null }
                    }
                }.orEmpty()
                candidates += ThirdTvboxSite(
                    name.ifEmpty { "未命名源" }, api, parseHeaders(item.opt("header")), type,
                    searchable = tvboxFlag(item, "searchable"),
                    quickSearch = tvboxFlag(item, "quickSearch"),
                    filterable = tvboxFlag(item, "filterable"),
                    categories = categoryIds,
                    timeoutSeconds = item.optInt("timeout", 0).coerceIn(0, MAX_TIMEOUT_SECONDS)
                )
            }
            else skipped += 1
        }
        return ThirdTvboxParse(candidates, skipped)
    }

    /** 源超时上限（秒）：配置异常值不拖垮全局。 */
    const val MAX_TIMEOUT_SECONDS = 60

    /** TVBox 布尔字段取值：兼容 true/false、0/1 数字与字符串形态（org.json 的 optBoolean 不认 0/1）。 */
    private fun tvboxFlag(item: JSONObject, key: String): Boolean = when(val raw = item.opt(key)) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.trim() == "1" || raw.trim().equals("true", ignoreCase = true)
        else -> true
    }

    /** 按源配置裁剪首页分类：categories 为空表示不限制，否则只保留指定 type_id（保持原顺序）。 */
    fun filterCategories(source: ThirdSource, list: List<ThirdCategory>): List<ThirdCategory> =
        if(source.categories.isEmpty()) list else list.filter { it.id in source.categories }
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

    /** 按源配置缓存的定制超时客户端：source.timeoutSeconds>0 时用新实例覆盖默认超时。 */
    private val timedClients = ConcurrentHashMap<Int, OkHttpClient>()
    private fun clientFor(timeoutSeconds: Int): OkHttpClient {
        if(timeoutSeconds <= 0) return http
        return timedClients.getOrPut(timeoutSeconds) {
            http.newBuilder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds.toLong() + 5, TimeUnit.SECONDS)
                .build()
        }
    }

    // 列表/分类/详情内存缓存：10 分钟 TTL，不落盘
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()
    private val cacheTtl = 10 * 60_000L

    // 在途 catalog 请求登记：切换分类/片源时统一取消，避免慢请求占满线程池导致界面长时间空态
    private val catalogCalls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())

    // 在途聚合搜索请求：返回搜索页或切换关键字时取消，避免旧源结果串入新关键字
    private val searchCalls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())

    fun cancelCatalog() {
        synchronized(catalogCalls) { catalogCalls.forEach { it.cancel() }; catalogCalls.clear() }
    }

    fun cancelSearch() {
        synchronized(searchCalls) { searchCalls.forEach { it.cancel() }; searchCalls.clear() }
    }

    /** 单请求：区分 HTTP 错误、空响应与网络异常，给出明确原因。catalog/search 时登记可取消。
     *  headers：源专用请求头，在默认 UA 之上逐条覆盖（OkHttp 头名不区分大小写）；
     *  timeoutSeconds：源配置的单源超时（0 = 全局默认）。 */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSeconds: Int = 0, catalog: Boolean = false, search: Boolean = false): ThirdHttp {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val call = clientFor(timeoutSeconds).newCall(builder.build())
        if(catalog) catalogCalls += call
        if(search) searchCalls += call
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
            if(search) searchCalls -= call
        }
    }

    /** 分类列表：JSON 用 ac=list（首页响应含完整 class[]）；XML 用 ac=videolist（class 随列表返回）。 */
    fun categories(source: ThirdSource): List<ThirdCategory> {
        val key = "${source.api}|categories"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<ThirdCategory>)?.let { return it }
        val xml = source.type == 0
        val params = if(xml) listOf("ac" to "videolist", "pg" to "1") else listOf("ac" to "list", "pg" to "1")
        val response = get(ThirdCms.apiUrl(source.api, params), headers = source.headers, timeoutSeconds = source.timeoutSeconds, catalog = true)
        val parsed = response.body?.let { body -> try {
            if(xml) ThirdCms.parseXmlCategories(body) else ThirdCms.parseList(body).categories
        } catch (_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
    }

    /** 分类列表与条目分页（ac=videolist，含封面与角标；JSON 版 ac=list 是轻量响应缺 vod_pic）。 */
    fun list(source: ThirdSource, categoryId: String?, page: Int): ThirdListPage? {
        val key = "${source.api}|list|$categoryId|$page"
        (cached(key) as? ThirdListPage)?.let { return it }
        val params = buildList {
            add("ac" to "videolist")
            if(!categoryId.isNullOrBlank()) add("t" to categoryId)
            add("pg" to page.toString())
        }
        val response = get(ThirdCms.apiUrl(source.api, params), headers = source.headers, timeoutSeconds = source.timeoutSeconds, catalog = true)
        val parsed = response.body?.let { body -> try {
            if(source.type == 0) ThirdCms.parseXmlList(body) else ThirdCms.parseList(body)
        } catch (_: Exception) { null } } ?: return null
        store(key, parsed); return parsed
    }

    /** 单条详情：JSON 用 ac=detail&ids=；XML 用 ac=videolist&ids=（CMS v1 无 detail 动作）。 */
    fun detail(source: ThirdSource, id: String): ThirdDetail? {
        val key = "${source.api}|detail|$id"
        (cached(key) as? ThirdDetail)?.let { return it }
        val params = if(source.type == 0) listOf("ac" to "videolist", "ids" to id) else listOf("ac" to "detail", "ids" to id)
        val response = get(ThirdCms.apiUrl(source.api, params), headers = source.headers, timeoutSeconds = source.timeoutSeconds)
        val parsed = response.body?.let { body -> try {
            if(source.type == 0) ThirdCms.parseXmlDetail(body) else ThirdCms.parseDetail(body)
        } catch (_: Exception) { null } } ?: return null
        store(key, parsed); return parsed
    }

    /** 搜索联想：接口不支持搜索或网络失败时静默返回空。 */
    fun suggest(source: ThirdSource, word: String): List<String> {
        val key = "${source.api}|suggest|$word"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<String>)?.let { return it }
        val action = if(source.type == 0) "videolist" else "list"
        val response = get(ThirdCms.apiUrl(source.api, listOf("ac" to action, "wd" to word, "pg" to "1")), headers = source.headers, timeoutSeconds = source.timeoutSeconds)
        val parsed = response.body?.let { body -> try {
            if(source.type == 0) ThirdCms.parseXmlSuggestions(body) else ThirdCms.parseSuggestions(body)
        } catch(_: Exception) { null } } ?: emptyList()
        store(key, parsed); return parsed
    }

    /** 片源内搜索分页（结果直接含封面与播放地址）。
     *  部分源未开启搜索时会返回「暂不支持搜索」等纯文本，这里给出明确原因而不是笼统的加载失败。 */
    fun searchPage(source: ThirdSource, word: String, page: Int): ThirdSearchResult {
        val key = "${source.api}|search|$word|$page"
        (cached(key) as? ThirdSearchPage)?.let { return ThirdSearchResult(it, null) }
        val action = if(source.type == 0) "videolist" else "detail"
        val response = get(ThirdCms.apiUrl(source.api, listOf("ac" to action, "wd" to word, "pg" to page.toString())), headers = source.headers, timeoutSeconds = source.timeoutSeconds, search = true)
        response.body ?: return ThirdSearchResult(null, response.error ?: "连接失败")
        val parsed = try {
            if(source.type == 0) ThirdCms.parseXmlDetailPage(response.body!!) else ThirdCms.parseDetailPage(response.body!!)
        } catch(_: Exception) { null } ?: return ThirdSearchResult(null, "该片源未开启搜索")
        store(key, parsed); return ThirdSearchResult(parsed, null)
    }

    /** 系列剧候选：按片名主名在同一片源里搜一次，结果自带封面与播放地址，切季可直接播。
     *  按「源 + 主名」缓存，同一系列的多季来回切不重复请求；源站关闭搜索或网络失败时返回空。 */
    fun seriesCandidates(source: ThirdSource, name: String): List<ThirdDetail> {
        val base = ThirdCms.seriesBase(name)
        if(base.isEmpty()) return emptyList()
        val key = "${source.api}|series|$base"
        @Suppress("UNCHECKED_CAST")
        (cached(key) as? List<ThirdDetail>)?.let { return it }
        val details = searchPage(source, base, 1).page?.details.orEmpty()
        store(key, details); return details
    }

    /** 探活：HTTP 200 + 响应含 list 结构即通过，返回条目数；失败带明确原因。 */
    fun probe(source: ThirdSource): ThirdProbe = probe(source.api, source.headers, source.timeoutSeconds, source.type)

    /** 裸地址探活（TVBox 导入预览用，源尚未建立时只拿得到 api 与 header）。 */
    fun probe(api: String, headers: Map<String, String> = emptyMap(), timeoutSeconds: Int = 0, type: Int = 1): ThirdProbe {
        val action = if(type == 0) "videolist" else "list"
        val response = get(ThirdCms.apiUrl(api, listOf("ac" to action, "pg" to "1")), headers = headers, timeoutSeconds = timeoutSeconds)
        response.body ?: return ThirdProbe(false, 0, response.error ?: "连接失败")
        val total = response.body?.let { body -> try {
            if(type == 0) ThirdCms.xmlProbeTotal(body) else ThirdCms.probeTotal(body)
        } catch (_: Exception) { null } } ?: return ThirdProbe(false, 0, "非 CMS 接口（响应不包含列表结构）")
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
