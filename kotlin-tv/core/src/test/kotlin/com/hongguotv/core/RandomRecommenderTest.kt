// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RandomRecommenderTest {
    private fun series(id: Int, title: String = "剧$id") = Series(id.toString(), title)

    private inner class FakeRepo(
        private val genreList: List<Genre> = listOf(Genre("g1", "爱情", listOf("1"))),
        private val pagesPerGenre: Int = 5,
        private val itemsPerPage: Int = 6
    ) : ContentRepository() {
        // Override the public methods used by RandomRecommender via reflection-free subclassing
        override fun home(page: Int, type: ContentType, genre: Genre?): CatalogPage {
            val items = (0 until itemsPerPage).map { series((page - 1) * itemsPerPage + it + 1) }
            return CatalogPage(items, page < pagesPerGenre)
        }
        override fun ranking(board: RankBoard, page: Int): CatalogPage {
            val items = (0 until itemsPerPage).map { series(1000 + (page - 1) * itemsPerPage + it + 1) }
            return CatalogPage(items, page < 3)
        }
        override fun genres(type: ContentType): List<Genre> = genreList
    }

    @Test
    fun pick_returnsItemsFromPool_excludingShown() {
        val rec = RandomRecommender(FakeRepo(), listOf(series(1), series(2), series(3)), rand = Random(1))
        val first = rec.pick(2)
        assertEquals(2, first.size)
        val second = rec.pick(2)
        assertEquals(1, second.size) // 3 - 2 shown = 1 left
    }

    @Test
    fun pick_emptyWhenPoolEmpty() {
        val rec = RandomRecommender(FakeRepo(), emptyList(), rand = Random(1))
        assertTrue(rec.pick(5).isEmpty())
    }

    @Test
    fun shown_resetsAfterLimit() {
        val items = (1..100).map { series(it) }
        val rec = RandomRecommender(FakeRepo(), items, rand = Random(7))
        // shownLimit=80; 17 picks of 5 = 85 shown, triggers clear
        repeat(17) { rec.pick(5) }
        val p = rec.pick(5)
        assertEquals(5, p.size)
    }

    @Test
    fun replenish_addsAndDedups() {
        val rec = RandomRecommender(FakeRepo(itemsPerPage = 6), emptyList(), rand = Random(3))
        val added = rec.replenish(target = 100, pages = 4)
        assertTrue(added >= 6)
        assertTrue(rec.size >= 6)
        assertEquals(rec.size, rec.snapshot().distinctBy { it.id }.size)
    }

    @Test
    fun replenish_stopsAtTarget() {
        val rec = RandomRecommender(FakeRepo(itemsPerPage = 6), emptyList(), rand = Random(3))
        rec.replenish(target = 10, pages = 20)
        assertTrue(rec.size <= 12) // stops around target
    }

    @Test
    fun snapshot_returnsDefensiveCopy() {
        val rec = RandomRecommender(FakeRepo(), listOf(series(1)), rand = Random(1))
        rec.snapshot().toMutableList().clear()
        assertEquals(1, rec.size)
    }

    @Test
    fun pickDoesNotReturnDuplicates() {
        val rec = RandomRecommender(FakeRepo(), (1..20).map { series(it) }, rand = Random(5))
        val p = rec.pick(8)
        assertEquals(8, p.distinctBy { it.id }.size)
    }

    @Test
    fun randomPoolSnapshot_roundTrip() {
        val items = listOf(series(1), series(2))
        val encoded = RandomPoolSnapshot.encode(items, 1000L)
        val decoded = RandomPoolSnapshot.decode(encoded, 2000L)
        assertEquals(2, decoded?.size)
    }

    @Test
    fun randomPoolSnapshot_expires() {
        val encoded = RandomPoolSnapshot.encode(listOf(series(1)), 0L)
        assertNull(RandomPoolSnapshot.decode(encoded, 8L * 24 * 60 * 60 * 1000 + 1))
    }
}
