// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

/**
 * ISO BMFF（MP4）sample 表解析：给定 ftyp+moov 头部字节，产出每条轨的
 * sample 列表（mdat 绝对偏移 / 长度 / 合成时间戳 / 关键帧标记）。
 *
 * 用途：ByteVC2 融合路径的自建 Extractor（Bvc2Extractor）靠它索引整条流，
 * ExoPlayer 的 TrackOutput.sampleMetadata 需要逐 sample 的 pts 与关键帧标记，
 * SeekMap 需要时间↔字节映射。
 *
 * 纯字节解析、零 Android 依赖，放 :core 以便 JVM 单测与 :app 的 Extractor 共用。
 *
 * ⚠️ 红果容器的 moov 有「box 间插入垃圾字节」混淆（2026-11-20 用
 * bytevc2_decoder/scripts/inspect_moov.py 实证：标准 size 链走到 off=280 就出现
 * `\xa7mdi` 这类乱码 fourcc）。因此这里**不按 size 链遍历**，而是与
 * [MediaCrypto.find] 同策略：逐字节搜 4CC，用 size 只做边界校验、不做游标。
 *
 * 真实样本（bytevc2_decoder/tests/samples/real_probe/f4ea...mdl）基准：
 * 1280x720 / 10643 帧 / 30fps / 354.767s / timescale 15360；音频 44100Hz AAC。
 * （方案文档 1.4 写的 4005 帧 / 133.5s / 640x360 是《迁魂》EP35 现取的 360p
 *   临时样本，与本 repo 的 real_probe 样本不是同一个文件，勿混用基准。）
 */
object Mp4SampleTable {

    data class SampleInfo(
        /** 文件坐标（stco 给的就是 mdat 绝对偏移，无需再算）。 */
        val offset: Long,
        val size: Int,
        /** 展示时间戳（pts，含 ctts 偏移），微秒。 */
        val timeUs: Long,
        val isKeyFrame: Boolean
    )

    data class TrackSamples(
        val trackId: Int,
        val handler: String,      // "vide" / "soun"
        val timescale: Long,
        val durationUs: Long,
        val width: Int,
        val height: Int,
        /** 解码顺序（stsz/stts 顺序），不重排。 */
        val samples: List<SampleInfo>
    )

    private const val MAX_SAMPLES = 200_000
    private const val MAX_CHUNKS = 200_000
    private const val MAX_BOX = 8 * 1024 * 1024L

    /** 逐字节找 4CC；返回 type 起点，box 起点 = 返回值 - 4。 */
    private fun indexOf4cc(data: ByteArray, type: String, start: Int, limit: Int): Int {
        val c0 = type[0].code.toByte()
        var at = start.coerceAtLeast(4)
        while (at + 8 <= limit) {
            if (data[at] == c0) {
                var ok = true
                for (k in 1..3) if (data[at + k] != type[k].code.toByte()) { ok = false; break }
                if (ok) return at
            }
            at++
        }
        return -1
    }

    /**
     * 定位一个 box：找到 4CC 后用前 4 字节的 size 做合理性校验，body 起点固定为
     * type+4（不受前面垃圾字节影响）。返回 [body, end)；end 用 size 估算并夹到 limit。
     */
    private fun locate(data: ByteArray, type: String, start: Int, limit: Int): Pair<Int, Int>? {
        var cursor = start
        while (true) {
            val i = indexOf4cc(data, type, cursor, limit)
            if (i < 4) return null
            cursor = i + 1
            val size = data.u32(i - 4)
            if (size < 8 || size > MAX_BOX) continue
            val body = i + 4
            val end = minOf((i - 4) + size.toInt(), limit)
            if (end <= body) continue
            return body to end
        }
    }

    /** 找出范围内所有同名 box 的 [body,end)，用于遍历 trak。 */
    private fun locateAll(data: ByteArray, type: String, start: Int, limit: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var cursor = start
        while (true) {
            val hit = locate(data, type, cursor, limit) ?: break
            out += hit
            cursor = hit.first + 4
        }
        return out
    }

    /**
     * 解析明文 MP4 头部（要求 moov 在文件头，与 [MediaCrypto.headerEnd] 同一假设）。
     * @param header ftyp+moov 的完整字节（mdat 可以不含）
     * @param totalSize 完整文件大小，用于校验 offset+size 不越界
     */
    fun parse(header: ByteArray, totalSize: Long): List<TrackSamples> {
        if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") throw IOException("不支持的视频容器")
        val moovAt = header.u32(0).toInt()
        val moov = MediaCrypto.box(header, moovAt) ?: throw IOException("视频索引不完整")
        require(moov.type == "moov")
        val tracks = mutableListOf<TrackSamples>()
        for ((trakBody, trakEnd) in locateAll(header, "trak", moov.body, moov.end)) {
            val hdlr = locate(header, "hdlr", trakBody, trakEnd) ?: continue
            val handler = if (hdlr.first + 12 <= hdlr.second)
                String(header, hdlr.first + 8, 4, Charsets.US_ASCII) else ""
            if (handler != "vide" && handler != "soun") continue
            val mdhd = locate(header, "mdhd", trakBody, trakEnd) ?: throw IOException("轨道时长缺失")
            val version = header[mdhd.first].toInt()
            val timescale = if (version == 1) header.u32(mdhd.first + 20) else header.u32(mdhd.first + 12)
            val duration = if (version == 1) header.i64(mdhd.first + 24) else header.u32(mdhd.first + 16)
            if (timescale <= 0) throw IOException("时间尺度异常")
            var trackId = 0; var width = 0; var height = 0
            locate(header, "tkhd", trakBody, trakEnd)?.let { tkhd ->
                val v = header[tkhd.first].toInt()
                trackId = header.u32(tkhd.first + if (v == 1) 20 else 12).toInt()
                if (handler == "vide") {
                    // tkhd v0: 4(ver/flags)+20(id区)+8(reserved)+8(layer..)+36(matrix) = 76
                    val dimAt = tkhd.first + if (v == 1) 88 else 76
                    width = (header.u32(dimAt) / 65536L).toInt()
                    height = (header.u32(dimAt + 4) / 65536L).toInt()
                }
            }
            tracks += TrackSamples(
                trackId = trackId, handler = handler, timescale = timescale,
                durationUs = duration * 1_000_000 / timescale,
                width = width, height = height,
                samples = readSamples(header, trakBody, trakEnd, timescale, totalSize)
            )
        }
        if (tracks.isEmpty()) throw IOException("无可用轨道")
        return tracks
    }

    private fun readSamples(
        header: ByteArray, start: Int, limit: Int, timescale: Long, totalSize: Long
    ): List<SampleInfo> {
        fun count(at: Int, end: Int, what: String): Int {
            if (at + 4 > end) throw IOException("$what 越界")
            return header.u32(at).also { if (it > MAX_SAMPLES) throw IOException("$what 数量过大") }.toInt()
        }

        // stsz：sample 尺寸表
        val stsz = locate(header, "stsz", start, limit) ?: throw IOException("样本索引缺失")
        val n = count(stsz.first + 8, stsz.second, "样本数")
        val defaultSize = header.u32(stsz.first + 4)
        if (defaultSize == 0L && stsz.first + 12 + n.toLong() * 4 > stsz.second) throw IOException("样本索引截断")

        // stsc：chunk→sample 映射
        val stsc = locate(header, "stsc", start, limit) ?: throw IOException("块索引缺失")
        val entryCount = count(stsc.first + 4, stsc.second, "块映射")
        if (entryCount == 0 || stsc.first + 8 + entryCount.toLong() * 12 > stsc.second) throw IOException("块索引截断")
        val firstChunk = IntArray(entryCount) { header.u32(stsc.first + 8 + it * 12).toInt() }
        val perChunk = IntArray(entryCount) { header.u32(stsc.first + 12 + it * 12).toInt() }

        // stco / co64：chunk 文件偏移
        val stco = locate(header, "stco", start, limit)
        val co64 = if (stco == null) locate(header, "co64", start, limit) else null
        if (stco == null && co64 == null) throw IOException("块偏移缺失")
        val chunk = stco ?: co64!!
        val widthOf = if (stco != null) 4 else 8
        val chunkCount = count(chunk.first + 4, chunk.second, "块数")
        if (chunkCount > MAX_CHUNKS) throw IOException("块数量过大")
        if (chunk.first + 8 + chunkCount.toLong() * widthOf > chunk.second) throw IOException("块偏移截断")
        val offsets = LongArray(chunkCount) { c ->
            if (widthOf == 4) header.u32(chunk.first + 8 + c * 4) else header.i64(chunk.first + 8 + c * 8)
        }

        // stts：(sample_count, delta) 对 → dts 累加
        val stts = locate(header, "stts", start, limit) ?: throw IOException("时间戳索引缺失")
        val sttsCount = count(stts.first + 4, stts.second, "时长项")
        if (stts.first + 8 + sttsCount.toLong() * 8 > stts.second) throw IOException("时间戳索引截断")
        val sttsCnt = IntArray(sttsCount) { header.u32(stts.first + 8 + it * 8).toInt() }
        val sttsDelta = LongArray(sttsCount) { header.u32(stts.first + 12 + it * 8) }

        // ctts（可选）：B 帧的 pts 偏移；version 0 无符号、version 1 有符号。
        // ⚠️ 混淆样本实测（f4ea...mdl @1500）：ctts box 头合法（entry_count=10643）
        // 但内容是垃圾 —— 出现 count=0 / count=0x0A000000 / off=0x01000048 这类非法值，
        // 与 moov 里四处插入的垃圾字节同源。而 stts 恒定 (10643,512)@15360=30fps，
        // bvc2 靠帧内 VPS/SPS/PPS 自初始化、无 B 帧（方案 1.3），ctts 本就不该参与。
        // 因此这里做合法性校验：任一 entry 越界就整体忽略该表，pts = dts。
        // 后续融合阶段 output.timeUs 以解码器 frame->pts 为准（方案 6 号坑），不依赖 ctts。
        val ctts = locate(header, "ctts", start, limit)
        var cttsCnt = IntArray(0); var cttsOff = LongArray(0)
        if (ctts != null) {
            val c = count(ctts.first + 4, ctts.second, "偏移项")
            if (ctts.first + 8 + c.toLong() * 8 > ctts.second) throw IOException("偏移索引截断")
            val signed = header[ctts.first].toInt() == 1
            val cnt = IntArray(c) { header.u32(ctts.first + 8 + it * 8).toInt() }
            val off = LongArray(c) {
                val raw = header.u32(ctts.first + 12 + it * 8)
                if (signed) raw.toLong() else raw
            }
            val sane = cnt.all { it in 1..n } && cnt.sum() == n &&
                off.all { Math.abs(it) <= timescale * 10 }
            if (sane) { cttsCnt = cnt; cttsOff = off }
        }

        // stss（可选）：1-based 关键帧序号；缺失 = 全关键帧
        val stss = locate(header, "stss", start, limit)
        var keys = IntArray(0)
        if (stss != null) {
            val k = count(stss.first + 4, stss.second, "关键帧")
            if (stss.first + 8 + k.toLong() * 4 > stss.second) throw IOException("关键帧索引截断")
            keys = IntArray(k) { header.u32(stss.first + 8 + it * 4).toInt() }
        }
        var keyPtr = 0

        val out = ArrayList<SampleInfo>(n)
        var dts = 0L
        var deltaCur = 0L
        var sttsIdx = 0; var sttsLeft = 0
        var cttsIdx = 0; var cttsLeft = 0
        var sampleIndex = 0; var entry = 0
        for (chunkIdx in offsets.indices) {
            while (entry + 1 < entryCount && firstChunk[entry + 1] <= chunkIdx + 1) entry++
            if (firstChunk[entry] > chunkIdx + 1) throw IOException("样本与块数量不一致")
            val inChunk = perChunk[entry]
            if (inChunk <= 0) throw IOException("块内样本数异常")
            var position = offsets[chunkIdx]
            repeat(inChunk) {
                if (sampleIndex >= n) return@repeat
                val sz = if (defaultSize > 0) defaultSize else header.u32(stsz.first + 12 + sampleIndex * 4)
                if (sz <= 0 || position < 0 || position + sz > totalSize)
                    throw IOException("样本越界 idx=$sampleIndex pos=$position sz=$sz total=$totalSize default=$defaultSize")
                // dts：ISO BMFF 里 dts(0)=0，sample i 结束时才累加 delta(i)
                while (sttsLeft == 0) {
                    if (sttsIdx >= sttsCount) throw IOException("时间戳不足")
                    sttsLeft = sttsCnt[sttsIdx]
                    deltaCur = sttsDelta[sttsIdx]
                    if (sttsLeft <= 0) throw IOException("时间戳计数异常")
                    sttsIdx++
                }
                var pts = dts
                if (cttsCnt.isNotEmpty()) {
                    while (cttsLeft == 0) {
                        if (cttsIdx >= cttsCnt.size) throw IOException("偏移不足")
                        cttsLeft = cttsCnt[cttsIdx]
                        if (cttsLeft <= 0) throw IOException("偏移计数异常")
                        cttsIdx++
                    }
                    cttsLeft--
                    pts += cttsOff[cttsIdx - 1]
                }
                val key = stss == null || (keyPtr < keys.size && keys[keyPtr] == sampleIndex + 1).also {
                    if (it) keyPtr++
                }
                out += SampleInfo(position, sz.toInt(), pts * 1_000_000 / timescale, key)
                position += sz; sampleIndex++
                dts += deltaCur; sttsLeft--
            }
        }
        if (sampleIndex != n) throw IOException("样本不完整 (${sampleIndex}/$n)")
        return out
    }

    /**
     * 纯函数：判断 ftyp+moov 里的视频 sample entry 是否为 bvc2（融合方案 Extractor 的 sniff 判据）。
     * 用「4CC 精确匹配」而非 String.contains——随机字节里偶然出现 "bvc2" 会误命中。
     *
     * 命中条件（覆盖两种形态）：
     *  - stsd 第一个 entry 的 4CC == "bvc2"（MediaCrypto.prepare 还原 encv 之后，运行时实际形态）；
     *  - 或 entry == "encv" 且同段 frma 的 body == "bvc2"（原始加密态，方案坑 #9）。
     * 普通集 hvc1/avc1/hev1 一律 false。
     */
    fun isBvc2(header: ByteArray): Boolean {
        val stsd = indexOf4cc(header, "stsd", 0, header.size)
        if (stsd < 4) return false
        // stsd 4CC 起点 +4 = body；body: version/flags(4)+entry_count(4)；
        // 第一个 entry: size(4)+type(4)。故 type 4CC 在 stsd+4+8+4 = stsd+16。
        val entryTypeAt = stsd + 16
        if (entryTypeAt + 4 > header.size) return false
        val entryType = String(header, entryTypeAt, 4, Charsets.US_ASCII)
        if (entryType == "bvc2") return true
        if (entryType == "encv") {
            val frma = indexOf4cc(header, "frma", entryTypeAt, header.size)
            if (frma >= 4 && frma + 8 <= header.size &&
                String(header, frma + 4, 4, Charsets.US_ASCII) == "bvc2") return true
        }
        return false
    }
}
