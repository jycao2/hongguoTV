// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小爱语音入口的纯逻辑：昵称怎么翻成动作、巴法返回怎么读。 */
class XiaoAiVoiceTest {

    @Test
    fun `剧名原样当搜索词`() {
        assertEquals(XiaoAiVoice.Action.Watch("黄石第五季"), XiaoAiVoice.actionOf("黄石第五季"))
    }

    @Test
    fun `红果前缀后面是模块名就跳模块`() {
        assertEquals(XiaoAiVoice.Action.Open("source"), XiaoAiVoice.actionOf("红果片源"))
        assertEquals(XiaoAiVoice.Action.Open("home"), XiaoAiVoice.actionOf("红果首页"))
    }

    @Test
    fun `红果前缀后面是剧名就开播`() {
        assertEquals(XiaoAiVoice.Action.Watch("黄石第五季"), XiaoAiVoice.actionOf("红果黄石第五季"))
    }

    @Test
    fun `前缀不区分大小写也允许带空格`() {
        assertEquals(XiaoAiVoice.Action.Open("home"), XiaoAiVoice.actionOf("Hongguo 首页"))
        assertEquals(XiaoAiVoice.Action.Watch("文盲修仙"), XiaoAiVoice.actionOf("红果TV 文盲修仙"))
    }

    @Test
    fun `单个字不猜`() {
        assertNull(XiaoAiVoice.actionOf("看"))
        assertNull(XiaoAiVoice.actionOf(""))
        assertNull(XiaoAiVoice.actionOf("   "))
    }

    @Test
    fun `只有前缀没有内容不响应`() {
        assertNull(XiaoAiVoice.actionOf("红果"))
        assertNull(XiaoAiVoice.actionOf("hongguo"))
    }

    @Test
    fun `两端装饰符去掉`() {
        assertEquals(XiaoAiVoice.Action.Watch("黄石第五季"), XiaoAiVoice.actionOf("· 黄石第五季 ·"))
    }

    @Test
    fun `模块别名齐全`() {
        assertEquals(XiaoAiVoice.Action.Open("history"), XiaoAiVoice.actionOf("最近观看"))
        assertEquals(XiaoAiVoice.Action.Open("history"), XiaoAiVoice.actionOf("观看历史"))
        assertEquals(XiaoAiVoice.Action.Open("rank"), XiaoAiVoice.actionOf("排行榜"))
        assertEquals(XiaoAiVoice.Action.Open("favorite"), XiaoAiVoice.actionOf("收藏"))
        assertEquals(XiaoAiVoice.Action.Open("library"), XiaoAiVoice.actionOf("本地库"))
        assertEquals(XiaoAiVoice.Action.Open("settings"), XiaoAiVoice.actionOf("设置"))
    }

    /** 实测响应（2026-11-08 抓的原文）：主题数组被包在 data.data 里。 */
    private val realPayload = """
        {"code":0,"msg":"success","data":{"data":[
          {"topic":"hgplay1001","type":1,"share":false,"time":"2026-11-08 09:12:34","unix":1794100354,
           "online":false,"name":"黄石第五季","room":"","msg":"","pubOnline":true,"deviceType":"outlet",
           "group":"","createTime":"2026-11-08 09:12:34"},
          {"topic":"lamp002","type":1,"share":false,"time":"2026-11-08 09:12:34","unix":1794100355,
           "online":false,"name":"台灯","room":"","msg":"","pubOnline":true,"deviceType":"bulb",
           "group":"","createTime":"2026-11-08 09:12:34"}
        ]}}
    """.trimIndent()

    /** 扁平结构：万一巴法改回来，也别再白屏一次。 */
    private val flatPayload = """
        {"code":0,"message":"OK","data":[
          {"topic":"hghs001","msg":"on","name":"黄石第五季","online":true,"unix":1762500000},
          {"topic":"hgmod1001","msg":"off","name":"红果片源","online":true,"unix":1762500100},
          {"topic":"lamp002","msg":"on","name":"台灯","online":true,"unix":1762500200}
        ]}
    """.trimIndent()

    @Test
    fun `真实响应的两层 data 能读到主题`() {
        val topics = XiaoAiVoice.parse(realPayload)
        assertEquals(1, topics.size)
        assertEquals("hgplay1001", topics[0].topic)
        assertEquals("黄石第五季", topics[0].name)
        assertEquals(1794100354L, topics[0].unix)
    }

    @Test
    fun `扁平 data 数组也兼容`() {
        assertEquals(2, XiaoAiVoice.parse(flatPayload).size)
    }

    @Test
    fun `只认 hg 开头的主题`() {
        val topics = XiaoAiVoice.parse(flatPayload)
        assertEquals(2, topics.size)
        assertEquals(listOf("hghs001", "hgmod1001"), topics.map { it.topic })
        assertEquals("黄石第五季", topics[0].name)
        assertEquals("on", topics[0].msg)
        assertEquals(1762500000L, topics[0].unix)
    }

    @Test
    fun `私钥错时返回空列表而不是抛异常`() {
        assertTrue(XiaoAiVoice.parse("""{"code":40003,"message":"openID error"}""").isEmpty())
        // 实测：私钥不认时 data 是空字符串，不是数组也不是对象
        assertTrue(XiaoAiVoice.parse("""{"code":-1,"msg":"获取失败","data":""}""").isEmpty())
        assertTrue(XiaoAiVoice.parse("not json").isEmpty())
    }

    @Test
    fun `错误码翻成人话`() {
        assertNull(XiaoAiVoice.problem("""{"code":0,"message":"OK"}"""))
        assertEquals("巴法云私钥不对", XiaoAiVoice.problem("""{"code":40003}"""))
        assertEquals("巴法云参数有误", XiaoAiVoice.problem("""{"code":10002}"""))
        // 实测：私钥格式对但服务端不认时回的是 code=-1 和空 data
        assertEquals("巴法云没认出这把私钥", XiaoAiVoice.problem("""{"code":-1,"msg":"获取失败","data":""}"""))
        assertTrue(XiaoAiVoice.problem("oops").orEmpty().isNotEmpty())
    }

    @Test
    fun `载荷里的剧名能直接读出来`() {
        // 小爱技能把剧名写进 msg，这就是任意剧名的入口
        assertEquals("黄石第五季", XiaoAiVoice.queryOf("黄石第五季"))
        assertEquals("黄石第五季", XiaoAiVoice.queryOf("  黄石第五季\n"))
        assertEquals("The Good Doctor", XiaoAiVoice.queryOf("The Good Doctor"))
    }

    @Test
    fun `开关语义的载荷不当剧名`() {
        // 米家只发 on/off，这些必须继续走「昵称 = 剧名/模块」的老路
        listOf("on", "off", "ON", " Off ", "toggle", "true", "1", "开", "关").forEach {
            assertNull("「$it」不该被当成剧名", XiaoAiVoice.queryOf(it))
        }
    }

    @Test
    fun `太短太长带控制字符的载荷都丢掉`() {
        assertNull(XiaoAiVoice.queryOf(""))
        assertNull(XiaoAiVoice.queryOf("   "))
        // 单字：八成是噪声，猜错会直接开始播别的片
        assertNull(XiaoAiVoice.queryOf("看"))
        assertNull(XiaoAiVoice.queryOf("a".repeat(XiaoAiVoice.QUERY_MAX + 1)))
        assertNull("换行以外的控制字符要拒绝", XiaoAiVoice.queryOf("黄石\u0000第五季"))
        assertNotNull(XiaoAiVoice.queryOf("a".repeat(XiaoAiVoice.QUERY_MAX)))
    }
}
