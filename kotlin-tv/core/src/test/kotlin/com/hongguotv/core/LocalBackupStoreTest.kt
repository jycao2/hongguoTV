package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalBackupStoreTest {
    private val dir: File = Files.createTempDirectory("local-backup").toFile()
    private val store = LocalBackupStore(DirectoryBackupFiles(dir))
    private fun data(title: String) = BackupData(listOf(Series("1", title)), emptyList(), emptySet(), emptyList(), BackupSettings())

    @Test fun saveAndLoadRoundTripWritesMainFile() {
        assertTrue(store.save(data("测试剧")))
        assertTrue(File(dir, LocalBackupStore.MAIN).isFile)
        assertEquals("测试剧", store.load()?.favorites?.single()?.title)
    }

    @Test fun secondSaveRotatesPreviousCopyAndKeepsNewest() {
        assertTrue(store.save(data("第一版")))
        assertTrue(store.save(data("第二版")))
        assertEquals("第二版", store.load()?.favorites?.single()?.title)
        val prev = LibraryBackup.decode(File(dir, LocalBackupStore.PREV).readText())
        assertEquals("第一版", prev.favorites.single().title)
    }

    @Test fun damagedMainFallsBackToPreviousCopy() {
        assertTrue(store.save(data("第一版")))
        assertTrue(store.save(data("第二版")))
        File(dir, LocalBackupStore.MAIN).writeText("{broken")
        assertEquals("第一版", store.load()?.favorites?.single()?.title)
    }

    @Test fun bothCopiesDamagedReturnNullInsteadOfThrowing() {
        assertTrue(store.save(data("第一版")))
        assertTrue(store.save(data("第二版")))
        File(dir, LocalBackupStore.MAIN).writeText("{broken")
        File(dir, LocalBackupStore.PREV).writeText("{broken")
        assertNull(store.load())
    }

    @Test fun interruptedWriteLeavesOnlyTemporaryFileAndCleanupRemovesIt() {
        File(dir, LocalBackupStore.TMP).writeText(LibraryBackup.encode(data("半截")))
        assertFalse(store.hasBackup())
        assertNull(store.load())
        store.cleanup()
        assertFalse(File(dir, LocalBackupStore.TMP).exists())
    }

    @Test fun cleanupRemovesLegacyTemporaryName() {
        File(dir, LocalBackupStore.LEGACY_TMP).writeText(LibraryBackup.encode(data("半截")))
        store.cleanup()
        assertFalse(File(dir, LocalBackupStore.LEGACY_TMP).exists())
    }

    @Test fun oversizedFileIsIgnored() {
        assertTrue(store.save(data("第一版")))
        File(dir, LocalBackupStore.MAIN).writeText("x".repeat(LocalBackupStore.MAX_FILE_BYTES + 1))
        assertNull(store.load())
    }
}
