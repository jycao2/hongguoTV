// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAdFilterTest {

    private fun playlist(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `没有广告时返回 null`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXTINF:3.0,",
            "0000001.ts",
            "#EXTINF:3.0,",
            "0000002.ts",
            "#EXT-X-ENDLIST"
        )
        assertNull(HlsAdFilter.filter(text))
    }

    @Test
    fun `非清单文本返回 null`() {
        assertNull(HlsAdFilter.filter("<html>not a playlist</html>"))
    }

    @Test
    fun `中间插播的广告段被整段删除且合并 discontinuity`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXTINF:3.0,",
            "0000098.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "/video/adjump/time/1.ts",
            "#EXTINF:3.0,",
            "/video/adjump/time/2.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "0000099.ts",
            "#EXT-X-ENDLIST"
        )
        val out = HlsAdFilter.filter(text)!!
        assertTrue(out.contains("0000098.ts"))
        assertTrue(out.contains("0000099.ts"))
        assertTrue(!out.contains("adjump"))
        // 广告前的 discontinuity 已删除，只保留广告后那一个
        assertEquals(1, out.split("#EXT-X-DISCONTINUITY").size - 1)
        // 正片顺序不能乱
        assertTrue(out.indexOf("0000098.ts") < out.indexOf("0000099.ts"))
    }

    @Test
    fun `片头的广告被删除`() {
        val text = playlist(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "/video/adjump/time/1.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "0000001.ts",
            "#EXT-X-ENDLIST"
        )
        val out = HlsAdFilter.filter(text)!!
        assertTrue(!out.contains("adjump"))
        assertTrue(out.contains("0000001.ts"))
        assertTrue(out.startsWith("#EXTM3U"))
    }

    @Test
    fun `多段广告全部删除`() {
        val text = playlist(
            "#EXTM3U",
            "#EXTINF:3.0,",
            "0000001.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "/video/adjump/time/a.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "0000002.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "/video/adjump/time/b.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "0000003.ts",
            "#EXT-X-ENDLIST"
        )
        val out = HlsAdFilter.filter(text)!!
        assertTrue(!out.contains("adjump"))
        assertTrue(out.contains("0000001.ts"))
        assertTrue(out.contains("0000002.ts"))
        assertTrue(out.contains("0000003.ts"))
    }

    @Test
    fun `名字接近的正常分片不会被误删`() {
        val text = playlist(
            "#EXTM3U",
            "#EXTINF:3.0,",
            "adjump_overlay.ts",
            "#EXTINF:3.0,",
            "0000100.ts",
            "#EXT-X-ENDLIST"
        )
        assertNull(HlsAdFilter.filter(text))
    }

    @Test
    fun `块内注释行不影响后续分片识别`() {
        val text = playlist(
            "#EXTM3U",
            "#EXTINF:3.0,",
            "0000001.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "/video/adjump/time/a.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:3.0,",
            "0000002.ts",
            "#EXT-X-ENDLIST"
        )
        val out = HlsAdFilter.filter(text)!!
        assertEquals(1, out.split("#EXT-X-DISCONTINUITY").size - 1)
    }
}
