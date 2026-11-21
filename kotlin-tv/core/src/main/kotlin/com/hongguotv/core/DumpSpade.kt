package com.hongguotv.core

import org.json.JSONObject

/**
 * 诊断工具：dump 播放模型里所有清晰度分档的原始字段，重点提取 spade_a / codec_type / main_url。
 * 用途：手机版 stream() 会主动过滤 bytevc2 分档，导致「本集片源暂不兼容」。
 *      本工具绕过过滤，直接把服务端下发的 bytevc2 分档 spade_a 捞出来，供 CENC 解密。
 *
 * 用法（在 kotlin-tv 目录）：
 *   ./core/build/install/core/bin/core-dump --series <seriesId>            # 扫描全部集
 *   ./core/build/install/core/bin/core-dump --series <seriesId> --ep 0     # 只扫第 N 集
 */
fun mainDump(args: Array<String>) {
    if (args.contains("--verbose")) ContentRepository.probeLog = { println("probe: $it") }
    // --find <keyword>: search series and print id/title pairs (entry point for --series below).
    if (args.contains("--find")) {
        val repository = ContentRepository()
        try {
            val kw = args.getOrNull(args.indexOf("--find") + 1) ?: "迁魂"
            val res = repository.search(kw)
            println("keyword=$kw hits=${res.items.size}")
            res.items.take(8).forEach { println("${it.id}\t${it.title}") }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    // --levels: scan device_level 1..6, report codec distribution per level.
    // Answers whether the server can serve a TV-decodable (hvc1/avc) variant at all.
    if (args.contains("--levels")) { mainLevels(args); return }
    // --ladder <episodeId>: print the cached quality ladder (normal tiers desc + bvc2 fallback last).
    if (args.contains("--ladder")) {
        val repository = ContentRepository()
        try {
            val ep = args.getOrNull(args.indexOf("--ladder") + 1) ?: error("缺少 --ladder <episodeId>")
            val tiers = repository.ladder(ep)
            println("ladder ep=$ep tiers=${tiers.size} (普通档在前高→低，bytevc2 垫底)")
            tiers.forEachIndexed { i, t ->
                println("%d. %-6s codec=%-8s %s".format(i + 1, t.quality, t.codec.ifEmpty { "?" },
                    t.url.take(48) + "...(len=${t.url.length}) key=${if (t.key != null) "有" else "无"}"))
            }
            println("二次调用（应零请求命中缓存）：${repository.cachedLadder(ep)?.size} tiers")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val seriesId = args.getOrNull(args.indexOf("--series") + 1)
        ?: error("缺少 --series <seriesId>")
    val onlyEp = args.getOrNull(args.indexOf("--ep") + 1)?.toIntOrNull()
    val repository = ContentRepository()
    try {
        val detail = repository.detail(seriesId)
        println("series=${detail.series.title}; episodes=${detail.episodes.size}")
        val target = if (onlyEp != null) listOf(detail.episodes.getOrElse(onlyEp) { detail.episodes.last() })
                     else detail.episodes
        for ((idx, ep) in target.withIndex()) {
            val epNo = if (onlyEp != null) onlyEp else idx
            try {
                val model = repository.videoModel(ep)
                val raw = model.opt("fallback_api")
                val fallback = when (raw) {
                    is JSONObject -> raw.str("fallback_api")
                    is org.json.JSONArray -> raw.optString(0)
                    else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }
                        .getOrDefault(raw.toString())
                }
                if (!fallback.startsWith("https://")) {
                    println("EP[$epNo] id=$ep fallback=INVALID(${fallback.take(40)})")
                    continue
                }
                val text = repository.http.newCall(okhttp3.Request.Builder().url(fallback).build())
                    .execute().use { it.body?.string().orEmpty() }
                // 命中通窗口时把完整 fallback 响应落盘，供离线消费（避免再次丢失）
                if (args.contains("--save")) {
                    val dir = java.io.File("dump_capture"); dir.mkdirs()
                    java.io.File(dir, "series_${seriesId}_ep${epNo}.json").writeText(text)
                }
                val info = JSONObject(text).optJSONObject("video_info")?.optJSONObject("data")
                val videoList = info?.optJSONObject("video_list")
                if (videoList == null) {
                    println("EP[$epNo] id=$ep video_list=EMPTY")
                    continue
                }
                val rows = videoList.keys().asSequence().mapNotNull { k ->
                    videoList.optJSONObject(k)?.let { k to it }
                }.toList()
                val bvc = rows.filter { o ->
                    o.second.str("codec_type").lowercase() in setOf("bytevc2", "bvc2")
                }
                println("EP[$epNo] id=$ep total=${rows.size} bytevc2=${bvc.size} route=${model.optString("__route")}")
                for ((k, o) in bvc) {
                    val spade = o.str("spade_a")
                    val url = o.str("main_url")
                    val seed = info.str("key_seed")
                    println("  [$k] codec=${o.str("codec_type")} h=${o.str("vheight")}/${o.str("height")}")
                    println("      spade_a=$spade")
                    println("      key_seed=$seed")
                    println("      main_url=${url.take(120)}")
                    // 派生 key 与解密 URL（输出可直接供 ffmpeg 使用）
                    val derived = runCatching {
                        val kb = MediaCrypto.deriveKey(spade)
                        kb.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    }.getOrElse { "ERR:${it.message?.take(40)}" }
                    val plainUrl = runCatching {
                        val seedBytes = runCatching {
                            java.util.Base64.getDecoder().decode(seed.replace('-', '+').replace('_', '/'))
                        }.getOrElse { ByteArray(0) }
                        MediaCrypto.decryptUrl(url, seedBytes)
                    }.getOrElse { "ERR:${it.message?.take(40)}" }
                    println("      DECRYPT_KEY=$derived")
                    println("      PLAIN_URL=$plainUrl")
                }
            } catch (t: Throwable) {
                println("EP[$epNo] id=$ep ERROR=${t.javaClass.simpleName}:${t.message?.take(80)}")
            }
        }
    } finally {
        repository.http.connectionPool.evictAll()
        repository.http.dispatcher.executorService.shutdown()
    }
}

/**
 * --levels <seriesId> [--ep N]: for one episode, sweep device_level 1..6 (and both hosts via
 * tabVideoModel) and dump every codec_type served. Purpose: find out whether the platform has
 * ANY TV-decodable variant (hvc1/avc/h264) for an all-bytevc2 episode, or whether bytevc2 is
 * the only asset that exists. ASCII output only (Windows console mangles CJK).
 */
private fun mainLevels(args: Array<String>) {
    val seriesId = args.getOrNull(args.indexOf("--series") + 1) ?: error("缺少 --series <seriesId>")
    val onlyEp = args.getOrNull(args.indexOf("--ep") + 1)?.toIntOrNull() ?: 0
    val repository = ContentRepository()
    val identity = PlaybackDeviceSession().profile(repository.http)
    try {
        val detail = repository.detail(seriesId)
        val ep = detail.episodes.getOrElse(onlyEp) { detail.episodes.last() }
        println("series=${detail.series.title}; episodes=${detail.episodes.size}; ep=$onlyEp id=$ep")
        for (level in 1..6) {
            for (alt in listOf(false, true)) {
                val model = try { repository.tabVideoModel(ep, identity, level, alt) }
                    catch (t: Throwable) { println("L$level alt=$alt ERROR=${t.javaClass.simpleName}:${t.message?.take(70)}"); continue }
                if (model == null) { println("L$level alt=$alt model=NULL"); continue }
                val raw = model.opt("fallback_api")
                val fallback = when (raw) {
                    is JSONObject -> raw.str("fallback_api")
                    is org.json.JSONArray -> raw.optString(0)
                    else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString())
                }
                if (!fallback.startsWith("https://")) { println("L$level alt=$alt route=${model.optString("__route")} fallback=INVALID"); continue }
                val text = try {
                    repository.http.newCall(okhttp3.Request.Builder().url(fallback).build()).execute().use { it.body?.string().orEmpty() }
                } catch (t: Throwable) { println("L$level alt=$alt fetch ERROR=${t.message?.take(60)}"); continue }
                val info = runCatching { JSONObject(text).optJSONObject("video_info")?.optJSONObject("data") }.getOrNull()
                val vl = info?.optJSONObject("video_list")
                if (vl == null) { println("L$level alt=$alt route=${model.optString("__route")} video_list=EMPTY"); continue }
                val codecs = vl.keys().asSequence().mapNotNull { k -> vl.optJSONObject(k)?.let { k to it } }
                    .joinToString(",") { (k, o) -> "$k=${o.str("codec_type")}@${o.str("vheight").ifEmpty { o.str("height") }}" }
                println("L$level alt=$alt route=${model.optString("__route")} codecs=[$codecs]")
            }
        }
    } finally {
        repository.http.connectionPool.evictAll()
        repository.http.dispatcher.executorService.shutdown()
    }
}
