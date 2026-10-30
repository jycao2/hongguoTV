// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File

/** Minimal file surface so Android can substitute MediaStore on scoped-storage-only systems. */
interface BackupFiles {
    fun exists(name: String): Boolean
    fun read(name: String): String?
    fun write(name: String, value: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun delete(name: String): Boolean
}

/** Plain java.io backend used on API 26-28, API 30+ and in JVM tests. */
class DirectoryBackupFiles(private val dir: File) : BackupFiles {
    override fun exists(name: String) = File(dir, name).isFile
    override fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.isFile || file.length() > LocalBackupStore.MAX_FILE_BYTES) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }
    override fun write(name: String, value: String): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val bytes = value.toByteArray(Charsets.UTF_8)
        File(dir, name).outputStream().use { out -> out.write(bytes); out.flush(); out.fd.sync() }
        true
    }.getOrDefault(false)
    override fun rename(from: String, to: String): Boolean = runCatching {
        File(dir, from).renameTo(File(dir, to))
    }.getOrDefault(false)
    override fun delete(name: String): Boolean = runCatching {
        File(dir, name).let { !it.exists() || it.delete() }
    }.getOrDefault(false)
}

/**
 * Rotating local backup: main file plus one previous copy, replaced atomically through a temp file.
 * Any step may be interrupted without losing the only good copy.
 */
class LocalBackupStore(private val files: BackupFiles) {
    companion object {
        const val MAIN = "hongguotv-backup.json"
        const val PREV = "hongguotv-backup.prev.json"
        /** 临时文件必须以 .json 结尾：MediaStore 会按 MIME 给非 .json 后缀的显示名补上 .json，
         *  那样按显示名查询永远找不到这一行，每次保存都会新插一条，最终堆满重复条目并写入失败。 */
        const val TMP = "hongguotv-backup.tmp.json"
        /** 旧版本临时名（补后缀后落成 hongguotv-backup.json.tmp.json 及其 (N) 变体），升级后清掉这些孤儿行。 */
        const val LEGACY_TMP = "hongguotv-backup.json.tmp.json"
        const val MAX_FILE_BYTES = LibraryBackup.MAX_BYTES + 64 * 1024
    }
    fun cleanup() { files.delete(TMP); files.delete(LEGACY_TMP) }
    fun hasBackup() = files.exists(MAIN) || files.exists(PREV)
    /** Returns true when the new snapshot became the main file. */
    fun save(data: BackupData): Boolean {
        val encoded = runCatching { LibraryBackup.encode(data) }.getOrNull() ?: return false
        // Recover a rotation interrupted between the two renames before starting a new one.
        if (!files.exists(MAIN) && files.exists(PREV)) files.rename(PREV, MAIN)
        if (!files.write(TMP, encoded)) { files.delete(TMP); return false }
        files.delete(PREV)
        if (files.exists(MAIN) && !files.rename(MAIN, PREV)) { files.delete(TMP); return false }
        if (!files.rename(TMP, MAIN)) return false
        return true
    }
    /** Main file first, then the previous rotation copy; silently ignores damaged or unsupported data. */
    fun load(): BackupData? {
        for (name in listOf(MAIN, PREV)) {
            val text = files.read(name) ?: continue
            runCatching { LibraryBackup.decode(text) }.getOrNull()?.let { return it }
        }
        return null
    }
}
