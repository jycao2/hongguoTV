package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class LibraryIndexTest {
    private fun series(id: Int) = Series("$id", "剧 $id", "https://example.com/$id.jpg", "完整本机简介", "全 80 集", "剧情")
    private fun progress(id: Int, position: Long = 100, updated: Long = id.toLong()) =
        BackupProgress(series(id), "${id + 1000}", 2, position, 60_000, false, updated)
    private fun data() = BackupData((1..500).map(::series), (1..200).map { progress(it) }, setOf("2"),
        listOf(RecentSearch("测试", ContentType.SHORT)), BackupSettings(720, 1.5f, false, ContentType.SHORT, VideoFrameMode.FIT),
        listOf(series(3)), setOf("4"))

    @Test fun `legacy keys round trip without dropping metadata or settings`() {
        val original = data()
        val values = mapOf("favorites" to LibraryDisk.series(original.favorites), "progress" to LibraryDisk.history(original.history),
            "later" to LibraryDisk.series(original.later), "hidden" to original.hidden, "watched" to original.watched,
            "searches" to SearchHistory.encode(original.searches), "quality" to 720, "playbackSpeed" to 1.5f,
            "autoNext" to false, "contentType" to "short", "frameMode" to "FIT",
            "favoriteUpdates" to LibraryDisk.updates(mapOf("1" to FavoriteUpdate(83, 80, 10))))
        val state = LibraryDisk.decode(values)
        assertEquals(original.copy(history = original.history.sortedByDescending { it.updatedAt }), state.snapshot())
        assertEquals("完整本机简介", state.progress("1")!!.series.description)
        assertEquals(3, state.favoriteUpdate("1")!!.added)
        assertEquals(1, state.updatedFavorites())
    }

    @Test fun `repeated reads reuse snapshots and paused checkpoints do not write`() {
        val state = LibraryIndex(data())
        val history = state.history()
        val favoriteRows = state.favorites()
        repeat(1000) {
            assertSame(history, state.history())
            assertSame(favoriteRows, state.favorites())
            assertSame(history.first(), state.progress("200"))
            assertTrue(state.favorite("500"))
        }
        assertFalse(state.save(progress(200, updated = 999)))
        assertSame(history, state.history())
        assertEquals(200L, state.progress("200")!!.updatedAt)
        assertTrue(state.save(progress(200, position = 9000, updated = 1000)))
        assertEquals(100L, history.first().position)
        assertEquals(9000L, state.history().first().position)
    }

    @Test fun `history and favorite eviction remove corresponding indexes`() {
        val state = LibraryIndex(data(), (1..500).associate { "$it" to FavoriteUpdate(81, 80, 1) })
        assertEquals(500, state.updatedFavorites())
        state.save(progress(201, updated = 1000))
        assertEquals(200, state.history().size)
        assertNull(state.progress("1"))
        assertNotNull(state.progress("201"))
        assertTrue(state.toggle(series(501)))
        assertFalse(state.favorite("500"))
        assertNull(state.favoriteUpdate("500"))
        assertEquals(499, state.updatedFavorites())
        assertTrue(state.observeFavorite("1", 81, 10, true))
        assertEquals(498, state.updatedFavorites())
        assertFalse(state.toggle(series(1)))
        assertNull(state.favoriteUpdate("1"))
        assertEquals(498, state.updatedFavorites())
    }

    @Test fun `captured snapshots survive later mutations and keep backup merge semantics`() {
        val state = LibraryIndex(data(), mapOf("1" to FavoriteUpdate(81, 80, 10)))
        val saved = state.snapshot()
        val updates = state.updates()
        state.setWatched("1", true)
        state.hide("5")
        state.toggleLater(series(3))
        state.observeFavorite("1", 85, 20, false)
        state.removeHistory("2")
        assertEquals(setOf("2"), saved.watched)
        assertEquals(setOf("4"), saved.hidden)
        assertEquals(listOf(series(3)), saved.later)
        assertEquals(1, updates.getValue("1").added)
        assertEquals(5, state.favoriteUpdate("1")!!.added)
        assertFalse(state.watched("2"))
        val merged = LibraryBackup.merge(state.snapshot(), saved, false)
        assertEquals(200, merged.history.size)
        assertEquals(state.settings, merged.settings)
    }

    @Test fun `malformed rows do not discard valid records`() {
        val favoriteJson = "[null,{},${series(1).toJson()},false]"
        val state = LibraryDisk.decode(mapOf("favorites" to favoriteJson, "progress" to "bad", "favoriteUpdates" to "bad"))
        assertTrue(state.favorite("1"))
        assertTrue(state.history().isEmpty())
        assertEquals(0, state.updatedFavorites())
    }

    @Test fun `favorite groups assign normalize and clear on unfavorite`() {
        val state = LibraryIndex(data())
        assertTrue(state.groups().isEmpty())
        assertFalse(state.setGroup("999", "追剧"))
        assertTrue(state.setGroup("1", " 追剧 "))
        assertTrue(state.setGroup("2", "追剧"))
        assertTrue(state.setGroup("3", "漫剧"))
        assertEquals(listOf("追剧", "漫剧"), state.groups())
        assertEquals("追剧", state.groupOf("1"))
        assertFalse(state.setGroup("1", "追剧"))
        assertTrue(state.setGroup("1", "漫剧"))
        assertEquals(listOf("漫剧", "追剧"), state.groups())
        assertTrue(state.setGroup("3", null))
        assertNull(state.groupOf("3"))
        assertFalse(state.setGroup("3", null))
        assertTrue(state.setGroup("4", "ab" + "长".repeat(20)))
        assertEquals("ab" + "长".repeat(10), state.groupOf("4"))
        assertFalse(state.toggle(series(1)))
        assertNull(state.groupOf("1"))
        val saved = state.snapshot()
        assertEquals(state.groupsSnapshot(), saved.favoriteGroups)
        assertEquals(state.groupsSnapshot(), LibraryIndex(saved).groupsSnapshot())
    }

    @Test fun `favorite group cap rejects new names beyond twenty`() {
        val state = LibraryIndex(data())
        repeat(20) { assertTrue(state.setGroup("${it + 1}", "组$it")) }
        assertFalse(state.setGroup("21", "组 20"))
        assertTrue(state.setGroup("21", "组0"))
        assertTrue(state.setGroup("1", "组1"))
        assertEquals(20, state.groups().size)
    }

    @Test fun `favorite groups survive disk round trip and ignore orphan keys`() {
        val state = LibraryIndex(data())
        state.setGroup("1", "追剧")
        val values = mapOf("favorites" to LibraryDisk.series(state.favorites()),
            "favoriteGroups" to LibraryDisk.groups(state.groupsSnapshot() + ("999" to "孤儿")))
        val restored = LibraryDisk.decode(values)
        assertEquals(mapOf("1" to "追剧"), restored.groupsSnapshot())
        assertEquals(emptyMap<String, String>(), LibraryDisk.decode(mapOf("favoriteGroups" to "bad")).groupsSnapshot())
    }

    // ---- 云端收藏上新基线 ----

    @Test fun `cloud baseline records silently on first run then flags new shelf entries`() {
        val state = LibraryIndex(data())
        assertTrue(state.observeCloudFavorite("9001", 80, 10))
        assertTrue(state.observeCloudFavorite("9002", 66, 10, markNew = true))
        assertNull(state.cloudUpdate("9001")!!.takeIf { it.hasUpdate })
        assertEquals(0, state.updatedCloudFavorites())
        assertTrue(state.isCloudNew("9002"))
        assertFalse(state.isCloudNew("9001"))
        assertEquals(1, state.cloudNewCount())
        // 集数为 0 的条目（badge 缺失）不建基线
        assertFalse(state.observeCloudFavorite("9003", 0, 10, markNew = true))
        assertNull(state.cloudUpdate("9003"))
    }

    @Test fun `cloud episode growth shows update and acknowledge resets it`() {
        val state = LibraryIndex(data())
        state.observeCloudFavorite("9001", 80, 10)
        assertTrue(state.observeCloudFavorite("9001", 92, 20))
        val update = state.cloudUpdate("9001")!!
        assertEquals(12, update.added)
        assertEquals(92, update.total)
        assertEquals(1, state.updatedCloudFavorites())
        // 点进详情确认：基线重置、角标消失
        assertTrue(state.acknowledgeCloudFavorite("9001", 92, 30))
        assertEquals(0, state.cloudUpdate("9001")!!.added)
        assertEquals(0, state.updatedCloudFavorites())
        // 重复确认不产生变化
        assertFalse(state.acknowledgeCloudFavorite("9001", 92, 40))
    }

    @Test fun `cloud episode regression waits for season confirmation instead of counting as update`() {
        val state = LibraryIndex(data())
        state.observeCloudFavorite("9001", 202, 10)
        // 集数回退（疑似换季重置）：不改基线、不算加更
        assertFalse(state.observeCloudFavorite("9001", 40, 20))
        assertEquals(0, state.cloudUpdate("9001")!!.added)
        assertEquals(0, state.updatedCloudFavorites())
        // 补查详情确认新季：只挂新季标记，集数基线保持原值
        val season = Series("9100", "剧 9001 第二季", "", "", "全 40 集", "剧情")
        assertTrue(state.observeCloudFavorite("9001", 40, 30, newSeason = season))
        val update = state.cloudUpdate("9001")!!
        assertTrue(update.hasNewSeason)
        assertEquals("9100", update.newSeasonId)
        assertEquals(202, update.total)
        assertEquals(1, state.updatedCloudFavorites())
        // 同一部新季不重复提示；确认后连同 ackedSeasonId 一起消掉
        assertFalse(state.observeCloudFavorite("9001", 40, 40, newSeason = season))
        assertTrue(state.acknowledgeCloudFavorite("9001", 40, 50))
        assertFalse(state.cloudUpdate("9001")!!.hasNewSeason)
    }

    @Test fun `cloud pruning drops stale baselines and new flags keep live ones`() {
        val state = LibraryIndex(data())
        state.observeCloudFavorite("9001", 80, 10)
        state.observeCloudFavorite("9002", 66, 10, markNew = true)
        assertTrue(state.pruneCloudFavorites(setOf("9001")))
        assertNull(state.cloudUpdate("9002"))
        assertFalse(state.isCloudNew("9001"))
        assertFalse(state.isCloudNew("9002"))
        // 无变化时 prune 不报告变更
        assertFalse(state.pruneCloudFavorites(setOf("9001")))
        assertEquals(80, state.cloudUpdate("9001")!!.total)
    }

    @Test fun `cloud baselines and new flags survive disk round trip and reject corrupt json`() {
        val state = LibraryIndex(data())
        state.observeCloudFavorite("9001", 80, 10)
        state.observeCloudFavorite("9002", 92, 20)
        state.observeCloudFavorite("9003", 66, 10, markNew = true)
        val values = mapOf(
            "cloudUpdates" to LibraryDisk.updates(state.cloudUpdates()),
            "cloudNewIds" to state.cloudNewIds(),
        )
        val restored = LibraryDisk.decode(values)
        assertEquals(92, restored.cloudUpdate("9002")!!.total)
        assertTrue(restored.isCloudNew("9003"))
        assertFalse(restored.isCloudNew("9001"))
        // 坏数据整体退化为空，不能让库加载失败
        val broken = LibraryDisk.decode(mapOf("cloudUpdates" to "{bad json", "cloudNewIds" to setOf("9001")))
        assertEquals(0, broken.cloudBaselineCount())
        assertTrue(broken.isCloudNew("9001"))
    }
}
