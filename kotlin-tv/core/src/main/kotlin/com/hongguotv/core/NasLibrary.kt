// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 本地库（NAS）目录组织与文件名识别。
 * 规则来源：docs/design/local-library/filename-parsing-rules.md
 * 原则：宁可不识别，也不识别错；识别失败降级为文件列表直选。
 */
object NasLibrary {

    const val MAX_FILES = 20000
    const val MAX_DEPTH = 5
    const val FILES_DEGRADE_MIN = 4
    const val SEASON_SPECIAL = 0      // 特别篇
    const val SEASON_DATE = 90        // 日期分组

    data class NasEntry(val path: String, val isDir: Boolean, val size: Long = 0)

    data class NasEpisode(
        val season: Int,
        val episode: Int?,          // null = 未识别，display 用原文件名
        val display: String,
        val file: NasEntry,
        val date: String? = null,
    )

    data class NasSeason(
        val season: Int,
        val label: String,
        val episodes: List<NasEpisode>,
        val missing: List<Int>,
    )

    enum class Kind { SERIES, MOVIE, FILES }

    data class NasSeries(
        val title: String,
        val path: String,
        val kind: Kind,
        val seasons: List<NasSeason>,
    ) {
        val episodeCount: Int get() = seasons.sumOf { it.episodes.size }
    }

    // ---------------- 常量表 ----------------

    private val VIDEO_EXT = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "ts", "m2ts",
        "rmvb", "webm", "mpg", "mpeg", "vob", "3gp",
    )
    private val SKIP_KEYWORDS = listOf("sample", "trailer", "预告", "样片")
    private val SKIP_DIRS = setOf("@eadir", "#recycle", "\$recycle.bin")

    private val BRACKET = Regex("""\[([^]]*)]|【([^】]*)】""")
    private val BRACKET_NUM = Regex("""^\d{1,3}$""")
    private val VERSION = Regex("""(?i)\bv(\d{1,2})(?!\d)""")
    private val YEAR = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")
    private val TECH = Regex(
        """(?i)(?<![A-Za-z0-9])(4320p|2160p|1080p|1080i|720p|576p|480p|8k|4k|uhd|fhd|hdr10|hdr|10bit|8bit|x265|x264|h265|h264|hevc|avc|av1|vp9|dolby|dts-hd|dts|truehd|atmos|aac|ac3|flac|7\.1|5\.1|2\.0|blu-?ray|bdrip|web-?dl|webrip|hdtv|dvdrip|remux|hd)(?![A-Za-z0-9])""" +
            "|修复版|导演剪辑版|加长版|未删减|国语|粤语|中字|内嵌|外挂"
    )
    private val DATE = Regex("""(?<!\d)(20\d{2})[.\-年](0?[1-9]|1[0-2])[.\-月](0?[1-9]|[12]\d|3[01])日?(?!\d)""")
    private val R1 = Regex("""(?i)s(\d{1,2})[._\s-]*e(\d{1,3})""")
    private val R2 = Regex("""第([0-9]{1,3}|[零一二三四五六七八九十两]{1,4})(?:集|话|期|回)""")
    private val R3 = Regex("""(?i)(?<![A-Za-z0-9])e(?:p)?(\d{1,3})(?![A-Za-z0-9])""")
    private val R5 = Regex("""(?:^|\D)(\d{1,3})$""")

    private val SEASON_DIR = Regex(
        """(?i)^(s\d{1,2}|season\s*\d{1,2}|第[0-9零一二三四五六七八九十两]{1,4}季|specials?|sp|ova|番外|特别篇)$"""
    )

    // ---------------- 入口 ----------------

    /** 把 WebDAV 列出的全部条目组织成剧集/电影/文件列表。path 以 "/" 分隔，首段可为空。 */
    fun organize(entries: List<NasEntry>): List<NasSeries> {
        val files = entries.asSequence()
            .filter { !it.isDir }
            .filter { isVideo(it.path) }
            .filter { !skipped(it.path) }
            .filter { depth(it.path) <= MAX_DEPTH }
            .take(MAX_FILES)
            .toList()

        // 按父目录分组；allDirs 包含所有层级的祖先目录（含无直接文件的中间目录）
        val byDir = files.groupBy { parentPath(it.path) }
        val allDirs = files.flatMap { f ->
            val segs = parentPath(f.path).split('/').filter { it.isNotEmpty() }
            segs.indices.map { segs.take(it + 1).joinToString("/") }
        }.toSet()

        // 目录 -> 其一级子目录中含视频的目录名集合
        fun childDirsWithVideo(dir: String): List<String> {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            return allDirs.asSequence()
                .filter { it != dir && it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        }

        // 已被上级吸收为季目录的路径前缀
        val absorbed = mutableSetOf<String>()
        // 已生成条目的目录
        val handled = mutableSetOf<String>()
        val result = mutableListOf<NasSeries>()

        // 根目录散放文件：每个文件一部电影
        byDir[""]?.forEach { f ->
            result.add(movieFromFile(f, ""))
        }
        handled += ""

        // 按路径深度排序，保证父目录先处理
        val dirs = allDirs.filter { it.isNotEmpty() }.sortedBy { it.count { c -> c == '/' } }
        for (dir in dirs) {
            if (absorbed.any { dir == it || dir.startsWith("$it/") }) continue
            if (isSeasonDirName(dir.substringAfterLast('/'))) {
                // 裸季目录（父目录不是剧集）：自己成为条目
                result.add(buildSeries(dir, byDir[dir].orEmpty(), emptyMap(), titleOf(dir)))
                handled += dir
                continue
            }
            val own = byDir[dir].orEmpty()
            val seasonChildren = childDirsWithVideo(dir)
                .filter { isSeasonDirName(it) }
                .associateWith { "$dir/$it" }
            if (own.isEmpty() && seasonChildren.isEmpty()) continue

            if (own.isEmpty() && seasonChildren.size == childDirsWithVideo(dir).size) {
                // 纯季目录结构：多季剧集
                val seasonFiles = seasonChildren.mapValues { (_, p) -> byDir[p].orEmpty() }
                absorbed += seasonChildren.values
                result.add(buildSeries(dir, emptyList(), seasonChildren.entries.associate { (name, p) ->
                    seasonNumber(name) to byDir[p].orEmpty()
                }, titleOf(dir)))
                handled += dir
            } else {
                // 本目录文件为第 1 季；季子目录并入；其他子目录独立处理
                val seasons = linkedMapOf<Int, List<NasEntry>>()
                if (own.isNotEmpty()) seasons[1] = own
                seasonChildren.forEach { (name, p) ->
                    seasons[seasonNumber(name)] = byDir[p].orEmpty()
                    absorbed += p
                }
                result.add(buildSeries(dir, own, seasons, titleOf(dir)))
                handled += dir
            }
        }
        return result
    }

    // ---------------- 条目构建 ----------------

    private fun buildSeries(
        dir: String,
        own: List<NasEntry>,
        seasons: Map<Int, List<NasEntry>>,
        title: String,
    ): NasSeries {
        // own 传空时 seasons 已包含全部；own 非空时 seasons 也已包含 1->own（调用方保证）
        val seasonMap = if (seasons.isEmpty() && own.isNotEmpty()) mapOf(1 to own) else seasons

        // 先解析全部文件
        val parsedBySeason = seasonMap.mapValues { (_, files) -> files.map { parse(it) } }
        val total = parsedBySeason.values.sumOf { it.size }
        val unrecognized = parsedBySeason.values.flatten().count { it.episode == null && it.date == null }

        // 降级：文件数 >=4 且超过半数无法识别 -> 文件列表
        if (total >= FILES_DEGRADE_MIN && unrecognized * 2 > total) {
            val all = seasonMap.values.flatten()
                .sortedWith(naturalOrder { fileBase(it.path) })
                .map { NasEpisode(1, null, fileBase(it.path), it) }
            return NasSeries(title, dir, Kind.FILES, listOf(NasSeason(1, "文件列表", all, emptyList())))
        }

        val seasonList = parsedBySeason.toSortedMap().map { (season, files) ->
            buildSeason(season, files)
        }.sortedBy { if (it.season == SEASON_SPECIAL) Int.MAX_VALUE else it.season }

        val kind = if (total == 1) Kind.MOVIE else Kind.SERIES
        val fixed = if (kind == Kind.MOVIE) seasonList.map { s ->
            s.copy(episodes = s.episodes.map { e -> if (e.episode == null && e.date == null) e.copy(episode = 1) else e })
        } else seasonList
        return NasSeries(title, dir, kind, fixed)
    }

    private fun buildSeason(season: Int, files: List<Parsed>): NasSeason {
        val dated = files.filter { it.date != null }
        val rest = files - (files.filter { it.date != null }.toSet())
        val datedEpisodes = dated.sortedBy { it.date }
            .map { NasEpisode(SEASON_DATE, null, it.base, it.file, it.date) }

        // 同集多版本去重
        val recognized = rest.filter { it.episode != null }
            .groupBy { it.episode!! }
            .map { (_, group) ->
                group.sortedWith(compareByDescending<Parsed> { it.version }.thenByDescending { it.resRank }.thenByDescending { it.file.size }).first()
            }
            .sortedBy { it.episode }
        val unparsed = rest.filter { it.episode == null }
            .sortedWith(naturalOrder { it.base })

        val episodes = recognized.map {
            NasEpisode(season, it.episode, "第 ${it.episode} 集", it.file)
        } + unparsed.map { NasEpisode(season, null, it.base, it.file) }

        val eps = recognized.mapNotNull { it.episode }
        val missing = if (eps.isEmpty()) emptyList() else (1..eps.max()).filter { it !in eps }

        val label = when (season) {
            SEASON_SPECIAL -> "特别篇"
            else -> "第 $season 季"
        }
        // 日期文件挂在该季之后单独成组由调用方合并；这里简单并入
        return NasSeason(season, label, episodes + datedEpisodes, missing)
    }

    private fun movieFromFile(f: NasEntry, dir: String): NasSeries {
        val p = parse(f)
        val title = p.cleanedTitle.ifBlank { fileBase(f.path) }
        val ep = NasEpisode(1, 1, p.base, f, p.date)
        return NasSeries(title, dir, Kind.MOVIE, listOf(NasSeason(1, "", listOf(ep), emptyList())))
    }

    // ---------------- 单文件解析 ----------------

    internal data class Parsed(
        val file: NasEntry,
        val base: String,          // 去扩展名的原始名
        val cleanedTitle: String,  // 清洗后可作剧名的文本
        val season: Int?,
        val episode: Int?,
        val version: Int,
        val resRank: Int,
        val date: String?,
    )

    internal fun parse(f: NasEntry): Parsed {
        val base = fileBase(f.path)
        var text = base

        // 版本号
        var version = 0
        VERSION.find(text)?.let { m -> version = m.groupValues[1].toIntOrNull() ?: 0; text = text.removeRange(m.range) }

        // 方括号：纯数字留给 R4，其余剔除
        var bracketEp: Int? = null
        text = BRACKET.replace(text) { m ->
            val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (BRACKET_NUM.matches(inner.trim())) {
                if (bracketEp == null) bracketEp = inner.trim().toIntOrNull()
                ""
            } else ""
        }

        val resRank = resRankOf(base)

        // 技术标记与年份剔除
        text = TECH.replace(text, " ")
        text = YEAR.replace(text, " ")

        // 日期型优先（避免末尾数字把日期尾巴当集号）
        val dateM = DATE.find(base)
        if (dateM != null) {
            val dateStr = "${dateM.groupValues[1]}-${dateM.groupValues[2].padStart(2, '0')}-${dateM.groupValues[3].padStart(2, '0')}"
            val cleaned = cleanTitle(text.replace(dateM.value, " "))
            return Parsed(f, base, cleaned, null, null, version, resRank, dateStr)
        }

        var season: Int? = null
        var episode: Int? = null

        R1.find(text)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = validEp(m.groupValues[2].toIntOrNull())
            text = text.removeRange(m.range)
        }
        if (episode == null) R2.find(text)?.let { m ->
            episode = validEp2(m.groupValues[1])
            text = text.removeRange(m.range)
        }
        if (episode == null) R3.find(text)?.let { m ->
            episode = validEp(m.groupValues[1].toIntOrNull())
            text = text.removeRange(m.range)
        }
        if (episode == null && bracketEp != null) episode = validEp(bracketEp)
        if (episode == null) {
            val t = text.trim()
            R5.find(t)?.let { m -> episode = validEp(m.groupValues[1].toIntOrNull()) }
        }

        return Parsed(f, base, cleanTitle(text), season, episode, version, resRank, null)
    }

    // ---------------- 工具 ----------------

    private fun isVideo(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    private fun skipped(path: String): Boolean {
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.any { it.startsWith('.') || it.lowercase() in SKIP_DIRS }) return true
        val base = segs.lastOrNull()?.substringBeforeLast('.')?.lowercase() ?: return true
        return SKIP_KEYWORDS.any { base.contains(it) }
    }

    private fun depth(path: String): Int = path.split('/').count { it.isNotEmpty() }

    private fun parentPath(path: String): String {
        val segs = path.split('/').filter { it.isNotEmpty() }
        return segs.dropLast(1).joinToString("/")
    }

    private fun fileBase(path: String): String =
        path.split('/').last().substringBeforeLast('.')

    internal fun isSeasonDirName(name: String): Boolean = SEASON_DIR.matches(name.trim())

    internal fun seasonNumber(name: String): Int {
        val n = name.trim()
        if (Regex("""(?i)s0{1,2}|specials?|sp|ova|番外|特别篇""").matches(n)) return SEASON_SPECIAL
        Regex("""(?i)^s(\d{1,2})$""").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)^season\s*(\d{1,2})$""").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("""^第([0-9零一二三四五六七八九十两]{1,4})季$""").find(n)?.let {
            return cnNumber(it.groupValues[1]) ?: 1
        }
        return 1
    }

    private fun titleOf(dir: String): String {
        val raw = dir.substringAfterLast('/')
        val cleaned = cleanTitle(YEAR.replace(TECH.replace(BRACKET.replace(raw, ""), " "), " "))
        return cleaned.ifBlank { raw }.ifBlank { "未命名目录" }
    }

    private fun cleanTitle(s: String): String =
        s.replace(Regex("""[._\-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('「', '」', '『', '』', '(', ')', '（', '）')

    private fun validEp(n: Int?): Int? = if (n != null && n in 1..999) n else null
    private fun validEp2(s: String): Int? = validEp(s.toIntOrNull() ?: cnNumber(s))

    internal fun cnNumber(s: String): Int? {
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        return if ('十' in s) {
            val parts = s.split('十')
            if (parts.size != 2) return null
            val tens = if (parts[0].isEmpty()) 1 else digits[parts[0].singleOrNull()] ?: return null
            val ones = if (parts[1].isEmpty()) 0 else digits[parts[1].singleOrNull()] ?: return null
            tens * 10 + ones
        } else if (s.length == 1) digits[s[0]] else null
    }

    private fun resRankOf(name: String): Int {
        val n = name.lowercase()
        return when {
            Regex("""4320p|8k""").containsMatchIn(n) -> 4
            Regex("""2160p|4k|uhd""").containsMatchIn(n) -> 3
            Regex("""1080p|1080i|fhd""").containsMatchIn(n) -> 2
            Regex("""720p|(?<![a-z0-9])hd(?![a-z0-9])""").containsMatchIn(n) -> 1
            else -> 0
        }
    }

    /** 文件名自然排序：数字段按数值比较。 */
    internal fun <T> naturalOrder(selector: (T) -> String): Comparator<T> =
        Comparator { a, b -> naturalCompare(selector(a), selector(b)) }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ea = i; var eb = j
                while (ea < a.length && a[ea].isDigit()) ea++
                while (eb < b.length && b[eb].isDigit()) eb++
                val na = a.substring(i, ea).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(j, eb).trimStart('0').ifEmpty { "0" }
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ea; j = eb
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                i++; j++
            }
        }
        return a.length - b.length
    }
}
