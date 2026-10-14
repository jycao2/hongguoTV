package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchingTest {
    @Test fun `favorite baseline is quiet and multiple checks accumulate unread episodes`() {
        val first=FavoriteUpdate.first(80,100)
        assertEquals(0,first.added)
        val one=first.observe(82,200)
        assertEquals(2,one.added)
        val two=FavoriteUpdate.decode(one.json())!!.observe(85,300)
        assertEquals(5,two.added)
        assertEquals(0,two.observe(85,400,acknowledge=true).added)
        assertEquals(1,two.observe(85,400,true).observe(86,500).added)
    }
    @Test fun `temporary shrink and stale requests do not invent updates or undo acknowledgements`() {
        val first=FavoriteUpdate.first(80,100)
        assertEquals(0,first.observe(75,200).observe(80,300).added)
        val acknowledged=first.observe(85,400,true)
        assertEquals(acknowledged,acknowledged.observe(83,300))
        assertEquals(0,acknowledged.observe(70,500,true).observe(85,600).added)
    }
    @Test fun `corrupt update metadata falls back to a fresh baseline`() {
        assertNull(FavoriteUpdate.decode(null))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":0,"seen":1,"checkedAt":1}""")))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":10,"seen":-1,"checkedAt":1}""")))
        assertEquals(0,FavoriteUpdate.first(100,20).added)
    }
    @Test fun `new season marker survives persistence and clears on acknowledge`() {
        val base=FavoriteUpdate.first(80,100)
        assertFalse(base.hasNewSeason)
        val marked=base.observeNewSeason("456","仙途第二季",200)
        assertTrue(marked.hasNewSeason)
        assertEquals("456",marked.newSeasonId)
        assertEquals("仙途第二季",marked.newSeasonTitle)
        val restored=FavoriteUpdate.decode(marked.json())!!
        assertEquals("456",restored.newSeasonId)
        assertEquals("仙途第二季",restored.newSeasonTitle)
        assertFalse(restored.observe(80,300,acknowledge=true).hasNewSeason)
        assertTrue(restored.observe(80,300).hasNewSeason)
    }
    @Test fun `new season marker ignores stale invalid and repeated values`() {
        val base=FavoriteUpdate.first(80,100).observeNewSeason("456","第二季",200)
        assertEquals(base,base.observeNewSeason("456","第二季改",300))
        assertEquals(base,base.observeNewSeason("abc","坏ID",300))
        assertEquals(base,base.observeNewSeason("","空",300))
        assertEquals(base,base.observeNewSeason("789","更早",150))
        val newer=base.observeNewSeason("789","第三季",300)
        assertEquals("789",newer.newSeasonId)
        assertEquals("第三季",newer.newSeasonTitle)
    }
    @Test fun `legacy rows without season fields still decode`() {
        val decoded=FavoriteUpdate.decode(JSONObject("""{"total":10,"seen":8,"checkedAt":7}"""))!!
        assertEquals(2,decoded.added)
        assertFalse(decoded.hasNewSeason)
    }
    @Test fun `acknowledged season does not resurface but a newer season still notifies`() {
        val marked=FavoriteUpdate.first(80,100).observeNewSeason("456","第二季",200)
        val acked=marked.observe(80,300,acknowledge=true)
        assertFalse(acked.hasNewSeason)
        assertEquals(acked,acked.observeNewSeason("456","第二季",400))
        val persisted=FavoriteUpdate.decode(acked.json())!!
        assertEquals(persisted,persisted.observeNewSeason("456","第二季",500))
        val next=persisted.observeNewSeason("789","第三季",600)
        assertTrue(next.hasNewSeason)
        assertEquals("789",next.newSeasonId)
        // The monitor always reports the newest season, so remembering the latest acked id is enough.
        val reacked=next.observe(80,700,acknowledge=true)
        assertEquals(reacked,reacked.observeNewSeason("789","第三季",800))
    }
    @Test fun `detail newer seasons only list finite seasons beyond the current one`() {
        fun series(id: String,title: String)=Series(id,title)
        val first=Detail(series("1","仙途"),emptyList(),sameName=listOf(series("2","仙途第二季"),series("3","仙途"),series("4","其他剧")))
        assertEquals(listOf("2"),first.newerSeasons.map { it.id })
        val second=Detail(series("2","仙途第二季"),emptyList(),sameName=listOf(series("1","仙途"),series("5","仙途第三季"),series("6","仙途第四季")))
        assertEquals(listOf("5","6"),second.newerSeasons.map { it.id })
        val latest=Detail(series("6","仙途第四季"),emptyList(),sameName=listOf(series("1","仙途"),series("2","仙途第二季")))
        assertTrue(latest.newerSeasons.isEmpty())
    }
    @Test fun `next season picks the immediately following season in order`() {
        fun series(id: String,title: String)=Series(id,title)
        val first=Detail(series("1","仙途"),emptyList(),sameName=listOf(series("6","仙途第四季"),series("5","仙途第三季"),series("2","仙途第二季")))
        assertEquals("2",first.nextSeason?.id)
        val third=Detail(series("5","仙途第三季"),emptyList(),sameName=listOf(series("2","仙途第二季"),series("6","仙途第四季")))
        assertEquals("6",third.nextSeason?.id)
        val latest=Detail(series("6","仙途第四季"),emptyList(),sameName=listOf(series("2","仙途第二季")))
        assertNull(latest.nextSeason)
        val untitled=Detail(series("9","仙途"),emptyList(),sameName=listOf(series("8","仙途")))
        assertNull(untitled.nextSeason)
    }
    @Test fun `resume follows episode identity after reordering and completed episode advances`() {
        val episodes=listOf("new","a","b","c")
        assertEquals(ResumeTarget(2,52000),ResumePlayback.target(episodes,"b",52000,false))
        assertEquals(ResumeTarget(3,0),ResumePlayback.target(episodes,"b",52000,true))
        assertEquals(ResumeTarget(3,0),ResumePlayback.target(episodes,"c",52000,true))
        assertEquals(ResumeTarget(0,0),ResumePlayback.target(episodes,"removed",52000,false))
        assertEquals(ResumeTarget(1,0),ResumePlayback.target(episodes,"a",-100,false))
    }
    @Test fun `automatic recovery is bounded until the user starts a new attempt`() {
        val budget=RecoveryBudget()
        assertEquals(1000L,budget.delayMillis); assertTrue(budget.consume())
        assertEquals(2500L,budget.delayMillis); assertTrue(budget.consume())
        assertEquals(5000L,budget.delayMillis); assertTrue(budget.consume())
        repeat(10) { assertFalse(budget.consume()); assertNull(budget.delayMillis) }
        assertEquals(3,budget.attempts)
        budget.reset(); assertEquals(1000L,budget.delayMillis); assertTrue(budget.consume())
    }
    @Test fun `legacy global defaults stay valid and invalid values have safe defaults`() {
        assertEquals(720,PlaybackQuality.normalize(720)); assertEquals(1080,PlaybackQuality.normalize(1080))
        assertEquals(1080,PlaybackQuality.normalize(-1)); assertEquals(480,PlaybackQuality.normalize(480))
        assertEquals(1.5f,PlaybackSpeed.normalize(1.5f)); assertEquals(1f,PlaybackSpeed.normalize(Float.NaN))
    }
}
