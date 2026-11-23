// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),seriesTitle(o.str("title")),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
/** 详情页附带的社交数据，缺失时各字段保持默认空值，调用方按需隐藏。 */
data class SeriesSocial(val rating: Double = 0.0, val ratingCount: String = "", val favoriteText: String = "", val heatText: String = "", val likeText: String = "") {
    val isEmpty get() = rating<=0.0 && favoriteText.isBlank() && heatText.isBlank() && likeText.isBlank()
}
/**
 * 剧名规整。
 *
 * 详情接口偶尔把简介拼进剧名字段（实测拿到过 `《黄石第五季》\n蒙大拿荒野深处…`），而标题栏只有
 * 一行高，第二行就被裁成半截字——看起来像被什么东西盖住了。这里只留第一行，压掉多余空白。
 * 读缓存的路径也要过一遍：脏数据可能已经存进去了。
 */
fun seriesTitle(raw: String): String = raw.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() }
    .orEmpty()
    .replace(Regex("[\\s\\u3000]+"), " ")
    .take(48)
data class Detail(val series: Series, val episodes: List<String>, val related: List<Series> = emptyList(), val sameName: List<Series> = emptyList(), val social: SeriesSocial = SeriesSocial()) {
    /** Same-name entries with a finite season number beyond the current one (untitled season counts as 1). */
    val newerSeasons: List<Series> get() {
        val current=seasonNumber(series.title).let { if(it==Int.MAX_VALUE) 1 else it }
        return sameName.filter { val n=seasonNumber(it.title); n!=Int.MAX_VALUE && n>current }
    }
    /** The immediately following season, so auto-play continues in order instead of jumping to the latest. */
    val nextSeason: Series? get() = newerSeasons.minByOrNull { seasonNumber(it.title) }
}
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
/** One website genre selector entry; the slug selects the category path, ids echo back for validation. */
data class Genre(val slug: String, val name: String, val ids: List<String>)
/** [codec] 非空表示这是 ExoPlayer 无法解码、需要 NDK 解码器原生播放的编码（目前仅 bytevc2）。 */
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val codec: String = "", val qNum: Int = 0)
/** 一集的完整清晰度阶梯：普通档从高到低在前，bytevc2 垫底档在最后。
 *  服务端一次下发全部档位（实测固定 5 条 video_1..video_5，key_seed/spade_a 集级共用），
 *  客户端把 5 条直链一次解密缓存到 url_expire（约 1h）——切清晰度/自动降档纯内存换直链，零请求。 */
data class StreamLadder(val id: String, val tiers: List<StreamInfo>, val expireAt: Long)
/** 一次窗口取址的结果：[requested] 请求集数、[cached] 取址后可播放的集数、[batch] 本次批量新增、[route] 命中的路由。 */
data class WarmReport(val requested: Int, val cached: Int, val batch: Int, val route: String?)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 平台对该集只产出了 ByteVC2 等电视无法解码的编码，重试与换清晰度都不会成功。
 *  [bvc2Stream] 是已解析好的最低档 bytevc2 直链 + key，供上层切到 NDK 原生解码播放页。 */
class UnplayableVideoException(message: String, val diagnostic: String = "", val bvc2Stream: StreamInfo? = null): IOException(message)
/** 播放信息接口返回了 HTTP 200 空体；已按有界退避重读仍未成功。 */
class EmptyPlaybackResponseException: IOException("播放接口暂时返回空响应，请稍后重试")
/** 服务端 HTTP 429：唯一真正需要「等」的信号，按 Retry-After 冷却。
 *  与空体区分开：空体（200+0）是匿名设备额度用尽，换身份即可，不该锁死用户。 */
class PlaybackRateLimitedException(val retryAfterMs: Long = 0): IOException("服务端限流（HTTP 429）")
/** 官方取址接口处于行为风控冷却期：此刻任何取址都会被直接拒绝，继续请求只会拉长封禁。 */
class PlaybackThrottledException(val remainingMs: Long): IOException(
    "官方接口限流中，约 ${((remainingMs+999)/1000).coerceAtLeast(1)} 秒后恢复")

/** 站点 CDN 在重定向时下发 ttwid 并拦截无 Cookie 的后续请求，内存 Cookie 罐使其自动流转。 */
class MemoryCookies : okhttp3.CookieJar {
    private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
    // RFC 语义：同名 + 同域 + 同路径的新 Cookie 替换旧值，而不是叠加。
    private fun List<okhttp3.Cookie>.replacedBy(cookies: List<okhttp3.Cookie>) =
        filter { kept -> cookies.none { it.name==kept.name && it.domain==kept.domain && it.path==kept.path } }
            .toMutableList().also { it += cookies }
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, kept -> kept.orEmpty().replacedBy(cookies) }
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }
}

open class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).cookieJar(MemoryCookies()).build(), private val clock: () -> Long = System::currentTimeMillis, private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared, private val modelCache: PlaybackModelCache = PlaybackModelCache()) {
    /** 同一份取址缓存、另一套传输：预取需要更短的超时，但必须和正式播放看到同一个缓存。 */
    fun childTransport(http: OkHttpClient) = ContentRepository(http, clock, playbackSession, modelCache)
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val genreCache=mutableMapOf<ContentType,Pair<Long,List<Genre>>>()
    private val suggestCache=linkedMapOf<String,Pair<Long,List<String>>>()
    // 签名搜索接口被平台关停时会稳定返回空体，短期内不再重复尝试，避免每次搜索都白等两次往返
    @Volatile private var appSearchRetryAt=0L
    // 分类筛选接口只在较新客户端版本开放；版本号沿用参考实现验证过的取值，平台升级时集中改这里
    private val discoveryVersion=mapOf(
        "version_code" to "73532",
        "manifest_version_code" to "73532",
        "update_version_code" to "73532",
        "pv_player" to "73532",
        "version_name" to "7.3.5.32",
    )
    private fun categoryPath(type: ContentType)=when(type) {
        ContentType.COMIC -> "comic-drama"
        ContentType.SHORT -> "real-drama"
        ContentType.AI -> "ai-drama"
    }
    private fun text(url: String, signed: Signer.Request? = null, extraHeaders: Map<String,String> = emptyMap()): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        extraHeaders.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            if(bytes.isEmpty()) throw IOException("接口响应为空，请稍后重试")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),seriesTitle(o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } }),cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    open fun ranking(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/${board.path}?page=$page"),page,board)
    }
    fun comicRanking(page: Int = 1)=ranking(RankBoard.COMIC,page)
    fun realRanking(page: Int = 1)=ranking(RankBoard.REAL,page)
    /** Genre selectors from the website's category page; cached briefly because they rarely change. */
    open fun genres(type: ContentType): List<Genre> {
        synchronized(genreCache) { genreCache[type]?.let { if(clock()-it.first<600_000) return it.second } }
        val data=router("$site/category/${categoryPath(type)}")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("题材列表暂不可用")
        val rows=section.optJSONArray("selectorList") ?: throw IOException("题材列表暂不可用")
        val genres=linkedMapOf<String,Genre>()
        for(row in rows.objects()) for(item in row.optJSONArray("items")?.objects() ?: emptyList()) {
            val ids=item.optJSONArray("category_json_ids") ?: continue
            val list=(0 until ids.length()).map { ids.optString(it) }.filter { it.isNotBlank() }
            val slug=item.str("selector_item_id"); val name=item.str("show_name")
            if(slug.isNotBlank() && name.isNotBlank() && list.isNotEmpty()) genres.putIfAbsent(slug,Genre(slug,name,list))
        }
        if(genres.isEmpty()) throw IOException("题材列表暂不可用")
        return genres.values.toList().also { list -> synchronized(genreCache) { genreCache[type]=clock() to list } }
    }
    open fun home(page: Int = 1, type: ContentType = ContentType.SHORT, genre: Genre? = null): CatalogPage {
        require(page in 1..100)
        if(genre!=null) return categoryListing("$site/category/${categoryPath(type)}/${genre.slug}"+if(page>1) "?page=$page" else "",page,"题材",genre)
        if(type==ContentType.COMIC) return comicRanking(page)
        if(type==ContentType.AI) return categoryListing("$site/category/ai-drama"+if(page>1) "?page=$page" else "",page,"AI剧")
        return categoryListing("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "",page,"首页")
    }
    /**
     * 真人剧独立列表（官网 `/category/real-drama`，纯真人出演，不含漫剧/AI 剧）。
     * 与 [home] 的 SHORT 分支（首页混合流）区分开：手机版把它单独做成「真人剧」频道，
     * TV 版仍走 home()，行为不变。
     */
    open fun realDrama(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return categoryListing("$site/category/real-drama"+if(page>1) "?page=$page" else "",page,"真人剧")
    }
    private fun categoryListing(url: String,page: Int,label: String,genre: Genre? = null): CatalogPage {
        val data=router(url)
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("${label}数据结构已变化")
        if(genre!=null) {
            // The site silently ignores filters it does not recognize; only the echoed ids prove the genre applied.
            val applied=section.optJSONObject("apiQuery")?.optJSONArray("category_json_ids")
            val matched=applied!=null && genre.ids.any { id -> (0 until applied.length()).any { applied.optString(it)==id } }
            if(!matched) throw IOException("题材筛选暂不可用，请稍后重试")
        }
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("${label}数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // AI 剧没有独立搜索口径，官网把它归在漫剧搜索结果里
        if(type==ContentType.AI) return search(keyword,page,ContentType.COMIC)
        if(type==ContentType.COMIC) {
            try { return fillSeasonsFromDetail(appSearch(keyword,page,type),page) }
            catch(e: Exception) { /* App search API rejected the request or the cursor expired; the website paginates independently. */ }
            return fillSeasons(webSearch(keyword,page,COMIC_WEB_GENRE),page,type,keyword)
        }
        var appError: Exception? = null
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return fillSeasonsFromDetail(result,page) } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; appError=e }
        return try { fillSeasons(webSearch(keyword,page,SHORT_WEB_GENRE),page,type,keyword) }
        catch(e: Exception) { throw IOException("搜索暂不可用（App接口：${appError?.message ?: "无结果"}；网站：${e.message}）",e) }
    }
    /** 搜索联想词（App suggest 接口，5 分钟缓存）；接口按注册设备放行，失败返回空列表。 */
    fun searchSuggest(query: String): List<String> = suggestRaw(query)
    /** 轻量站点搜索（不做补季/题材过滤）：给搜索联想词前几条带封面上屏用。 */
    fun preview(keyword: String): CatalogPage = webSearch(keyword, 1, null)
    /** 高级搜索：按服务端下发的条件筛选剧集；条件变化后必须重取第 1 页才能拿到新的选项面板。 */
    fun filteredDiscovery(selection: DiscoverySelection, cursor: DiscoveryCursor? = null): FilteredDiscoveryPage {
        val values=VendorConstants.device.toMutableMap().apply { putAll(discoveryVersion) }
        val body=selection.request(cursor)
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",values,body.toByteArray(Charsets.UTF_8))
        // 该接口与搜索接口同源，偶发空体或 5xx 时重试一次再判定失败
        val raw=runCatching { text(signed.url,signed) }.getOrElse { first ->
            Thread.sleep(300)
            runCatching { text(signed.url,signed) }.getOrElse { throw first }
        }
        return DiscoveryParser.parse(raw,cursor==null)
    }
    // 站点搜索页已改为 SPA，数据由 /api/search/result/ 下发（返回 searchList/totalPages/hasMore，10 条一页）。
    private fun webSearch(keyword: String, page: Int, genre: String?): CatalogPage {
        val url="$site/api/search/result/?query=${Signer.encode(keyword)}&page=$page"
        // 该接口偶发 502，重试一次再判定失败，避免搜索页直接报错
        val body=runCatching { text(url) }.getOrElse { first ->
            Thread.sleep(300)
            runCatching { text(url) }.getOrElse { throw first }
        }
        val data=runCatching { JSONObject(body) }.getOrElse { throw IOException("搜索数据暂不可用") }
        val rows=data.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val mapped=rows.objects().let { list -> if(genre==null) list else list.filter { (it.optJSONObject("video_data") ?: it).optString("genre")==genre } }.map(::card)
        // 站点把漫剧/短剧混在同一结果里，按 genre 过滤后单页条数会减少；分页仍以站点页数为准。
        val lastPage=data.optInt("totalPages",page)
        return CatalogPage(valid(mapped),data.optBoolean("hasMore",false) && page<minOf(lastPage,100))
    }
    private data class SeasonFill(val at: Long, val items: List<Series>)
    private val seasonFills=mutableMapOf<String, SeasonFill>()
    /** 网站搜索固定只返回前 10 条，多季剧必然缺季；联想接口未被拦截，用同名季的精确标题回查网站补齐。 */
    private fun collectSeasons(base: String, known: List<Series>, genre: String?, hints: List<String>, cacheKey: String, budget: Int = 10): List<Series> {
        val knownIds=known.map { it.id }.toSet()
        // 缓存存的是「该剧名已知季并集」（写缓存时的 known + 本次新发现），不是差集。
        // 各季详情页的 related 聚合范围不一致（第 1 季页常带全季、第 5 季页只带前四季）：
        // 存差集时第 1 季页算出「无缺季」的空结果会污染第 5 季页，第 6 季直接消失（2026-11-08 用户实测）。
        // 存并集后，第 5 季页只需从缓存里减掉自己已有的季，缺的季原样补回，无需重新全网搜索。
        val cached=synchronized(seasonFills) { seasonFills[cacheKey]?.takeIf { clock()-it.at<86_400_000 }?.items }
        if(cached!=null) {
            val missing=cached.filter { it.id !in knownIds }.take(budget)
            // 缓存知识面覆盖本次已知（或确实还有缺季）才直接复用；缓存比本次知道的还少，则重新补一次。
            // 比较基数只数同基底的季：搜索页传进来的 known 混着多个剧，用总数会误判成「缓存太窄」而白跑一轮网络。
            if(missing.isNotEmpty() || cached.size>=known.count { baseName(it.title)==base }) return missing
        }
        val found=mutableListOf<Series>()
        fun absorb(page: CatalogPage?) {
            page?.items?.filter { it.id !in knownIds && baseName(it.title)==base && found.none { s -> s.id==it.id } }?.let { found+=it }
        }
        // 基底整词、基底前 4 字与联想互不依赖，并发取回，避免串行等待
        val queries=hints.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        // 线程数保持与改造前一致，仅把串行的联想查询改为并发，不额外放大站点并发请求
        val pool=Executors.newFixedThreadPool(4)
        try {
            val pages=listOf(base,base.take(4)).distinct().map { query -> pool.submit(Callable { runCatching { webSearch(query,1,genre) }.getOrNull() }) }
            // 联想查询彼此独立，逐个并发提交，避免串行等待多轮往返
            val suggestions=queries.map { query -> pool.submit(Callable { runCatching { suggestRaw(query) }.getOrDefault(emptyList()) }) }
            pages.forEach { absorb(it.get()) }
            // 多个 query 的联想结果互补，能覆盖更多季（联想每次返回的子集并不稳定）
            val knownTitles=(known.map { it.title }+found.map { it.title }).map { it.trim() }.toSet()
            val names=suggestions.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }.map { it.trim() }
                .filter { it.isNotBlank() && baseName(it)==base && it !in knownTitles }.distinct()
            // 逐季回查同样互不依赖，3 路并发把最耗时的这段压到原来的三分之一
            val lookups=names.map { name ->
                val season=seasonNumber(name)
                pool.submit(Callable {
                    runCatching { webSearch(name,1,genre) }.getOrNull()
                        ?.items?.firstOrNull { it.id !in knownIds && baseName(it.title)==base && seasonNumber(it.title)==season }
                })
            }
            for(task in lookups) {
                if(found.size>=budget) break
                val match=runCatching { task.get() }.getOrNull()
                if(match!=null && found.none { it.id==match.id }) found+=match
            }
        } finally {
            pool.shutdownNow()
        }
        val final=found.take(budget)
        // 写并集：本次已知（同基底）+ 新发现，供其它季页减去自己已有的季后复用。
        val union=(known.filter { baseName(it.title)==base }+final).distinctBy { it.id }.take(40)
        synchronized(seasonFills) {
            seasonFills[cacheKey]=SeasonFill(clock(),union)
            while(seasonFills.size>50) seasonFills.remove(seasonFills.keys.first())
        }
        return final
    }
    private fun fillSeasons(page: CatalogPage, pageNumber: Int, type: ContentType, keyword: String): CatalogPage {
        if(pageNumber!=1 || page.items.size<2) return page
        val groups=page.items.groupBy { baseName(it.title) }.filter { (base,rows) ->
            base.isNotBlank() && (rows.size>1 || rows.any { seasonNumber(it.title)!=Int.MAX_VALUE })
        }
        if(groups.isEmpty()) return page
        val additions=mutableMapOf<String,List<Series>>()
        var budget=10
        for((base,rows) in groups.entries.sortedByDescending { it.value.size }.take(2)) {
            if(budget<=0) break
            val genre=if(type==ContentType.COMIC) COMIC_WEB_GENRE else SHORT_WEB_GENRE
            // 基底词、用户输入、基底前缀各取一次联想，多个 query 的结果互补
            val extra=collectSeasons(base,page.items,genre,listOf(base,keyword,base.take(4)),"$type|$base",budget)
            budget-=extra.size
            additions[base]=extra
        }
        if(additions.values.all { it.isEmpty() }) return page
        return mergeSeasonGroups(page,additions)
    }
    /** 原位合并：同一基底按季号排列，其余条目保持原有相关性顺序。 */
    private fun mergeSeasonGroups(page: CatalogPage, additions: Map<String,List<Series>>): CatalogPage {
        val output=mutableListOf<Series>()
        val placed=mutableSetOf<String>()
        for(item in page.items) {
            val base=baseName(item.title)
            if(base in additions) {
                if(!placed.add(base)) continue
                val group=(page.items.filter { baseName(it.title)==base }+additions.getValue(base)).distinctBy { it.id }
                output+=group.sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }
            } else output+=item
        }
        return page.copy(items=output)
    }
    /**
     * App 搜索接口把同系列的各季分散在分页里并与无关剧混排，首页常缺季。
     * 详情页 related 自带整季聚合（与详情页"系列 N 季"同源），用第一条命中的详情把首页同名季补齐；
     * 只对已出现 2 条以上的基底补，避免泛词搜索为单条结果额外拉详情页。
     */
    private fun fillSeasonsFromDetail(page: CatalogPage, pageNumber: Int): CatalogPage {
        if(pageNumber!=1 || page.items.size<2) return page
        val groups=page.items.groupBy { baseName(it.title) }.filter { (base,rows) -> base.isNotBlank() && rows.size>1 }
        if(groups.isEmpty()) return page
        val additions=mutableMapOf<String,List<Series>>()
        for((base,rows) in groups.entries.sortedByDescending { it.value.size }.take(2)) {
            val extra=detailSeasons(base,rows.first().id).filter { s -> page.items.none { it.id==s.id } }
            additions[base]=extra
        }
        if(additions.values.all { it.isEmpty() }) return page
        return mergeSeasonGroups(page,additions)
    }
    private fun detailSeasons(base: String, id: String): List<Series> {
        val key="detail|$base"
        val cached=synchronized(seasonFills) { seasonFills[key]?.takeIf { clock()-it.at<86_400_000 }?.items }
        if(cached!=null) return cached
        val found=runCatching { val d=detail(id); (listOf(d.series)+d.sameName).filter { baseName(it.title)==base }.distinctBy { it.id } }.getOrElse { emptyList() }
        synchronized(seasonFills) {
            seasonFills[key]=SeasonFill(clock(),found)
            while(seasonFills.size>50) seasonFills.remove(seasonFills.keys.first())
        }
        return found
    }
    /**
     * 系列剧（同 IP 多季合集）：`bookapi/plan/v1` 里 `show_type=547` 的行。
     * 协议参照上游 PhoenixReadApi（C#）GetDramaSeasonsAsync 的 plan feed 路径：
     * 按当前剧 id 直接换整季列表，不需要 album_id，也不依赖播放域签名。
     * 与搜索/联想同一套注册身份放行规则；失败返回空列表（调用方自己兜底）。
     */
    fun planSeries(id: String): List<Series> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val key="plan|$id"
        synchronized(seasonFills) { seasonFills[key]?.takeIf { clock()-it.at<600_000 }?.let { return it.items } }
        val found=runCatching {
            planCells(id,"single_col_same_ip_series",id).objects()
                .firstOrNull { it.optInt("show_type")==547 }
                ?.let { planCellItems(it) }
                // 官方会列出「已官宣未上线」的季（episode_cnt=0，上游 ParseSeasons 注释同口径）：
                // 这类季的网页详情页不存在，点进去必 HTTP 404（2026-11-22 公路求生榜第四季实测），
                // 不能给入口；当前剧自己保底（正常在播，不该被误过滤）。
                ?.filter { it.badge.isNotEmpty() || it.id==id }
                .orEmpty()
                .sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }
        }.getOrElse { emptyList() }
        synchronized(seasonFills) {
            seasonFills[key]=SeasonFill(clock(),found)
            while(seasonFills.size>60) seasonFills.remove(seasonFills.keys.first())
        }
        return found
    }
    /**
     * 详情页相关推荐（官方 plan feed）：`bookapi/plan/v1` 带 from=select_panel_recommend + scene=10，
     * 只取 `show_type=160`（详情页相关视频）的 cell。协议参照上游 openhongguo SeriesRecommend——
     * 该界面没有专用 RPC，官方客户端复用通用 plan feed。
     * 注意（上游实测结论）：匿名身份服务端常只回通用「更多好剧」cell（show_type=401，不随剧变），
     * 真·相关 cell 要登录账号派生的身份才稳定；因此这里只认 160，拿不到就返回空——
     * 调用方回落官网详情页内嵌 related_list（匿名场景下它本身就是真·相关推荐）。
     */
    fun planRelated(id: String): List<Series> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val loggedIn=runCatching { loginCookieProvider?.invoke() }.getOrNull()?.cookie?.isNotBlank()==true
        val key="planrel|$id${if(loggedIn) "|L" else ""}"
        synchronized(seasonFills) { seasonFills[key]?.takeIf { clock()-it.at<600_000 }?.let { return it.items } }
        val found=runCatching {
            planCells(id,"select_panel_recommend",null).objects()
                .firstOrNull { it.optInt("show_type")==160 }
                ?.let { planCellItems(it) }.orEmpty()
        }.getOrElse { emptyList() }.filter { it.id!=id }
        synchronized(seasonFills) {
            seasonFills[key]=SeasonFill(clock(),found)
            while(seasonFills.size>60) seasonFills.remove(seasonFills.keys.first())
        }
        return found
    }
    /** Probe --related 用：plan/v1 相关推荐场景全部 cell 的概览（show_type / cell 名 / 条目数），不看条目明细。 */
    fun planRelatedProbe(id: String): List<Triple<Int,String,Int>> = runCatching {
        planCells(id,"select_panel_recommend",null).objects().map { cell ->
            Triple(cell.optInt("show_type"),cell.str("cell_name"),
                (cell.optJSONArray("video_data") ?: cell.optJSONArray("cell_data"))?.length() ?: 0)
        }
    }.getOrElse { emptyList() }
    /** 登录态供给（MainActivity 注入 AccountManager 的账号凭证）：有值时 plan/v1 按登录身份请求。 */
    var loginCookieProvider: (() -> AccountCredentials?)? = null

    private fun planCells(id: String,from: String,filterIds: String?): JSONArray {
        VendorConstants.regenerate()
        val values=searchValues()
        // 登录态请求要「Cookie + 账号设备身份」配套：Cookie 是登录时按 credentials.deviceId/installId
        // 注册的账号设备下发的，签名参数若仍是 RecommendIdentity 的另一套 did/iid，服务端视同匿名
        // 只回 401 通用「更多好剧」（2026-11-21 logcat 实测 cookieLen=1090 仍回 [401] 实锤）。
        val account=runCatching { loginCookieProvider?.invoke() }.getOrNull()?.takeIf { it.cookie.isNotBlank() }
        if(account!=null) {
            for((k,v) in account.device) values[k]=v
            values["device_id"]=account.deviceId
            values["iid"]=account.installId
        }
        val cookie=account?.let { acc ->
            (listOf(acc.cookie)+acc.deviceCookie.entries.map { (k,v)->"$k=$v" })
                .filter { it.isNotBlank() }.joinToString("; ")
        }
        values.putAll(linkedMapOf(
            "book_id" to id,
            "from" to from,
            "scene" to "10",
            "session_uuid" to java.util.UUID.randomUUID().toString().replace("-",""),
            "user_is_login" to if(account!=null) "1" else "0",
        ))
        filterIds?.let { values["filter_ids"]=it }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/plan/v1/",values)
        val data=JSONObject(text(signed.url,signed,if(cookie!=null) mapOf("Cookie" to cookie!!) else emptyMap()))
        if(data.optInt("code",-1)!=0) return JSONArray()
        return data.optJSONArray("data") ?: data.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
    }
    /** plan cell 条目 → Series（show_type=160/401/547 的行同构，共用解析）。 */
    private fun planCellItems(cell: JSONObject): List<Series> =
        (cell.optJSONArray("video_data") ?: cell.optJSONArray("cell_data"))?.objects().orEmpty().mapNotNull { row ->
            val video=row.optJSONObject("video_data") ?: row
            val sid=video.str("series_id").ifEmpty { video.str("series_id_str").ifEmpty { row.str("book_id") } }
            if(!sid.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
            val count=video.optInt("episode_cnt")
            Series(sid,seriesTitle(video.str("series_title").ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }),
                cleanUrl(video.str("cover").ifEmpty { video.str("series_cover") }),"",
                if(count>0) "全 $count 集" else "")
        }.distinctBy { it.id }
    /** App 搜索/联想接口和播放接口一样按注册设备放行：未注册的随机身份只会拿到 HTTP 200 空体。 */
    private fun searchValues(): MutableMap<String,String> {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        runCatching { playbackSession.profile(http) }.getOrNull()?.values?.let { identity ->
            for(k in listOf("device_id","iid","cdid")) identity[k]?.let { values[k]=it }
        }
        return values
    }
    private fun suggestRaw(query: String): List<String> {
        synchronized(suggestCache) { suggestCache[query]?.takeIf { clock()-it.first<300_000 }?.let { return it.second } }
        val result=runCatching {
            if(query.isBlank() || query.length>40) return emptyList()
            VendorConstants.regenerate()
            val values=searchValues()
            values["query"]=query
            val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v",values)
            val data=JSONObject(text(signed.url,signed))
            if(data.optInt("code",-1)!=0) return emptyList()
            val rows=data.optJSONObject("data")?.optJSONArray("query_result") ?: return emptyList()
            (0 until rows.length()).map { rows.optString(it).trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        }.getOrElse { emptyList() }
        if(result.isNotEmpty()) synchronized(suggestCache) {
            suggestCache[query]=clock() to result
            while(suggestCache.size>200) suggestCache.remove(suggestCache.keys.first())
        }
        return result
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        if(clock()<appSearchRetryAt) throw IOException("搜索接口暂不可用")
        val tabType=type.searchTab ?: throw IOException("${type.label}暂不支持搜索")
        VendorConstants.regenerate()
        val values=searchValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to tabType.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        // 官方 APK 逆向确认主网关为 reading.snssdk.com；签名只覆盖参数与主机无关，被网关拦截时换主机兜底
        var data: JSONObject? = null
        for(host in listOf("https://api5-normal-sinfonlinea.fqnovel.com","https://reading.snssdk.com")) {
            val signed=signer.sign("$host/reading/bookapi/search/tab/v",values)
            val candidate=runCatching { JSONObject(text(signed.url,signed)) }.getOrNull() ?: continue
            if(candidate.optInt("code",-1)==0) { data=candidate; break }
        }
        if(data==null) {
            appSearchRetryAt=clock()+300_000
            throw IOException("搜索接口暂不可用")
        }
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val rows=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { rows.addAll(it) }
            for(row in rows) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,seriesTitle(title),cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    /** Pinyin-initial suggestions from the signed app API; every failure returns an empty list so the TV keyboard keeps working. */
    fun suggest(query: String): List<String> {
        val key=query.trim().lowercase()
        if(key.isEmpty() || key.length>20 || !key.matches(Regex("[a-z0-9]+"))) return emptyList()
        return suggestRaw(key)
    }
    /** 详情公共部分：剧集列表 + 卡片 + 社交数；related/sameName 只有完整详情才需要（两者都要联网）。 */
    private fun detailCommon(id: String): Triple<Detail, String, JSONObject> {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 404 大多是「已官宣未上线」的季（官方 plan feed 会列出、但网页详情页还没建），
        // 报成网络错误会误导用户去检查网络（2026-11-22 公路求生榜第四季实测）。
        val html=runCatching { text("$site/detail?series_id=$id") }.getOrElse { e->
            throw IOException(if(e.message.orEmpty().contains("HTTP 404")) "该季暂未上线，等官方放出后再来" else e.message, e)
        }
        val loader=extractRouter(html).getJSONObject("loaderData")
        val o=loader.optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val series=card(o).copy(id=id)
        val social=social(loader.optJSONObject("detail_page")?.optJSONObject("seriesSocialInfo"))
        return Triple(Detail(series,episodes,social=social),html,o)
    }
    /** Feed 起播用的轻量详情：只要剧集列表和社交数，跳过 related/sameName 的补季扫描
     *  （sameName 会并发连发多次网站搜索，feed 滑动换剧完全用不到，白耗 1-2 秒）。 */
    open fun detailLite(id: String): Detail = detailCommon(id).first
    fun detail(id: String): Detail {
        val (base,html,o)=detailCommon(id)
        // 相关推荐双层：官方 plan/v1（from=select_panel_recommend，show_type=160）为主；
        // 匿名身份常拿不到 160 cell（只回通用 401「更多好剧」），此时回落官网详情页内嵌
        // related_list（本身就是按剧下发的真·相关推荐，匿名最优解）。登录态接入后 160 才稳定出现。
        val related=runCatching { planRelated(id) }.getOrElse { emptyList() }.ifEmpty { related(html,base.series) }
        // 系列剧双层（上游 openhongguo Seasons 同链路）：官方 plan feed
        // （from=single_col_same_ip_series，show_type=547，列出全部季含未上线的）为主，
        // 命中即省掉 sameName 的搜索推断风暴（净减请求）；plan 没给 547 行时回落
        // 原有同名推断（网页 related_list 同名兄弟 + collectSeasons 搜索）。
        // album_detail 接口（上游第 2 层）不接：album_id 只在原生详情 API 下发，网页拿不到。
        val seasons=runCatching { planSeries(id) }.getOrElse { emptyList() }
            .ifEmpty { sameName(base.series,related) }
            .filter { it.id!=base.series.id }
        return base.copy(related=related,sameName=seasons)
    }
    // 评分与收藏数只在详情页 SSR 里下发，卡片数据源没有这些字段，因此只读取不追加请求。
    private fun social(o: JSONObject?): SeriesSocial {
        if(o==null) return SeriesSocial()
        val favorite=compactCount(o.str("series_favorite_count"))
        return SeriesSocial(
            rating=o.optDouble("rating",0.0).takeIf { it>0 } ?: 0.0,
            ratingCount=compactCount(o.str("rating_count")),
            favoriteText=if(favorite.isBlank()) "" else "${favorite}收藏",
            heatText=o.optJSONObject("hot_score_data")?.str("text").orEmpty(),
            // 官方 feed 爱心下方显示的是点赞数（series_like_count），不是热度；没有时不显示
            likeText=compactCount(o.str("series_like_count"))
        )
    }
    private fun compactCount(raw: String): String {
        val value=raw.trim().toLongOrNull() ?: return ""
        if(value<1) return ""
        fun trim(number: Double, unit: String)=String.format(Locale.CHINA,"%.1f",number).removeSuffix(".0")+unit
        return when {
            value>=100_000_000 -> trim(value/100_000_000.0,"亿")
            value>=10_000 -> trim(value/10_000.0,"万")
            else -> value.toString()
        }
    }
    // The detail page has no dedicated same-name group, so other seasons are collected from its related
    // payload and filled with the same website/suggestion pipeline used by search.
    private fun sameName(current: Series, related: List<Series>): List<Series> {
        val base=baseName(current.title); if(base.isBlank()) return emptyList()
        val siblings=related.filter { it.id!=current.id && baseName(it.title)==base }.distinctBy { it.id }
        val extra=collectSeasons(base,listOf(current)+siblings,null,listOf(base,base.take(4)),"detail|$base",20)
        return (siblings+extra).distinctBy { it.id }.filter { it.id!=current.id }
            .sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }.take(20)
    }
    private fun baseName(title: String)=title.replace(SeasonPattern,"").trim().trim('：',':','-','·',' ')
    // The detail page embeds its recommendation payload as an HTML-escaped JSON array inside the
    // `data-fn-args` attribute, separate from _ROUTER_DATA. Same-name entries (other seasons) sort first.
    private fun related(html: String,current: Series): List<Series> {
        val marker="data-fn-args=\""
        val start=html.indexOf(marker); if(start<0) return emptyList()
        val from=start+marker.length; val end=html.indexOf('"',from); if(end<0) return emptyList()
        val args=runCatching {
            JSONArray(decodeEntities(html.substring(from,end)))
                .optJSONArray(1)?.optJSONObject(0)?.optJSONArray("routerDataFnArgs")?.optString(0).orEmpty()
        }.getOrNull() ?: return emptyList()
        if(args.isEmpty()) return emptyList()
        val groups=runCatching { JSONObject(args).optJSONObject("recommendationGroups") }.getOrNull() ?: return emptyList()
        val rows=groups.optJSONArray("related_list") ?: groups.optJSONArray("hot_list") ?: return emptyList()
        val items=valid(rows.objects().map(::card)).filter { it.id!=current.id }
        val key=current.title.trim()
        if(key.isEmpty()) return items
        return items.sortedBy { item ->
            when {
                item.title.equals(key,ignoreCase=true) -> 0
                item.title.startsWith(key) || key.startsWith(item.title) -> 1
                else -> 2
            }
        }
    }
    private fun decodeEntities(value: String) = value.replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">").replace("&#39;","'").replace("&amp;","&")
    /** 取一集的直链播放地址。
     *  正常情况只要 1 个请求（v5 签名链）；站方空响应（HTTP 200 + 0 字节）会立刻转成
     *  [PlaybackThrottledException] 并进入全局冷却，冷却期内本方法不发任何请求。 */
    fun stream(id: String, maxQuality: Int = 1080, quiet: Boolean = false): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 风控冷却期内连一次请求都不发：继续打只会把冷却期成倍拉长。
        guardPlaybackCooldown()
        try {
            return streamFromModel(videoModel(id,quiet=quiet),maxQuality)
        } catch (unplayable: UnplayableVideoException) {
            // 静默模式（后台预取）不做多轮重试：预取只是优化，失败交给下次触发，别为它白花请求。
            if(quiet) throw unplayable
            // 站方对同一集会间歇性下发降级模型（video_list 全是 bytevc2 加密 blob）。
            // 换身份实测不影响下发内容，所以只剔缓存 + 退避重取，不再白花一次设备注册请求。
            var failure=unplayable
            for(round in 1 until PLAYBACK_RETRY_ROUNDS) {
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
                // 中途被风控冷却：立刻停手，别把重试当成继续请求的理由。
                guardPlaybackCooldown()
                modelCache.evict(id)
                if(round>1) Thread.sleep(RETRY_BACKOFF_MS*(round-1))
                try {
                    val (level,preferAlt)=RETRY_PLANS[round-1]
                    return streamFromModel(videoModel(id,level,preferAlt),maxQuality)
                } catch(next: UnplayableVideoException) {
                    failure=next
                }
            }
            throw failure
        }
    }
    /** 一次性取流：不做多轮重试/退避；整集只剩 bytevc2 时直接返回最低档直链，不抛 Unplayable。
     *  用途：已确认是 vc2 片源的集在切清晰度/重播时走这条快路径——
     *  完整 stream() 会反复打模型接口 + 退避数秒，且中途可能混入会 403 的普通直链，
     *  造成「降级重试」闪屏后回落 360p（2026-11-21 真机实测）。 */
    fun streamFast(id: String, maxQuality: Int = 1080): StreamInfo = streamFromLadder(id,maxQuality)

    private val ladderCache=HashMap<String,StreamLadder>()

    /** 取一集完整清晰度阶梯（见 [StreamLadder]）。
     *  普通档从高到低在前，bytevc2 档按从低到高垫底（最低档 = 最后兜底）；
     *  某一档解密失败/直链为空就跳过该档；全部档位都拿不到时抛 [UnplayableVideoException]。
     *  结果缓存到 url_expire，期间切清晰度/重播/自动降档全部零请求。 */
    @Synchronized fun ladder(id: String,quiet: Boolean=false): List<StreamInfo> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val now=System.currentTimeMillis()/1000
        ladderCache[id]?.takeIf { it.expireAt>now && it.tiers.isNotEmpty() }?.let { return it.tiers }
        guardPlaybackCooldown()
        val model=videoModel(id,quiet=quiet)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val data=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=data.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        val normal=ArrayList<Triple<Int,String,JSONObject>>()
        val vc2=ArrayList<Triple<Int,String,JSONObject>>()
        for(entry in videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }) {
            val num=qualityNumber(entry.second.str("quality_desc").ifEmpty { entry.second.str("height").ifEmpty { entry.second.str("vheight").ifEmpty { entry.second.str("quality").ifEmpty { entry.first } } } })
            (if(entry.second.str("codec_type").lowercase() in setOf("bytevc2","bvc2")) vc2 else normal)+=Triple(num,entry.first,entry.second)
        }
        normal.sortByDescending { it.first }; vc2.sortBy { it.first }
        val tiers=ArrayList<StreamInfo>(normal.size+vc2.size)
        normal.forEach { runCatching { tiers+=makeStream(it,data,1080) } }
        vc2.forEach { runCatching { tiers+=makeStream(it,data,1080).copy(codec="bytevc2") } }
        if(tiers.isEmpty()) throw UnplayableVideoException("本集片源暂不兼容，请播放下一集",
            "route=ladder id=$id video_list="+compactVideoList(videoList),null)
        val expire=data.optLong("url_expire").takeIf { it>now } ?: (now+3600)
        ladderCache[id]=StreamLadder(id,tiers.toList(),expire)
        return ladderCache.getValue(id).tiers
    }

    /** 只读已缓存的阶梯（绝不触发网络）；播放失败自动降档用它，失败路径不能再打接口。 */
    @Synchronized fun cachedLadder(id: String): List<StreamInfo>? =
        ladderCache[id]?.takeIf { it.expireAt>System.currentTimeMillis()/1000 }?.tiers?.takeIf { it.isNotEmpty() }

    /** 从阶梯挑一条（不 prepare）：普通档里 ≤cap 的最高档；全无普通档则 bytevc2 最低档。 */
    fun streamFromLadder(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val tiers=ladder(id)
        val normal=tiers.filter { it.codec!="bytevc2" }
        return normal.lastOrNull { it.qNum in 1..maxQuality }
            ?: normal.lastOrNull { it.qNum>0 }
            ?: normal.lastOrNull()
            ?: tiers.lastOrNull { it.codec=="bytevc2" }
            ?: tiers.first()
    }

    private fun streamFromModel(model: JSONObject, maxQuality: Int): StreamInfo {
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        val all=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
            .map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=all.filter {
            it.third.str("main_url").isNotEmpty() && it.third.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull()
        if(selected!=null) return makeStream(selected,info,maxQuality)
        // 整集只剩 bytevc2：把最低档（软解代价最小）的直链 + key 交给上层，
        // 由 Bvc2PlayerActivity 用 NDK 解码器原生播放；解码器不可用时上层仍回退原提示。
        val vc2=all.lastOrNull { it.third.str("codec_type").lowercase() in setOf("bytevc2","bvc2") && it.third.str("main_url").isNotEmpty() }
        val bvc2Stream=vc2?.let { runCatching { makeStream(it,info,maxQuality).copy(codec="bytevc2") }.getOrNull() }
        throw UnplayableVideoException(
            "本集片源暂不兼容，请播放下一集",
            // 诊断：不整段截断（截断会漏掉后面的清晰度条目），逐条给紧凑摘要，
            // 并带上该模型来源路由，定位降级模型由哪条接口下发。
            "route="+model.optString(ROUTE_KEY).ifEmpty { "unknown" }+" lvl="+model.optString(LEVEL_KEY).ifEmpty { "?" }+" video_list="+compactVideoList(videoList),
            bvc2Stream)
    }
    /** 从一条 video_list 记录解析出可下载的直链与 CENC key（兼容档与 bytevc2 共用）。 */
    private fun makeStream(row: Triple<Int,String,JSONObject>, info: JSONObject, maxQuality: Int): StreamInfo {
        val item=row.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(row.first>0) "${row.first}P"+(if(row.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality,qNum=row.first)
    }
    /** 诊断用：每条清晰度给 `key:codec:高度:main_url形态/backup形态` 的紧凑摘要，条目间用分号，不再整段截断。 */
    private fun compactVideoList(videoList: JSONObject): String = videoList.keys().asSequence().mapNotNull { k ->
        val o=videoList.optJSONObject(k) ?: return@mapNotNull null
        val codec=o.str("codec_type").ifEmpty { "?" }
        val height=o.str("vheight").ifEmpty { o.str("height").ifEmpty { "?" } }
        "$k:$codec:$height:"+urlShape(o.str("main_url"))+"/"+urlShape(o.str("backup_url_1"))
    }.joinToString(";")
    /** 地址归类：http=直接可播 URL；enc=base64 加密 blob（非 http 开头）；empty=缺字段。 */
    private fun urlShape(value: String) = when {
        value.isEmpty() -> "empty"
        value.startsWith("http") -> "http"
        else -> "enc"
    }
    /** 预热一整窗剧集的播放模型：命中缓存的不再请求，批量最多 15 集（站方上限 20）。
     *  失败不上抛——播放时按需取址仍是兜底，预取只负责把后续切集变成 0 次网络。 */
    fun warm(episodes: List<String>): WarmReport {
        val ids=episodes.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
        if(ids.isEmpty()) return WarmReport(0,0,0,null)
        val now=clock()
        val cached=ids.count { modelCache.get(it,now)!=null }
        // 冷却期内的预取纯属白送请求（必然被拒），直接当作「这窗已缓存」返回，等用户真点播再说。
        if(cooldownRemainingMs()>0) return WarmReport(ids.size,cached,0,null)
        val missing=ids.filter { modelCache.get(it,now)==null }.take(BATCH_LIMIT)
        if(missing.isEmpty()) return WarmReport(ids.size,cached,0,null)
        // 批量连续空响应退避（2026-11-21）：空批量本身就是风控收紧信号（实测单集取址正常时批量也在空），
        // 继续每窗空撞等于给风控窗口续命。连续 3 轮零填充就静默跳过批量 10 分钟，成功一次即复位。
        if(now<batchBackoffUntil.get()) return WarmReport(ids.size,cached,0,null)
        val batch=runCatching { batchModels(missing) }.getOrElse { Batch(0,null) }
        if(batch.filled>0) {
            batchEmptyStreak.set(0)
        } else {
            val streak=batchEmptyStreak.incrementAndGet()
            if(streak>=BATCH_EMPTY_LIMIT) {
                batchBackoffUntil.set(now+BATCH_BACKOFF_MS)
                batchEmptyStreak.set(0)
                probeLog?.invoke("批量预热连续 $streak 轮空响应，退避 ${BATCH_BACKOFF_MS/60000} 分钟")
            }
        }
        return WarmReport(ids.size,cached+batch.filled,batch.filled,batch.route)
    }
    private data class Batch(val filled: Int, val route: String?)
    /** 一次签名请求问一整窗；空体就是风控信号，直接停手（换设备再问只会加剧），交给按需取址。 */
    private fun batchModels(ids: List<String>): Batch =
        sweepBatch(batchPayload(ids),ids,playbackSession.profile(http))
    private fun sweepBatch(payload: ByteArray, ids: List<String>, identity: PlaybackDeviceSession.Profile): Batch {
        for(path in BATCH_ROUTES) {
            val body=try {
                RequestScope.execute(http.newCall(playerRequest(path,payload,identity))) { response ->
                    if(!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                    response.body?.string().orEmpty()
                }
            } catch(problem: java.io.InterruptedIOException) {
                throw problem
            } catch(_: IOException) {
                continue
            } catch(_: RuntimeException) {
                continue
            }
            if(body.isBlank()) {
                // 批量只是预取优化：空体按「这条路由没批量能力」处理，静默跳过；
                // 绝不 trip 全局冷却——否则一次预取失败会让后面正常起播全被拦下（2026-11-20 实测踩过）。
                probeLog?.invoke("batch $path 空体，跳过该路由")
                continue
            }
            val root=runCatching { JSONObject(body) }.getOrNull() ?: continue
            if(root.optInt("Code",root.optInt("code",-1))!=0) continue
            val data=root.optJSONObject("data") ?: continue
            val models=modelsOf(data).filterKeys { it in ids }
            if(models.isEmpty()) continue
            val now=clock()
            // 批量取到的模型也会进缓存、后续被 stream 消费：路由标记在这里一起注入，诊断才能覆盖批量预热路径。
            models.forEach { (id,model) -> modelCache.put(id,model.put(ROUTE_KEY,path),now) }
            return Batch(models.size,path)
        }
        return Batch(0,null)
    }
    /** 批量响应的模型可能嵌在 `data.video_model_data[<id>]`，也可能直接以 id 为键。 */
    private fun modelsOf(data: JSONObject): Map<String,JSONObject> {
        val rows=data.optJSONObject("video_model_data") ?: data
        val models=linkedMapOf<String,JSONObject>()
        for(key in rows.keys()) {
            val entry=rows.optJSONObject(key) ?: continue
            val model=modelOf(entry.opt("video_model")) ?: entry.takeIf { it.has("fallback_api") }
            if(model!=null) models[key]=model
        }
        return models
    }
    private fun batchPayload(ids: List<String>): ByteArray = batchPayload(ids,"1004")
    /** [mapKey] 可注入：官方 APK 逆向（skydeadpeng 契约文档 2026-11-11）证实 key 是内容类型短整数
     *  （1=短剧/7=ShortPlay/1003=Album/1004=漫剧），我们批量一直发 "1004" 而普通短剧应为 "1"——
     *  11-07 批量能成功可能是服务端当时容错，风控收紧后大概率按 key 校验。 */
    private fun batchPayload(ids: List<String>, mapKey: String): ByteArray {
        // 对齐官方 7.4.5.32 客户端形态（上游 573d75a 反编译）：完整 biz_param + mixed_video_id_map，
        // v1 路径不带 dr_scene（`:dr_scene` 变体才用，对匿名请求返 200+0）。
        val biz=JSONObject()
            .put("detail_page_version",0)
            // 真果鉴实测：biz_param.device_level 默认 3（编码不兼容才提到 4）；1 是我们早期填的值，
            // 与参考客户端不一致，属于风控「参数不对齐」的可疑项。
            .put("device_level",3)
            .put("disable_digg_stat",false)
            .put("disable_video_relate_book",false)
            .put("need_all_video_definition",true)
            .put("need_mp4_align",false)
            .put("use_os_player",false)
            .put("use_server_dns",false)
            .put("video_platform",1024)
        return JSONObject()
            .put("biz_param",biz)
            .put("mixed_video_id_map",JSONObject().put(mapKey,JSONArray(ids)))
            .toString()
            .toByteArray()
    }
    /** 批量路由矩阵探针的单次结果。 */
    data class BatchProbeResult(val httpCode: Int, val bodyLen: Int, val code: Int, val message: String, val filled: Int) {
        override fun toString()="http=$httpCode bodyLen=$bodyLen code=$code msg=${message.take(60)} filled=$filled"
    }
    /** 批量路由矩阵探针（2026-11-21 全网调研+官方 APK 逆向产物，仅供 CLI --batch-probe，生产链路不调用）。
     *  发一次 POST /novel/player/multi_video_model/v1：[mapKey] 注入内容类型、[signed]=false 时按
     *  10-28 配方（注册身份 + 无签名，query 只带设备字段与 _rticket）。单发、不重试、空体原样报告；
     *  冷却期内拒绝执行（防探针把风控窗口续命），--force 由调用方用 [force] 越过。 */
    internal fun batchProbe(ids: List<String>, mapKey: String, signed: Boolean, force: Boolean=false): BatchProbeResult {
        val remainingMs=cooldownRemainingMs()
        if(remainingMs>0 && !force) throw PlaybackThrottledException(remainingMs)
        val identity=playbackSession.profile(http)
        val body=gzipBytes(batchPayload(ids,mapKey))
        val path="/novel/player/multi_video_model/v1"
        val request=if(signed) playerRequest(path,body,identity) else run {
            val query=(identity.values+mapOf("_rticket" to System.currentTimeMillis().toString()))
                .entries.joinToString("&") { (k,v)-> java.net.URLEncoder.encode(k,"UTF-8")+"="+java.net.URLEncoder.encode(v,"UTF-8") }
            Request.Builder().url("https://api5-normal-sinfonlinea.fqnovel.com$path?$query").apply {
                if(identity.cookie.isNotBlank()) header("Cookie",identity.cookie)
                header("User-Agent",MobilePlayback.UA)
                header("Accept","application/json")
                header("Content-Encoding","gzip")
                post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        }
        val text=RequestScope.execute(http.newCall(request)) { response ->
            val code=response.code; val content=response.body?.string().orEmpty(); code to content
        }
        val (httpCode,content)=text
        if(content.isBlank()) return BatchProbeResult(httpCode,0,-1,"空响应",-1)
        val root=runCatching { JSONObject(content) }.getOrNull()
            ?: return BatchProbeResult(httpCode,content.length,-1,"非JSON: ${content.take(80)}",-1)
        val code=root.optInt("Code",root.optInt("code",-1))
        val msg=root.optString("Message",root.optString("msg",""))
        val data=root.optJSONObject("data")
        val filled=if(data==null) 0 else modelsOf(data).size
        return BatchProbeResult(httpCode,content.length,code,msg,filled)
    }
    /** 2026-11-20 对齐上游 0.1.33：GET/POST 播放 profile 都用 7.3.9.32 / 73932，真签名按最终 query 重生成。 */
    private fun playbackValues(identity: PlaybackDeviceSession.Profile) = identity.values + mapOf(
        "version_code" to "73932",
        "manifest_version_code" to "73932",
        "update_version_code" to "73932",
        "pv_player" to "73932",
        "version_name" to "7.3.9.32",
    )
    private fun signedBuilder(signed: Signer.Request, identity: PlaybackDeviceSession.Profile): Request.Builder =
        Request.Builder().url(signed.url).apply {
            signed.headers.forEach { (key,value) -> header(key,value) }
            if(identity.cookie.isNotBlank()) header("Cookie",identity.cookie)
        }
    private fun signedRealBuilder(signed: Signer.Request, body: ByteArray?, identity: PlaybackDeviceSession.Profile): Request.Builder =
        Request.Builder().url(signed.url).apply {
            realSignedHeaders(signed.url,signed.headers,body).forEach { (key,value) -> header(key,value) }
            if(identity.cookie.isNotBlank()) header("Cookie",identity.cookie)
        }
    /** 批量取址：v1 路径 + gzip body；签名用真 argus/ladon（v5 占位头已被官方静默丢弃）。 */
    private fun playerRequest(path: String,payload: ByteArray,identity: PlaybackDeviceSession.Profile): Request {
        val body=gzipBytes(payload)
        val values=linkedMapOf(
            "aid" to "8662","app_name" to "novelread",
            "version_code" to "74532","version_name" to "7.4.5.32",
            "channel" to "vivo_8662_64","device_platform" to "android",
            "device_type" to "PGT-AN10","os_version" to "12","update_version_code" to "74532",
            "iid" to identity.values.getValue("iid"),
            "cdid" to identity.values.getValue("cdid"),
            "device_id" to identity.values.getValue("device_id"),
        )
        val signed=signer.signPlaybackV5("https://api5-normal-sinfonlinea.fqnovel.com$path",values,body)
        return signedRealBuilder(signed,body,identity)
            .header("User-Agent",MobilePlayback.UA)
            .header("Accept","application/json")
            .header("Content-Encoding","gzip")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
    /** 官方 App 现行的播放模型接口：`GET /reading/bookapi/video_tab/multi_video_model/v1/`。
     *  2026-11-02 起旧的 `/novel/player/` 接口族对所有请求返回 HTTP 200 + 0 字节（多域名、多 UA、
     *  签名与匿名身份均如此），而这条 GET 接口同刻正常返回 `code 0` 与完整 `video_list`。
     *  参数取自官方 `GetVideoModelRequest` 的 snake_case 字段，缺任一字段会回 `100103`。
     *  失败一律返回 null，由调用方回退旧接口，不改变旧的空响应与身份轮换语义。 */
    internal fun tabVideoModel(id: String, identity: PlaybackDeviceSession.Profile, level: Int = 1, preferAlt: Boolean = false): JSONObject? {
        val extra=linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to level.toString(),
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
        val hosts=listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",
            "https://reading.snssdk.com/reading/bookapi/video_tab/multi_video_model/v1/",
        )
        // preferAlt：重试诊断用，优先探测备用主机（两个主机可能落在不同后端集群）。
        val ordered=if(preferAlt) hosts.reversed() else hosts
        for(base in ordered) {
            // GET video_tab 是上游 0.1.33 的首选取址：v5 占位头换成 PlayerSigner 真 argus/ladon。
            val signed=signer.signPlaybackV5(base,playbackValues(identity)+extra,null)
            val request=signedRealBuilder(signed,null,identity)
                .header("User-Agent",PLAYBACK_UA)
                .header("Accept","application/json")
                .get().build()
            val statusBox = IntArray(1)
            val body=try {
                RequestScope.execute(http.newCall(request)) { response ->
                    statusBox[0] = response.code
                    if(response.code==429) throw PlaybackRateLimitedException(retryAfterMs(response))
                    if(!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                    response.body?.string().orEmpty()
                }
            } catch (limited: PlaybackRateLimitedException) {
                throw limited
            } catch (problem: java.io.InterruptedIOException) {
                throw problem
            } catch (problem: IOException) {
                continue
            } catch (problem: RuntimeException) {
                continue
            }
            val blank = body.isBlank()
            val status = statusBox[0]
            val root=runCatching { JSONObject(body) }.getOrNull()
            if(root==null) {
                // 200 + 0 字节是本轮风控的固定形态：别再打备用主机，直接交给上层触发冷却。
                if(blank) {
                    probeLog?.invoke("tab $base 空响应 HTTP=$status len=0")
                    throw EmptyPlaybackResponseException()
                }
                probeLog?.invoke("tab $base 非JSON body=${body.take(120)}")
                continue
            }
            if(root.optInt("code",root.optInt("Code",-1))!=0) {
                probeLog?.invoke("tab $base code=${root.optInt("code",-1)} body=${body.take(160)}")
                continue
            }
            val data=root.optJSONObject("data") ?: continue
            val rows=data.optJSONObject("video_model_data") ?: data
            val row=rows.optJSONObject(id) ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull() ?: continue
            val model=modelOf(row.opt("video_model")) ?: continue
            return model.put(ROUTE_KEY,"video_tab|"+base.removePrefix("https://").substringBefore('/'))
        }
        return null
    }
    /** 播放模型：官方现用 `/novel/player/video_model/v1/`（旧参数格式）。
     *  2026-11-20 下午 `multi_video_model/player/v1` 对所有身份/版本/参数组合一律返 200+0，
     *  取址改回同族旧端点后恢复正常（实测 1080P、ftyp 校验通过）；v5 签名流程完全没变。
     *  空响应仍然是站方行为风控信号：立即停手并进入全局冷却，不扫旧路由、不换匿名设备。 */
    internal fun videoModel(id: String, level: Int = 1, preferAlt: Boolean = false, quiet: Boolean = false): JSONObject {
        modelCache.get(id,clock())?.let { return it }
        guardPlaybackCooldown()
        val model=fetchVideoModel(id,level,preferAlt,quiet).put(LEVEL_KEY,level)
        modelCache.put(id,model,clock())
        return model
    }
    /** 上次成功取址走的路由（模型里的 `__route` 标记）与命中时刻。
     *  主路空响应会触发全局冷却，所以一旦靠 video_tab 兜底救回来，下一次优先走它，省掉一轮必然空响应的请求；
     *  但记忆有 [GOOD_ROUTE_TTL_MS] 的有效期，到期重新从主路开始，主路恢复后自动切回。 */
    @Volatile private var goodRoute: Pair<String,Long>?=null
    private fun preferredRouteIsTab(): Boolean {
        val memory=goodRoute ?: return false
        if(clock()-memory.second>GOOD_ROUTE_TTL_MS) { goodRoute=null; return false }
        return memory.first.startsWith("video_tab")
    }
    private fun rememberRoute(model: JSONObject) {
        val route=model.optString(ROUTE_KEY)
        if(route.isNotEmpty()) goodRoute=route to clock()
    }
    private fun fetchVideoModel(id: String, level: Int = 1, preferAlt: Boolean = false, quiet: Boolean = false): JSONObject {
        // 2026-11-23 全网复核（openhongguo README 与真果鉴TV 0.8.27 一致口径）：
        // 「一台匿名设备基本只给一次播放模型」，之后同设备只会拿到 200+0。
        // 因此空响应的正确处置是「换一台匿名设备重试」，而不是在同一身份上等——
        // 旧实现每次都抬全局冷却（20/60/120/300s），用户看片中途被锁全是自己造成的。
        // 只有真正的 HTTP 429 才需要冷却，且按 Retry-After。
        var resets = 0
        while (true) {
            val identity = playbackSession.profile(http)
            try {
                // 2026-11-23：v5 `player/v1` 是唯一活路。此前把 `video_tab` 放前面，
                // 它空响应后立刻换身份/抬冷却，v5 永远轮不到——顺序错了等于一直在打死路。
                // 同身份先 device_level=4，空响应升 5 再试一次（同行 0.27.4 实测：4 空但 5 能出），
                // 5 仍空才按「本台身份额度用尽」换身份。预取只打一发，不参与升档。
                val model = if (quiet) MobilePlayback.model(id, identity, http, signer, clock(), 4)
                else try {
                    MobilePlayback.model(id, identity, http, signer, clock(), 4)
                } catch (blank: EmptyPlaybackResponseException) {
                    probeLog?.invoke("v5 device_level=4 空响应 → 升 5 同身份重试")
                    MobilePlayback.model(id, identity, http, signer, clock(), 5)
                }
                if (model != null) {
                    clearPlaybackCooldown()
                    val routed = model.put(ROUTE_KEY, "v5|player")
                    rememberRoute(routed)
                    probeLog?.invoke("v5 命中 id=$id")
                    return routed
                }
                // v5 给了非空失败（错误码/解析不到模型）才退回老 tab；它的空响应不再放大成全局冷却。
                val tab = try {
                    tabVideoModel(id, identity, level, preferAlt)
                } catch (blank: EmptyPlaybackResponseException) {
                    probeLog?.invoke("tab 兜底空响应，忽略")
                    null
                }
                if (tab != null) {
                    clearPlaybackCooldown()
                    rememberRoute(tab)
                    probeLog?.invoke("tab 命中 id=$id route=${tab.optString(ROUTE_KEY)}")
                    return tab
                }
                // 两条路由都没给模型：等同本台设备额度用尽。
                throw EmptyPlaybackResponseException()
            } catch (blank: EmptyPlaybackResponseException) {
                // 预取只是优化，不能替用户消耗身份额度，也不能因此锁死正片。
                if (quiet) throw IOException("空响应（预取静默跳过）")
                if (resets >= MAX_IDENTITY_RESETS) throw tripPlaybackCooldown()
                resets++
                probeLog?.invoke("空响应 → 换匿名身份重试（第 $resets 次）")
                playbackSession.renew(http)
            } catch (limited: PlaybackRateLimitedException) {
                if (quiet) throw IOException("限流（预取静默跳过）")
                limitPlaybackCooldown(limited.retryAfterMs)
                throw tripPlaybackCooldown()
            }
        }
    }
    /** 新路由的播放模型在 `data.video_model`，旧路由在 `data[<vid>].video_model`；两种都可能被序列化成字符串。 */
    private fun modelOf(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    companion object {
        /** 2026-11-20 官方只认真签名：v5 占位三头（x-helios/x-medusa 等）与真头同现必被静默丢弃（200+0）。
         *  对齐上游 0.1.33（PlayerSigner = fqapp argus/ladon 移植）：只保留 UA/Cookie 一类普通头，
         *  x-* 签名头按最终 query 用 PlayerSigner 重新生成；x-khronos 必须与 URL 的 _rticket 同源。 */
        internal fun realSignedHeaders(url: String,headers: Map<String,String>,body: ByteArray?): Map<String,String> {
            val placeholder=setOf("x-gorgon","x-helios","x-medusa","x-argus","x-ladon","x-khronos","x-ss-stub","x-ss-req-ticket","x-medias","x-tt-dt")
            val kept=headers.filterKeys { it.lowercase() !in placeholder }
            val query=url.substringAfter('?',"")
            val ticket=query.split('&').firstOrNull { it.startsWith("_rticket=") }?.substringAfter('=')?.toLongOrNull()
            val real=if(ticket!=null) PlayerSigner.headers(query,body,ts=ticket/1000,ticket=ticket) else PlayerSigner.headers(query,body)
            return kept+real
        }
        /** 断供排查用的一次性探针：为 null 时不产生任何开销，也不影响生产日志。
         *  设置后逐跳打印播放取址的响应摘要（哪条路由空、哪条给了错误码）。 */
        internal var probeLog: ((String) -> Unit)? = null
        // 网站搜索混合两类内容：video_data.genre 205 是漫剧，203 是短剧
        // （2026-10-13 用站点自带的漫剧热播榜与短剧分类列表逐条核对确认）。
        private const val COMIC_WEB_GENRE="205"
        private const val SHORT_WEB_GENRE="203"
        // 播放链路指纹对齐新签名算法内嵌的 7.3.5.32 客户端版本。
        private const val PLAYBACK_UA="com.phoenix.read/73532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
        // —— 官方取址行为风控冷却（2026-11-20）——
        // 空响应 = HTTP 200 + 0 字节，与签名/路径/身份/出口 IP 全部无关；实测单轮取址会打出 16 个请求，
        // 触发后 3 分钟→10 分钟都不恢复，冷却时间随触发次数变长。所以命中一次就让全部取址停下来：
        // 冷却期内 stream()/videoModel() 直接抛 PlaybackThrottledException，一个请求都不发。
        // 首次 20 秒（承认「偶发一次抖动」的可能，给一次 20 秒后的机会），连续命中按 60→120→300 秒递增。
        private val COOLDOWN_STEPS=listOf(20_000L, 60_000L, 120_000L, 300_000L)
        /** 空响应换身份的次数上限：与 openhongguo 的 DeviceResets 默认值一致（一次调用最多换 2 台）。 */
        private const val MAX_IDENTITY_RESETS=2
        /** 解析 Retry-After（秒）；缺失或非数字返回 0，由调用方给默认值。 */
        internal fun retryAfterMs(response: okhttp3.Response): Long {
            val raw=response.header("Retry-After")?.trim().orEmpty()
            val seconds=raw.toLongOrNull() ?: return 0L
            return (seconds*1000).coerceIn(1_000L, 600_000L)
        }
        /** 仅 HTTP 429 走这里：按 Retry-After 冷却，不参与空响应的等级递增。 */
        private fun limitPlaybackCooldown(retryAfter: Long) {
            synchronized(cooldownLock) {
                val wait=if(retryAfter>0) retryAfter else 30_000L
                val until=System.currentTimeMillis()+wait
                if(until>cooldownUntil.get()) cooldownUntil.set(until)
                probeLog?.invoke("HTTP 429 限流，冷却 ${wait}ms")
            }
        }
        // 成功路由记忆的有效期：到期重新从主路试一次，避免主路恢复后长期停在兜底路由上。
        private const val GOOD_ROUTE_TTL_MS=600_000L
        private val cooldownUntil=java.util.concurrent.atomic.AtomicLong(0L)
        private val cooldownStreak=java.util.concurrent.atomic.AtomicInteger(0)
        // —— 批量预热空响应退避（2026-11-21）——
        // 批量路由全空 = 风控收紧信号（或路由已废弃）；连续 3 轮零填充就跳过批量 10 分钟，
        // 别让「每次换集 1 个注定空的请求」持续给取址密度添柴。批量成功一次即复位。
        private const val BATCH_EMPTY_LIMIT=3
        private const val BATCH_BACKOFF_MS=10*60_000L
        private val batchEmptyStreak=java.util.concurrent.atomic.AtomicInteger(0)
        private val batchBackoffUntil=java.util.concurrent.atomic.AtomicLong(0L)
        internal fun cooldownRemainingMs(now: Long=System.currentTimeMillis()): Long =
            (cooldownUntil.get()-now).coerceAtLeast(0L)
        internal fun guardPlaybackCooldown() {
            val remaining=cooldownRemainingMs()
            if(remaining>0) throw PlaybackThrottledException(remaining)
        }
        private fun tripPlaybackCooldown(): PlaybackThrottledException {
            // 同一轮并发取址（如自由刷剧进入时起播+两路预取同时被拒）只记一次冷却事件：
            // 此前每个失败各抬一级，20s 一次进 feed 就连跳成 120s。加锁串行判定，
            // 已在冷却中的失败按剩余时间拒绝、不再叠加等级（2026-11-23）。
            synchronized(cooldownLock) {
                val now=System.currentTimeMillis()
                val remaining=cooldownRemainingMs(now)
                if(remaining>0) return PlaybackThrottledException(remaining)
                val streak=cooldownStreak.incrementAndGet()
                val wait=COOLDOWN_STEPS[(streak-1).coerceIn(0,COOLDOWN_STEPS.lastIndex)]
                cooldownUntil.set(now+wait)
                probeLog?.invoke("空响应触发冷却 ${wait}ms（连续第 $streak 次）")
                return PlaybackThrottledException(wait)
            }
        }
        private val cooldownLock=Object()
        private fun clearPlaybackCooldown() {
            cooldownStreak.set(0); cooldownUntil.set(0L)
        }
        /** 单测用：冷却状态是进程级共享的，用例之间必须清干净，否则一个用例的「空响应」会毒到下一个。 */
        internal fun resetPlaybackCooldown() = clearPlaybackCooldown()
        // 一次注册问一整窗：站方硬上限 20 集，取 15 留出余量。
        private const val BATCH_LIMIT=15
        // 诊断专用：随模型进缓存的路由标记，streamFromModel 抛不兼容异常时写进 diagnostic。
        private const val ROUTE_KEY="__route"
        // 降级模型按请求间歇出现（实测约 1/3），一次取址失败后最多再赌两轮正常模型；仍降级才报不兼容。
        private const val PLAYBACK_RETRY_ROUNDS=3
        private const val RETRY_BACKOFF_MS=400L
        // 重试轮次轮换参数：诊断降级窗口下 level=3 与备用主机是否下发电视可解码的转码集
        // （1=现行为，3=官方档位；preferAlt=优先 reading.snssdk.com 备用主机）。
        private val RETRY_PLANS=listOf(1 to true, 3 to false)
        private const val LEVEL_KEY="__level"
        // 2026-11-20 下午实测：`multi_video_model/v1/` 用精简 biz_param + video_platform:1024 仍正常下发，
        // 而 `.../player/v1` 及兄弟路径一律 200+0。批量只打这一条，避免空体把主路带进冷却。
        // 2026-11-20 对齐官方 7.4.5.32：v1 路径（无尾斜杠、无 dr_scene）+ mixed_video_id_map + 74532 版本参数。
        private val BATCH_ROUTES=listOf("/novel/player/multi_video_model/v1")
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}

/** 播放模型的小缓存：一窗剧集一次批量取址，切集直接命中，不再向站方重复要地址。 */
class PlaybackModelCache(private val limit: Int = 120, private val ttlMillis: Long = 2*60*60*1000L) {
    private class Entry(val at: Long, val model: JSONObject)
    // accessOrder=true：get/put 命中都把条目移到末尾，淘汰时删最久未用的（LRU），
    // 而不是最早插入的——反复回看的前几集不该被后面批量插入的集挤掉。
    private val entries=object : LinkedHashMap<String,Entry>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: Map.Entry<String,Entry>?) = size > limit
    }
    @Synchronized fun get(id: String, now: Long): JSONObject? {
        val entry=entries[id] ?: return null
        if(now-entry.at>ttlMillis) { entries.remove(id); return null }
        return entry.model
    }
    @Synchronized fun put(id: String, model: JSONObject, now: Long) {
        entries[id]=Entry(now,model)
    }
    @Synchronized fun evict(id: String) {
        entries.remove(id)
    }
}

private val SeasonPattern=Regex("第([0-9零〇一二三四五六七八九十百千]+)(?:季|部)")
fun seasonNumber(title: String): Int {
    val matched=SeasonPattern.find(title)?.groupValues?.get(1) ?: return Int.MAX_VALUE
    return chineseNumber(matched)
}
private fun chineseNumber(text: String): Int {
    text.toIntOrNull()?.let { return it }
    val digits=mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
    val ten=text.indexOf('十')
    if(ten<0) return text.singleOrNull()?.let { digits[it] } ?: Int.MAX_VALUE
    val high=if(ten==0) 1 else digits[text.first()] ?: 1
    val low=if(ten==text.lastIndex) 0 else digits[text.last()] ?: 0
    return high*10+low
}

/** gzip 请求体：Java 的 GZIPOutputStream 头部 MTIME/OS 全为 0，与官方客户端一致（服务端会比对 stub）。 */
private fun gzipBytes(source: ByteArray): ByteArray =
    java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { it.write(source) }
    }.toByteArray()
