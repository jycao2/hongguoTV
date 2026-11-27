// SPDX-License-Identifier: GPL-3.0-only
// 境外手机号登录：地区表、号码线格式、mode4 签名。
// 移植自 aston314/hongguo-tv core/cmd/hongguotv/passport_international.go
// 与 account_phone.go（GPL-3.0-only），依据红果官方 v1.2.4 客户端静态逆向。
//
// 隔离约定：本文件的全部入口只服务非 +86 号码；大陆号继续走 PhoneLogin 原有的
// Signer.sign()（branch 0 + Medusa，已真机验收），本文件对境内零影响。
package com.hongguotv.core

import java.security.SecureRandom

object IntlPhone {
    data class Country(val code: String, val name: String, val min: Int, val max: Int)

    /** 与官方登录 UI 对齐的 14 个地区（区号、本地号码长度范围）。 */
    val COUNTRIES =
        listOf(
            Country("+86", "中国大陆", 11, 11),
            Country("+852", "中国香港", 8, 8),
            Country("+853", "中国澳门", 8, 8),
            Country("+886", "中国台湾", 9, 10),
            Country("+60", "马来西亚", 9, 10),
            Country("+65", "新加坡", 8, 8),
            Country("+62", "印度尼西亚", 10, 13),
            Country("+95", "缅甸", 9, 10),
            Country("+63", "菲律宾", 7, 10),
            Country("+84", "越南", 9, 10),
            Country("+855", "柬埔寨", 8, 9),
            Country("+81", "日本", 9, 11),
            Country("+82", "韩国", 8, 11),
            Country("+971", "阿联酋", 9, 9),
        )

    fun countryForCode(code: String): Country? = COUNTRIES.firstOrNull { it.code == code }

    /**
     * 内部规范号码（PhoneLogin.parse 产物，如 `+886 912345678`）→ 平台线格式。
     * 大陆号原样返回（大陆路径不用本方法）；境外按地区最大长度分组，区号后不留空格，
     * 如 `+886 912345678` → `+886912 3456 78`。表外区号不分组，原样拼接。
     */
    fun wire(internal: String): String {
        val text = internal.trim()
        if (!text.startsWith("+")) return text
        val code = text.drop(1).takeWhile(Char::isDigit)
        val local = text.drop(1 + code.length).filter(Char::isDigit)
        val country = countryForCode("+$code") ?: return "+$code$local"
        val widths: List<Int> =
            when (country.max) {
                10 -> listOf(3, 4, 3)
                11 -> listOf(3, 4, 4)
                else -> {
                    val width = if (country.max % 4 == 0) 4 else 3
                    generateSequence(country.max) { it - width }.takeWhile { it > 0 }.map { width }.toList()
                }
            }
        val groups = mutableListOf<String>()
        var rest = local
        for (width in widths) {
            if (rest.isEmpty()) break
            val take = minOf(width, rest.length)
            groups += rest.take(take)
            rest = rest.drop(take)
        }
        return "+$code" + groups.joinToString(" ")
    }

    /** 手机号／验证码线格式：逐字节 XOR 5 后转 hex（配套 mix_mode=1）。 */
    fun xorWire(value: String): String =
        value.toByteArray(Charsets.UTF_8).map { (it.u() xor 5).toByte() }.toByteArray().hex()

    /**
     * mode4 的 Gorgon：**标准 RC4**（KSA/PRGA 都交换 S 表）。
     * 大陆 full 模式用的 [Signer.gorgon] 是变体（PRGA 只写 s[i]、不换回 s[j]），两者不可混用。
     */
    fun gorgon(query: String, body: ByteArray?, ts: Long, nonce: Int): String {
        val q = digest("MD5", query.toByteArray())
        val m = if (body == null || body.isEmpty()) ByteArray(16) else digest("MD5", body)
        val data = q.copyOf(4) + m.copyOf(4) + ByteArray(4) + le32(67503104) + be32(ts.toInt())
        val key = byteArrayOf(0x4a, 0x40, 0x16, (nonce ushr 8).toByte(), 0x47, 0x6c, 1, nonce.toByte())
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + key[i % 8].u()) and 255
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        j = 0
        var i = 0
        val out =
            ByteArray(data.size) { n ->
                i = (i + 1) and 255
                j = (j + s[i]) and 255
                val t = s[i]; s[i] = s[j]; s[j] = t
                (data[n].u() xor s[(s[i] + s[j]) and 255]).toByte()
            }
        for (n in out.indices) {
            val rotated = ((out[n].u() shl 4) or (out[n].u() ushr 4)) and 255
            val next = out[if (n + 1 < out.size) n + 1 else 0].u()
            out[n] = (((Integer.reverse((rotated xor next) and 255) ushr 24) xor 20).inv()).toByte()
        }
        return (hex("8404") + byteArrayOf(nonce.toByte(), (nonce ushr 8).toByte(), 0x40, 1) + out).hex()
    }

    /**
     * mode4 签名：branch-2 调 `_rticket` 命中 F13 分支 2，头集合无 Medusa，
     * 改用 Argus/Ladon/Khronos/Req-Ticket/Helios/Gorgon/Trace-Id，有 body 附 Stub。
     * F13 本身不进头（无 Medusa），所以只复用 [PassportSign.tuneQuery] 与 [PassportSign.helios]。
     */
    fun signInternational(
        queryBase: Map<String, String>,
        body: ByteArray,
        stampMs: Long,
    ): Pair<String, Map<String, String>> {
        val query = PassportSign.tuneQuery(queryBase, body, stampMs)
        val ts = stampMs / 1000
        val random = SecureRandom()
        val r14 = ByteArray(14)
        random.nextBytes(r14)
        val gorgonNonce = r14[0].u() or (r14[1].u() shl 8)
        val heliosNonce =
            r14[2].u() or (r14[3].u() shl 8) or (r14[4].u() shl 16) or (r14[5].u() shl 24)
        // Go: fmt.Sprintf("%08x10%x", uint32(stamp), random[6:])；stamp 取低 32 位按 uint 打印，
        // 后段是 8 字节随机的 hex（Kotlin 用 %s 直接拼已 hex 的字符串）。
        val stampHex = java.lang.String.format("%08x", stampMs.toInt())
        val trace = "$stampHex" + "10" + r14.copyOfRange(6, 14).hex()
        val headers =
            linkedMapOf(
                "X-Khronos" to ts.toString(),
                "X-SS-Req-Ticket" to query.substringAfter("_rticket=").substringBefore('&'),
                "X-Argus" to le32(ts.toInt()).base64(),
                "X-Ladon" to be32(ts.toInt()).base64(),
                "X-Helios" to PassportSign.helios(ts, heliosNonce),
                "X-Gorgon" to gorgon(query, if (body.isEmpty()) null else body, ts, gorgonNonce),
                "X-TT-Trace-Id" to "00-$trace-${trace.take(16)}-01",
            )
        if (body.isNotEmpty()) headers["X-SS-STUB"] = digest("MD5", body).hex().uppercase()
        return query to headers
    }
}
