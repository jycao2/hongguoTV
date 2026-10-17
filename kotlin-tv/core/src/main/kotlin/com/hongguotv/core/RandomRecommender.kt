// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import kotlin.random.Random

/**
 * 随机荐剧：在「分类随机页 + 热播榜」之间加权抽样，构建候选池并去重随机抽取。
 *
 * 内容源没有随机接口，靠「随机题材 × 随机页码」合成随机性。分类页是未签名的网站请求，
 * 被封风险低于搜索 App API；30% 概率取热播榜保证质量，70% 取分类长尾保证多样性。
 *
 * 线程安全：本类无锁，调用方需保证 [replenish] 与 [pick] 不并发（通常 [replenish] 跑在后台线程，
 * [pick] 在主线程；用方把 [replenish] 的结果合并回池时再切主线程即可，参考 app 侧用法）。
 */
class RandomRecommender(
    private val repo: ContentRepository,
    initial: List<Series> = emptyList(),
    private val maxPage: Int = 30,
    private val rankWeight: Float = 0.3f,
    private val rand: Random = Random.Default
) {
    private val pool = initial.distinctBy { it.id }.toMutableList()
    /** 近期展示过的 id，避免短时间内重复；达到上限后整体清空。 */
    private val shown = LinkedHashSet<String>()
    private val shownLimit = 80

    val size get() = pool.size

    fun snapshot(): List<Series> = pool.toList()

    /** 从池中随机取 n 条，排除近期展示过的；池空或全展示过则返回空并由调用方补充。 */
    fun pick(n: Int = 8): List<Series> {
        val candidates = pool.filter { it.id !in shown }
        if (candidates.isEmpty()) return emptyList()
        val picked = candidates.shuffled(rand).take(n)
        picked.forEach { shown.add(it.id) }
        if (shown.size > shownLimit) shown.clear()
        return picked
    }

    /**
     * 后台补充候选池。最多抓取 [pages] 个随机页，直到池达到 [target] 条。
     * @return 本次新增条数。
     */
    fun replenish(target: Int = 150, pages: Int = 6): Int {
        var added = 0
        repeat(pages) {
            if (pool.size >= target) return added
            val page = runCatching { fetchRandomPage() }.getOrNull() ?: return@repeat
            val known = pool.mapTo(HashSet()) { it.id }
            for (s in page.items) {
                if (s.id !in known) { pool.add(s); known.add(s.id); added++ }
            }
        }
        return added
    }

    private fun fetchRandomPage(): CatalogPage {
        val type = if (rand.nextBoolean()) ContentType.SHORT else ContentType.COMIC
        if (rand.nextFloat() < rankWeight) {
            val board = if (type == ContentType.COMIC) RankBoard.COMIC else RankBoard.REAL
            // 热播榜深页可信度低，只取前 3 页。
            return repo.ranking(board, rand.nextInt(1, 4))
        }
        val genres = runCatching { repo.genres(type) }.getOrDefault(emptyList())
        val genre = if (genres.isNotEmpty() && rand.nextBoolean()) genres.random(rand) else null
        val page = rand.nextInt(1, maxPage + 1)
        return repo.home(page, type, genre)
    }
}
