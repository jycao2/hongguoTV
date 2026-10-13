package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackComfortTest {
    @Test fun `time limit uses elapsed clock and fires once including paused time`() {
        var now=100L; val timer=SleepTimer { now }
        timer.afterMinutes(15); now+=899999
        assertFalse(timer.poll()); assertEquals("1 分钟后",timer.label())
        now++; assertTrue(timer.poll()); assertFalse(timer.active); assertFalse(timer.poll())
    }
    @Test fun `episode limit only consumes ends and replacing or cancelling disarms it`() {
        var now=0L; val timer=SleepTimer { now }
        timer.afterEpisodes(3); now=1000000
        assertFalse(timer.poll()); assertFalse(timer.episodeEnded()); assertFalse(timer.episodeEnded())
        assertTrue(timer.episodeEnded()); assertFalse(timer.episodeEnded())
        timer.afterEpisodes(5); timer.afterMinutes(1); assertEquals(0,timer.episodesLeft)
        timer.cancel(); now+=60000; assertFalse(timer.poll())
        timer.afterMinutes(1); timer.afterEpisodes(1); now+=60000
        assertFalse(timer.poll()); assertTrue(timer.episodeEnded())
    }
    @Test fun `elapsed timer also wins at episode transition`() {
        var now=0L; val timer=SleepTimer { now }; timer.afterMinutes(1); now=60000
        assertTrue(timer.episodeEnded()); assertFalse(timer.active)
        assertTrue(runCatching { timer.afterMinutes(0) }.isFailure)
        assertTrue(runCatching { timer.afterEpisodes(11) }.isFailure)
        assertEquals(VideoFrameMode.FIT,VideoFrameMode.fromStored("unknown"))
    }

    @Test fun `skip marks round trip and ignore malformed entries`() {
        val marks=linkedMapOf("123" to SkipMark(30,90),"bad id" to SkipMark(10,10),"456" to SkipMark(0,45))
        val decoded=SkipMarks.decode(SkipMarks.encode(marks))
        assertEquals(mapOf("123" to SkipMark(30,90),"456" to SkipMark(0,45)),decoded)
        assertTrue(SkipMarks.decode("not json").isEmpty())
        assertTrue(SkipMarks.decode(null).isEmpty())
        assertTrue(SkipMarks.decode("""{"123":{"intro":-1,"outro":5},"456":{"intro":601,"outro":0}}""").isEmpty())
    }

    @Test fun `setting an empty mark removes the entry and oldest entries are trimmed`() {
        var marks=linkedMapOf<String,SkipMark>()
        marks=SkipMarks.set(marks,"1",SkipMark(30,0))
        marks=SkipMarks.set(marks,"2",SkipMark(0,60))
        assertEquals(SkipMark(30,0),marks["1"])
        marks=SkipMarks.set(marks,"1",SkipMark(0,0))
        assertFalse(marks.containsKey("1"))
        marks=SkipMarks.set(marks,"1",null)
        assertEquals(listOf("2"),marks.keys.toList())
        repeat(SkipMarks.MAX_ENTRIES+5) { marks=SkipMarks.set(marks,"id$it",SkipMark(10,10)) }
        assertEquals(SkipMarks.MAX_ENTRIES,marks.size)
        assertFalse(marks.containsKey("2"))
    }

    @Test fun `skip mark bounds are enforced`() {
        assertTrue(runCatching { SkipMark(-1,0) }.isFailure)
        assertTrue(runCatching { SkipMark(0,601) }.isFailure)
        assertTrue(SkipMark(0,0).empty)
        assertFalse(SkipMark(0,1).empty)
    }
}
