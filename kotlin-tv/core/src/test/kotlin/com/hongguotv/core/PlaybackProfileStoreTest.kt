// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地缓存的读写与坏档容错：缓存坏了必须等价于「没有缓存」。 */
class PlaybackProfileStoreTest {
    private val dir: File = Files.createTempDirectory("hgu-profile").toFile()
    private val file = File(dir, "playback-profile.json")
    private val store = PlaybackProfileStore(file)

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun envelope(): String = File(locateRoot(), "playback-profile.json").readText(Charsets.UTF_8)

    private fun locateRoot(): File =
        listOf(File("../.."), File(".."), File(".")).first { File(it, "playback-profile.json").isFile }

    @Test
    fun `写入后能读回同一份档案`() {
        assertTrue(store.save(envelope()))
        assertTrue(store.exists)
        assertTrue(store.savedAtMillis > 0)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(PlaybackProfile.builtin().toJson(), loaded!!.toJson())
    }

    @Test
    fun `缓存内容被改成垃圾就当没有缓存`() {
        file.writeText("{}", Charsets.UTF_8)
        assertNull(store.load())
        // 文件还在，但解析不过：调用方拿 null 后继续用内置默认。
        assertTrue(store.exists)
    }

    @Test
    fun `缓存被截断也当没有缓存`() {
        val text = envelope()
        file.writeText(text.substring(0, text.length / 2), Charsets.UTF_8)
        assertNull(store.load())
    }

    @Test
    fun `重复写入是覆盖而不是追加`() {
        assertTrue(store.save(envelope()))
        assertTrue(store.save(envelope()))
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("playback-profile") })
        assertNotNull(store.load())
    }

    @Test
    fun `写盘不留半成品临时文件`() {
        store.save(envelope())
        assertFalse("不能残留 .part", File(dir, "playback-profile.json.part").exists())
    }

    @Test
    fun `清空后回到没有缓存`() {
        store.save(envelope())
        assertTrue(store.clear())
        assertFalse(store.exists)
        assertNull(store.load())
        assertNull(store.loadRaw())
    }

    @Test
    fun `目录不存在时会自动创建`() {
        val nested = PlaybackProfileStore(File(dir, "a/b/playback-profile.json"))
        assertTrue(nested.save(envelope()))
        assertNotNull(nested.load())
    }
}
