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
        assertEquals(RemoteCommand.Watch("黄石"), parse("""{"cmd":"watch","query":"黄石"}"""))
        // 遥控页整句：可能是指令也可能是剧名，理解交给电视端
        assertEquals(RemoteCommand.Say("检查有没有更新"), parse("""{"cmd":"say","text":"检查有没有更新"}"""))
        assertNull("空句子不该被当成指令", parse("""{"cmd":"say","text":"   "}"""))
        assertEquals(RemoteCommand.Open("source"), parse("""{"cmd":"open","module":"source"}"""))
        // 大小写与两端空白由协议层吃掉，桥接那边不用自己归一
        assertEquals(RemoteCommand.Open("rank"), parse("""{"cmd":"open","module":" RANK "}"""))
    }

    @Test fun `open only accepts whitelisted modules`() {
        // 白名单外的键一律拒绝：界面层不该为一个不存在的页签做兜底
        assertNull(parse("""{"cmd":"open","module":"porn"}"""))
        assertNull(parse("""{"cmd":"open","module":""}"""))
        assertNull(parse("""{"cmd":"open"}"""))
        assertNull(parse("""{"cmd":"watch","query":""}"""))
        assertNull(parse("""{"cmd":"watch","query":"${"x".repeat(81)}"}"""))
        assertNull(parse("""{"cmd":"watch"}"""))
    }

    @Test fun `watch can carry an episode number`() {
        // 说清集号才允许直接落到那一集；0 表示没说，界面只出搜索结果
        assertEquals(RemoteCommand.Watch("文盲修仙", 7), parse("""{"cmd":"watch","query":"文盲修仙","episode":7}"""))
        assertEquals(0, (parse("""{"cmd":"watch","query":"文盲修仙"}""") as RemoteCommand.Watch).episode)
        assertNull("集号越界不该被夹到合法值", parse("""{"cmd":"watch","query":"x","episode":10000}"""))
        assertNull(parse("""{"cmd":"watch","query":"x","episode":-1}"""))
        assertNull(parse("""{"cmd":"watch","query":"x","episode":1.5}"""))
    }

    @Test fun `quick transfer and dialog acts validate at the edge`() {
        assertEquals(RemoteCommand.Act("netspeed.set", "on"), parse("""{"cmd":"act","id":"netspeed.set","arg":"on"}"""))
        assertEquals(RemoteCommand.Act("netspeed.set", "off"), parse("""{"cmd":"act","id":"netspeed.set","arg":"off"}"""))
        assertEquals(RemoteCommand.Act("transfer.preview"), parse("""{"cmd":"act","id":"transfer.preview"}"""))
        assertEquals(RemoteCommand.Act("backdrop.set"), parse("""{"cmd":"act","id":"backdrop.set"}"""))
        assertEquals(RemoteCommand.Act("dialog.confirm"), parse("""{"cmd":"act","id":"dialog.confirm"}"""))
        // 显式开关必须给值；无参数动作不能带尾巴，界面层只该收到自己认识的值
        assertNull(parse("""{"cmd":"act","id":"netspeed.set"}"""))
        assertNull(parse("""{"cmd":"act","id":"netspeed.set","arg":"maybe"}"""))
        assertNull(parse("""{"cmd":"act","id":"transfer.preview","arg":"2"}"""))
        assertNull(parse("""{"cmd":"act","id":"backdrop.set","arg":"on"}"""))
        assertNull(parse("""{"cmd":"act","id":"dialog.confirm","arg":"yes"}"""))
    }

    @Test fun `new acts validate their arguments at the edge`() {
        assertEquals(RemoteCommand.Act("type.set", "short"), parse("""{"cmd":"act","id":"type.set","arg":"short"}"""))
        assertEquals(RemoteCommand.Act("random.pick", ""), parse("""{"cmd":"act","id":"random.pick"}"""))
        assertEquals(RemoteCommand.Act("sleep.set", "min:30"), parse("""{"cmd":"act","id":"sleep.set","arg":"min:30"}"""))
        assertEquals(RemoteCommand.Act("sleep.set", "off"), parse("""{"cmd":"act","id":"sleep.set","arg":"off"}"""))
        assertEquals(RemoteCommand.Act("panel.quick"), parse("""{"cmd":"act","id":"panel.quick"}"""))
        assertEquals(RemoteCommand.Act("favorite.check"), parse("""{"cmd":"act","id":"favorite.check"}"""))
        assertEquals(RemoteCommand.Act("volume.mute", "on"), parse("""{"cmd":"act","id":"volume.mute","arg":"on"}"""))
        assertEquals(RemoteCommand.Act("player.close"), parse("""{"cmd":"act","id":"player.close"}"""))
        // 参数校验：类型键、定时格式、静音参数都只收认识的值
        assertNull(parse("""{"cmd":"act","id":"type.set","arg":"porn"}"""))
        assertNull(parse("""{"cmd":"act","id":"type.set"}"""))
        assertNull(parse("""{"cmd":"act","id":"sleep.set","arg":"min:0"}"""))
        assertNull(parse("""{"cmd":"act","id":"sleep.set","arg":"30"}"""))
        assertNull(parse("""{"cmd":"act","id":"volume.mute","arg":"maybe"}"""))
        assertNull(parse("""{"cmd":"act","id":"nope"}"""))
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
