// SPDX-License-Identifier: GPL-3.0-only
// x-argus / x-ladon / legacy x-gorgon Kotlin ports of fqapp
// (github.com/ch6vip/fqapp) rust/src/sign/{argus,simon,ladon,gorgon,protobuf}.rs.
// GPL-3.0-only, kept with attribution as required by the source license.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.security.SecureRandom

/** Encode-only protobuf writer used by x-argus; varints truncate to 32 bits. */
internal class ProtoBuf {
    private class Field(val tag: Int, val type: Int, val value: Long, val blobIndex: Int)
    private val fields = mutableListOf<Field>()
    private val blobs = mutableListOf<ByteArray>()
    fun putVarint(idx: Int, v: Long): ProtoBuf { fields += Field(idx, 0, v, -1); return this }
    private fun putBlob(idx: Int, data: ByteArray): ProtoBuf { blobs += data; fields += Field(idx, 2, 0, blobs.size - 1); return this }
    fun putUtf8(idx: Int, s: String) = putBlob(idx, s.toByteArray(Charsets.UTF_8))
    fun putBytes(idx: Int, data: ByteArray) = putBlob(idx, data)
    fun putProtobuf(idx: Int, nested: ProtoBuf) = putBlob(idx, nested.toBuf())
    fun toBuf(): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) {
            writeVarint(out, (f.tag.toLong() shl 3) or f.type.toLong())
            when (f.type) {
                0 -> writeVarint(out, f.value)
                2 -> { val b = blobs[f.blobIndex]; writeVarint(out, b.size.toLong()); out.write(b) }
            }
        }
        return out.toByteArray()
    }
    companion object {
        private fun writeVarint(dst: ByteArrayOutputStream, v: Long) {
            var n = v and 0xffffffffL
            while (n >= 128) { dst.write(((n and 127) or 128).toInt()); n = n ushr 7 }
            dst.write((n and 127).toInt())
        }
    }
}

/** Simon block cipher (128-bit key, 64-bit block, 72 rounds) used by x-argus. */
internal object Simon {
    private const val Z = 0x3DC94C3A046D678BL
    fun keyExpansion(k: LongArray): LongArray {
        val key = LongArray(72)
        System.arraycopy(k, 0, key, 0, 4)
        for (i in 4 until 72) {
            var tmp = java.lang.Long.rotateRight(key[i - 1], 3)
            tmp = tmp xor key[i - 3]
            tmp = tmp xor java.lang.Long.rotateRight(tmp, 1)
            val bit = (Z shr ((i - 4) % 62)) and 1
            key[i] = key[i - 4].inv() xor tmp xor bit xor 3
        }
        return key
    }
    fun encrypt(xInput: Long, x1Input: Long, k: LongArray): LongArray {
        val key = keyExpansion(k)
        var xi = xInput
        var xi1 = x1Input
        for (roundKey in key) {
            val tmp = xi1
            val f = java.lang.Long.rotateLeft(xi1, 1) and java.lang.Long.rotateLeft(xi1, 8)
            xi1 = xi xor f xor java.lang.Long.rotateLeft(xi1, 2) xor roundKey
            xi = tmp
        }
        return longArrayOf(xi, xi1)
    }
    /** Inverse of encrypt; used by the 551 golden-vector verification tests. */
    fun decrypt(xLeftInput: Long, xRightInput: Long, k: LongArray): LongArray {
        val key = keyExpansion(k)
        var xLeft = xLeftInput
        var xRight = xRightInput
        for (i in 71 downTo 0) {
            val tmp = xLeft
            val f = java.lang.Long.rotateLeft(xLeft, 1) and java.lang.Long.rotateLeft(xLeft, 8)
            xLeft = xRight xor f xor java.lang.Long.rotateLeft(xLeft, 2) xor key[i]
            xRight = tmp
        }
        return longArrayOf(xLeft, xRight)
    }
}

/** x-argus: ordered protobuf bean -> PKCS7 -> Simon -> 551 framing -> AES-CBC. */
internal object Argus {
    private val SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val AES_KEY = hex("d31e3718288a1027baab59f146a09a9c")
    private val AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54")
    private val MAGIC8 = byteArrayOf(0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte(), 0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte())
    private val TAIL9 = byteArrayOf(0xea.toByte(), 0xfb.toByte(), 0x2c, 0xfe.toByte(), 0x85.toByte(), 0x68, 0x51, 0x91.toByte(), 0x54)

    fun bodyHash(stubHex: String): ByteArray =
        if (stubHex.isEmpty()) sm3(ByteArray(16)).copyOf(6)
        else runCatching { sm3(hex(stubHex)).copyOf(6) }.getOrDefault(sm3(ByteArray(0)).copyOf(6))

    fun queryHash(query: String): ByteArray =
        if (query.isEmpty()) sm3(ByteArray(16)).copyOf(6) else sm3(query.toByteArray()).copyOf(6)

    private fun pkcs7Align(data: MutableList<Byte>) {
        val pad = 16 - data.size % 16
        repeat(pad) { data.add(pad.toByte()) }
    }

    private fun simonEncryptPadded(protobuf: ByteArray): ByteArray {
        val key = LongArray(4) { SIGN_KEY.i64(it * 8, true) }
        val out = ByteArray(protobuf.size / 16 * 16)
        for (i in 0 until protobuf.size / 16) {
            val block = protobuf.copyOfRange(i * 16, i * 16 + 16)
            val ct = Simon.encrypt(block.i64(0, true), block.i64(8, true), key)
            le64(ct[0]).copyInto(out, i * 16)
            le64(ct[1]).copyInto(out, i * 16 + 8)
        }
        return out
    }

    /** The x-argus wrapper accepted by the current upstream (ArgusBean551 layout). */
    fun sign(query: String, stubHex: String, ts: Long, aid: Long, licenseId: Long, deviceId: String, installId: String, randField: Int): String {
        val bean = ProtoBuf()
        bean.putVarint(1, 1_077_940_818)
        bean.putVarint(2, 2)
        bean.putVarint(3, randField.toLong() and 0xffffffffL)
        bean.putUtf8(4, (if (aid == 0L) 1967 else aid).toString())
        bean.putUtf8(5, deviceId)
        bean.putUtf8(6, (if (licenseId == 0L) 1_611_921_764 else licenseId).toString())
        bean.putUtf8(7, queryParam(query, "version_name").orEmpty())
        bean.putUtf8(8, "v04.04.05-ov-android")
        bean.putVarint(9, 134_744_640)
        bean.putBytes(10, ByteArray(8))
        bean.putVarint(11, 0)
        bean.putVarint(12, (ts shl 1) and 0xffffffffL)
        bean.putBytes(13, bodyHash(stubHex))
        bean.putBytes(14, queryHash(query))
        val sub15 = ProtoBuf()
        sub15.putVarint(1, 1); sub15.putVarint(2, 1); sub15.putVarint(3, 1); sub15.putVarint(7, 0xC792ECCC)
        bean.putProtobuf(15, sub15)
        bean.putUtf8(16, installId)
        bean.putUtf8(20, "none")
        bean.putVarint(21, 738)
        val sub23 = ProtoBuf()
        sub23.putUtf8(1, "NX551J"); sub23.putVarint(2, 8196); sub23.putVarint(4, 0x80E0D800L)
        bean.putProtobuf(23, sub23)
        bean.putVarint(25, 2)

        val protobuf = bean.toBuf().toMutableList()
        pkcs7Align(protobuf)
        val encPb = simonEncryptPadded(protobuf.toByteArray())

        val un = MAGIC8 + encPb + TAIL9
        val buf = ByteArray(un.size)
        MAGIC8.copyInto(buf, 0)
        for (i in 8 until un.size) buf[i] = (un[i].u() xor MAGIC8[i % 8].u()).toByte()
        buf.reverse()
        val padded = (buf + byteArrayOf('a'.code.toByte(), 'o'.code.toByte())).toMutableList()
        pkcs7Align(padded)
        val encrypted = aesEncrypt(padded.toByteArray(), AES_KEY, AES_IV)
        return (byteArrayOf(0xf2.toByte(), 0x81.toByte()) + encrypted).base64()
    }

    /** Legacy (PHP 55 / SignatureManager) encryption path, golden-vector tested. */
    fun encryptLegacy(protobuf: ByteArray): String {
        val aligned = protobuf.toMutableList()
        pkcs7Align(aligned)
        val alignedBytes = aligned.toByteArray()
        val encPb = simonEncryptPadded(alignedBytes)
        val buffer = encryptEncPb(encPb, alignedBytes.size + 8).toMutableList()
        buffer.add('a'.code.toByte()); buffer.add('o'.code.toByte())
        pkcs7Align(buffer)
        return aesEncrypt(buffer.toByteArray(), AES_KEY, AES_IV).base64()
    }

    /** `encryptEncPb($data, $length)` with `length = len(data) + 8`. */
    fun encryptEncPb(data: ByteArray, length: Int): ByteArray {
        val buf = ByteArray(length)
        data.copyInto(buf, 0, 0, minOf(data.size, length))
        for (i in 8 until length) {
            val src = if (i < data.size) data[i].u() else 0
            buf[i] = (src xor data[i % 8].u()).toByte()
        }
        buf.reverse()
        return buf
    }

    /** Inverse of the 551 framing: bean bytes, or null when malformed. */
    fun decrypt551(argusB64: String): ByteArray? = runCatching {
        val raw = decode64(argusB64)
        require(raw.size > 18 && raw[0].u() == 0xf2 && raw[1].u() == 0x81)
        val padded = aes("CBC", AES_KEY, AES_IV, raw.copyOfRange(2, raw.size))
        var end = padded.size
        val pad = padded.last().u()
        if (pad in 1..16 && pad <= end) end -= pad
        require(end >= 2 && padded[end - 2].u() == 'a'.code && padded[end - 1].u() == 'o'.code)
        val framed = padded.copyOfRange(0, end - 2).reversedArray()
        val un = ByteArray(framed.size)
        for (i in framed.indices) un[i] = if (i < 8) framed[i] else (framed[i].u() xor MAGIC8[i % 8].u()).toByte()
        require(un.copyOfRange(0, 8).contentEquals(MAGIC8))
        require(un.copyOfRange(un.size - 9, un.size).contentEquals(TAIL9))
        val encPb = un.copyOfRange(8, un.size - 9)
        val key = LongArray(4) { SIGN_KEY.i64(it * 8, true) }
        ByteArray(encPb.size)
            .also { out ->
                for (i in 0 until encPb.size / 16) {
                    val block = encPb.copyOfRange(i * 16, i * 16 + 16)
                    val pt = Simon.decrypt(block.i64(0, true), block.i64(8, true), key)
                    le64(pt[0]).copyInto(out, i * 16)
                    le64(pt[1]).copyInto(out, i * 16 + 8)
                }
            }
    }.getOrNull()

    /** Extracts bean field 3 (the per-call nonce) from an x-argus 551 blob. */
    fun recover551Field3(argusB64: String): Int? {
        val bean = decrypt551(argusB64) ?: return null
        var at = 0
        while (at < bean.size) {
            var key = 0L; var shift = 0
            while (at < bean.size) {
                val b = bean[at].u(); at++
                key = key or ((b and 127).toLong() shl shift); shift += 7
                if (b and 128 == 0) break
            }
            val field = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            when (wire) {
                0 -> {
                    var value = 0L; shift = 0
                    while (at < bean.size) {
                        val b = bean[at].u(); at++
                        value = value or ((b and 127).toLong() shl shift); shift += 7
                        if (b and 128 == 0) break
                    }
                    if (field == 3) return value.toInt()
                }
                2 -> {
                    var len = 0L; shift = 0
                    while (at < bean.size) {
                        val b = bean[at].u(); at++
                        len = len or ((b and 127).toLong() shl shift); shift += 7
                        if (b and 128 == 0) break
                    }
                    at += len.toInt()
                }
                5 -> at += 4
                1 -> at += 8
                else -> return null
            }
        }
        return null
    }
}

/** x-ladon: a custom 34-round ARX block cipher over u64. */
internal object Ladon {
    fun encrypt(khronos: Long, lcId: Long, aid: Long, randomBytes: ByteArray): String {
        val data = "$khronos-$lcId-$aid"
        val md5Hex = digest("MD5", randomBytes + aid.toString().toByteArray()).hex()
        val ht = expandTable(md5Hex)
        val size = data.length
        val newSize = size + (16 - size % 16)
        val input = ByteArray(newSize) { (newSize - size).toByte() }
        data.toByteArray().copyInto(input, 0)
        val out = ByteArray(newSize)
        for (i in 0 until newSize / 16) encryptBlock(ht, input, i * 16).copyInto(out, i * 16)
        return (randomBytes + out).base64()
    }
    private fun expandTable(md5Hex: String): LongArray {
        val ht = ByteArray(288)
        val src = md5Hex.toByteArray()
        src.copyInto(ht, 0, 0, minOf(32, src.size))
        var b0 = ht.i64(0, true)
        var b8 = ht.i64(8, true)
        // Rust starts with temp = [word2, word3] and pushes before each pop.
        val temp = ArrayDeque(listOf(ht.i64(16, true), ht.i64(24, true)))
        for (i in 0 until 34) {
            var x8 = java.lang.Long.rotateRight(b8, 8) + b0
            x8 = x8 xor i.toLong()
            temp.addLast(x8)
            x8 = x8 xor java.lang.Long.rotateLeft(b0, 3)
            for (j in 0 until 8) ht[(i + 1) * 8 + j] = ((x8 ushr (j * 8)) and 255).toByte()
            b0 = x8
            b8 = temp.removeFirst()
        }
        return LongArray(36) { ht.i64(it * 8, true) }
    }
    private fun encryptBlock(ht: LongArray, data: ByteArray, at: Int): ByteArray {
        var data0 = data.i64(at, true)
        var data1 = data.i64(at + 8, true)
        for (i in 0 until 34) {
            data1 = ht[i] xor (data0 + java.lang.Long.rotateRight(data1, 8))
            data0 = data1 xor java.lang.Long.rotateLeft(data0, 3)
        }
        return le64(data0) + le64(data1)
    }
}

/** Legacy registration-time x-gorgon (query-only input), fqapp gorgon.rs port. */
internal object Gorgon8404 {
    fun generate(param: String, ts: Long, rand1: Int, rand2: Int): String {
        val sum = digest("MD5", param.toByteArray())
        var data = sum.copyOf(4) + byteArrayOf(0, 0, 0, 0, 0, 1, 7, 4) + be32(ts.toInt())
        val table = IntArray(256) { it }
        val key = intArrayOf(74, 0, 22, rand2, 71, 108, 0, rand1)
        var j = 0
        for (i in 0 until 256) {
            j = (j + table[i] + key[i and 7]) and 255
            table[i] = table[j]
        }
        val dlen = data.size
        var k = 0
        for (i in 1..dlen) {
            val idx = ((((i - 1) xor 1) + ((i - 1) and 1)) shl 1) and 255
            val tableVal = table[idx]
            val xorVal = k xor tableVal
            k = (((k or tableVal) shl 1) - xorVal) and -1 // wrap on Int is the 32-bit mask
            val idx2 = k and 255
            table[idx] = table[idx2]
            table[idx2] = table[idx2]
            val encIdx = ((table[idx2] or table[idx2]) + (table[idx2] and table[idx2])) and 255
            data[i - 1] = (data[i - 1].u() xor table[encIdx]).toByte()
        }
        val lenInv = dlen.inv()
        for (i in 1..dlen) {
            var value = data[i - 1].u()
            value = ((value ushr 4) and 15) or ((value and 15) shl 4)
            data[i - 1] = (value and 255).toByte()
            if (i == dlen) data[i - 1] = ((data[i - 1].u() xor data[0].u()) and 255).toByte()
            else {
                val next = data[i].u()
                data[i - 1] = (((value or next) - (value and next)) and 255).toByte()
            }
            value = data[i - 1].u()
            val t1 = 0xffffffaa.toInt() and (value shl 1)
            val t2 = 85 and (value ushr 1)
            value = t1 or t2
            val t3 = value shl 2
            val t4 = 51 and (value ushr 2)
            var mix = (t3 and -0x31) or t4
            val high = (mix ushr 4) and 15
            val low = mix and 0x0fffffff
            mix = (low shl 4) or high
            value = mix xor lenInv
            data[i - 1] = (value and 255).toByte()
        }
        return (byteArrayOf(132.toByte(), 4, rand1.toByte(), rand2.toByte(), 0, 0) + data).hex()
    }
}

/** Shared query-string parameter lookup (Go url.Values.Get semantics). */
internal fun queryParam(query: String, name: String): String? {
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        val key = if (eq < 0) part else part.substring(0, eq)
        if (decodeForm(key) == name) return decodeForm(if (eq < 0) "" else part.substring(eq + 1))
    }
    return null
}
private fun decodeForm(value: String): String = runCatching {
    URLDecoder.decode(value, "UTF-8")
}.getOrDefault(value)

/** Upstream request headers for the fanqie player endpoints. */
internal object PlayerSigner {
    const val LICENSE_ID = 1_611_921_764L
    private val random = SecureRandom()
    fun headers(
        query: String,
        body: ByteArray?,
        ts: Long = System.currentTimeMillis() / 1000,
        // 与 URL 里的 _rticket 保持一致；缺省时按整秒生成。
        ticket: Long? = null,
    ): Map<String, String> {
        val requestTicket = ticket ?: (ts * 1000)
        val aid = queryParam(query, "aid")?.toLongOrNull() ?: 1967L
        val stub = body?.takeIf { it.isNotEmpty() }?.let { digest("MD5", it).hex() }.orEmpty()
        val ladonRandom = ByteArray(4).also { random.nextBytes(it) }
        val headers = linkedMapOf(
            "x-ss-req-ticket" to requestTicket.toString(),
            "x-khronos" to ts.toString(),
            // 真果鉴（Adsryen/zhenguojian）README 实测：取流类请求固定四头
            // X-Gorgon / X-Khronos / X-Ladon / X-Argus，缺 gorgon 同样会被风控空包。
            // 算法与 X-Ladon 等绑定同一 (query, body, ts)，见下方 gorgon()。
            "x-gorgon" to gorgon(query, body, ts),
            "x-ladon" to Ladon.encrypt(ts, LICENSE_ID, aid, ladonRandom),
            "x-argus" to Argus.sign(query, stub, ts, aid, LICENSE_ID, queryParam(query, "device_id").orEmpty(), "", random.nextInt()),
        )
        if (stub.isNotEmpty()) headers["x-ss-stub"] = stub
        return headers
    }

    /**
     * 取流类 X-Gorgon（26 字节：6 字节版本头 84 04 40 1c 00 00 + 20 字节载荷）。
     * 载荷 = query MD5 前 4 字节 + body MD5 前 4 字节（无 body 则 0）+ {0,6,11,28} + BE 时间戳，
     * 先异或固定 20 字节密钥，再逐字节「左旋 4 位 → 与下一字节异或 → 位反转 → ^0xff^20」。
     * 与 juneix/redguo `signHongguoRequest`、真果鉴四头方案逐字节一致。
     */
    private fun gorgon(query: String, body: ByteArray?, ts: Long): String {
        val payload = ByteArray(20)
        digest("MD5", query.toByteArray()).copyInto(payload, 0, 0, 4)
        if (body != null && body.isNotEmpty()) digest("MD5", body).copyInto(payload, 4, 0, 4)
        payload[13] = 6; payload[14] = 11; payload[15] = 28
        be32(ts.toInt()).copyInto(payload, 16)
        for (i in payload.indices) payload[i] = (payload[i].u() xor GORGON_KEY[i].u()).toByte()
        for (j in payload.indices) {
            val rotated = ((payload[j].u() shl 4) or (payload[j].u() ushr 4)) and 255
            val mixed = rotated xor payload[(j + 1) % payload.size].u()
            payload[j] = ((java.lang.Integer.reverse(mixed) ushr 24) xor 0xFF xor payload.size).toByte()
        }
        return (byteArrayOf(0x84.toByte(), 0x04, 0x40, 0x1c.toByte(), 0, 0) + payload).hex()
    }
    private val GORGON_KEY = byteArrayOf(
        0x44.toByte(), 0xb9.toByte(), 0xb9.toByte(), 0xd9.toByte(), 0xa4.toByte(), 0xae.toByte(), 0xf9.toByte(), 0xfc.toByte(),
        0xa4.toByte(), 0x93.toByte(), 0xaa.toByte(), 0x75.toByte(), 0x7c.toByte(), 0xa3.toByte(), 0xc2.toByte(), 0xc4.toByte(),
        0xa4.toByte(), 0x96.toByte(), 0x93.toByte(), 0x8f.toByte(),
    )
    /** Same headers plus the legacy gorgon used only while a device registers. */
    fun registrationHeaders(query: String, body: ByteArray?, ts: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        val headers = LinkedHashMap(headers(query, body, ts))
        headers["x-gorgon"] = Gorgon8404.generate(query, ts, random.nextInt(256), random.nextInt(256))
        return headers
    }
}

private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
        init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        doFinal(data)
    }
