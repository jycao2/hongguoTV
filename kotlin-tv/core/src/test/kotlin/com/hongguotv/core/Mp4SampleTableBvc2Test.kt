// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 融合方案阶段 5 回归：Mp4SampleTable.isBvc2（Bvc2Extractor.sniff 的判据）。
 * 用真实《迁魂》EP35 360p 标准样本（bytevc2_decoder 仓库，不入库、不 push）。
 *
 *  - 原始加密态 stsd entry = "encv" + frma="bvc2" → 命中（方案坑 #9）；
 *  - MediaCrypto.prepare 还原后 entry = "bvc2" → 命中；
 *  - 普通 hvc1 集 → 不命中（方案 5.3「普通集不能被误接管」）。
 */
class Mp4SampleTableBvc2Test {

    private val sample = File(
        "D:/code/hongguoTV_new/bytevc2_decoder/tests/samples/standard_qianhun_ep35_360p.mdl"
    )

    private fun header(): ByteArray {
        if (!sample.exists()) return ByteArray(0)
        val d = sample.readBytes()
        val ftyp = u32(d, 0)
        val moov = u32(d, ftyp.toInt())
        return d.copyOfRange(0, (ftyp + moov).toInt())
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toInt() and 0xff).toLong() shl 24) or
            ((b[at + 1].toInt() and 0xff).toLong() shl 16) or
            ((b[at + 2].toInt() and 0xff).toLong() shl 8) or
            (b[at + 3].toInt() and 0xff).toLong()

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        var i = 0
        while (i + pattern.size <= data.size) {
            var ok = true
            for (k in pattern.indices) if (data[i + k] != pattern[k]) { ok = false; break }
            if (ok) return i
            i++
        }
        return -1
    }

    @Test fun encryptedEncvWithFrmaBvc2IsDetected() {
        val h = header()
        if (h.isEmpty()) return  // 样本不在（换机器）时跳过
        assertTrue("真实 bytevc2 加密样本(encv+frma=bvc2)必须命中", Mp4SampleTable.isBvc2(h))
    }

    @Test fun prepareRestoredBvc2EntryIsDetected() {
        val h = header()
        if (h.isEmpty()) return
        val stsd = indexOf(h, "stsd".toByteArray())
        assertTrue("样本应含 stsd", stsd >= 0)
        val restored = h.copyOf()
        "bvc2".toByteArray().copyInto(restored, stsd + 16)
        assertTrue("还原后的 bvc2 entry 必须命中", Mp4SampleTable.isBvc2(restored))
    }

    @Test fun normalHvc1SampleIsNotTakenOver() {
        val h = header()
        if (h.isEmpty()) return
        val stsd = indexOf(h, "stsd".toByteArray())
        assertTrue("样本应含 stsd", stsd >= 0)
        val hvc1 = h.copyOf()
        "hvc1".toByteArray().copyInto(hvc1, stsd + 16)
        // 第二分支只在 entry=="encv" 时检查 frma，改成 hvc1 后应为 false。
        assertFalse("普通 hvc1 集不能被接管", Mp4SampleTable.isBvc2(hvc1))
    }

    @Test fun noStsdOrGarbageBytesAreNotTakenOver() {
        assertFalse("无 stsd 的空头不能命中", Mp4SampleTable.isBvc2(ByteArray(64)))
        // 随机字节里偶然出现 "bvc2" 不该误命中（精确 4CC 匹配的价值）
        val garbage = ByteArray(256) { (it * 7 and 0xff).toByte() }
        "bvc2".toByteArray().copyInto(garbage, 100)
        assertFalse("随机字节里的 bvc2 字样不能误命中", Mp4SampleTable.isBvc2(garbage))
    }

    /** 构造一个纯 hvc1 的 ftyp+moov（无任何 bvc2/frma 痕迹），模拟真·普通集。 */
    @Test fun pureHvc1HeaderIsNotTakenOver() {
        val moov = ByteArray(64)
        fun put(at: Int, s: String) { s.toByteArray().copyInto(moov, at) }
        // stsd 4CC 在 moov[8]，则 entryTypeAt = 8+16 = 24
        put(8, "stsd")
        put(24, "hvc1")
        val ftyp = ByteArray(16)
        "ftyp".toByteArray().copyInto(ftyp, 4)
        val header = ftyp + moov
        assertFalse("纯 hvc1 头(无 bvc2 任何痕迹)不能被接管", Mp4SampleTable.isBvc2(header))
    }
}
