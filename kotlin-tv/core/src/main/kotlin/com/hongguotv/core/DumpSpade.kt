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
