// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.Locale

/** 快传允许落盘的文件类别：决定存到哪个目录，以及是否需要 APK 头校验。 */
enum class TransferKind { APK, VIDEO, IMAGE, OTHER }

/** 快传的文件规则（纯函数，可单测）：白名单、文件名清洗、APK 头识别、落盘命名。
 *  只放协议无关的判断，目录与剩余空间由调用方通过参数注入。 */
object TransferRules {
    /** 单文件上限：电视内部存储通常 8GB，超过 2GB 的文件也不适合边传边占。 */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    /** 落盘前必须预留的空闲空间：避免写满 /data 导致系统或其它应用异常。 */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024
    const val MAX_NAME_LENGTH = 120
    /** 临时文件后缀：失败或进程被杀后残留，下次打开快传时清理。 */
    const val PART_SUFFIX = ".part"
    /** 残留临时文件的清理阈值：1 小时内仍可能在传输中，不删。 */
    const val STALE_PART_MILLIS = 60L * 60_000L

    private val KINDS = mapOf(
        "apk" to TransferKind.APK,
        "mp4" to TransferKind.VIDEO, "mkv" to TransferKind.VIDEO, "ts" to TransferKind.VIDEO,
        "mov" to TransferKind.VIDEO, "webm" to TransferKind.VIDEO, "avi" to TransferKind.VIDEO,
        "jpg" to TransferKind.IMAGE, "jpeg" to TransferKind.IMAGE, "png" to TransferKind.IMAGE,
        "webp" to TransferKind.IMAGE,
        "srt" to TransferKind.OTHER, "ass" to TransferKind.OTHER, "torrent" to TransferKind.OTHER,
        "zip" to TransferKind.OTHER, "json" to TransferKind.OTHER,
    )

    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if(dot <= 0 || dot == fileName.lastIndex) return null
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** 不在白名单返回 null，调用方据此回 400。 */
    fun kindOf(fileName: String): TransferKind? = extensionOf(fileName)?.let { KINDS[it] }

    /** 清洗客户端文件名：取 basename、去控制字符、限长且保留扩展名。
     *  含路径分隔符的名字只保留最后一段，避免 ../ 之类的路径穿越；结果必须以白名单扩展名结尾。 */
    fun sanitize(fileName: String): String? {
        val raw = fileName.trim()
        if(raw.isEmpty()) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = base.filter { it.code in 0x20..0x7E || it.code > 0x7F }.trim()
        if(clean.isEmpty() || clean == "." || clean == "..") return null
        // 白名单交给 kindOf 判断：这里只管名字安全，错误文案才能区分“名字非法”和“类型不支持”
        val extension = extensionOf(clean) ?: return null
        if(clean.length <= MAX_NAME_LENGTH) return clean
        // 超长只截主体，扩展名原样保留，否则会变成不可识别的文件
        val head = clean.substring(0, MAX_NAME_LENGTH - extension.length - 1)
        return "$head.$extension"
    }

    /** APK 是 zip 容器：改名的其它文件不该进入安装流程。 */
    fun looksLikeApk(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** 落盘名加时间戳前缀，避免同名覆盖，同时让电视上的文件按接收顺序排列。 */
    fun targetName(fileName: String, nowMs: Long): String = "${nowMs / 1000}-$fileName"

    /** 每个类型落哪个目录：APK 单独放，便于安装流程与清理区分。 */
    fun directoryName(kind: TransferKind): String = if(kind == TransferKind.APK) "quick" else "transfers"

    fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576f)
        else -> "%.1f GB".format(bytes / 1073741824f)
    }
}
