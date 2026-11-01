// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File

/** 电视上已收到的快传文件：名字已去掉落盘时的时间戳前缀。 */
data class ReceivedFile(val file: File, val kind: TransferKind, val receivedAt: Long, val name: String)

/** 快传目录的读取、展示名还原与保留策略（纯 JVM，可单测）。 */
object TransferFiles {
    /** 保留上限：更老的自动删掉，避免长期使用后目录无限堆积。 */
    const val KEEP_FILES = 200

    /** 落盘名是 `<epoch 秒>-原名`，展示时还原成用户上传时的文件名。 */
    fun displayName(fileName: String): String {
        val dash = fileName.indexOf('-')
        if(dash <= 0) return fileName
        val stamp = fileName.substring(0, dash)
        // 时间戳前缀必须是 9–11 位纯数字，避免把 “2024-全家福.jpg” 这类正常名字也截掉
        if(stamp.length !in 9..11 || !stamp.all { it.isDigit() }) return fileName
        return fileName.substring(dash + 1).ifEmpty { fileName }
    }

    fun receivedAt(file: File): Long {
        val dash = file.name.indexOf('-')
        if(dash > 0) {
            val seconds = file.name.substring(0, dash).toLongOrNull()
            if(seconds != null && seconds > 0) return seconds * 1000
        }
        return file.lastModified()
    }

    /** 只列已完成的文件（跳过 .part 临时文件），按接收时间倒序。 */
    fun list(directories: List<File>): List<ReceivedFile> = directories.asSequence()
        .filter { it.isDirectory }
        .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
        .filter { it.isFile && !it.name.endsWith(TransferRules.PART_SUFFIX) }
        .mapNotNull { file ->
            val name = displayName(file.name)
            TransferRules.kindOf(name)?.let { kind -> ReceivedFile(file, kind, receivedAt(file), name) }
        }
        .sortedByDescending { it.receivedAt }
        .toList()

    /** 超过保留上限时删除最旧的，返回真正删掉的数量。 */
    fun prune(directories: List<File>, keep: Int = KEEP_FILES): Int {
        val files = list(directories)
        if(files.size <= keep) return 0
        return files.drop(keep).count { runCatching { it.file.delete() }.getOrDefault(false) }
    }
}
