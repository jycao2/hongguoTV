// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 一句话 → 遥控命令。
 *
 * 设计取舍：**不做通用语义理解，只做「能确定才执行」**。
 * 语音入口最怕的不是听不懂，是听错——喊「关掉」结果开始播别的剧，比没反应烦十倍。
 * 所以这里每一步匹配不上就往下退，最终退到「当剧名去搜」，退无可退才返回 null。
 *
 * 匹配顺序（顺序本身就是语义优先级）：
 * 1. 剧名 + 集数：`文盲修仙第七集` → `watch(文盲修仙, 7)`
 * 2. 列表 + 序数：`历史记录第二个` → `list.play history:2`
 * 3. 时间定位：`快进30秒` → `step(+30000)`；`跳到5分钟` 同理
 * 4. 倍速：`1.5倍速` → `speed(1.5)`
 * 5. 定时：`半小时后关掉` → `sleep.set min:30`
 * 6. 动作词表：`检查有没有更新` → `update.check`
 * 7. 模块导航：`进去排行榜` → `open rank`
 * 8. 返回与退出：`返回上一级` → `back`
 * 9. 模糊兜底：说错几个字也能认（`检查版本是否有更新`）
 * 10. 剧名：剩下的话当搜索词
 *
 * 纯函数，没有任何 Android 依赖，可单测。
 */
object VoiceIntent {

    /** 解析结果：[command] 要执行什么，[echo] 是回执（电视上轻提示用，说人话）。 */
    data class Plan(val command: RemoteCommand, val echo: String)

    /** 一条动作规则：说中任意一个词就执行 [command]。 */
    private data class Rule(val words: List<String>, val command: RemoteCommand, val echo: String)

    /** 一条模块规则。 */
    private data class ModuleRule(val words: List<String>, val module: String, val label: String)

    // ---------------------------------------------------------------- 词表

    /**
     * 动作词表。**长的词必须排在前面**：`切换主题` 与 `主题` 同时命中时，取更具体的那个。
     * 同一个 [RemoteCommand] 出现多次是正常的，别名越多越好说话。
     */
    private val RULES: List<Rule> = listOf(
        // 版本与更新
        Rule(listOf("有没有新版本", "有没有新版", "有没有更新", "检查版本", "检查更新", "看看更新", "看看有没有新版", "版本更新", "是不是最新版", "升级版本"),
            RemoteCommand.Act("update.check"), "正在检查新版本"),
        // 主题
        Rule(listOf("切换主题", "换主题", "换个主题", "切换风格", "换风格", "换个风格", "主题切换"),
            RemoteCommand.Act("theme.toggle"), "主题已切换"),
        Rule(listOf("深色主题", "夜间模式", "暗色主题", "黑色主题", "换成黑的", "换成深色"),
            RemoteCommand.Act("theme.set", "dark"), "已换成深夜影院"),
        Rule(listOf("浅色主题", "白色主题", "亮色主题", "白天模式", "换成白的", "换成浅色"),
            RemoteCommand.Act("theme.set", "light"), "已换成暖玉白"),
        // 外观与界面
        Rule(listOf("显示时间", "显示时钟", "时间显示", "顶栏时间"),
            RemoteCommand.Act("clock.toggle"), "已切换时间显示"),
        Rule(listOf("恢复默认背景", "默认背景", "恢复背景"),
            RemoteCommand.Act("backdrop.reset"), "已恢复默认背景"),
        // 关弹窗。必须和「关弹幕」严格分开：两者只差一个字，早先没有这条规则时
        // 「关闭弹窗」被模糊匹配吃成了切弹幕——喊关窗口结果把字幕评论开了。
        Rule(listOf("关闭弹窗", "关掉弹窗", "关弹窗", "关闭弹框", "关掉弹框", "关弹框",
            "关闭窗口", "关掉窗口", "关窗口", "关闭对话框", "关掉对话框", "关闭提示框"),
            RemoteCommand.Act("dialog.close"), "关闭弹窗"),
        // 播放偏好
        Rule(listOf("自动连播", "自动播放下一集", "播完自动下一集"),
            RemoteCommand.Act("autonext.toggle"), "已切换自动连播"),
        Rule(listOf("系列剧连播", "跨季连播", "自动下一季"),
            RemoteCommand.Act("autoseason.toggle"), "已切换系列剧连播"),
        Rule(listOf("打开弹幕", "开启弹幕", "关掉弹幕", "关闭弹幕", "开弹幕", "关弹幕", "弹幕"),
            RemoteCommand.Act("danmaku.toggle"), "已切换弹幕"),
        Rule(listOf("自动跳过", "跳过不兼容"),
            RemoteCommand.Act("autoskip.toggle"), "已切换自动跳过"),
        // 播放控制
        Rule(listOf("下一集", "放下一集", "换下一集"),
            RemoteCommand.Act("play.next"), "下一集"),
        Rule(listOf("上一集", "回上一集", "放上一集"),
            RemoteCommand.Act("play.prev"), "上一集"),
        Rule(listOf("从头播放", "从头看", "重新播放", "重播这一集", "重新开始"),
            RemoteCommand.Act("play.replay"), "从头播放"),
        Rule(listOf("退出播放", "退出播放器", "关掉播放", "关闭播放", "退出观看", "回到详情", "回到详情页"),
            RemoteCommand.Act("player.close"), "退出播放"),
        Rule(listOf("打开选集", "选集面板", "换一集", "看看选集", "选集"),
            RemoteCommand.Act("panel.episodes"), "选集"),
        Rule(listOf("跳过片头", "片头跳过"),
            RemoteCommand.Act("skip.intro"), "跳过片头"),
        Rule(listOf("暂停", "停一下", "先停", "别放了"),
            RemoteCommand.Act("play.toggle"), "已暂停"),
        Rule(listOf("继续播放", "接着放", "恢复播放", "继续"),
            RemoteCommand.Act("play.toggle"), "继续播放"),
        // 倍速。数字倍速由 speedOf 处理，这里只管「相对快慢」。
        Rule(listOf("快一点", "快一些", "加快播放", "播放快点", "加速播放"),
            RemoteCommand.Act("speed.up"), "倍速加快"),
        Rule(listOf("慢一点", "慢一些", "放慢播放", "播放慢点", "减速播放"),
            RemoteCommand.Act("speed.down"), "倍速放慢"),
        // 继续观看
        Rule(listOf("接着看", "继续看", "继续观看", "上次看到哪", "没看完的", "看到哪了"),
            RemoteCommand.Act("resume.latest"), "继续上次观看"),
        // 详情页
        Rule(listOf("收藏这个", "收藏它", "收藏当前", "加收藏", "取消收藏", "收藏一下"),
            RemoteCommand.Act("favorite.toggle"), "已切换收藏"),
        // 音量：走系统媒体音量，不依赖播放器是否在跑
        Rule(listOf("声音大一点", "音量大一点", "音量调大", "声音调大", "大声一点", "大声点", "声音大点", "大点声"),
            RemoteCommand.Act("volume.up"), "音量调大"),
        Rule(listOf("声音小一点", "音量小一点", "音量调小", "声音调小", "小声一点", "小声点", "声音小点", "小点声"),
            RemoteCommand.Act("volume.down"), "音量调小"),
        Rule(listOf("取消静音", "解除静音", "打开声音", "关掉静音"),
            RemoteCommand.Act("volume.mute", "off"), "取消静音"),
        Rule(listOf("静音", "关掉声音", "关闭声音", "没声音了"),
            RemoteCommand.Act("volume.mute", "on"), "已静音"),
        // 内容类型。放在随机推荐之后：`随机推荐一部漫剧` 里也有「漫剧」，
        // 让更长的「随机推荐一部漫剧」先命中。
        Rule(listOf("随机推荐一部漫剧", "随机来个漫剧", "随机一部漫剧", "随便来个漫剧"),
            RemoteCommand.Act("random.pick", "comic"), "随机一部漫剧"),
        Rule(listOf("随机推荐一部短剧", "随机来个短剧", "随机一部短剧", "随便来个短剧"),
            RemoteCommand.Act("random.pick", "short"), "随机一部短剧"),
        Rule(listOf("随机推荐一部ai剧", "随机一部ai剧", "随机推荐一部ai"),
            RemoteCommand.Act("random.pick", "ai"), "随机一部 AI 剧"),
        Rule(listOf("随机推荐", "随便来一部", "随机一部", "来点随机的", "随便看看", "推荐一部", "有什么好看的", "换一部看看"),
            RemoteCommand.Act("random.pick"), "随机推荐"),
        Rule(listOf("切换漫剧", "换成漫剧", "看漫剧", "漫剧"),
            RemoteCommand.Act("type.set", "comic"), "漫剧"),
        Rule(listOf("切换短剧", "换成短剧", "看短剧", "短剧"),
            RemoteCommand.Act("type.set", "short"), "短剧"),
        Rule(listOf("切换ai剧", "换成ai剧", "看ai剧", "ai剧"),
            RemoteCommand.Act("type.set", "ai"), "AI 剧"),
        // 关于与工具
        Rule(listOf("清理缓存", "清缓存", "释放空间", "缓存清理"),
            RemoteCommand.Act("cache.clear"), "正在清理缓存"),
        Rule(listOf("观看统计", "看了多少", "看剧时长", "统计一下"),
            RemoteCommand.Act("stats.show"), "观看统计"),
        Rule(listOf("备份记录", "导出记录", "备份"),
            RemoteCommand.Act("backup.show"), "本机备份"),
        Rule(listOf("手机快传", "快传", "传文件", "传apk"),
            RemoteCommand.Act("quick.send"), "手机快传"),
        // 快传的三件事：换一张码、看收到的文件、把文件清掉。
        // 「快传文件」刻意比「快传」长：说「打开快传文件」要落到文件列表，而不是再开一个上传面板。
        Rule(listOf("刷新二维码", "二维码刷新", "换个二维码", "换一个二维码", "重新生成二维码", "刷新快传码"),
            RemoteCommand.Act("transfer.refresh"), "二维码已刷新"),
        Rule(listOf("打开快传文件", "快传文件", "查看快传文件", "看看快传文件", "打开收到的文件", "收到的文件", "传过来的文件"),
            RemoteCommand.Act("transfer.files"), "快传文件"),
        Rule(listOf("清空快传文件", "清空收到的文件", "清空传过来的文件", "删掉快传文件", "删除快传文件", "清空文件"),
            RemoteCommand.Act("transfer.clear"), "清空快传文件"),
        // 搜索历史主站与片源各存一份，一句话两边一起清——人不会记得自己在哪一栏搜过。
        Rule(listOf("清空搜索历史", "清除搜索历史", "清空搜索记录", "清除搜索记录", "删掉搜索历史", "清空历史记录"),
            RemoteCommand.Act("history.clear"), "已清空搜索历史"),
        Rule(listOf("遥控地址", "遥控器地址", "局域网地址", "遥控接口"),
            RemoteCommand.Act("remote.address"), "遥控地址"),
        Rule(listOf("小爱同学设置", "小爱设置", "语音设置", "小爱连接"),
            RemoteCommand.Act("xiaoai.show"), "小爱同学"),
        Rule(listOf("登录红果账户", "登录红果账号", "红果账号", "我的账号", "登录账号", "账号登录", "扫码登录", "登录"),
            RemoteCommand.Act("account.show"), "账号"),
        Rule(listOf("接口自检", "自检", "检查接口"),
            RemoteCommand.Act("check.interface"), "接口自检"),
        Rule(listOf("恢复隐藏", "恢复推荐", "取消隐藏"),
            RemoteCommand.Act("hidden.restore"), "已恢复隐藏推荐")
    ).sortedByDescending { rule -> rule.words.maxOf { it.length } }

    /**
     * 模块词表。这里刻意**不含**「搜索」：`搜一下` 后面跟着的通常是剧名，
     * 把它当导航会把人扔到空搜索页。要跳搜索页请说「打开搜索页」。
     */
    private val MODULES: List<ModuleRule> = listOf(
        ModuleRule(listOf("首页", "主页", "回首页", "回到首页"), "home", "首页"),
        ModuleRule(listOf("搜索页", "打开搜索", "去搜索"), "search", "搜索"),
        ModuleRule(listOf("排行榜", "排行", "榜单", "热播榜", "榜"), "rank", "排行榜"),
        ModuleRule(listOf("收藏夹", "我的收藏", "收藏页"), "favorite", "收藏"),
        ModuleRule(listOf("历史记录", "观看历史", "最近观看", "观看记录", "历史"), "history", "最近观看"),
        ModuleRule(listOf("设置页", "设置", "系统设置"), "settings", "设置"),
        ModuleRule(listOf("三方源", "片源页", "片源"), "source", "片源"),
        ModuleRule(listOf("本地库", "本地视频", "nas"), "library", "本地库")
    ).sortedByDescending { rule -> rule.words.maxOf { it.length } }

    /** 列表词 → `list.play` 的列表键。 */
    private val LIST_WORDS: List<Pair<String, List<String>>> = listOf(
        "history" to listOf("历史记录", "观看历史", "最近观看", "观看记录", "历史", "记录"),
        "favorite" to listOf("收藏"),
        "episodes" to listOf("选集", "集数"),
        "catalog" to listOf("列表", "首页", "这里", "当前")
    )

    private val LIST_LABELS = mapOf(
        "history" to "最近观看", "favorite" to "收藏",
        "catalog" to "列表", "episodes" to "选集", "current" to "当前列表"
    )

    /** 句首的语气词：只在「认动作」时剥掉，认剧名时用原句（`请回答1988` 不能被剥成 `回答1988`）。 */
    private val LEAD_WORDS = listOf(
        "小爱同学", "你能不能", "可不可以", "麻烦你", "帮我", "帮忙", "给我",
        "我要", "我想", "想要", "能不能", "可以", "现在", "直接", "麻烦", "请", "你"
    )

    private val TAIL_WORDS = listOf("一下", "呗", "吧", "呢", "啊", "哦", "嘛", "呀")

    /** 剧名句子的动词前缀，只在「认剧名」这一步剥。 */
    private val TITLE_LEADS = listOf(
        "我要看", "我想看", "我要搜", "我想搜", "帮我找", "帮我搜",
        "播放", "搜一下", "搜搜", "搜索", "找一下", "找找", "看看", "看", "搜", "找", "放"
    )

    /** 标点与装饰：书名号/引号会夹在剧名里，统一当分隔符处理。 */
    private val PUNCTUATION = Regex("[，。！？；：、“”‘’（）《》〈〉【】…—·,.!?;:\"'()\\[\\]<>]+")

    /** 「快进」「快退」这类相对定位的动词。 */
    private val SEEK_FORWARD = listOf("快进", "前进", "往前")
    private val SEEK_BACKWARD = listOf("快退", "后退", "倒退", "往回倒", "倒回去", "往后")

    /** 返回与退出。`返回首页` 这类带模块词的说法由模块先吃掉，走不到这里。 */
    private val BACK_WORDS = listOf(
        "返回上一级", "返回上级", "返回上一层", "返回上一页", "回到上一级",
        "上一级", "退一步", "返回", "退回"
    )

    /** 定时停止的触发词。 */
    private val SLEEP_WORDS = listOf("定时", "睡眠", "自动关机", "关电视", "关机", "看完这集", "看完这一集")

    /** 弱触发词：只有带上时长才当定时（`半小时后关掉`），单独出现多半是在说「关掉弹幕」。 */
    private val SLEEP_LOOSE = listOf("后关掉", "就关", "再关", "然后关")

    /** 剧名位置不能是这些「列表/导航」词，否则 `选集第3集` 会被当成搜「选集」。 */
    private val NOT_TITLES = setOf("选集", "列表", "历史", "首页", "收藏", "排行榜", "搜索", "片源", "设置", "这里", "当前")

    // ---------------------------------------------------------------- 入口

    /**
     * 解析一句话。
     *
     * @return null 表示「不像任何能执行的东西」——调用方应当什么都不做，而不是猜。
     */
    fun parse(raw: String): Plan? {
        val text = normalize(raw)
        if(text.length < 2) return null

        // 「认动作」用剥过语气词的形式；「认剧名」用原句，避免把真片名开头剥掉
        val bare = stripPoliteness(text)
        if(bare.isEmpty()) return null

        // 1. 剧名 + 集数：`文盲修仙第七集` / `我要看黄石第五季第3集`
        titleWithEpisode(text)?.let { return it }

        // 2. 列表 + 序数：`历史记录第二个` / `收藏第 3 个`
        val ordinal = ordinalOf(bare)
        if(ordinal != null) {
            val kind = listKindOf(bare)
            if(kind != null) {
                return Plan(RemoteCommand.Act("list.play", "$kind:${ordinal.value}"), "打开${LIST_LABELS[kind] ?: kind}第 ${ordinal.value} 个")
            }
            // 没有列表语境时，只有「整句话基本就是这个序号」才当定位。
            // 否则《黄石第五季》《第九区》这类片名会被当成「第五个」「第九个」。
            if(ordinal.end >= bare.length - 1 && bare.length <= 10) {
                val target = if(bare.contains("集")) "episodes" else "current"
                return Plan(RemoteCommand.Act("list.play", "$target:${ordinal.value}"), "第 ${ordinal.value} 个")
            }
        }

        // 3. 时间定位：`快进30秒` / `后退一分钟`（放在倍速之前：`快进` 与 `1.5倍` 不会同时出现）
        seekOf(bare)?.let { return it }

        // 4. 倍速：`1.5倍速`。用未规范化的原文，normalize 会把小数点当标点删掉
        speedOf(raw)?.let { return it }

        // 5. 定时停止：`半小时后关掉` / `播完这集就关`
        sleepOf(bare)?.let { return it }

        // 6. 动作词表（精确包含，长词优先）
        exact(bare)?.let { return it }

        // 7. 模块导航
        moduleOf(bare)?.let { rule -> return Plan(RemoteCommand.Open(rule.module), "打开${rule.label}") }

        // 8. 返回 / 退出
        backOf(bare)?.let { return it }

        // 9. 模糊兜底：允许说错/多说几个字
        fuzzy(bare)?.let { return it }

        // 10. 剩下的当剧名
        titleOf(text)?.let { return Plan(RemoteCommand.Watch(it), "搜索「$it」") }
        return null
    }

    // ---------------------------------------------------------------- 各步实现

    /**
     * 「剧名 + 集数」：`我要看文盲修仙的第七集` → `Watch(文盲修仙, 7)`。
     *
     * 只说集数不说剧名（`我要看第七集`）不在这里处理——交给后面的序数规则，
     * 那是「当前这部剧的第 7 集」，两种语义不能混。
     */
    private fun titleWithEpisode(text: String): Plan? {
        val match = EPISODE.find(text) ?: return null
        val episode = chineseNumber(match.groupValues[1]) ?: return null
        if(episode !in 1..999) return null
        val head = (text.substring(0, match.range.first) + " " + text.substring(match.range.last + 1)).trim()
        val title = cleanTitle(head) ?: return null
        if(title in NOT_TITLES) return null
        return Plan(RemoteCommand.Watch(title, episode), "《$title》第 $episode 集")
    }

    /** 精确匹配：任意别名是句子的子串。返回命中的最长别名对应的规则。 */
    private fun exact(bare: String): Plan? {
        var best: Pair<Rule, String>? = null
        for(rule in RULES) {
            for(word in rule.words) {
                if(!bare.contains(word)) continue
                if(best == null || word.length > best!!.second.length) best = rule to word
            }
        }
        val picked = best ?: return null
        return Plan(picked.first.command, picked.first.echo)
    }

    private fun moduleOf(bare: String): ModuleRule? {
        var best: Pair<ModuleRule, String>? = null
        for(rule in MODULES) {
            for(word in rule.words) {
                if(!bare.contains(word)) continue
                if(best == null || word.length > best!!.second.length) best = rule to word
            }
        }
        return best?.first
    }

    private fun listKindOf(bare: String): String? {
        var best: Pair<String, String>? = null
        for((kind, words) in LIST_WORDS) {
            for(word in words) {
                if(!bare.contains(word)) continue
                if(best == null || word.length > best!!.second.length) best = kind to word
            }
        }
        return best?.first
    }

    /**
     * 快进 / 快退 / 跳到某个时间点。
     *
     * 只说「快进」不说时长时按 15 秒走——这是遥控器上最常见的步长，
     * 模糊的指令给一个确定的默认值，比反问一句「快进多少」更接近人的预期。
     */
    private fun seekOf(bare: String): Plan? {
        val dir = (SEEK_BACKWARD + SEEK_FORWARD).sortedByDescending { it.length }.firstOrNull { bare.contains(it) } ?: return null
        val forward = dir in SEEK_FORWARD
        val seconds = secondsIn(bare) ?: 15
        val ms = if(forward) seconds * 1000L else -seconds * 1000L
        return Plan(RemoteCommand.Step(ms), (if(forward) "快进 " else "快退 ") + durationLabel(seconds))
    }

    /** 倍速：`1.5倍速` / `两倍`。数值必须落在播放器支持的档位上，取最近的一档。 */
    private fun speedOf(text: String): Plan? {
        val match = SPEED.find(text) ?: return null
        val token = match.groupValues[1]
        val value: Float? = when {
            token == "半" -> 0.5f
            token.firstOrNull()?.isDigit() == true -> token.toFloatOrNull()
            else -> chineseNumber(token)?.toFloat()
        }
        val speed = value ?: return null
        // 「八倍镜」「三倍人生」是片名：倍数只认出 0.5～3 之间的说法
        if(speed !in 0.5f..3f) return null
        // 不带「倍速」的句子，要求「倍」正好落在句尾，否则仍按片名处理
        if(!text.contains("倍速") && match.range.last != text.lastIndex) return null
        val nearest = PlaybackSpeed.options.minByOrNull { kotlin.math.abs(it - speed) } ?: return null
        return Plan(RemoteCommand.Speed(nearest), "倍速 ${PlaybackSpeed.label(nearest)}")
    }

    /** 定时停止：`半小时后关掉` → `sleep.set min:30`；`看完这集就关` → `sleep.set ep:1`。 */
    private fun sleepOf(bare: String): Plan? {
        val explicit = SLEEP_WORDS.any { bare.contains(it) }
        val loose = SLEEP_LOOSE.any { bare.contains(it) }
        if(!explicit && !loose) return null
        if(bare.contains("这集") || bare.contains("这一集")) {
            return Plan(RemoteCommand.Act("sleep.set", "ep:1"), "看完这集就停")
        }
        // 弱触发词必须带时长：`关掉` 这种两个字在别的指令里到处都是
        val seconds = secondsIn(bare, 3600) ?: (if(explicit) 1800 else return null)
        val minutes = (seconds / 60).coerceIn(1, 180)
        return Plan(RemoteCommand.Act("sleep.set", "min:$minutes"), "定时 $minutes 分钟后停止")
    }

    /** 返回上一级。只说「返回」也算——它是这组里唯一没有歧义的解释。 */
    private fun backOf(bare: String): Plan? {
        val hit = BACK_WORDS.firstOrNull { bare.contains(it) } ?: return null
        return Plan(RemoteCommand.Back, if(hit == "返回" || hit == "退回") "返回上一级" else hit)
    }

    /**
     * 模糊兜底：只容忍**多说**几个字，不容忍**少一个字**。
     *
     * 判据是「别名的每一个不同字都在句子里出现过」，也就是命中率必须是 100%。
     * 这个门槛看似严格，实际正是想要的：`检查版本是否有更新` 里「检查」「更」「新」都在，
     * 能认出 `检查更新`；而 `我要看黄金时代` 共用不到「接」「着」，认不出 `接着看`。
     *
     * 曾经放过 0.75 的宽松档，结果 `关闭弹窗` 因为只差一个「幕」字被判成 `关闭弹幕`——
     * 四个字错一个字，语义就可能整个拧了（关窗口 vs 开弹幕）。中文短词里没有「差一个字还算对」，
     * 所以这里宁可退到剧名那一步，也不猜。
     *
     * 两个不同动作的得分并列时同样放弃：并列意味着这句话真的有两种解释。
     */
    private fun fuzzy(bare: String): Plan? {
        var bestScore = 0.0
        var best: Rule? = null
        var tie = false
        for(rule in RULES) {
            for(word in rule.words) {
                if(word.length < 3) continue
                if(bare.length > word.length * 2) continue
                val distinct = word.toSet()
                val hit = distinct.count { it in bare }
                // 必须全覆盖：少一个字不猜。
                if(hit < distinct.size) continue
                val score = 1.0
                if(score > bestScore) { bestScore = score; best = rule; tie = false }
                else if(best != null && best !== rule) tie = true
            }
        }
        if(best == null || tie) return null
        return Plan(best.command, best.echo)
    }

    /**
     * 剧名提取：剥掉句首动词后剩下的部分。
     *
     * 用原句 [text] 而不是剥过语气词的 bare：`请回答1988` 这类片名一旦被剥掉「请」
     * 就再也搜不到了。剥完剩下的长度不足 2 就放弃（一个字的搜索词没有意义）。
     */
    private fun titleOf(text: String): String? = cleanTitle(text)

    /** 剥掉句首动词与句末「的」，得到干净的片名；不合格返回 null。 */
    private fun cleanTitle(text: String): String? {
        var title = text.trim()
        var changed = true
        while(changed) {
            changed = false
            for(lead in TITLE_LEADS) {
                // 不要求「剩下还有字」：`我要看第七集` 里剥完是空的，说明没有片名，
                // 这时该由序数规则接手「当前这部剧的第 7 集」，而不是把「我要看」当地剧名。
                if(title.startsWith(lead)) {
                    title = title.substring(lead.length).trim()
                    changed = true
                    break
                }
            }
        }
        title = title.trim(*SEPARATORS).trim()
        while(title.length > 2 && title.endsWith("的")) title = title.dropLast(1).trim()
        title = title.trim(*SEPARATORS).trim()
        if(title.length < 2 || title.length > 80) return null
        return title
    }

    /**
     * 序数：`第二个` / `第2个` / `第三集` / `第十二条`。
     *
     * 两条排除规则都是为了别把片名当序号：
     * - 后面紧跟「季」的（《黄石第五季》）是片名，不是「第五个」；
     * - 只认带「第」的写法，`看两个` 这种在中文里歧义太大，不猜。
     *
     * 返回 [Ordinal.end] 让调用方判断「序号是不是占满了整句话」。
     */
    private fun ordinalOf(bare: String): Ordinal? {
        for(match in ORDINAL.findAll(bare)) {
            val after = bare.getOrNull(match.range.last + 1)
            if(after == '季') continue
            val value = chineseNumber(match.groupValues[1]) ?: continue
            if(value in 1..999) return Ordinal(value, match.range.last)
        }
        return null
    }

    private data class Ordinal(val value: Int, val end: Int)

    /**
     * 句子里出现的时长，单位秒。
     *
     * 支持 `30秒` / `一分钟` / `半小时` / `一刻钟` / `1个半小时`。上限 10 分钟，
     * 与 [REMOTE_STEP_MAX_MS] 对齐——超出的说法多半不是想「快进」。
     */
    private fun secondsIn(bare: String, limit: Int = 600): Int? {
        if(bare.contains("半小时")) return 1800.coerceAtMost(limit)
        if(bare.contains("一刻钟")) return 900.coerceAtMost(limit)
        val match = DURATION.find(bare) ?: return null
        val token = match.groupValues[1]
        // 第 2 组是「个」，单位在第 3 组
        val unit = match.groupValues[3]
        val value = if(token == "半") 0.5 else chineseNumber(token)?.toDouble() ?: return null
        val seconds = when(unit.first()) {
            '秒' -> value
            '分' -> value * 60
            '小' -> value * 3600
            '钟' -> if(unit == "钟头") value * 3600 else value * 60
            else -> return null
        }
        return seconds.toInt().coerceIn(1, limit)
    }

    private fun durationLabel(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} 小时"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        else -> "$seconds 秒"
    }

    /** 中文数字 → 整数。支持「两」「十」「十二」「二十」「九十九」。 */
    private fun chineseNumber(text: String): Int? {
        if(text.isEmpty()) return null
        if(text.all { it.isDigit() }) return text.toIntOrNull()
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if(text == "十") return 10
        if(text.length == 1) return digits[text[0]]
        val ten = text.indexOf('十')
        if(ten < 0 || text.length > ten + 2) return null
        val high = if(ten == 0) 1 else digits[text[ten - 1]] ?: return null
        val lowChar = if(ten == text.length - 1) null else text[ten + 1]
        val low = if(lowChar == null) 0 else digits[lowChar] ?: return null
        return high * 10 + low
    }

    /** 全角转半角、去标点、压空白。 */
    internal fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for(ch in raw.trim()) {
            when {
                ch.code == 0x3000 -> sb.append(' ')
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(PUNCTUATION, " ").replace(Regex("\\s+"), " ").trim()
    }

    /** 剥句首语气词与句尾虚词，只用于「认动作」。 */
    internal fun stripPoliteness(text: String): String {
        var out = text
        var changed = true
        while(changed) {
            changed = false
            for(word in LEAD_WORDS) {
                if(out.startsWith(word) && out.length > word.length) {
                    out = out.substring(word.length).trim()
                    changed = true
                    break
                }
            }
        }
        var trimmed = true
        while(trimmed) {
            trimmed = false
            for(word in TAIL_WORDS) {
                if(out.endsWith(word) && out.length > word.length) {
                    out = out.dropLast(word.length).trim()
                    trimmed = true
                    break
                }
            }
        }
        return out
    }

    private val ORDINAL = Regex("第\\s*([0-9]+|[零一二两三四五六七八九十]+)\\s*(?:个|条|部|项|号|集|话|期|回)?")

    /** 剧名里的集号：`第七集` / `第 12 话` / `第3期`。 */
    private val EPISODE = Regex("第\\s*([0-9]+|[零一二两三四五六七八九十]+)\\s*(?:集|话|期|回)")

    /** 时长：`30秒` / `一分钟` / `半分钟` / `两小时`。 */
    private val DURATION = Regex("([0-9]+|[零一二两三四五六七八九十]+|半)\\s*(个)?\\s*(秒钟|秒|分钟|分|小时|钟头|刻钟)")

    /** 倍速：`1.5倍` / `两倍` / `半倍`。 */
    private val SPEED = Regex("([0-9]+(?:\\.[0-9]+)?|[零一二两三四五六七八九十]+|半)\\s*倍")

    private val SEPARATORS = charArrayOf(' ', '\u3000', '·', '-', '_', '：', ':', '|', '/', '、')
}
