// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 协议档案的本地缓存。
 *
 * 缓存的是**远端原文（信封）**，不是解析结果：每次读都重新验签 + 重新校验，
 * 这样「缓存被人改过」和「远端发坏档案」是同一个失败路径 —— 一律当作没有缓存。
 *
 * 写盘先落 `.part` 再原子替换：电视断电是常态，宁可丢掉一份新档案，
 * 也不能留下半份解析不过的文件。
 */
class PlaybackProfileStore(private val file: File) {
    private val lock = Any()

    val exists: Boolean get() = file.isFile

    /** 上次成功写入的时间（0 表示没有缓存），用于 6 小时刷新节流。 */
    val savedAtMillis: Long get() = if (file.isFile) file.lastModified() else 0L

    fun loadRaw(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    /** 读缓存并验签校验；任何一步失败都返回 null。 */
    fun load(): PlaybackProfile? {
        val raw = loadRaw() ?: return null
        return try {
            PlaybackProfileEnvelope.decode(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun save(raw: String): Boolean = synchronized(lock) {
        val target = file.absoluteFile
        try {
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".part")
            partial.writeText(raw, Charsets.UTF_8)
            try {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            partial.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }
}
