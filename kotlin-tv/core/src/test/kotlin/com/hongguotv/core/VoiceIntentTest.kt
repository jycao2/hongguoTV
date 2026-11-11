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
        // 网速改成显式开 / 关：说「显示网速」时本来开着不该被切成关
        assertEquals("netspeed.set" to "on", act("显示网速"))
        assertEquals("netspeed.set" to "off", act("关闭网速"))
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

    // ------------------------------------------------------------ 剧名 + 集数

    /**
     * 「我要看文盲修仙的第七集」：剧名和集号必须一起留下。
     *
     * 早先的序数规则会把「第七集」当成「当前这部剧的第 7 集」，剧名整段丢掉；
     * 反过来只当剧名又会去搜「文盲修仙的第七集」这种搜不到的长句。
     */
    @Test
    fun titleWithEpisodeKeepsBoth() {
        assertEquals(RemoteCommand.Watch("文盲修仙", 7), command("我要看文盲修仙的第七集"))
        assertEquals(RemoteCommand.Watch("文盲修仙", 7), command("文盲修仙第七集"))
        assertEquals(RemoteCommand.Watch("文盲修仙", 12), command("看《文盲修仙》第12集"))
        // 季号属于片名，不能当集号吃掉
        assertEquals(RemoteCommand.Watch("黄石第五季", 3), command("我要看黄石第五季第3集"))
        // 只说集号：那是「当前这部剧的第 7 集」，不是剧名
        assertEquals("list.play" to "episodes:7", act("我要看第七集"))
    }

    // ------------------------------------------------------------ 进度定位

    @Test
    fun seekByVoice() {
        assertEquals(RemoteCommand.Step(30_000), command("快进30秒"))
        assertEquals(RemoteCommand.Step(-60_000), command("快退一分钟"))
        assertEquals(RemoteCommand.Step(-15_000), command("后退十五秒"))
        assertEquals(RemoteCommand.Step(90_000), command("快进九十秒"))
        // 不说时长按 15 秒走
        assertEquals(RemoteCommand.Step(15_000), command("快进"))
        // 超过 10 分钟的「快进」没有意义，按上限截断
        assertEquals(RemoteCommand.Step(600_000), command("快进一小时"))
    }

    @Test
    fun backAndExit() {
        assertEquals(RemoteCommand.Back, command("返回上一级"))
        assertEquals(RemoteCommand.Back, command("返回"))
        // 带模块词的「返回」是导航，不是返回上一级
        assertEquals(RemoteCommand.Open("home"), command("返回首页"))
        assertEquals(RemoteCommand.Open("rank"), command("返回排行榜"))
        assertEquals("player.close" to "", act("退出播放"))
    }

    @Test
    fun speedAndVolume() {
        assertEquals(RemoteCommand.Speed(1.5f), command("1.5倍速"))
        assertEquals(RemoteCommand.Speed(2f), command("两倍速"))
        assertEquals("speed.up" to "", act("快一点"))
        assertEquals("speed.down" to "", act("慢一点"))
        assertEquals("volume.up" to "", act("声音大一点"))
        assertEquals("volume.down" to "", act("声音小一点"))
        assertEquals("volume.mute" to "on", act("静音"))
        assertEquals("volume.mute" to "off", act("取消静音"))
        // 《八倍镜》这类片名不能被当倍速
        assertEquals(RemoteCommand.Watch("八倍镜"), command("八倍镜"))
    }

    @Test
    fun sleepTimer() {
        assertEquals("sleep.set" to "min:30", act("半小时后关掉"))
        assertEquals("sleep.set" to "min:60", act("一小时后关机"))
        assertEquals("sleep.set" to "ep:1", act("看完这集就关"))
        assertEquals("sleep.set" to "min:30", act("定时30分钟"))
        // 「关掉弹幕」不能被定时吃掉
        assertEquals("danmaku.toggle", act("关掉弹幕")?.first)
    }

    // ------------------------------------------------------------ 推荐与类型

    @Test
    fun randomPickAndTypeSwitch() {
        assertEquals("random.pick" to "comic", act("给我随机推荐一部漫剧"))
        assertEquals("random.pick" to "short", act("随机推荐一部短剧"))
        assertEquals("random.pick" to "", act("随便来一部"))
        assertEquals("type.set" to "short", act("切换短剧"))
        assertEquals("type.set" to "comic", act("看漫剧"))
        assertEquals("panel.episodes" to "", act("打开选集"))
        assertEquals("account.show" to "", act("登录红果账户"))
    }

    // ------------------------------------------------------------ 快传与历史

    /**
     * 快传的四句话各归各的：换码、看文件、清空文件。
     *
     * 最容易串的是「打开快传文件」——它同时含有 `快传`（手机快传面板）和 `快传文件`（文件列表），
     * 长词优先的规则在这里必须真正生效，否则说「看文件」却又弹出一张二维码。
     */
    @Test
    fun quickTransferVariants() {
        assertEquals("transfer.refresh" to "", act("刷新二维码"))
        assertEquals("transfer.refresh" to "", act("换个二维码"))
        assertEquals("transfer.files" to "", act("打开快传文件"))
        assertEquals("transfer.files" to "", act("快传文件"))
        assertEquals("transfer.clear" to "", act("清空文件"))
        assertEquals("transfer.clear" to "", act("清空快传文件"))
        // 不带「文件」二字的仍是上传面板
        assertEquals("quick.send" to "", act("手机快传"))
        assertEquals("quick.send" to "", act("打开快传"))
    }

    @Test
    fun clearSearchHistory() {
        listOf("清空搜索历史", "清除搜索历史", "清空搜索记录", "清空历史记录").forEach {
            assertEquals("「$it」应清搜索历史", "history.clear", act(it)?.first)
        }
        // 「历史记录」本身是导航，不能被清空规则吃掉
        assertEquals(RemoteCommand.Open("history"), command("打开历史记录"))
    }

    // ------------------------------------------------------------ 网络代理

    /**
     * 代理的三种说法各归各的：开、关、查状态。
     *
     * 「代理」两个字太短——片名带「代理」的不少，所以词表里最短也要三个字。
     * 「关闭代理服务器」这类混合句同时命中「关闭代理」和「代理服务器」时，
     * 宁可落到只读的 status 也不要落成方向相反的开关。
     */
    @Test
    fun proxyVariants() {
        listOf("打开网络代理", "开启代理", "开代理", "开个代理").forEach {
            assertEquals("「$it」应开代理", "proxy.set" to "on", act(it))
        }
        listOf("关闭网络代理", "关掉代理", "关代理", "关了代理").forEach {
            assertEquals("「$it」应关代理", "proxy.set" to "off", act(it))
        }
        listOf("代理状态", "网络代理状态", "代理服务器", "代理开着吗").forEach {
            assertEquals("「$it」应查代理状态", "proxy.status" to "", act(it))
        }
        // 切换语义：不带方向的「网络代理」「代理开关」
        listOf("网络代理", "代理开关", "切换代理").forEach {
            assertEquals("「$it」应切代理", "proxy.set" to "", act(it))
        }
        // 混合长句不落成开关
        assertEquals("proxy.status", act("关闭代理服务器")?.first)
    }

    @Test
    fun proxyArgsSurviveProtocol() {
        listOf("on", "off", "").forEach { arg ->
            val body = """{"cmd":"act","id":"proxy.set","arg":"$arg"}"""
            assertEquals(RemoteCommand.Act("proxy.set", arg), RemoteProtocol.parse(body))
        }
        // 非法 arg 在协议入口就被拒掉
        assertNull(RemoteProtocol.parse("""{"cmd":"act","id":"proxy.set","arg":"maybe"}"""))
        assertEquals(RemoteCommand.Act("proxy.status"), RemoteProtocol.parse("""{"cmd":"act","id":"proxy.status"}"""))
    }

    // ------------------------------------------------------------ 指令扩充

    /** 取消定时必须在时长判断之前生效，否则「取消定时」会被吃成「定时 30 分钟」。 */
    @Test
    fun sleepCancelVariants() {
        listOf("取消定时", "解除定时", "关闭定时", "不定时了", "取消关机").forEach {
            assertEquals("「$it」应取消定时", "sleep.set" to "off", act(it))
        }
        // 设置了再取消，整条链路语义正确
        assertEquals("sleep.set" to "min:30", act("定时30分钟"))
        assertEquals("sleep.set" to "off", act("取消定时"))
    }

    @Test
    fun quickPanelAndFavoriteCheck() {
        listOf("播放设置", "快捷面板", "播放面板", "播放快捷面板").forEach {
            assertEquals("「$it」应呼出播放快捷面板", "panel.quick", act(it)?.first)
        }
        listOf("检查收藏更新", "看看收藏有没有更新", "收藏有没有更新", "检查收藏").forEach {
            assertEquals("「$it」应检查收藏更新", "favorite.check", act(it)?.first)
        }
        // 「播放设置」不能吃掉「播放记录」这种导航说法
        assertEquals(RemoteCommand.Open("history"), command("播放记录"))
    }

    @Test
    fun enrichedAliases() {
        assertEquals("play.next" to "", act("下一话"))
        assertEquals("play.prev" to "", act("上一话"))
        assertEquals("play.replay" to "", act("再放一遍"))
        assertEquals("player.close" to "", act("不看了"))
        assertEquals("resume.latest" to "", act("上次那个"))
        assertEquals("random.pick" to "", act("来点别的"))
        assertEquals("update.check" to "", act("版本升级"))
        assertEquals("volume.mute" to "on", act("静音一下"))
        assertEquals(RemoteCommand.Open("rank"), command("打开热榜"))
        assertEquals(RemoteCommand.Open("favorite"), command("收藏列表"))
        assertEquals(RemoteCommand.Open("library"), command("本地片库"))
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
            "历史记录第二个", "第五集",
            // 新增：播放页动作、类型切换、随机推荐、音量、定时
            "从头播放", "退出播放", "打开选集", "跳过片头", "快一点", "慢一点",
            "半小时后关掉", "看完这集就关", "声音大一点", "声音小一点", "静音", "取消静音",
            "随机推荐一部漫剧", "随机推荐", "切换短剧", "看漫剧",
            // 新增：快传三件事与搜索历史
            "刷新二维码", "打开快传文件", "清空文件", "清空搜索历史",
            // 新增：网络代理
            "打开代理", "关代理", "代理状态", "网络代理"
        )
        for(text in samples) {
            val cmd = VoiceIntent.parse(text)?.command
            if(cmd is RemoteCommand.Act) {
                assertTrue("「$text」产出的 ${cmd.id} 不在白名单里", cmd.id in REMOTE_ACTIONS)
            }
        }
    }

    // ------------------------------------------------------------ 11-11 新增动作

    /** 新功能补的四个动作 + 网速显式开关：长词优先，旧说法不能被新规则抢走。 */
    @Test
    fun quickTransferAndDialogActsAreUnambiguous() {
        assertEquals("netspeed.set" to "on", act("显示网速"))
        assertEquals("netspeed.set" to "on", act("打开网速显示"))
        assertEquals("netspeed.set" to "off", act("隐藏网速"))
        assertEquals("netspeed.set" to "off", act("关闭网速显示"))
        assertEquals("netspeed.set" to "off", act("关掉网速"))
        assertEquals("netspeed.toggle" to "", act("切换网速"))
        assertEquals("netspeed.toggle" to "", act("网速显示"))
        assertEquals("transfer.preview" to "", act("预览快传文件"))
        assertEquals("transfer.preview" to "", act("全屏预览"))
        assertEquals("backdrop.set" to "", act("设为电视背景"))
        assertEquals("backdrop.set" to "", act("把快传图片设为背景"))
        assertEquals("dialog.confirm" to "", act("确认接收"))
        assertEquals("dialog.confirm" to "", act("点确认"))
        // 旧说法原地不动
        assertEquals("transfer.files" to "", act("打开快传文件"))
        assertEquals("transfer.files" to "", act("快传文件"))
        assertEquals("backdrop.reset" to "", act("恢复默认背景"))
        assertEquals("dialog.close" to "", act("关闭弹窗"))
        assertEquals("quick.send" to "", act("手机快传"))
    }

    /** 两个字的光杆词不能当指令：那是剧名的地盘，别名一律最短三个字。 */
    @Test
    fun shortBareWordsAreNotCommands() {
        for(word in listOf("确认", "网速", "预览", "背景")) {
            assertNull("「$word」不该产出动作键", act(word))
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
