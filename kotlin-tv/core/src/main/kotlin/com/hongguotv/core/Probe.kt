package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** --feed --did/--iid 用的固定身份。 */
private fun DiscoveryIdentityStyle(did:String,iid:String):Map<String,String> =
    VendorConstants.device.toMutableMap().apply { put("device_id",did); put("iid",iid) }

fun main(args: Array<String>) {
    // --passport <register|send|login>：登录平面（官方 App 原生会话）探针。
    //   register            设备注册（7.3.5.32 画像）→ 打印 device_id/iid，会话落盘 --session
    //   send <phone>        发短信验证码
    //   login <phone> <code>  验码登录 → 落盘会话 → 立即打 plan/v1 打印全部 cell（验证 160 是否解锁）
    //   plan <bookId>       读 --session 落盘会话，打 plan/v1（160 相关推荐 + 547 系列剧）
    if(args.contains("--passport")) { mainPassport(args); return }
    // --dump-spade：绕过 bytevc2 过滤，dump 播放模型里所有分档的 spade_a / codec / url。
    // --ladder <episodeId>：打印清晰度阶梯缓存输出，验证五档一次下发/缓存命中。
    if(args.contains("--dump-spade") || args.contains("--ladder")) { mainDump(args); return }
    // --batch-probe：批量取址矩阵探针——key(1/1004) × 签名(有/无) 四组合各发一次，
    // 定位批量空响应的真实原因（2026-11-21 调研：key 应为内容类型、路由配方可能要无签名）。
    if(args.contains("--batch-probe")) { mainBatchProbe(args); return }
    // --batch5：现行协议一发批量取址验证（预取生效前提）。
    if(args.contains("--batch5")) { mainBatch5(args); return }
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
/** --batch5 <seriesId> [--count 15]：现行协议的一发批量取址验证（preload/v1 + v5 四头 + F21=312）。
 *  用途：确认「1 发喂满一窗」在当前服务端是否成立——这是预取能不能真正生效的前提。
 *  单发一次，不换身份、不重试。 */
private fun mainBatch5(args: Array<String>) {
    if(args.contains("--verbose")) ContentRepository.probeLog={ println("probe: $it") }
    fun arg(name: String): String? { val i=args.indexOf(name); return if(i>=0) args.getOrNull(i+1) else null }
    val http=okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val repository=ContentRepository(http)
    val seriesArg=arg("--batch5")?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
    val count=(arg("--count")?.toIntOrNull() ?: 15).coerceIn(1,20)
    val ids=try {
        val detail=repository.detail(seriesArg ?: repository.home().items.first().id)
        detail.episodes.take(count).also { println("剧=${detail.series.title} 集数=${detail.episodes.size} 请求=${it.size}") }
    } catch(e:Exception) { throw IllegalStateException("取剧集列表失败: ${e.message}",e) }
    val identity=PlaybackDeviceSession().profile(http)
    val started=System.currentTimeMillis()
    try {
        val models=MobilePlayback.batch(ids,identity,http,Signer())
        println("RESULT 请求=${ids.size} 返回=${models.size} ms=${System.currentTimeMillis()-started}")
        models.entries.take(20).forEach { (id,model) ->
            val list=model.optJSONObject("video_list")
            val array=model.optJSONArray("video_list")
            val tiers=array?.length() ?: list?.length() ?: 0
            val first=array?.optJSONObject(0) ?: list?.let { obj -> obj.keys().asSequence().mapNotNull { obj.optJSONObject(it) }.firstOrNull() }
            val meta=first?.optJSONObject("video_meta")
            println("  ${ids.indexOf(id)+1}集 $id tiers=$tiers codec=${meta?.optString("codec_type")} def=${meta?.optString("definition")}")
        }
        val missing=ids.filter { it !in models }
        if(missing.isNotEmpty()) println("  未返回 ${missing.size} 集：${missing.take(5).joinToString(",")}")
        arg("--dump")?.let { path ->
            models.values.firstOrNull()?.let { model ->
                java.io.File(path).writeText(model.toString())
                val row=model.optJSONArray("video_list")?.optJSONObject(0)
                val enc=row?.optJSONObject("encrypt_info")
                println("  dump=$path spade_a=${if(enc?.optString("spade_a").orEmpty().isNotEmpty()) "有" else "无"}")
            }
        }
    } catch(e:Exception) {
        println("RESULT 失败 ${e.javaClass.simpleName}: ${e.message} ms=${System.currentTimeMillis()-started}")
    }
    http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
}

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

/**
 * 登录平面探针：设备注册 → 短信登录 → 携会话打 plan/v1。
 *
 * 用法：
 *   --passport register [--session <file>]
 *   --passport send <phone> [--session <file>]
 *   --passport login <phone> <code> [--session <file>] [--book <id>]
 *   --passport plan <bookId> [--session <file>] [--host a|b]
 *
 * 会话文件是 PassportClient.toJson 的落盘结果；不传则每次从零注册（登录必须同一次调用里完成，
 * 因为发码与验码是同一会话的 ticket）。
 */
private fun mainPassport(args: Array<String>) {
    fun arg(name: String): String? { val i = args.indexOf(name); return if (i >= 0) args.getOrNull(i + 1) else null }
    val action = arg("--passport") ?: run { println("用法: --passport <register|send|login|plan>"); return }
    val sessionFile = arg("--session") ?: ".passport-session.json"
    val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    fun save(client: PassportClient) {
        java.io.File(sessionFile).writeText(client.toJson())
        println("会话已落盘: $sessionFile")
    }
    fun dumpPlan(client: PassportClient, bookId: String, label: String, host: String) {
        println("== plan/v1 $label book_id=$bookId host=${host.substringAfter("//").substringBefore('.')}")
        val raw = try { client.planRaw(bookId, "select_panel_recommend", null, host) }
        catch (e: Exception) { println("   失败: ${e.message}"); return }
        val code = raw.optInt("code", -1)
        if (code != 0) { println("   code=$code message=${raw.optString("message")} ${raw.toString().take(160)}"); return }
        val cells = raw.optJSONArray("data") ?: raw.optJSONObject("data")?.optJSONArray("data") ?: org.json.JSONArray()
        if (cells.length() == 0) { println("   （code=0 但无 cell：${raw.toString().take(200)}）"); return }
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val rows = cell.optJSONArray("video_data") ?: cell.optJSONArray("cell_data")
            println("   cell$i show_type=${cell.optInt("show_type")}  「${cell.str("cell_name")}」  条目=${rows?.length() ?: 0}")
        }
    }

    try {
        when (action) {
            "register" -> {
                val client = PassportClient(http, PassportSession())
                val session = client.registerDevice()
                println("== 设备注册成功 device_id=${session.deviceId} iid=${session.installId} cdid=${session.cdid}")
                save(client)
            }
            "send" -> {
                val phone = arg("--phone") ?: args.getOrNull(args.indexOf("--passport") + 2)
                    ?: run { println("用法: --passport send <phone>"); return }
                val base = java.io.File(sessionFile).takeIf { it.exists() }?.let { PassportClient.fromJson(it.readText()) } ?: PassportSession()
                val client = PassportClient(http, base)
                if (!base.registered) { client.registerDevice(); println("设备已注册 device_id=${client.snapshot().deviceId}") }
                val retry = client.sendCode(phone)
                save(client)
                println("== 验证码已发送（打码 ${phone.take(3)}****${phone.takeLast(4)}），${retry}s 后可重发")
                if (args.contains("--debug")) println("原始响应: ${client.lastReply}")
            }
            "login" -> {
                val phone = arg("--phone") ?: args.getOrNull(args.indexOf("--passport") + 2)
                    ?: run { println("用法: --passport login <phone> <code>"); return }
                val code = arg("--code") ?: args.getOrNull(args.indexOf("--passport") + 3)
                    ?: run { println("用法: --passport login <phone> <code>"); return }
                val base = java.io.File(sessionFile).takeIf { it.exists() }?.let { PassportClient.fromJson(it.readText()) } ?: PassportSession()
                val client = PassportClient(http, base)
                if (!base.registered) { client.registerDevice(); println("设备已注册 device_id=${client.snapshot().deviceId}") }
                // 已有 mobile_ticket 时直接验码，不重发（重发会让用户手上的码作废）。
                if (base.mobileTicket.isBlank() || args.contains("--resend")) {
                    val retry = client.sendCode(phone)
                    println("验证码已发送（${retry}s 可重发）")
                    if (args.contains("--debug")) println("原始响应: ${client.lastReply}")
                } else {
                    println("复用已发出的验证码（ticket 已存在，未重发）")
                }
                save(client)
                // 交互式读验证码，避免把验证码写进 shell 历史；非交互环境走 --code。
                val entered = if (code.matches(Regex("[0-9]{4,8}"))) code
                    else runCatching { readLine()?.trim() }.getOrNull()?.takeIf { it.matches(Regex("[0-9]{4,8}")) }
                        ?: run { println("缺验证码：--code <验证码>"); return }
                val session = try { client.smsLogin(phone, entered, arg("--variant") ?: "none") } catch (e: Exception) {
                    println("验码失败: ${e.message}")
                    client.lastReply?.let { println("原始响应: $it") }
                    return
                }
                save(client)
                println("== 登录成功 device_id=${session.deviceId} token=${if (session.token.isNotBlank()) "有" else "无"} cookies=${session.cookies.keys.joinToString(",")}")
                val bookId = arg("--book") ?: "7706512650816982040"
                dumpPlan(client, bookId, "登录态相关推荐", PassportClient.CONTENT_HOST)
                dumpPlan(client, bookId, "登录态相关推荐(a域)", "https://api5-normal-sinfonlinea.fqnovel.com")
            }
            "play" -> {
                // 诊断：用 passport 签名族打播放接口。若它能出货而 PlayerSigner 不能，
                // 说明当前空响应是播放签名族被判，而非设备额度/IP 风控。
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val client = PassportClient(http, PassportClient.fromJson(base.readText()))
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7486311576044047385"
                val extra = linkedMapOf(
                    "video_id" to vid, "video_id_type" to "0", "device_level" to "3",
                    "need_all_video_definition" to "1", "need_mp4_align" to "0",
                    "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
                    "from_video_id" to "",
                )
                arg("--extra")?.split(',')?.forEach { pair ->
                    val key = pair.substringBefore('='); val value = pair.substringAfter('=', "")
                    extra[key] = value
                }
                arg("--drop")?.split(',')?.forEach { extra.remove(it) }
                // --form：改走 body（video_detail 疑似只吃 POST）；给值时业务参数从 query 移入 body。
                val form = linkedMapOf<String, String>()
                arg("--form")?.split(',')?.filter { it.isNotBlank() }?.forEach { pair ->
                    val key = pair.substringBefore('=').trim(); val value = pair.substringAfter('=', "")
                    if (key in extra) extra.remove(key)
                    form[key] = value
                }
                if (form.isNotEmpty()) arg("--drop")?.split(',')?.forEach { form.remove(it) }
                val method = arg("--method") ?: if (form.isEmpty()) "GET" else "POST"
                for (host in listOf(PassportClient.CONTENT_HOST, "https://reading.snssdk.com")) {
                    val path = arg("--path") ?: "/reading/bookapi/video_tab/multi_video_model/v1/"
                    val raw = try { client.signedRequest(method, path, extra, form, host) }
                    catch (e: Exception) { println("  ${host.substringAfter("//").substringBefore('.')} 失败 ${e.message}"); continue }
                    val code = raw.optInt("code", raw.optInt("Code", -1))
                    val data = raw.optJSONObject("data")
                    val hasModel = data != null && data.toString().contains("video_model")
                    println("  ${host.substringAfter("//").substringBefore('.')} [$method] code=$code len=${raw.toString().length} 有模型=$hasModel")
                    arg("--dump")?.let { java.io.File(it).writeText(raw.toString()) }
                    if (code != 0) println("     ${raw.toString().take(160)}")
                }
            }
            "tabplay" -> {
                // 2026-11-23 22:30 上游 0.16.12 配方验证：video_tab GET 用 v5 签名 + MD5(空字节)。
                // 只发这一发（不打主 POST）：这是主接口空响应下唯一确认可用的通道。
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7296031515048152100"
                val host = if (args.contains("--alt")) PassportClient.CONTENT_HOST else MobilePlayback.HOST
                val identity = PlaybackDeviceSession().profile(http)
                println("== tabplay video=$vid host=${host.removePrefix("https://").substringBefore('/')} device_id=${identity.values["device_id"]}")
                val started = System.currentTimeMillis()
                runCatching {
                    MobilePlayback.videoTab(vid, identity, http, Signer(), System.currentTimeMillis(), host)
                }.onSuccess { model ->
                    val list = model?.optJSONObject("video_list")
                    val codecs = list?.keys()?.asSequence()?.mapNotNull { list.optJSONObject(it)?.optString("codec_type") }?.toList()
                    println("RESULT 有模型=${model != null} videoList=${list?.length() ?: 0} codecs=$codecs ms=${System.currentTimeMillis()-started}")
                    arg("--dump")?.let { java.io.File(it).writeText(model.toString()) }
                }.onFailure { e ->
                    println("RESULT 失败 ${e.javaClass.simpleName}: ${e.message} ms=${System.currentTimeMillis()-started}")
                }
            }
            "v5app" -> {
                // 2026-11-23 22:45 上游 0.16.14 配方验证：Medusa F21 由 377 改 312，请求走
                // reading.snssdk.com + preload/v1 + dr_scene=preload + device_level=2 + 平台默认 UA。
                // 单发一次，不换身份、不重试。
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7709197486451084312"
                val identity = PlaybackDeviceSession().profile(http)
                println("== v5app video=$vid device_id=${identity.values["device_id"]} device=${identity.values["device_type"]} ver=7.4.5.32")
                val started = System.currentTimeMillis()
                runCatching {
                    MobilePlayback.model(vid, identity, http, Signer(), System.currentTimeMillis(), 4,
                        MobilePlayback.PATH, "", "", true)
                }.onSuccess { model ->
                    val list = model?.optJSONObject("video_list")
                    val codecs = list?.keys()?.asSequence()?.mapNotNull { list.optJSONObject(it)?.optString("codec_type") }?.toList()
                    println("RESULT 有模型=${model != null} videoList=${list?.length() ?: 0} codecs=$codecs ms=${System.currentTimeMillis()-started}")
                    arg("--dump")?.let { java.io.File(it).writeText(model.toString()) }
                }.onFailure { e ->
                    println("RESULT 失败 ${e.javaClass.simpleName}: ${e.message} ms=${System.currentTimeMillis()-started}")
                }
            }
            "v5play" -> {
                // 2026-11-23 22:15 实验：真 v5 头（ss-stub/khronos/helios/medusa）+ 登录账号 Cookie，单发一次。
                // 要判定的假设：官方 APK 能播只是因为它带账号——匿名请求没有计次主体，服务端给空最省事。
                // 关键：query 里的 device_id / iid / cdid 与 Cookie 必须来自同一会话，否则服务端会当成两台设备。
                // 加 --anonymous 则同身份不带 Cookie 做对照（默认不带，避免多花一发）。
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val session = PassportClient.fromJson(base.readText())
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7486311576044047385"
                val cookie = if (args.contains("--anonymous")) "" else
                    session.cookies.entries.filter { it.key != "install_id" }
                        .joinToString("; ") { "${it.key}=${it.value}" }
                val values = linkedMapOf(
                    "device_platform" to "android", "os" to "android", "ssmix" to "a",
                    "cdid" to session.cdid, "channel" to "xiaomi_8662_64",
                    "aid" to "8662", "app_name" to "novelread",
                    "version_code" to "74332", "version_name" to "7.4.3.32",
                    "manifest_version_code" to "74332", "update_version_code" to "74332",
                    "resolution" to "1200*2670", "dpi" to "460",
                    "device_type" to "23127PN0CC", "device_brand" to "Xiaomi",
                    "language" to "zh", "os_api" to "34", "os_version" to "14", "ac" to "wifi",
                    "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
                    "pv_player" to "74332", "compliance_status" to "0",
                    "need_personal_recommend" to "1", "player_so_load" to "1",
                    "is_android_pad_screen" to "0",
                    "iid" to session.installId, "device_id" to session.deviceId,
                )
                val profile = PlaybackDeviceSession.Profile(values, "")
                println("== v5play video=$vid device_id=${session.deviceId} 带Cookie=${cookie.isNotBlank()} " +
                    "cookieKeys=${session.cookies.size} token=${if (session.token.isBlank()) "无" else "有"} " +
                    "loggedIn=${session.loggedIn}")
                val started = System.currentTimeMillis()
                runCatching {
                    MobilePlayback.model(vid, profile, http, Signer(), System.currentTimeMillis(), 4, MobilePlayback.PATH, cookie,
                        if (args.contains("--anonymous")) "" else session.token)
                }.onSuccess { model ->
                    val list = model?.optJSONObject("video_list")
                    val codecs = list?.keys()?.asSequence()?.mapNotNull { list.optJSONObject(it)?.optString("codec_type") }?.toList()
                    println("RESULT 有模型=${model != null} videoList=${list?.length() ?: 0} codecs=$codecs ms=${System.currentTimeMillis()-started}")
                    arg("--dump")?.let { java.io.File(it).writeText(model.toString()) }
                }.onFailure { e ->
                    println("RESULT 失败 ${e.javaClass.simpleName}: ${e.message} ms=${System.currentTimeMillis()-started}")
                }
            }
            "openv5" -> {
                // 逐字复刻 openhongguo PlaybackV5Pairs/PlaybackV5Body（7.4.3.32 抓包，2026-11-18），
                // 与 MobilePlayback 的差异在：完整 30 项 query（pv_player/compliance_status/player_so_load…）、
                // dr_scene=player、device_level=1、version 74332。
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7296039582766746686"
                val level = (arg("--level") ?: "1").toInt()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .cookieJar(MemoryCookies())
                    .build()
                val identity = PlaybackDeviceSession().profile(client)
                val q = linkedMapOf(
                    "device_platform" to "android", "os" to "android", "ssmix" to "a",
                    "cdid" to identity.values.getValue("cdid"),
                    "channel" to "xiaomi_8662_64",
                    "aid" to "8662", "app_name" to "novelread",
                    "version_code" to "74332", "version_name" to "7.4.3.32",
                    "manifest_version_code" to "74332", "update_version_code" to "74332",
                    "resolution" to "1200*2670", "dpi" to "460",
                    "device_type" to "23127PN0CC", "device_brand" to "Xiaomi",
                    "language" to "zh", "os_api" to "34", "os_version" to "14",
                    "ac" to "wifi", "host_abi" to "arm64-v8a",
                    "dragon_device_type" to "phone", "pv_player" to "74332",
                    "compliance_status" to "0", "need_personal_recommend" to "1",
                    "player_so_load" to "1", "is_android_pad_screen" to "0",
                    "iid" to identity.values.getValue("iid"),
                    "device_id" to identity.values.getValue("device_id"),
                )
                val body = """{"biz_param":{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$vid"]},"dr_scene":"player"}"""
                val gz = java.io.ByteArrayOutputStream().also { out ->
                    java.util.zip.GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }.toByteArray()
                val signer = Signer()
                for (host in listOf("https://api5-normal-sinfonlinea.fqnovel.com", "https://api5-normal-sinfonlineb.fqnovel.com")) {
                    val signed = try { signer.signPlaybackV5(host + "/novel/player/multi_video_model/player/v1", q, gz) }
                    catch (e: Exception) { println("  签名失败 ${e.message}"); continue }
                    val req = okhttp3.Request.Builder().url(signed.url)
                        .header("User-Agent", PassportClient.PLAYBACK_UA)
                        .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                        .header("x-xs-from-web", "0")
                        .header("Content-Encoding", "gzip")
                        .apply { ContentRepository.realSignedHeaders(signed.url, signed.headers, gz).forEach { (k, v) -> header(k, v) } }
                        .post(okhttp3.RequestBody.create(null, gz))
                        .build()
                    try {
                        client.newCall(req).execute().use { resp ->
                            val text = resp.body?.string().orEmpty()
                            println("  ${host.substringAfter("//").substringBefore('.')} HTTP=${resp.code} len=${text.length}")
                            if (text.isBlank()) return@use
                            val root = runCatching { org.json.JSONObject(text) }.getOrNull()
                            val code = root?.optInt("code", -1) ?: -1
                            println("     code=$code ${text.take(200)}")
                            arg("--dump")?.let { java.io.File(it).writeText(text) }
                        }
                    } catch (e: Exception) { println("  失败 ${e.message}") }
                }
            }
            "v5" -> {
                // 按 openhongguo PlaybackV5Body / PlaybackBody 复刻：JSON body + gzip，这条是唯一活路。
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val client = PassportClient(http, PassportClient.fromJson(base.readText()))
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7296039582766746686"
                val level = (arg("--level") ?: "1").toInt()
                val v5Body = """{"biz_param":{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$vid"]},"dr_scene":"player"}"""
                val legacyBody = """{"video_id":"$vid","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
                val only = arg("--only")
                if (only != "legacy") {
                    println("== V5 /novel/player/multi_video_model/player/v1 (level=$level)")
                    runCatching { client.signedJson("/novel/player/multi_video_model/player/v1", v5Body) }
                        .onSuccess { r ->
                            val code = r.optInt("code", -1)
                            println("   code=$code len=${r.toString().length} 有 url=${r.toString().contains("\"url\"")} 有 video_model=${r.toString().contains("video_model")}")
                            arg("--dump")?.let { java.io.File(it).writeText(r.toString()) }
                            if (code != 0) println("   ${r.toString().take(220)}")
                            else if (!r.toString().contains("\"url\"")) println("   ${r.toString().take(300)}")
                        }
                        .onFailure { println("   失败 ${it.message}") }
                }
                if (only != "v5") {
                    println("== legacy /novel/player/video_model/v1/")
                    runCatching { client.signedJson("/novel/player/video_model/v1/", legacyBody) }
                        .onSuccess { r ->
                            val code = r.optInt("code", -1)
                            println("   code=$code len=${r.toString().length} 有 url=${r.toString().contains("\"url\"")}")
                            if (code != 0) println("   ${r.toString().take(220)}")
                        }
                        .onFailure { println("   失败 ${it.message}") }
                }
            }
            "rotate" -> {
                // 验证「一台匿名设备只给一次播放模型」：同一身份连打两次 + 每次换新身份。
                val vid = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7486311576044047385"
                val tries = (arg("--tries") ?: "3").toInt()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .cookieJar(MemoryCookies())
                    .build()
                val signer = Signer()
                fun once(identity: PlaybackDeviceSession.Profile): String = try {
                    val model = MobilePlayback.model(vid, identity, client, signer, System.currentTimeMillis())
                    if (model == null || !model.toString().contains("http")) "空" else "成功"
                } catch (e: Exception) { "${e.javaClass.simpleName}" }
                for (i in 1..tries) {
                    val identity = PlaybackDeviceSession().profile(client)
                    println("#$i 新身份 device=${identity.values["device_id"]} 首发=${once(identity)}")
                    println("#$i 同身份复用=${once(identity)}")
                }
            }
            "throttle" -> {
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val ps = PassportClient.fromJson(base.readText())
                val videoId = arg("--video") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7486311576044047385"
                val rounds = (arg("--rounds") ?: "4").toInt()
                val gap = (arg("--gap") ?: "5000").toLong()
                val cookieLine = ps.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
                fun build(login: Boolean): ContentRepository {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                        .cookieJar(MemoryCookies())
                        .apply {
                            // 换出口 IP 用：判定「空响应」是身份级还是 IP 级（本机代理 7890）。
                            arg("--proxy")?.let { p ->
                                val host = p.substringBefore(':')
                                val port = p.substringAfter(':', "7890").toIntOrNull() ?: 7890
                                proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
                            }
                            if (login) addInterceptor { chain ->
                                val request = chain.request()
                                val merged = listOf(request.header("Cookie").orEmpty(), cookieLine)
                                    .filter { it.isNotBlank() }.joinToString("; ")
                                val builder = request.newBuilder()
                                if (merged.isNotBlank()) builder.header("Cookie", merged)
                                if (ps.token.isNotBlank()) builder.header("X-Tt-Token", ps.token)
                                chain.proceed(builder.build())
                            }
                        }
                        .build()
                    // 登录身份：设备 id 必须与下发 Cookie 的那台一致，否则服务端视同匿名。
                    val session = if (login) PlaybackDeviceSession { _, _ ->
                        DeviceRegister.Registered(ps.deviceId, ps.installId, cookies = ps.cookies)
                    } else PlaybackDeviceSession()
                    return ContentRepository(client, playbackSession = session)
                }
                val anon = build(false)
                val auth = build(true)
                fun probe(repo: ContentRepository): String = try {
                    val model = repo.videoModel(videoId, 3)
                    val ok = model.toString().contains("http")
                    "成功 url=${ok}"
                } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message?.take(40)}" }
                // 冷却状态是进程级共享的，交替调用会 A 臂失败后把 B 臂直接挡在守卫上；
                // 因此单臂模式一进程一请求，由外部脚本交替编排。
                arg("--arm")?.let { arm ->
                    if (args.contains("--debug")) ContentRepository.probeLog = { println("   · $it") }
                    println("[$arm] ${probe(if (arm == "login") auth else anon)}")
                    return
                }
                println("== 对照 id=$videoId rounds=$rounds gap=${gap}ms 登录态=${ps.loggedIn}")
                var anonFail = 0
                for (round in 1..rounds) {
                    val a = probe(anon)
                    val b = probe(auth)
                    println("  #$round 匿名→ $a    |    登录→ $b")
                    if (a.startsWith("成功").not()) anonFail++
                    if (round < rounds) Thread.sleep(gap)
                    if (anonFail >= 2 && round >= 2) { println("  （匿名连续失败，提前收敛，避免继续放大风控）"); break }
                }
            }
            "froms" -> {
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val client = PassportClient(http, PassportClient.fromJson(base.readText()))
                val bookId = arg("--book") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7706512650816982040"
                for (from in listOf("select_panel_recommend", "select_panel_related", "video_detail", "detail_recommend",
                    "book_detail", "select_panel_series", "reading_recommend", "video_detail_end", "select_panel_feed")) {
                    val raw = try { client.planRaw(bookId, from) } catch (e: Exception) { println("  $from 失败 ${e.message}"); continue }
                    val cells = raw.optJSONArray("data") ?: raw.optJSONObject("data")?.optJSONArray("data") ?: org.json.JSONArray()
                    val info = (0 until cells.length()).mapNotNull { cells.optJSONObject(it)?.let { c -> "${c.optInt("show_type")}:${c.str("cell_name")}" } }
                    println("  from=$from code=${raw.optInt("code", -1)} cells=$info")
                }
            }
            "raw" -> {
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val client = PassportClient(http, PassportClient.fromJson(base.readText()))
                val bookId = arg("--book") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7706512650816982040"
                val raw = client.planRaw(bookId, arg("--from") ?: "select_panel_recommend")
                java.io.File(".workbuddy-plan-raw.json").writeText(raw.toString())
                println("已写入 .workbuddy-plan-raw.json 长度=${raw.toString().length}")
                println(raw.toString().take(1200))
            }
            "deep" -> {
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val client = PassportClient(http, PassportClient.fromJson(base.readText()))
                println("登录态=${client.snapshot().loggedIn}")
                val seed = arg("--book") ?: args.getOrNull(args.indexOf("--passport") + 2) ?: "7706512650816982040"
                val ids = LinkedHashSet<String>()
                val wantKeys = if (args.contains("--series")) setOf("series_id") else setOf("book_id", "vid", "series_id", "bookId")
                fun harvest(value: Any?) {
                    when (value) {
                        is org.json.JSONObject -> value.keys().forEach { key ->
                            if (key in wantKeys) {
                                value.opt(key)?.toString()?.takeIf { it.length > 10 }?.let { ids += it }
                            } else harvest(value.opt(key))
                        }
                        is org.json.JSONArray -> for (i in 0 until value.length()) harvest(value.opt(i))
                    }
                }
                harvest(client.planRaw(seed, "select_panel_recommend"))
                println("候选 ${ids.size} 部")
                var hit = false
                for (id in ids.take(12)) {
                    if (hit) break
                    val raw = try { client.planRaw(id, "select_panel_recommend") } catch (e: Exception) { println("  $id 失败 ${e.message}"); continue }
                    val cells = raw.optJSONArray("data") ?: raw.optJSONObject("data")?.optJSONArray("data") ?: org.json.JSONArray()
                    val types = (0 until cells.length()).mapNotNull { cells.optJSONObject(it)?.optInt("show_type") }
                    println("  $id code=${raw.optInt("code", -1)} cells=$types")
                    if (types.contains(160)) { hit = true; dumpPlan(client, id, "★160 命中", PassportClient.CONTENT_HOST) }
                }
                if (!hit) println("（本轮未见 160）")
            }
            "plan" -> {
                val bookId = arg("--book") ?: args.getOrNull(args.indexOf("--passport") + 2)
                    ?: run { println("用法: --passport plan <bookId>"); return }
                val base = java.io.File(sessionFile).takeIf { it.exists() }
                    ?: run { println("缺会话文件 $sessionFile：先跑 --passport login"); return }
                val client = PassportClient(http, PassportClient.fromJson(base.readText()))
                val host = if (arg("--host") == "a") "https://api5-normal-sinfonlinea.fqnovel.com" else PassportClient.CONTENT_HOST
                println("会话: device_id=${client.snapshot().deviceId} 登录态=${client.snapshot().loggedIn}")
                dumpPlan(client, bookId, "相关推荐", host)
                dumpPlan(client, bookId, "系列剧", host)
            }
            else -> println("未知动作: $action（register|send|login|plan）")
        }
    } catch (e: Exception) {
        println("失败: ${e.javaClass.simpleName} ${e.message}")
    } finally {
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }
}
