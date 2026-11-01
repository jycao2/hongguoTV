// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TransferFilesTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("quick-files").toFile()
    private fun write(dir: File, name: String, bytes: Int = 4): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test fun `display name strips the receive stamp and keeps normal names`() {
        assertEquals("剧集.mp4", TransferFiles.displayName("1793522109-剧集.mp4"))
        assertEquals("red.apk", TransferFiles.displayName("1793522109-red.apk"))
        assertEquals("2024-全家福.jpg", TransferFiles.displayName("2024-全家福.jpg"))
        assertEquals("plain.jpg", TransferFiles.displayName("plain.jpg"))
    }

    @Test fun `stamp drives receive time with a modified-time fallback`() {
        val dir = dir()
        val stamped = write(dir, "1793522109-a.apk")
        assertEquals(1_793_522_109_000L, TransferFiles.receivedAt(stamped))
        val plain = write(dir, "b.png")
        assertEquals(plain.lastModified(), TransferFiles.receivedAt(plain))
    }

    @Test fun `list is newest first and skips part files plus unknown types`() {
        val dir = dir()
        write(dir, "1793520001-old.mp4")
        write(dir, "1793520200-new.jpg")
        write(dir, "1793520150-mid.ass")
        write(dir, "x.tmp.part")
        write(dir, "tool.exe")
        val files = TransferFiles.list(listOf(dir, File(dir, "missing")))
        assertEquals(listOf("new.jpg", "mid.ass", "old.mp4"), files.map { it.name })
        assertEquals(TransferKind.IMAGE, files.first().kind)
        assertTrue(files.none { it.file.name.endsWith(TransferRules.PART_SUFFIX) })
        dir.deleteRecursively()
    }

    @Test fun `prune keeps the newest files and deletes the rest`() {
        val dir = dir()
        repeat(205) { index -> write(dir, "${1_793_500_000L + index}-f$index.mp4") }
        assertEquals(5, TransferFiles.prune(listOf(dir), keep = 200))
        val left = TransferFiles.list(listOf(dir))
        assertEquals(200, left.size)
        assertEquals("f204.mp4", left.first().name)
        dir.deleteRecursively()
    }
}
