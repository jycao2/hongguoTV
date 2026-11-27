// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 镜像链的尝试顺序：原始地址挂了继续试镜像，全挂才判失败。 */
class PlaybackProfileRepositoryTest {
    @Test
    fun `优先用原始地址`() {
        val tried = mutableListOf<String>()
        val repo = PlaybackProfileRepository()
        val doc = repo.fetchFrom(listOf("a", "b", "c")) { url ->
            tried += url
            PlaybackProfileRepository.Document("raw", PlaybackProfile.builtin())
        }
        assertEquals(listOf("a"), tried)
        assertEquals("raw", doc.raw)
    }

    @Test
    fun `原始地址失败后依次落到镜像`() {
        val tried = mutableListOf<String>()
        val repo = PlaybackProfileRepository()
        repo.fetchFrom(listOf("a", "b", "c")) { url ->
            tried += url
            if (url != "c") throw IOException("boom $url")
            PlaybackProfileRepository.Document("ok", PlaybackProfile.builtin())
        }
        assertEquals(listOf("a", "b", "c"), tried)
    }

    @Test
    fun `全部失败时抛出可读异常`() {
        val repo = PlaybackProfileRepository()
        val failed = try {
            repo.fetchFrom(listOf("a", "b")) { throw IOException("网络不可达") }
            false
        } catch (e: IOException) {
            assertTrue("异常里要带最后一次原因", e.message!!.contains("网络不可达"))
            true
        }
        assertTrue(failed)
    }

    @Test
    fun `默认候选地址覆盖原始地址与全部镜像`() {
        val mirrors = AppUpdate.MIRRORS
        assertTrue("镜像链至少要有备用，否则大陆网络下必挂", mirrors.size >= 2)
        val hosts = AppUpdate.ALLOWED_HOSTS
        assertTrue("镜像域名必须在白名单里", hosts.contains("gh-proxy.com") && hosts.contains("ghfast.top"))
    }
}
