package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {
    private fun parse(body: String) = RemoteProtocol.parse(body)

    @Test fun `playback commands map to typed values`() {
        assertEquals(RemoteCommand.Play(true), parse("""{"cmd":"play","on":true}"""))
        assertEquals(RemoteCommand.Seek(12_345), parse("""{"cmd":"seek","to":12345}"""))
        assertEquals(RemoteCommand.Step(-10_000), parse("""{"cmd":"step","by":-10000}"""))
        assertEquals(RemoteCommand.Episode(1, 12), parse("""{"cmd":"episode","group":1,"index":12}"""))
        assertEquals(RemoteCommand.Speed(1.5f), parse("""{"cmd":"speed","value":1.5}"""))
        assertEquals(RemoteCommand.Muted(true), parse("""{"cmd":"muted","on":true}"""))
        assertEquals(RemoteCommand.Danmaku(false), parse("""{"cmd":"danmaku","on":false}"""))
        assertEquals(RemoteCommand.Back, parse("""{"cmd":"back"}"""))
        assertEquals(RemoteCommand.Menu(true), parse("""{"cmd":"menu","long":true}"""))
        assertEquals(RemoteCommand.Detach, parse("""{"cmd":"detach"}"""))
        assertEquals(RemoteCommand.Search("总裁的替身新娘"), parse("""{"cmd":"search","query":"总裁的替身新娘"}"""))
    }

    @Test fun `out of range and malformed commands are rejected instead of clamped`() {
        // 越界一律拒绝：把 seek 夹到合法区间会让用户拖到片尾却毫无反应
        assertNull(parse("""{"cmd":"seek","to":-1}"""))
        assertNull(parse("""{"cmd":"seek","to":${REMOTE_SEEK_MAX_MS + 1}}"""))
        assertNull(parse("""{"cmd":"seek","to":1.5}"""))
        assertNull(parse("""{"cmd":"seek"}"""))
        assertNull(parse("""{"cmd":"seek","to":"12345"}"""))
        assertNull(parse("""{"cmd":"step","by":0}"""))
        assertNull(parse("""{"cmd":"step","by":${REMOTE_STEP_MAX_MS + 1}}"""))
        assertNull(parse("""{"cmd":"episode","group":-1,"index":3}"""))
        assertNull(parse("""{"cmd":"episode","group":0,"index":10000}"""))
        assertNull(parse("""{"cmd":"speed","value":0.1}"""))
        assertNull(parse("""{"cmd":"speed","value":3.0}"""))
        assertNull(parse("""{"cmd":"play","on":"yes"}"""))
        assertNull(parse("""{"cmd":"dpad","key":"escape"}"""))
        assertNull(parse("""{"cmd":"search","query":""}"""))
        assertNull(parse("""{"cmd":"search","query":"${"x".repeat(81)}"}"""))
        assertNull(parse("""{"cmd":"search","query":"a\u0000b"}"""))
        assertNull(parse("""{"cmd":"nope"}"""))
        assertNull(parse("not json"))
        assertNull(parse("""{"cmd":"seek","to":1}""".padEnd(RemoteProtocol.MAX_BODY + 1, ' ')))
    }

    @Test fun `session id is only accepted when it looks like one`() {
        assertEquals("abc123", RemoteProtocol.sidOf("""{"sid":"abc123"}"""))
        assertNull(RemoteProtocol.sidOf("""{"sid":"a"}"""))
        assertNull(RemoteProtocol.sidOf("""{"sid":"a b c d"}"""))
        assertNull(RemoteProtocol.sidOf("""{"sid":"${"x".repeat(41)}"}"""))
        assertNull(RemoteProtocol.sidOf("""{"cmd":"play"}"""))
        assertNull(RemoteProtocol.sidOf("not json"))
    }

    @Test fun `dpad hold is clamped to a bounded repeat window`() {
        assertEquals(RemoteCommand.Dpad("up", 0), parse("""{"cmd":"dpad","key":"up"}"""))
        assertEquals(RemoteCommand.Dpad("left", 600), parse("""{"cmd":"dpad","key":"left","hold":600}"""))
        assertEquals(RemoteCommand.Dpad("down", 5000), parse("""{"cmd":"dpad","key":"down","hold":99999}"""))
        assertEquals(RemoteCommand.Dpad("right", 0), parse("""{"cmd":"dpad","key":"RIGHT"}"""))
        assertNull(parse("""{"cmd":"dpad","key":"escape"}"""))
        assertNull(parse("""{"cmd":"dpad"}"""))
    }

    @Test fun `durable set covers only state setting commands`() {
        // 断线重连只能重放「把状态设成 X」，不能重放 step/back 这类有时效性的命令
        val durable = listOf("""{"cmd":"play","on":true}""", """{"cmd":"seek","to":5}""",
            """{"cmd":"episode","group":0,"index":1}""", """{"cmd":"speed","value":1.25}""",
            """{"cmd":"muted","on":true}""", """{"cmd":"danmaku","on":true}""", """{"cmd":"resume","id":"a"}""")
        durable.forEach { assertTrue(it, parse(it)!!.durable) }
        val transient = listOf("""{"cmd":"step","by":10000}""", """{"cmd":"dpad","key":"up"}""",
            """{"cmd":"back"}""", """{"cmd":"menu","long":false}""", """{"cmd":"search","query":"x"}""",
            """{"cmd":"detach"}""")
        transient.forEach { assertFalse(it, parse(it)!!.durable) }
    }

    @Test fun `navigation and playback commands split into two paths`() {
        // 导航类不能进 RemoteTarget：浏览模式下没有 target，但这批命令恰恰只在无播放时才有意义
        assertTrue(parse("""{"cmd":"play","on":true}""")!!.isPlayback)
        assertTrue(parse("""{"cmd":"episode","group":0,"index":1}""")!!.isPlayback)
        listOf("""{"cmd":"dpad","key":"up"}""", """{"cmd":"back"}""", """{"cmd":"menu","long":true}""",
            """{"cmd":"search","query":"x"}""", """{"cmd":"resume","id":"a"}""").forEach {
            assertFalse(it, parse(it)!!.isPlayback)
        }
    }

    @Test fun `snapshot serializes session fields and leaves episode list out`() {
        val snapshot = PlaybackSnapshot(
            source = PlaybackSource.OFFICIAL,
            title = "总裁的替身新娘",
            groups = listOf(EpisodeGroup("正片", 88)),
            group = 0,
            episode = 11,
            positionMs = 123_456,
            durationMs = 1_450_000,
            playing = true,
            speed = 1.25f
        )
        val json = JSONObject(snapshot.toJson(epoch = 7, seq = 42, ts = 1_762_337_000_000, clients = 1, evictions = 2))
        assertEquals(7, json.getLong("epoch"))
        assertEquals(42, json.getLong("seq"))
        assertEquals("OFFICIAL", json.getString("source"))
        assertEquals("总裁的替身新娘", json.getString("title"))
        assertEquals(1, json.getJSONArray("groups").length())
        assertEquals(88, json.getJSONArray("groups").getJSONObject(0).getInt("count"))
        // 集列表不进快照：88 集每 400ms 重发一次是纯浪费
        assertFalse(json.getJSONArray("groups").getJSONObject(0).has("episodes"))
        assertEquals(11, json.getInt("episode"))
        assertTrue(json.getJSONObject("tv").getBoolean("foreground"))
        assertEquals(1, json.getJSONObject("remote").getInt("clients"))
        assertEquals(2, json.getJSONObject("remote").getInt("evictions"))
        assertTrue(json.isNull("error"))
        // 支持的片源默认可以开关弹幕；三方片源要显式说「不支持」，
        // 否则手机端只能把「没有弹幕」显示成「弹幕关」并且点了毫无反应
        assertTrue(json.getJSONObject("caps").getBoolean("danmaku"))
        val third = JSONObject(PlaybackSnapshot(source = PlaybackSource.THIRD, danmaku = false, danmakuAvailable = false).toJson(1, 1, 1, 1, 0))
        assertFalse(third.getJSONObject("caps").getBoolean("danmaku"))
    }

    @Test fun `empty snapshot is not controllable and forces browse mode`() {
        assertFalse(PlaybackSnapshot().controllable)
        assertTrue(PlaybackSnapshot(source = PlaybackSource.THIRD).controllable)
    }

    @Test fun `episode and resume payloads carry what the page needs`() {
        val episodes = listOf(EpisodeGroupDetail("正片", listOf(EpisodeEntry(0, "第 1 集"), EpisodeEntry(1, "第 2 集"))))
        val json = JSONObject(episodes.toEpisodesJson())
        assertEquals(2, json.getJSONArray("groups").getJSONObject(0).getJSONArray("episodes").length())
        assertEquals("第 2 集", json.getJSONArray("groups").getJSONObject(0).getJSONArray("episodes").getJSONObject(1).getString("title"))

        val resume = JSONObject(listOf(ResumeItem("a1", "剧名", "第 3 集 · 05:12", 312_000, 1_400_000)).toResumeJson())
        assertEquals("a1", resume.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("第 3 集 · 05:12", resume.getJSONArray("items").getJSONObject(0).getString("caption"))
    }
}
