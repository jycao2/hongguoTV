// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音意图解析。
 *
 * 断言重点是**不能乱执行**：凡是拿不准的输入，宁可返回 null 让界面层什么都不做，
 * 也不要猜一个动作出来。所以「不该命中」的用例和「该命中」的用例一样重要。
 */
class VoiceIntentTest {

    private fun command(text: String): RemoteCommand? = VoiceIntent.parse(text)?.command

    private fun act(text: String): Pair<String, String>? {
        val cmd = command(text) as? RemoteCommand.Act ?: return null
        return cmd.id to cmd.arg
    }

    // ------------------------------------------------------------ 用户提的例子

    @Test
    fun userExamplesAllWork() {
        // 「检查版本是否有更新」
        assertEquals("update.check" to "", act("检查版本是否有更新"))
        // 「我要看历史记录第二个」
        assertEquals("list.play" to "history:2", act("我要看历史记录第二个"))
        // 「我要接着看」
        assertEquals("resume.latest" to "", act("我要接着看"))
        // 「进去排行榜」
        assertEquals(RemoteCommand.Open("rank"), command("进去排行榜"))
        // 「切换主题背景」
        assertEquals("theme.toggle" to "", act("切换主题背景"))
    }

    // ------------------------------------------------------------ 动作

    @Test
    fun updateCheckVariants() {
        listOf("有没有新版本", "检查更新", "看看有没有新版", "是不是最新版了").forEach {
            assertEquals("「$it」应触发检查更新", "update.check", act(it)?.first)
        }
    }

    @Test
    fun themeVariants() {
        assertEquals("theme.set" to "light", act("换成白的"))
        assertEquals("theme.set" to "dark", act("夜间模式"))
        assertEquals("theme.toggle" to "", act("换个主题"))
    }

    @Test
    fun playbackControls() {
        assertEquals("play.next" to "", act("下一集"))
        assertEquals("play.prev" to "", act("回上一集"))
        assertEquals("play.toggle" to "", act("暂停一下"))
        assertEquals("play.toggle" to "", act("继续播放"))
    }

    @Test
    fun toggles() {
        assertEquals("autonext.toggle" to "", act("自动连播"))
        assertEquals("autoseason.toggle" to "", act("系列剧连播"))
        assertEquals("autoskip.toggle" to "", act("自动跳过"))
        assertEquals("danmaku.toggle" to "", act("关掉弹幕"))
        assertEquals("clock.toggle" to "", act("显示时间"))
    }

    @Test
    fun tools() {
        assertEquals("cache.clear" to "", act("清理缓存"))
        assertEquals("stats.show" to "", act("观看统计"))
        assertEquals("backup.show" to "", act("备份"))
        assertEquals("quick.send" to "", act("手机快传"))
        assertEquals("remote.address" to "", act("遥控地址"))
        assertEquals("account.show" to "", act("登录账号"))
        assertEquals("check.interface" to "", act("接口自检"))
    }

    // ------------------------------------------------------------ 弹窗 vs 弹幕

    /**
     * `关闭弹窗` 曾经被模糊兜底吃成 `danmaku.toggle`：两者只差一个字，
     * 而 0.75 的宽松阈值刚好放行。喊「关掉这个窗」结果开了弹幕，是最典型的听错型事故。
     */
    @Test
    fun closeDialogIsNotCloseDanmaku() {
        listOf("关闭弹窗", "关弹窗", "关掉弹窗", "关掉这个弹窗", "关闭弹框", "关掉窗口", "关闭对话框")
            .forEach { assertEquals("「$it」应关弹窗", "dialog.close", act(it)?.first) }

        listOf("关闭弹幕", "关弹幕", "关掉弹幕", "把弹幕关了", "开弹幕")
            .forEach { assertEquals("「$it」应切弹幕", "danmaku.toggle", act(it)?.first) }
    }

    /** 模糊兜底只补「多说了几个字」，不再接受缺字：四个字错一个字，语义就可能整个拧。 */
    @Test
    fun fuzzyDoesNotGuessMissingCharacter() {
        // 「关弹窗」三个字都在，能认出来（多说「一下」不影响）
        assertEquals("dialog.close", act("关一下弹窗")?.first)
        // 缺「闭」的「关闭弹窗」不能靠模糊猜；这里落到剧名都行，就是不能变成切弹幕
        val loose = act("关闭窗")
        assertTrue("不该猜成弹幕", loose == null || loose.first != "danmaku.toggle")
    }

    // ------------------------------------------------------------ 列表序数

    @Test
    fun listOrdinals() {
        assertEquals("list.play" to "history:2", act("历史记录第二个"))
        assertEquals("list.play" to "history:12", act("看观看历史第十二个"))
        assertEquals("list.play" to "favorite:3", act("收藏第三个"))
        assertEquals("list.play" to "episodes:5", act("第五集"))
        assertEquals("list.play" to "current:2", act("第二个"))
        assertEquals("list.play" to "history:2", act("我要看历史记录第2个"))
    }

    @Test
    fun chineseNumbers() {
        assertEquals("list.play" to "episodes:10", act("第十集"))
        assertEquals("list.play" to "episodes:20", act("第二十集"))
        assertEquals("list.play" to "episodes:99", act("第九十九集"))
    }

    // ------------------------------------------------------------ 导航与剧名

    @Test
    fun moduleNavigation() {
        assertEquals(RemoteCommand.Open("settings"), command("打开设置"))
        assertEquals(RemoteCommand.Open("history"), command("去历史记录"))
        assertEquals(RemoteCommand.Open("favorite"), command("我的收藏"))
        assertEquals(RemoteCommand.Open("source"), command("打开片源"))
    }

    @Test
    fun titlesStayIntact() {
        // 带书名号也要能搜
        assertEquals(RemoteCommand.Watch("黄石第五季"), command("《黄石第五季》"))
        // 剥动词前缀
        assertEquals(RemoteCommand.Watch("黄金时代"), command("我要看黄金时代"))
        assertEquals(RemoteCommand.Watch("文盲修仙"), command("播放文盲修仙"))
        // 「请」是多部真实剧名的开头，认剧名这一步绝不能剥
        assertEquals(RemoteCommand.Watch("请回答1988"), command("请回答1988"))
    }

    @Test
    fun ambiguousInputIsRejected() {
        assertNull(VoiceIntent.parse(""))
        assertNull(VoiceIntent.parse("啊"))
        assertNull(VoiceIntent.parse("  "))
    }

    // ------------------------------------------------------------ 一致性

    /**
     * 解析器产出的动作键必须都在协议白名单里。
     *
     * 这条测试是为了防止「语音说得出、手机端发不出去」这种割裂：白名单和词表是两份数据，
     * 加词表时忘了登记白名单，症状是电视上完全没反应，极难查。
     */
    @Test
    fun everyActIdIsInWhitelist() {
        val samples = listOf(
            "检查版本是否有更新", "切换主题", "换成白的", "显示时间", "恢复默认背景",
            "关闭弹窗", "自动连播", "系列剧连播", "关掉弹幕", "自动跳过", "下一集", "上一集",
            "暂停", "继续播放", "接着看", "收藏这个", "清理缓存", "观看统计", "备份",
            "手机快传", "遥控地址", "小爱同学设置", "登录账号", "接口自检", "恢复隐藏",
            "历史记录第二个", "第五集"
        )
        for(text in samples) {
            val cmd = VoiceIntent.parse(text)?.command
            if(cmd is RemoteCommand.Act) {
                assertTrue("「$text」产出的 ${cmd.id} 不在白名单里", cmd.id in REMOTE_ACTIONS)
            }
        }
    }

    /** 解析器的动作键必须能被协议往返一次：解析出来的命令，序列化后还要能再解析回来。 */
    @Test
    fun actSurvivesProtocolRoundTrip() {
        val cmd = RemoteCommand.Act("list.play", "history:2")
        val body = """{"cmd":"act","id":"${cmd.id}","arg":"${cmd.arg}"}"""
        assertEquals(cmd, RemoteProtocol.parse(body))
    }
}
