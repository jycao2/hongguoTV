package com.hongguotv.core

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private fun series(id: String)=Series(id,"测试 $id","https://example.com/cover.jpg","", "已完结", "剧情")
    private fun progress(id: String,time: Long)=BackupProgress(series(id),"100$id",0,time,10000,false,time)
    private fun data()=BackupData(listOf(series("1")),listOf(progress("1",100)),setOf("1"),listOf(RecentSearch("你好",ContentType.COMIC)),BackupSettings())
    @Test fun `backup round trips records and settings with cover and text sanitization`() {
        val original=data().copy(settings=BackupSettings(720,1.5f,false,ContentType.COMIC,VideoFrameMode.ZOOM))
        assertEquals(original,LibraryBackup.decode(LibraryBackup.encode(original)))
        val sanitized=original.copy(favorites=listOf(series("1").copy(title="你\n好",cover="http://127.0.0.1/secret")))
        val result=LibraryBackup.decode(LibraryBackup.encode(sanitized))
        assertEquals("你 好",result.favorites.single().title); assertEquals("",result.favorites.single().cover)
    }
    @Test fun `merge retains newer progress and watched state unless imported progress is newer`() {
        val local=data(); val incoming=data().copy(favorites=listOf(series("2"),series("1")),history=listOf(progress("1",50),progress("2",200)),watched=emptySet(),settings=BackupSettings(480,2f))
        val merged=LibraryBackup.merge(local,incoming,false)
        assertEquals(listOf("1","2"),merged.favorites.map { it.id })
        assertEquals(listOf(200L,100L),merged.history.map { it.updatedAt })
        assertTrue("1" in merged.watched); assertEquals(local.settings,merged.settings)
        val newer=LibraryBackup.merge(local,incoming.copy(history=listOf(progress("1",300))),true)
        assertFalse("1" in newer.watched); assertEquals(incoming.settings,newer.settings)
        assertEquals(1,newer.searches.size)
    }
    @Test fun `merge caps collections and does not mark local favorites watched from stale orphan flags`() {
        val local=data().copy(favorites=(1..500).map { series(it.toString()) })
        val merged=LibraryBackup.merge(local,data().copy(favorites=listOf(series("501")),history=emptyList(),watched=setOf("2")),false)
        assertEquals(500,merged.favorites.size); assertFalse("2" in merged.watched)
        val records=(1..200).map { progress(it.toString(),it.toLong()) }
        val history=LibraryBackup.merge(local.copy(history=records),data().copy(history=listOf(progress("201",201))),false).history
        assertEquals(200,history.size); assertEquals("201",history.first().series.id); assertFalse(history.any { it.series.id=="1" })
    }
    @Test fun `auto season setting round trips and legacy backups default to enabled`() {
        val off=data().copy(settings=BackupSettings().copy(autoSeason=false))
        assertEquals(off,LibraryBackup.decode(LibraryBackup.encode(off)))
        val legacy=JSONObject(LibraryBackup.encode(data())); legacy.getJSONObject("settings").remove("autoSeason")
        assertTrue(LibraryBackup.decode(legacy.toString()).settings.autoSeason)
    }
    @Test fun `danmaku prefs round trip and out of range values normalize`() {
        val custom=data().copy(settings=BackupSettings().copy(danmakuOpacity=60,danmakuFont=0,danmakuSpeed=4,danmakuRows=5))
        assertEquals(custom,LibraryBackup.decode(LibraryBackup.encode(custom)))
        val legacy=JSONObject(LibraryBackup.encode(data()))
        legacy.getJSONObject("settings").apply { remove("danmakuOpacity"); remove("danmakuFont"); remove("danmakuSpeed"); remove("danmakuRows") }
        val restored=LibraryBackup.decode(legacy.toString()).settings
        assertEquals(DanmakuPrefs.OPACITY_DEFAULT,restored.danmakuOpacity); assertEquals(DanmakuPrefs.FONT_DEFAULT,restored.danmakuFont)
        assertEquals(DanmakuPrefs.SPEED_DEFAULT,restored.danmakuSpeed); assertEquals(DanmakuPrefs.ROWS_DEFAULT,restored.danmakuRows)
        val wild=JSONObject(LibraryBackup.encode(data()))
        wild.getJSONObject("settings").apply { put("danmakuOpacity",5); put("danmakuFont",99); put("danmakuSpeed",-3); put("danmakuRows",42) }
        val normalized=LibraryBackup.decode(wild.toString()).settings
        assertEquals(DanmakuPrefs.OPACITY_DEFAULT,normalized.danmakuOpacity); assertEquals(DanmakuPrefs.FONT_LABELS.lastIndex,normalized.danmakuFont)
        assertEquals(0,normalized.danmakuSpeed); assertEquals(DanmakuPrefs.ROWS_DEFAULT,normalized.danmakuRows)
    }
    @Test fun `favorite groups round trip merge and legacy backups`() {
        val original=data().copy(favoriteGroups=mapOf("1" to "追剧"))
        assertEquals(original,LibraryBackup.decode(LibraryBackup.encode(original)))
        val legacy=JSONObject(LibraryBackup.encode(data())); legacy.remove("favoriteGroups")
        assertTrue(LibraryBackup.decode(legacy.toString()).favoriteGroups.isEmpty())
        val messy=data().copy(favoriteGroups=mapOf("1" to "你\n好" + "长".repeat(30)))
        assertEquals("你 好" + "长".repeat(17),LibraryBackup.decode(LibraryBackup.encode(messy)).favoriteGroups.getValue("1"))
        val blank=data().copy(favoriteGroups=mapOf("1" to "   "))
        assertTrue(LibraryBackup.decode(LibraryBackup.encode(blank)).favoriteGroups.isEmpty())
        val local=data().copy(favoriteGroups=mapOf("1" to "本地"))
        val incoming=data().copy(favorites=listOf(series("2")),history=emptyList(),watched=emptySet(),favoriteGroups=mapOf("1" to "导入","2" to "新剧","3" to "孤儿"))
        assertEquals(mapOf("1" to "导入","2" to "新剧"),LibraryBackup.merge(local,incoming,false).favoriteGroups)
    }
    @Test fun `invalid favorite groups are rejected`() {
        fun reject(change: (JSONObject)->Unit) { val o=JSONObject(LibraryBackup.encode(data())); change(o); assertTrue(o.toString(),runCatching { LibraryBackup.decode(o.toString()) }.isFailure) }
        reject { it.getJSONObject("favoriteGroups").put("1","x".repeat(21)) }
        reject { it.getJSONObject("favoriteGroups").put("notAnId","a") }
        reject { it.getJSONObject("favoriteGroups").put("1","") }
        reject { it.getJSONObject("favoriteGroups").put("1",1) }
    }
    @Test fun `invalid format ranges settings duplicates and oversized nesting are rejected`() {
        fun reject(change: (JSONObject)->Unit) { val o=JSONObject(LibraryBackup.encode(data())); change(o); assertTrue(o.toString(),runCatching { LibraryBackup.decode(o.toString()) }.isFailure) }
        reject { it.put("version",2) }; reject { it.put("format","other") }
        reject { it.getJSONObject("settings").put("speed",5) }; reject { it.getJSONObject("settings").put("quality",123) }
        reject { it.getJSONObject("settings").put("autoNext","true") }; reject { it.getJSONObject("settings").put("frame","OTHER") }
        reject { it.getJSONArray("history").getJSONObject(0).put("updatedAt",-1) }
        reject { it.getJSONArray("history").getJSONObject(0).put("position",1.5) }
        reject { it.getJSONArray("favorites").put(it.getJSONArray("favorites").getJSONObject(0)) }
        reject { it.getJSONArray("favorites").getJSONObject(0).put("id","../x") }
        reject { it.put("searches",JSONArray((1..21).map { JSONObject().put("query","a").put("type",ContentType.COMIC.storedValue) })) }
        assertTrue(runCatching { LibraryBackup.decode("[".repeat(10000)) }.isFailure)
        assertTrue(runCatching { LibraryBackup.decode(" ".repeat(LibraryBackup.MAX_BYTES+1)) }.isFailure)
    }
}
