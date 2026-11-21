package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** --feed --did/--iid 用的固定身份。 */
private fun DiscoveryIdentityStyle(did:String,iid:String):Map<String,String> =
    VendorConstants.device.toMutableMap().apply { put("device_id",did); put("iid",iid) }

fun main(args: Array<String>) {
    // --dump-spade：绕过 bytevc2 过滤，dump 播放模型里所有分档的 spade_a / codec / url。
    // --ladder <episodeId>：打印清晰度阶梯缓存输出，验证五档一次下发/缓存命中。
    if(args.contains("--dump-spade") || args.contains("--ladder")) { mainDump(args); return }
    // --batch-probe：批量取址矩阵探针——key(1/1004) × 签名(有/无) 四组合各发一次，
    // 定位批量空响应的真实原因（2026-11-21 调研：key 应为内容类型、路由配方可能要无签名）。
    if(args.contains("--batch-probe")) { mainBatchProbe(args); return }
    // --diagnose：直接打官方取址接口，原样打印 HTTP 码/响应头/响应体，用于定位返空到底空在哪一层。
    if(args.contains("--diagnose")) { mainDiagnose(args); return }
    if(args.contains("--verbose")) ContentRepository.probeLog={ println("probe: $it") }
    // --feed：猜你喜欢首屏对照实验——同一身份连打两次 + 换全新注册身份，验证服务端
    // 是否真的按身份个性化排序（若两次全同，说明冷启动画像给的是固定热榜）。
    if(args.contains("--feed")) {
        val http=okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        fun dump(label:String, device:()->Map<String,String>, login:()->String? = {null}) {
            val repo=DiscoveryRepository(http, deviceProvider=device, loginCookie=login)
            println("== $label device_id=${device()["device_id"]} 登录=${if(login().isNullOrBlank()) "否" else "是"}")
            val page=try { repo.nextPage() } catch(e:Exception) { println("   失败: ${e.message}"); return }
            println("   route=${repo.lastRoute} note=${repo.lastNote} 条数=${page.items.size}")
            page.items.take(8).forEachIndexed { i,s -> println("   $i ${s.id} ${s.title}") }
        }
        val cookie=System.getenv("HONGGUOTV_COOKIE")
        fun argValue(name:String):String? { val i=args.indexOf(name); return if(i>=0) args.getOrNull(i+1) else null }
        val didArg=argValue("--did")
        val iidArg=argValue("--iid")
        if(didArg!=null && iidArg!=null) {
            // 指定身份：复现真实客户端的（注册身份 + 登录态）组合
            val device=DiscoveryIdentityStyle(didArg,iidArg)
            dump("指定身份 匿名", { device })
            if(!cookie.isNullOrBlank()) dump("指定身份+登录Cookie", { device }, { cookie })
        } else {
            val a=PlaybackDeviceSession().profile(http)
            dump("身份A+注册Cookie 第1次", { a.values }, { a.cookie })
            dump("身份A+注册Cookie 第2次", { a.values }, { a.cookie })
            val b=PlaybackDeviceSession().profile(http)
            dump("身份B+注册Cookie", { b.values }, { b.cookie })
            if(!cookie.isNullOrBlank()) dump("身份A+注册+登录Cookie", { a.values }, { a.cookie+"; "+cookie })
        }
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        return
    }
    val repository=ContentRepository()
    // --plan <id>：系列剧（plan/v1 show_type=547）实测——多季剧应返回整季列表。
    if(args.contains("--plan")) {
        val funArgValue:(String)->String? = { name -> val i=args.indexOf(name); if(i>=0) args.getOrNull(i+1) else null }
        val id=funArgValue("--plan") ?: "7706512650816982040"
        val list=repository.planSeries(id)
        println("== plan/v1 系列剧 book_id=$id 共 ${list.size} 条")
        list.forEachIndexed { i,s -> println("   $i ${s.id}  ${s.title}  ${s.badge}  ${s.cover.take(60)}") }
        if(list.isEmpty()) println("   （空：接口没给 547 行或被风控/签名拒绝，可加 --verbose 看原始响应）")
        return
    }
    // --related <id>：详情页相关推荐探针——plan/v1（from=select_panel_recommend）全部 cell 概览
    // + 实际采用结果。验证匿名身份下服务端回 160（真·相关）还是 401（通用「更多好剧」）。
    if(args.contains("--related")) {
        val id=args.getOrNull(args.indexOf("--related")+1)?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: "7706512650816982040"
        println("== plan/v1 相关推荐 book_id=$id · 全部 cell")
        val cells=repository.planRelatedProbe(id)
        if(cells.isEmpty()) println("   （无 cell：接口被拒/空响应，可加 --verbose 看原始响应）")
        cells.forEachIndexed { i,(st,name,cnt) -> println("   cell$i show_type=$st  「$name」  条目=$cnt") }
        val chosen=repository.planRelated(id)
        println("== 采用结果（只认 show_type=160）共 ${chosen.size} 条")
        chosen.forEachIndexed { i,s -> println("   $i ${s.id}  ${s.title}  ${s.badge}") }
        if(chosen.isEmpty()) println("   （匿名身份没拿到 160 cell → 详情页回落官网内嵌 related_list）")
        return
    }
    // --cloud-history <creds.json>：读官方云端观看历史，验证进度上报是否真的落库。
    // creds.json 是 AccountCredentials.fromJson 兼容的最小 JSON（cookie/deviceId/installId 必填），
    // 由手机 prefs（hongguotv_phone_login.xml）拼出，临时文件用完即删，不进仓库。
    if(args.contains("--cloud-history")) {
        val path=args.getOrNull(args.indexOf("--cloud-history")+1)
        if(path.isNullOrBlank()) { println("用法: --cloud-history <creds.json>"); return }
        val creds=AccountCredentials.fromJson(java.io.File(path).readText())
        val http=okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        try {
            val list=CloudAccountClient(http).history(creds)
            println("== 云端观看历史 共 ${list.size} 条")
            list.forEachIndexed { i,p ->
                val rate=if(p.duration>0) " ${(p.position*100/p.duration)}%" else ""
                val at=java.time.Instant.ofEpochMilli(p.updatedAt).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                println("   $i ${p.series.id} ${p.series.title}  ep${p.episodeIndex+1} ${p.position}/${p.duration}${rate}  @$at")
            }
            if(list.isEmpty()) println("   （空：账号云端历史里没有短剧进度）")
        } catch(e:Exception) { println("   失败: ${e.message}") }
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        return
    }
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=args.getOrNull(args.indexOf("--keyword")+1) ?: if(type==ContentType.COMIC) "修仙" else "总裁"
    val started=System.currentTimeMillis()
    // --stream-only：只验证详情与播放链路，跳过搜索和首页，避免内容接口偶发故障挡住播放排查。
    val streamOnly=args.contains("--stream-only")
    // 剧集号参数是 `--episode N` 的值，别把它当成剧集 ID。
    val seriesArg=args.withIndex().firstOrNull { (index,value) ->
        value.matches(Regex("[0-9]{1,30}")) && args.getOrNull(index-1)!="--episode"
    }?.value
    if(!streamOnly) {
        val search=repository.search(keyword,type=type); println("searchMs=${System.currentTimeMillis()-started}; search=${search.items.size}; hasMore=${search.hasMore}")
        if(type==ContentType.COMIC && search.hasMore) {
            val next=repository.search(keyword,2,type)
            println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        }
        val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
        if(type==ContentType.COMIC) {
            val next=repository.home(2,type)
            println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        }
    }
    val detail=repository.detail(seriesArg ?: repository.home(type=type).items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    // --warm：验证「一次注册问一整窗」是否可用，以及切集是否真的命中缓存（耗时接近 0ms）。
    if(args.contains("--warm")) {
        val ids=detail.episodes.take(15)
        val primary=repository.stream(ids.first())
        println("primary quality=${primary.quality}")
        val startedWarm=System.currentTimeMillis()
        val report=repository.warm(ids)
        println("warm requested=${report.requested} cached=${report.cached} batch=${report.batch} route=${report.route} ms=${System.currentTimeMillis()-startedWarm}")
        val startedHit=System.currentTimeMillis()
        val hit=repository.stream(ids.getOrElse(5) { ids.last() })
        println("cacheHit quality=${hit.quality} ms=${System.currentTimeMillis()-startedHit}")
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    val episodeIndex=args.getOrNull(args.indexOf("--episode")+1)?.toIntOrNull() ?: 0
    val stream=repository.stream(detail.episodes.getOrElse(episodeIndex) { detail.episodes.last() })
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}

/**
 * 官方取址接口诊断：每轮换一台匿名设备，连打三次 v5 主路，原样打印 HTTP 码 / 响应头 / 响应体。
 * 输出全用英文，Windows 控制台中文会乱码。
 */
private fun mainDiagnose(args: Array<String>) {
    val repository=ContentRepository()
    val http=repository.http
    val signer=Signer()
    val id=args.withIndex().firstOrNull { (index,value) ->
        value.matches(Regex("[0-9]{1,30}")) && args.getOrNull(index-1)!="--episode"
    }?.value ?: throw IllegalArgumentException("usage: --diagnose <episodeId> [--rounds N] [--host a|b]")
    val rounds=args.getOrNull(args.indexOf("--rounds")+1)?.toIntOrNull() ?: 3
    val host=args.getOrNull(args.indexOf("--host")+1) ?: "a"
    val session=PlaybackDeviceSession.shared
    // --legacy：旧端点线上仍在做参数校验（返回 101000/100001 而不是空体），用旧参数格式试能不能重新取到地址。
    if(args.contains("--legacy")) {
        val profile=session.profile(http)
        println("device_id=${profile.values["device_id"]}")
        val legacyBody="""{"video_id":"$id","content_type":1,"biz_param":{"need_all_video_definition":true,"need_mp4_align":false,"video_platform":3}}"""
        val bareBody="""{"video_id":"$id"}"""
        val cases=listOf(
            Triple("video_model|legacy","/novel/player/video_model/v1/",legacyBody),
            Triple("video_model|bare","/novel/player/video_model/v1/",bareBody),
            Triple("multi_v1|vp1024","/novel/player/multi_video_model/v1/","""{"video_id":"$id","biz_param":{"need_all_video_definition":true,"need_mp4_align":false,"video_platform":1024}}"""),
            Triple("multi_v1|ids1024","/novel/player/multi_video_model/v1/","""{"video_ids":["$id"],"biz_param":{"need_all_video_definition":true,"need_mp4_align":false,"video_platform":1024}}"""),
            Triple("multi_v1|mixed1024","/novel/player/multi_video_model/v1/","""{"biz_param":{"need_all_video_definition":true,"need_mp4_align":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""),
            Triple("player|legacy","/novel/player/multi_video_model/player/v1",legacyBody),
        )
        for((label,pathOverride,body) in cases) {
            val started=System.currentTimeMillis()
            println("LEGACY $label ${diagnoseV5(id,profile,http,signer,host,pathOverride=pathOverride,customBody=body)} ms=${System.currentTimeMillis()-started}")
            Thread.sleep(400)
        }
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    // --upstream：上游 tsukiyo-zero/hongguoTV@573d75a 的组合（v1 路径 + 无 dr_scene + mixed_video_id_map + 74532）。
    if(args.contains("--upstream")) {
        // --fresh：每次全新注册身份（区分「这个 device_id 已被拉黑」和「形态本身不对」）。
        val fresh=args.contains("--fresh")
        val rounds=if(fresh) (args.getOrNull(args.indexOf("--fresh")+1)?.toIntOrNull() ?: 2) else 1
        val body="""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}"""
        for(round in 1..rounds) {
            val proxyArg=args.withIndex().firstOrNull { (i,v) -> v=="--proxy" }?.let { args.getOrNull(it.index+1) }
            val base=ContentRepository()
            val repo=if(proxyArg==null) base else {
                val pHost=proxyArg.substringBefore(":"); val pPort=proxyArg.substringAfter(":").toInt()
                ContentRepository(base.http.newBuilder()
                    .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress(pHost,pPort)))
                    .build())
            }
            val session=if(fresh) PlaybackDeviceSession() else session
            val transport=repo.http
            val profile=session.profile(transport)
            val started=System.currentTimeMillis()
            val withCookie=!fresh // 全新身份不带 Cookie 的结论已有：这里重点补「注册身份 + Cookie」的对照
            println("UPSTREAM r$round dev=${profile.values["device_id"]} cookie=$withCookie ${diagnoseV5(id,profile,transport,signer,host,pathOverride="/novel/player/multi_video_model/v1",versionCode="74532",versionName="7.4.5.32",updateVersionCode="74532",customBody=body,withCookie=withCookie)} ms=${System.currentTimeMillis()-started}")
            if(repo!=base) { repo.http.connectionPool.evictAll(); repo.http.dispatcher.executorService.shutdown() }
            base.http.connectionPool.evictAll(); base.http.dispatcher.executorService.shutdown()
            Thread.sleep(600)
        }
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    // --ver：换客户端版本号再打，验证「服务端是否按版本门槛静默拒绝」（官方今天下午是否抬了最低版本）。
    if(args.contains("--ver")) {
        val profile=session.profile(http)
        println("device_id=${profile.values["device_id"]}")
        val candidates=listOf(
            "74332" to "7.4.3.32",
            "74532" to "7.4.5.32",
            "74632" to "7.4.6.32",
            "74732" to "7.4.7.32",
            "75002" to "7.5.0.2",
            "76002" to "7.6.0.2",
        )
        for((code,name) in candidates) {
            val started=System.currentTimeMillis()
            val line=diagnoseV5(id,profile,http,signer,host,versionCode=code,versionName=name).lineSequence().first()
            println("VER $name/$code $line ms=${System.currentTimeMillis()-started}")
            Thread.sleep(400)
        }
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    // --cookie：对比「不带注册 Cookie」与「带注册 Cookie」两种请求，验证是否变成必须登录/必须带票据。
    if(args.contains("--cookie")) {
        val profile=session.profile(http)
        println("device_id=${profile.values["device_id"]} cookie=[${profile.cookie.ifBlank { "<EMPTY>" }}]")
        for(attempt in 1..2) {
            println("NODEV-$attempt ${diagnoseV5(id,profile,http,signer,host).lineSequence().first()}")
            println("COOKIE-$attempt ${diagnoseV5(id,profile,http,signer,host,withCookie=true).lineSequence().first()}")
            Thread.sleep(500)
        }
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    // --once：每次都用一个全新 OkHttpClient + 全新注册设备打一次，用来区分「配额限流」和「落在不同后端实例」。
    if(args.contains("--once")) {
        val n=args.getOrNull(args.indexOf("--once")+1)?.toIntOrNull() ?: 10
        val proxyArg=args.getOrNull(args.indexOf("--proxy")+1)
        for(i in 1..n) {
            val plain=ContentRepository()
            val repo=if(proxyArg==null) plain else {
                val host=proxyArg.substringBefore(":"); val port=proxyArg.substringAfter(":").toInt()
                ContentRepository(plain.http.newBuilder()
                    .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress(host,port)))
                    .build())
            }
            val fresh=PlaybackDeviceSession()
            val profile=fresh.profile(repo.http)
            val started=System.currentTimeMillis()
            val line=diagnoseV5(id,profile,repo.http,signer,host).lineSequence().first()
            println("ONCE $i dev=${profile.values["device_id"]} $line ms=${System.currentTimeMillis()-started}")
            repo.http.connectionPool.evictAll(); repo.http.dispatcher.executorService.shutdown()
            Thread.sleep(400)
        }
        return
    }
    if(args.contains("--burst")) {
        val n=args.getOrNull(args.indexOf("--burst")+1)?.toIntOrNull() ?: 20
        val profile=session.profile(http)
        println("device_id=${profile.values["device_id"]}")
        for(i in 1..n) {
            val started=System.currentTimeMillis()
            val line=diagnoseV5(id,profile,http,signer,host).lineSequence().first()
            println("BURST $i $line ms=${System.currentTimeMillis()-started}")
        }
        for(gap in listOf(10,20,30,60)) {
            Thread.sleep(gap*1000L)
            val started=System.currentTimeMillis()
            val line=diagnoseV5(id,profile,http,signer,host).lineSequence().first()
            println("AFTER ${gap}s $line ms=${System.currentTimeMillis()-started}")
        }
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    if(args.contains("--matrix")) {
        val profile=session.profile(http)
        println("device_id=${profile.values["device_id"]} iid=${profile.values["iid"]} cdid=${profile.values["cdid"]}")
        val cases=listOf(
            "a|player/v1" to ("a" to "/novel/player/multi_video_model/player/v1"),
            "b|player/v1" to ("b" to "/novel/player/multi_video_model/player/v1"),
            "a|multi_v1" to ("a" to "/novel/player/multi_video_model/v1/"),
            "a|video_model_v1" to ("a" to "/novel/player/video_model/v1/"),
            "a|preload_v1" to ("a" to "/novel/player/multi_video_model/preload/v1/"),
        )
        for((label,pair) in cases) {
            val started=System.currentTimeMillis()
            println("CASE $label ${diagnoseV5(id,profile,http,signer,pair.first,pair.second)} ms=${System.currentTimeMillis()-started}")
            Thread.sleep(500)
        }
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    for(round in 1..rounds) {
        val profile=session.profile(http)
        println("ROUND $round device_id=${profile.values["device_id"]} iid=${profile.values["iid"]} cdid=${profile.values["cdid"]}")
        for(attempt in 1..3) {
            val started=System.currentTimeMillis()
            println("  ATTEMPT $attempt ${diagnoseV5(id,profile,http,signer,host)} ms=${System.currentTimeMillis()-started}")
            if(attempt<3) Thread.sleep(800)
        }
        if(round<rounds) session.renew(http)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}

private fun diagnoseV5(
    id: String,
    identity: PlaybackDeviceSession.Profile,
    http: okhttp3.OkHttpClient,
    signer: Signer,
    host: String,
    pathOverride: String? = null,
    withCookie: Boolean = false,
    versionCode: String = "74332",
    versionName: String = "7.4.3.32",
    updateVersionCode: String = "74332",
    customBody: String? = null,
): String {
    val base=if(host=="b") "https://api5-normal-sinfonlineb.fqnovel.com" else "https://api5-normal-sinfonlinea.fqnovel.com"
    val path=pathOverride ?: "/novel/player/multi_video_model/player/v1"
    val payload=customBody ?: """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""
    val body=java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { gz -> gz.write(payload.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()
    val values=linkedMapOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to versionCode, "version_name" to versionName,
        "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to updateVersionCode,
        "iid" to identity.values.getValue("iid"),
        "cdid" to identity.values.getValue("cdid"),
        "device_id" to identity.values.getValue("device_id"),
    )
    val signed=signer.signPlaybackV5(base+path,values,body)
    // --cookie：带上注册时拿到的身份 Cookie。用来验证「官方今天下午是否改成必须登录/必须带注册票据」。
    val extraCookie=if(withCookie) identity.cookie else ""
    val request=okhttp3.Request.Builder().url(signed.url)
        .header("User-Agent",MobilePlayback.UA)
        .header("Accept","application/json")
        .header("Content-Encoding","gzip")
        // 上游 0.1.33（N3urda 移植）：请求必须带 x-ss-stub = 大写 MD5(gzip body)，字节系反作弊头。
        .header("x-ss-stub",java.security.MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02X".format(it) })
        .apply { if(extraCookie.isNotBlank()) header("Cookie",extraCookie) }
        .apply { signed.headers.forEach { (key,value) -> header(key,value) } }
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    val transport=http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    return runCatching {
        RequestScope.execute(transport.newCall(request)) { response ->
            val bytes=response.body?.bytes() ?: ByteArray(0)
            val interesting=response.headers.names()
                .filter { it.startsWith("x-",true) || it.equals("server",true) || it.equals("content-length",true) }
                .joinToString(",") { "$it=${response.header(it)}" }
            val text=if(bytes.isEmpty()) "<EMPTY>" else String(bytes,Charsets.UTF_8).take(600)
            "HTTP=${response.code} len=${bytes.size} enc=${response.header("Content-Encoding")}\n      HDR[$interesting]\n      BODY[$text]"
        }
    }.getOrElse { "EXCEPTION=${it.javaClass.simpleName}: ${it.message}" }
}

/** --batch-probe [seriesId] [--force]：批量取址 2×2 矩阵探测。
 *  key=1(短剧)/1004(漫剧) × 签名(有/无) 各发一次（共 4 个请求，间隔 2s），
 *  定位批量预热空响应的真实原因。冷却期内默认拒绝执行（--force 越过）。 */
private fun mainBatchProbe(args: Array<String>) {
    if(args.contains("--verbose")) ContentRepository.probeLog={ println("probe: $it") }
    val http=okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val repository=ContentRepository(http)
    val seriesArg=args.getOrNull(args.indexOf("--batch-probe")+1)?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
    val ids=try {
        val detail=repository.detail(seriesArg ?: repository.home().items.first().id)
        detail.episodes.take(3).also { println("剧=${detail.series.title} 探测集=${it}") }
    } catch(e:Exception) { throw IllegalStateException("取剧集列表失败: ${e.message}",e) }
    val force=args.contains("--force")
    // --single：只发 1 个集 id——区分「端点死了」还是「服务端只容忍单 id」（11-20 14:09 单 id 同形态实测通过过）。
    val probeIds=if(args.contains("--single")) ids.take(1) else ids
    val combos=listOf("1" to true,"1" to false,"1004" to true,"1004" to false)
    for((key,signed) in combos) {
        val label="key=$key ${if(signed) "v5签名" else "无签名"} ids=${probeIds.size}"
        val result=try { repository.batchProbe(probeIds,key,signed,force) }
        catch(e:com.hongguotv.core.PlaybackThrottledException) { println("$label → 冷却拒绝: ${e.message}"); break }
        catch(e:Exception) { println("$label → 异常: ${e.javaClass.simpleName} ${e.message}"); continue }
        println("$label → $result")
        Thread.sleep(2000)
    }
}
