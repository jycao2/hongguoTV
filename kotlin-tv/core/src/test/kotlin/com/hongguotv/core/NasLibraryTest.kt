// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NasLibraryTest {

    private fun file(path: String, size: Long = 1000) = NasLibrary.NasEntry(path, false, size)

    private fun organize(vararg paths: String) =
        NasLibrary.organize(paths.map { file(it) })

    // ---------- 集号识别 ----------

    @Test
    fun `中文集号`() {
        val r = organize("都市之最强律师/都市之最强律师 第06集.mkv", "都市之最强律师/都市之最强律师 第07集.mkv")
        assertEquals(1, r.size)
        assertEquals("都市之最强律师", r[0].title)
        assertEquals(NasLibrary.Kind.SERIES, r[0].kind)
        assertEquals(listOf(6, 7), r[0].seasons[0].episodes.map { it.episode })
    }

    @Test
    fun `西式季集加季目录`() {
        val r = organize(
            "Show/Season 2/Show.S02E11.1080p.WEB-DL.mkv",
            "Show/Season 2/Show.S02E12.1080p.WEB-DL.mkv",
        )
        assertEquals("Show", r[0].title)
        assertEquals(2, r[0].seasons[0].season)
        assertEquals(listOf(11, 12), r[0].seasons[0].episodes.map { it.episode })
    }

    @Test
    fun `EP前缀`() {
        val r = organize("少年白马行/少年白马行 EP18 [x265].mkv", "少年白马行/少年白马行 EP19.mkv")
        assertEquals(listOf(18, 19), r[0].seasons[0].episodes.map { it.episode })
    }

    @Test
    fun `方括号集号`() {
        val r = organize("猫町物语/猫町物语 [08].mkv", "猫町物语/猫町物语 [09].mkv")
        assertEquals(listOf(8, 9), r[0].seasons[0].episodes.map { it.episode })
    }

    @Test
    fun `末尾数字`() {
        val r = organize("山海故人/山海故人 12.mkv", "山海故人/山海故人 13.mkv")
        assertEquals(listOf(12, 13), r[0].seasons[0].episodes.map { it.episode })
    }

    @Test
    fun `中文数字集号`() {
        val r = organize("灵剑尊/灵剑尊 第十二集 1080P.mkv", "灵剑尊/灵剑尊 第三十五集.mkv")
        assertEquals(listOf(12, 35), r[0].seasons[0].episodes.map { it.episode })
    }

    // ---------- 电影与散放文件 ----------

    @Test
    fun `单文件目录为电影`() {
        val r = organize("深海纪元/深海纪元.2024.2160p.BluRay.mkv")
        assertEquals(1, r.size)
        assertEquals(NasLibrary.Kind.MOVIE, r[0].kind)
        assertEquals("深海纪元", r[0].title)
        assertEquals(1, r[0].seasons[0].episodes[0].episode)
    }

    @Test
    fun `根目录散放文件各自为电影`() {
        val r = organize("城市边缘.1080p.mkv", "极地纪录 冰川.mkv")
        assertEquals(2, r.size)
        assertTrue(r.all { it.kind == NasLibrary.Kind.MOVIE })
        assertEquals("城市边缘", r[0].title)
        assertEquals("极地纪录 冰川", r[1].title)
    }

    // ---------- 日期分组 ----------

    @Test
    fun `日期型不归集号按日期排序`() {
        val r = organize("综艺大观/综艺大观 2024.01.15.mkv", "综艺大观/综艺大观 2024.01.08.mkv")
        val eps = r[0].seasons.flatMap { it.episodes }
        assertEquals(2, eps.size)
        assertTrue(eps.all { it.episode == null })
        assertEquals(listOf("2024-01-08", "2024-01-15"), eps.map { it.date })
    }

    // ---------- 多版本去重 ----------

    @Test
    fun `同集多版本取高版本高分辨率`() {
        val lo = "都市之最强律师/都市之最强律师 第06集 1080p.mkv"
        val hi = "都市之最强律师/都市之最强律师 第06集 v2 2160p.mkv"
        val r = organize(lo, hi, "都市之最强律师/都市之最强律师 第07集.mkv")
        val eps = r[0].seasons[0].episodes
        assertEquals(2, eps.size)
        assertEquals(hi, eps[0].file.path)
    }

    @Test
    fun `同版本取分辨率高者`() {
        val hd = "A/A 第01集 1080p.mkv"
        val sd = "A/A 第01集 720p.mkv"
        val r = organize(sd, hd, "A/A 第02集.mkv")
        assertEquals(hd, r[0].seasons[0].episodes[0].file.path)
    }

    // ---------- 防误判 ----------

    @Test
    fun `集号超界不识别`() {
        // 第1080集：1080 > 999 视为不可信；目录 2 个文件不足降级阈值，仍出 SERIES，但集号为 null
        val r = organize("B/B 第1080集.mkv", "B/B 第02集.mkv")
        val eps = r[0].seasons[0].episodes
        assertEquals(listOf(2, null), eps.map { it.episode })
        assertEquals("B 第1080集", eps[1].display)
    }

    @Test
    fun `分辨率数字不当集号`() {
        val r = organize("C/C 1080p.mkv", "C/C 720p.mkv", "C/C 第03集.mkv", "C/C 第04集.mkv")
        // 两个纯分辨率文件无法识别（2/4 = 50%，不超过半数，不降级），兜底排序在尾部
        val eps = r[0].seasons[0].episodes
        assertEquals(listOf(3, 4, null, null), eps.map { it.episode })
    }

    // ---------- 降级与过滤 ----------

    @Test
    fun `超半数无法识别降级为文件列表`() {
        val r = organize("D/D abc.mkv", "D/D def.mkv", "D/D ghi.mkv", "D/D 第01集.mkv")
        assertEquals(NasLibrary.Kind.FILES, r[0].kind)
        assertEquals("文件列表", r[0].seasons[0].label)
    }

    @Test
    fun `非视频与样片跳过`() {
        val r = organize(
            "E/E 第01集.mkv", "E/E 第02集.mkv",
            "E/E 第01集.srt", "E/cover.jpg", "E/sample.mkv", "E/预告.mkv", "E/.hidden.mkv",
        )
        assertEquals(1, r.size)
        assertEquals(2, r[0].episodeCount)
    }

    @Test
    fun `系统目录跳过`() {
        val r = organize(
            "F/F 第01集.mkv", "F/F 第02集.mkv",
            "@eaDir/junk.mkv", "#recycle/old.mkv",
        )
        assertEquals(1, r.size)
        assertEquals("F", r[0].title)
    }

    // ---------- 季与特别篇 ----------

    @Test
    fun `多季合并且特别篇排后`() {
        val r = organize(
            "G/Season 1/G 第01集.mkv", "G/Season 1/G 第02集.mkv",
            "G/Season 2/G 第01集.mkv",
            "G/SP/G 特别篇01.mkv",
        )
        assertEquals(listOf(1, 2, NasLibrary.SEASON_SPECIAL), r[0].seasons.map { it.season })
        assertEquals("特别篇", r[0].seasons[2].label)
    }

    @Test
    fun `季目录与正片混合时正片并入第一季`() {
        val r = organize(
            "H/H 第01集.mkv", "H/H 第02集.mkv",
            "H/第二季/H 第01集.mkv",
        )
        assertEquals(listOf(1, 2), r[0].seasons.map { it.season })
        assertEquals(2, r[0].seasons[0].episodes.size)
        assertEquals(1, r[0].seasons[1].episodes.size)
    }

    // ---------- 缺集 ----------

    @Test
    fun `缺集标出`() {
        val r = organize("I/I 第01集.mkv", "I/I 第02集.mkv", "I/I 第05集.mkv")
        assertEquals(listOf(3, 4), r[0].seasons[0].missing)
    }

    // ---------- 工具函数 ----------

    @Test
    fun `中文数字解析`() {
        assertEquals(1, NasLibrary.cnNumber("一"))
        assertEquals(10, NasLibrary.cnNumber("十"))
        assertEquals(12, NasLibrary.cnNumber("十二"))
        assertEquals(20, NasLibrary.cnNumber("二十"))
        assertEquals(35, NasLibrary.cnNumber("三十五"))
        assertEquals(99, NasLibrary.cnNumber("九十九"))
        assertEquals(null, NasLibrary.cnNumber("二三"))
        assertEquals(null, NasLibrary.cnNumber("一百"))
    }

    @Test
    fun `季目录判定`() {
        assertTrue(NasLibrary.isSeasonDirName("Season 2"))
        assertTrue(NasLibrary.isSeasonDirName("S01"))
        assertTrue(NasLibrary.isSeasonDirName("第二季"))
        assertTrue(NasLibrary.isSeasonDirName("SP"))
        assertTrue(!NasLibrary.isSeasonDirName("剧场版"))
    }
}
