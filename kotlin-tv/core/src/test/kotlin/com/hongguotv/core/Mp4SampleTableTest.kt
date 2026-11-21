// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 阶段 1 验收：验证 MP4 sample 表解析（ByteVC2 融合路径的 Bvc2Extractor 地基）。
 * 样本位于独立仓库 bytevc2_decoder（不入库、不 push），缺失时整组跳过（Assume），
 * CI / 换机器不受影响。
 *
 * 两组素材，性质完全不同（2026-11-20 用 inspect_moov.py 与 4CC 探针实证）：
 *
 *  A. `tests/samples/standard_qianhun_ep35_360p.mdl`（3.0MB，线上现取，运行时真实流）
 *     moov 是标准 ISO BMFF，inspect_moov.py 能一路 walk 到底。
 *     基准：640x360 / 4005 帧 / 30fps（stts 512@15360）/ 133.5s / 32 关键帧 /
 *           音频 44100Hz / 2878 帧 / 133.537959s；ctts 4005 项（B 帧重排，合法）。
 *     → 融合方案 1.4 / 阶段 1 断言的 4005 帧指的就是它。
 *
 *  B. `tests/samples/real_probe/f4ea...mdl`（7.38MB，早期存档）
 *     moov 被深度混淆：box 间插垃圾字节，且 stsz/ctts 表内容中段就变成 NAL 片段。
 *     标准 size 链走两步就漂（inspect_moov.py 实测 off=280 出现 `\xa7mdi`）。
 *     → 只做「容错不崩、不越界」回归，不做数值基准；真实播放不会遇到它。
 *
 * 解析器策略与 MediaCrypto.find 一致：逐字节搜 4CC，不依赖 size 链。
 */
class Mp4SampleTableTest {

    private val root = File("../../bytevc2_decoder/tests/samples")
    private val standard = File(root, "standard_qianhun_ep35_360p.mdl")
    private val obfuscated = File(root, "real_probe/f4ea5f90332bcc7e78bfd5b2801dfab5.mdl")

    private fun loadTable(file: File): Pair<ByteArray, Long> {
        assumeTrue("样本缺失，跳过集成单测: ${file.path}", file.isFile)
        val total = file.length()
        val head = RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(minOf(raf.length(), 4L * 1024 * 1024).toInt())
            raf.readFully(buf); buf
        }
        val end = MediaCrypto.headerEnd(head, total)
        return head.copyOf(end) to total
    }

    private fun standardTracks() = Mp4SampleTable.parse(loadTable(standard).first, standard.length())

    /** 把解析结果打出来，便于对照探针数据（融合阶段调试用）。 */
    @Test fun dumpParsedTables() {
        val tracks = standardTracks()
        tracks.forEach { t ->
            println("track handler=${t.handler} id=${t.trackId} ${t.width}x${t.height} " +
                "timescale=${t.timescale} durationUs=${t.durationUs} samples=${t.samples.size} " +
                "keys=${t.samples.count { it.isKeyFrame }} " +
                "first=${t.samples.firstOrNull()?.let { "off=${it.offset} size=${it.size} pts=${it.timeUs}" }} " +
                "last=${t.samples.lastOrNull()?.let { "off=${it.offset} pts=${it.timeUs}" }}")
        }
        assertEquals(2, tracks.size)   // 视频 + 音频
    }

    @Test fun videoTrackFrameCountMatchesReference() {
        val video = standardTracks().first { it.handler == "vide" }
        assertEquals(4005, video.samples.size)
        assertEquals(640, video.width)
        assertEquals(360, video.height)
    }

    @Test fun durationMatchesReference() {
        val tracks = standardTracks()
        val video = tracks.first { it.handler == "vide" }
        val audio = tracks.first { it.handler == "soun" }
        // mdhd: 2050560/15360 = 133.5s；音频 5889024/44100 = 133.537959s
        val expectedV = 2050560.0 * 1_000_000 / 15360
        val expectedA = 5889024.0 * 1_000_000 / 44100
        assertTrue("视频时长 ${video.durationUs}us", Math.abs(video.durationUs - expectedV) / expectedV < 0.001)
        assertTrue("音频时长 ${audio.durationUs}us", Math.abs(audio.durationUs - expectedA) / expectedA < 0.001)
        assertTrue("音视频时长应一致", Math.abs(video.durationUs - audio.durationUs) < 50_000)
    }

    @Test fun firstSampleIsKeyframeAndKeyframesAreSparse() {
        val video = standardTracks().first { it.handler == "vide" }
        assertTrue(video.samples[0].isKeyFrame)
        assertEquals("stss 表应有 32 个关键帧", 32, video.samples.count { it.isKeyFrame })
    }

    @Test fun cttsCompositionOffsetsAreApplied() {
        // ctts 4005 项、count 全 1、sum=4005；首项 offset=2560 ticks → 166666us
        val video = standardTracks().first { it.handler == "vide" }
        assertEquals("首个 sample 的 pts 应含 ctts 偏移 2560/15360s",
            2560L * 1_000_000 / 15360, video.samples[0].timeUs)
        // ctts 最大偏移 18432 ticks(1.2s)，B 帧重排使末帧 pts 可超过容器 duration（DTS 总长）
        val max = video.samples.maxOf { it.timeUs }
        assertTrue("最大 pts 异常: $max / ${video.durationUs}",
            max in video.durationUs..(video.durationUs + 2_000_000))
    }

    @Test fun audioTrackTimescaleMatchesAac() {
        val audio = standardTracks().first { it.handler == "soun" }
        assertEquals(44100L, audio.timescale)
        assertEquals(2879, audio.samples.size)
        assertTrue(audio.samples.all { it.isKeyFrame })   // 音频无 stss = 全关键帧
    }

    @Test fun offsetsMonotonicAndWithinFile() {
        val total = standard.length()
        standardTracks().forEach { t ->
            assertTrue("${t.handler} offset 不单调/重叠",
                t.samples.zipWithNext().all { (a, b) -> a.offset + a.size <= b.offset })
            assertTrue("${t.handler} offset 越界",
                t.samples.all { it.offset >= 0 && it.offset + it.size <= total })
        }
    }

    /** 混淆存档样本：moov 被插垃圾字节、stsz 表中段是 NAL 片段。运行时不会遇到，
     *  但解析器必须给出可读的 IOException，而不是 NPE / 静默产出错误表。 */
    @Test fun corruptSampleTableFailsLoudly() {
        val (header, total) = loadTable(obfuscated)
        val e = runCatching { Mp4SampleTable.parse(header, total) }.exceptionOrNull()
        assertTrue("损坏样本应明确报错，实际: $e", e is java.io.IOException)
        assertTrue("错误信息应含样本索引: ${e?.message}", e?.message?.contains("样本") == true)
    }
}
