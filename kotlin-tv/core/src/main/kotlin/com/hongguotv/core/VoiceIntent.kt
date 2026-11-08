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
 * 1. 列表 + 序数：`历史记录第二个` → `list.play history:2`
 * 2. 动作词表：`检查有没有更新` → `update.check`
 * 3. 模块导航：`进去排行榜` → `open rank`
 * 4. 光有序数：`第五集` → 选集第 5 集
 * 5. 模糊兜底：说错几个字也能认（`检查版本是否有更新`）
 * 6. 剧名：剩下的话当搜索词
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
        Rule(listOf("暂停", "停一下", "先停", "别放了"),
            RemoteCommand.Act("play.toggle"), "已暂停"),
        Rule(listOf("继续播放", "接着放", "恢复播放", "继续"),
            RemoteCommand.Act("play.toggle"), "继续播放"),
        // 继续观看
        Rule(listOf("接着看", "继续看", "继续观看", "上次看到哪", "没看完的", "看到哪了"),
            RemoteCommand.Act("resume.latest"), "继续上次观看"),
        // 详情页
        Rule(listOf("收藏这个", "收藏它", "收藏当前", "加收藏", "取消收藏"),
            RemoteCommand.Act("favorite.toggle"), "已切换收藏"),
        // 关于与工具
        Rule(listOf("清理缓存", "清缓存", "释放空间", "缓存清理"),
            RemoteCommand.Act("cache.clear"), "正在清理缓存"),
        Rule(listOf("观看统计", "看了多少", "看剧时长", "统计一下"),
            RemoteCommand.Act("stats.show"), "观看统计"),
        Rule(listOf("备份记录", "导出记录", "备份"),
            RemoteCommand.Act("backup.show"), "本机备份"),
        Rule(listOf("手机快传", "快传", "传文件", "传apk"),
            RemoteCommand.Act("quick.send"), "手机快传"),
        Rule(listOf("遥控地址", "遥控器地址", "局域网地址", "遥控接口"),
            RemoteCommand.Act("remote.address"), "遥控地址"),
        Rule(listOf("小爱同学设置", "小爱设置", "语音设置", "小爱连接"),
            RemoteCommand.Act("xiaoai.show"), "小爱同学"),
        Rule(listOf("我的账号", "登录账号", "账号登录", "登录"),
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

        // 1. 列表 + 序数：`历史记录第二个` / `收藏第 3 个`
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

        // 2. 动作词表（精确包含，长词优先）
        exact(bare)?.let { return it }

        // 3. 模块导航
        moduleOf(bare)?.let { rule -> return Plan(RemoteCommand.Open(rule.module), "打开${rule.label}") }

        // 4. 模糊兜底：允许说错/多说几个字
        fuzzy(bare)?.let { return it }

        // 5. 剩下的当剧名
        titleOf(text)?.let { return Plan(RemoteCommand.Watch(it), "搜索「$it」") }
        return null
    }

    // ---------------------------------------------------------------- 各步实现

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
     * 模糊兜底。
     *
     * 判据是「别名的每一个不同字都在句子里出现过」，并且句子不能比别名长太多。
     * 这样 `检查版本是否有更新` 能认出 `检查更新`，而 `我要看黄金时代` 不会因为
     * 共用一个「看」字就误判成 `接着看`——因为「接着」两个字都不在句子里。
     *
     * 两个不同动作的得分并列时宁可放弃：并列意味着这句话真的有两种解释。
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
                val score = hit.toDouble() / distinct.size
                if(score < 0.75) continue
                if(score > bestScore) { bestScore = score; best = rule; tie = false }
                else if(score == bestScore && best != null && best !== rule) tie = true
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
    private fun titleOf(text: String): String? {
        var title = text.trim()
        for(lead in TITLE_LEADS) {
            if(title.startsWith(lead) && title.length > lead.length) {
                title = title.substring(lead.length).trim()
                break
            }
        }
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

    private val ORDINAL = Regex("第\\s*([0-9]+|[零一二两三四五六七八九十]+)\\s*(?:个|条|部|集|项|号)?")

    private val SEPARATORS = charArrayOf(' ', '\u3000', '·', '-', '_', '：', ':', '|', '/', '、')
}
