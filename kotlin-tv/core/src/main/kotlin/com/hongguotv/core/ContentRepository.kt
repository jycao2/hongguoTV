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
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
/** 详情页附带的社交数据，缺失时各字段保持默认空值，调用方按需隐藏。 */
data class SeriesSocial(val rating: Double = 0.0, val ratingCount: String = "", val favoriteText: String = "", val heatText: String = "") {
    val isEmpty get() = rating<=0.0 && favoriteText.isBlank() && heatText.isBlank()
}
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
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 平台对该集只产出了 ByteVC2 等电视无法解码的编码，重试与换清晰度都不会成功。 */
class UnplayableVideoException(message: String): IOException(message)
/** 播放信息接口返回了 HTTP 200 空体；已按有界退避重读仍未成功。 */
class EmptyPlaybackResponseException: IOException("播放接口暂时返回空响应，请稍后重试")

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

open class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).cookieJar(MemoryCookies()).build(), private val clock: () -> Long = System::currentTimeMillis, private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val genreCache=mutableMapOf<ContentType,Pair<Long,List<Genre>>>()
    private val suggestCache=linkedMapOf<String,Pair<Long,List<String>>>()
    // 签名搜索接口被平台关停时会稳定返回空体，短期内不再重复尝试，避免每次搜索都白等两次往返
    @Volatile private var appSearchRetryAt=0L
    private fun categoryPath(type: ContentType)=when(type) {
        ContentType.COMIC -> "comic-drama"
        ContentType.SHORT -> "real-drama"
        ContentType.AI -> "ai-drama"
    }
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
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
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
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
        val cached=synchronized(seasonFills) { seasonFills[cacheKey]?.takeIf { clock()-it.at<86_400_000 }?.items }
        if(cached!=null) return cached
        val knownIds=known.map { it.id }.toSet()
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
        synchronized(seasonFills) {
            seasonFills[cacheKey]=SeasonFill(clock(),final)
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
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
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
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val html=text("$site/detail?series_id=$id")
        val loader=extractRouter(html).getJSONObject("loaderData")
        val o=loader.optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val series=card(o).copy(id=id)
        val related=related(html,series)
        val social=social(loader.optJSONObject("detail_page")?.optJSONObject("seriesSocialInfo"))
        return Detail(series,episodes,related,sameName(series,related),social)
    }
    // 评分与收藏数只在详情页 SSR 里下发，卡片数据源没有这些字段，因此只读取不追加请求。
    private fun social(o: JSONObject?): SeriesSocial {
        if(o==null) return SeriesSocial()
        val favorite=compactCount(o.str("series_favorite_count"))
        return SeriesSocial(
            rating=o.optDouble("rating",0.0).takeIf { it>0 } ?: 0.0,
            ratingCount=compactCount(o.str("rating_count")),
            favoriteText=if(favorite.isBlank()) "" else "${favorite}收藏",
            heatText=o.optJSONObject("hot_score_data")?.str("text").orEmpty()
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
    /** Fetch a direct MP4 URL for one episode.
     *  The player API only answers registered anonymous devices: an unregistered `device_id`/`install_id`
     *  pair gets `HTTP 200` with a zero-byte body. So the identity from [PlaybackDeviceSession] is written
     *  into the query together with `_rticket`, and the response's `Set-Cookie` values ride along as a
     *  `Cookie` header. No signature headers are involved in this call. */
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val data=JSONObject(playbackResponse(id,payload))
        val code=data.optInt("Code",data.optInt("code",0))
        if(code!=0) throw IOException("播放接口暂不可用（错误码 $code）")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw UnplayableVideoException("本集片源暂不兼容，请播放下一集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    private fun playerRequest(id: String,payload: ByteArray,identity: PlaybackDeviceSession.Profile): Request {
        val query=Signer.encode(identity.values+("_rticket" to clock().toString()))
        return Request.Builder()
            .url("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?$query")
            .header("User-Agent",PLAYBACK_UA)
            .header("Accept","application/json; charset=utf-8,application/x-protobuf")
            .apply { if(identity.cookie.isNotBlank()) header("Cookie",identity.cookie) }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
    /** 播放信息接口会间歇性返回 HTTP 200 空体（同身份、同参数连发十几次全成功，说明是平台侧抖动而非参数问题）。
     *  先按 0/700/1500/3000 毫秒退避重试，仍为空则重新注册一台匿名设备再走一轮，
     *  覆盖「进程内的注册身份在长时间播放后失效」的情况。 */
    private fun playbackResponse(id: String,payload: ByteArray): String {
        var identity=playbackSession.profile(http)
        repeat(2) { round ->
            var attempt=0
            while(true) {
                val body=RequestScope.execute(http.newCall(playerRequest(id,payload,identity))) { response ->
                    if(!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                    response.body?.string().orEmpty()
                }
                if(body.isNotBlank()) return body
                val delay=listOf(700L,1500L,3000L).getOrNull(attempt) ?: break
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
                Thread.sleep(delay)
                attempt++
            }
            if(round==0) identity=playbackSession.renew(http)
        }
        throw EmptyPlaybackResponseException()
    }
    companion object {
        // 网站搜索混合两类内容：video_data.genre 205 是漫剧，203 是短剧
        // （2026-10-13 用站点自带的漫剧热播榜与短剧分类列表逐条核对确认）。
        private const val COMIC_WEB_GENRE="205"
        private const val SHORT_WEB_GENRE="203"
        // 播放接口认的客户端版本与内容接口不同，沿用抓包样本里 7.1.5.32 + tt-ok 的 UA。
        private const val PLAYBACK_UA="com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
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
