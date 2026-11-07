// Kotlin port of drpys 22261adfa31435e3b3ef5a730b8a2a75bcaf1715; GPL-3.0-only.
// Only hash branch 0 is emitted: adjust the request ticket before signing.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class Signer(private val clock: () -> Long = System::currentTimeMillis, private val random: () -> Double = Math::random) {
    data class Request(val url: String, val headers: Map<String, String>, val body: ByteArray?)
    private fun rand() = (random() * 4294967296.0).toLong().toInt()
    private fun unsigned(v: Int) = v.toLong() and 0xffffffffL
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply { while (n > 127) { write(((n and 127) or 128).toInt()); n = n ushr 7 }; write(n.toInt()) }.toByteArray()
    }
    private data class Field(val tag: Int, val value: Any, val type: String = "string")
    private fun f(tag: Int, value: Any, type: String = "string") = Field(tag, value, type)
    private fun proto(vararg fields: Field): ByteArray = ByteArrayOutputStream().apply {
        for ((tag, value, type) in fields) {
            when (type) {
                "sint" -> { val n = (value as Number).toLong(); write(varint(tag.toLong() shl 3)); write(varint(if (n < 0) -n * 2 - 1 else n * 2)) }
                "float" -> { write(varint((tag.toLong() shl 3) or 5)); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((value as Number).toFloat()).array()) }
                else -> { val bytes = if (value is ByteArray) value else value.toString().toByteArray(); if (bytes.isNotEmpty()) { write(varint((tag.toLong() shl 3) or 2)); write(varint(bytes.size.toLong())); write(bytes) } }
            }
        }
    }.toByteArray()
    private fun getIv(initial: Int, data: ByteArray): Int {
        var value = initial
        for (i in data.indices) value = if (i and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor data[i].u() else ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        return value
    }
    private fun iv(query: ByteArray, body: ByteArray, ts: Int) = getIv(getIv(getIv(0x20230928, query), body), le32(ts))
    fun branch(query: String, body: ByteArray?, ts: Int): Int = (iv(sm3(query.toByteArray()), body?.let { digest("MD5", it) } ?: ByteArray(16), ts) and 15) % 3
    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = (check ushr (if (i and 1 == 0) 3 else 5)) xor check
            check = if (i and 1 == 0) data[i].u() xor (check shl 7) else (data[i].u() or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }
    fun hashF13(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        val index = (iv(query, body, ts) and 15) / 3
        require((iv(query, body, ts) and 15) % 3 == 0) { "Unsupported hash branch" }
        val ivV1 = intArrayOf(0xc4a78580.toInt(),0xb3c0fd39.toInt(),0xc58c5686.toInt(),0xc9aa3ba7.toInt(),0xf5a7adf2.toInt(),0x963c2ed1.toInt())[index]
        val count = ivV1 + ts
        val tt = VendorConstants.hashTable.map { ror(it, (count + 1) and 255) }
        val shift = (count + 2) and 7
        val pad = intArrayOf(0xfa,0x45,0x61,0xd7).map { ((it or (it shl 8)) shr shift).toByte() }.toByteArray()
        val data = query + body + le32(ts) + pad + hex("00000000000001a0")
        val di = (data.indices step 4).map { data.i32(it) }.toMutableList()
        for (i in 0 until 112) {
            val x = di[i + 1]; val y = di[i + 14]
            di.add(di[i] + di[i + 9] + (rol(x,14) xor rol(x,25) xor (x ushr 3)) + (rol(y,13) xor rol(y,15) xor (y ushr 10)))
        }
        val initial = intArrayOf(0x7aba4fc8,0x67166507,0x6403fa00,0x340f512f,984304912,3005047866L.toInt(),2874125293L.toInt(),2152413264L.toInt()).map { ror(it, count) }
        val chosen = arrayOf(intArrayOf(101,5,7,6,3,2,1,0,5,4,3),intArrayOf(96,0,6,7,5,3,2,1,5,4,4),intArrayOf(96,7,6,2,1,4,0,5,4,3,5),intArrayOf(99,3,6,2,4,5,1,0,0,7,6),intArrayOf(96,0,5,6,7,3,1,2,5,4,4),intArrayOf(100,2,0,3,5,4,6,7,2,1,5))[index]
        val d = initial.toMutableList()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = rol(d[chosen[1]],26) xor rol(d[chosen[1]],21) xor rol(d[chosen[1]],7)
            val n4 = di[base and 127] + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = rol(d[chosen[2]],30) xor rol(d[chosen[2]],19) xor rol(d[chosen[2]],10)
            val n6 = (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val old = d[chosen[9]]
            d.add(0,d.removeAt(7)); d[chosen[10]] = n5 + n6 + n4; d[chosen[8]] = old + n4
        }
        val ret = (0..7).fold(byteArrayOf()) { a, i -> a + be32(d[i] + initial[i]) }
        val folded = xor(ret.copyOfRange(0,16),ret.copyOfRange(16,32))
        return folded + le32(sumMd5(folded))
    }
    fun gorgon(query: String, body: ByteArray?, ts: Int, random: Int): String {
        val input = digest("MD5",query.toByteArray()).copyOf(4) + (body?.let { digest("MD5",it).copyOf(4) } ?: ByteArray(4)) + ByteArray(4) + le32(67503104) + be32(ts)
        val key = intArrayOf(0x4a,0x40,0x16,(random shr 8) and 255,0x47,0x6c,1,random and 255)
        val s = IntArray(256) { it }; var j = 0
        for (i in 0..255) { j = (j + s[i] + key[i % 8]) and 255; s[i] = s[j] }
        j = 0
        val out = ByteArray(input.size) { n -> val i = n + 1; j += s[i]; val y = s[j and 255]; s[i] = y; (input[n].u() xor s[(y + y) and 255]).toByte() }
        for (i in out.indices) {
            val value = ((out[i].u() shr 4) or (out[i].u() shl 4)) and 255
            val next = out[if (i + 1 < out.size) i + 1 else 0].u()
            val reversed = Integer.reverse((next xor value) and 255) ushr 24
            out[i] = (reversed xor 20).inv().toByte()
        }
        return (hex("8404") + byteArrayOf(random.toByte(),(random shr 8).toByte(),0x40,1) + out).hex()
    }
    private fun deviceProto() = proto(f(1,1,"sint"),f(2,2,"sint"),f(3,"8662"),f(4,VendorConstants.device.getValue("device_id")),f(5,VendorConstants.processNonce),f(6,"!noperm!"),f(7,-888888,"sint"),f(8,-888888,"sint"),f(9,3,"sint"),f(10,-888888,"sint"),f(11,"!notset!"),f(12,"Asia/Shanghai,8"),f(13,"zh_CN"),f(14,4,"sint"),f(16,255.24993896484375,"float"),f(17,35.58599090576172,"float"),f(18,3.467449188232422,"float"),f(19,3.467449188232422,"float"),f(20,255.1754913330078,"float"),f(21,42.17544174194336,"float"),f(22,"16"),f(23,41,"sint"),f(24,36,"sint"),f(25,VendorConstants.processStarted,"sint"),f(26,VendorConstants.processStarted,"sint"),f(27,VendorConstants.processStarted,"sint"),f(28,VendorConstants.processStarted+2,"sint"),f(29,-1,"sint"),f(30,"25053RT47C"),f(31,"Redmi"),f(32,"25053RT47C"),f(33,"25053RT47C"),f(34,"Xiaomi"),f(35,"Redmi"),f(36,"Redmi"),f(38,31,"sint"))
    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28; val d0 = key[at].u(); val d1 = key[at + 1].u(); val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1; d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255; d2 = (d2 xor d0) and 255; encoded[data.size-i-1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size-2]; val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] = (encoded[2] + ((last-first) xor (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor 2)) and 255
        for (i in 0 until encoded.size-4) {
            val temp = (((encoded[i+2] shl 3) or (encoded[i+2] ushr 5)) and 255) xor encoded[i+1] xor (i+3)
            encoded[i+3] = (temp.inv() + encoded[i+3]) and 255
        }
        encoded[encoded.size-1] = encoded.last() xor encoded[encoded.size-2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }
    fun medusa(url: String, body: ByteArray?, ts: Int, deviceId: String = VendorConstants.device.getValue("device_id"), versionName: String = VendorConstants.device.getValue("version_name")): String {
        val bodyMd5 = body?.let { digest("MD5",it) } ?: ByteArray(16)
        val query = url.substringAfter('?').toByteArray(); val querySm3 = sm3(query)
        val queryBodyTs = hashF13(querySm3,bodyMd5,ts)
        val nested = proto(f(1,111,"sint"),f(2,10,"sint"),f(3,694367,"sint"),f(5,586952199,"sint"))
        val messageRand = unsigned(rand()); val envLaunch = (random()*21).toInt()+100; val envPid = (random()*2000).toInt()+10001
        val env = proto(f(1,envLaunch,"sint"),f(2,146331399,"sint"),f(3,146331396,"sint"),f(5,7,"sint"),f(6,"v04.06.04.03-bugfix"),f(7,envPid,"sint"),f(12,deviceProto()),f(13,proto(f(1,clock()/1000,"sint"),f(2,-2,"sint"),f(4,200,"sint"))),f(14,versionName))
        val queryHash = sm3(query+bodyMd5+"none".toByteArray())
        val message = proto(f(1,hex("f7e85ffad7d7dc3bd62ac87057cf6118")),f(2,3,"sint"),f(3,messageRand,"sint"),f(4,"8662"),f(5,deviceId),f(6,"1588093228"),f(7,versionName),f(8,"v04.06.04-ml-android"),f(9,67503104,"sint"),f(10,hex("4001000000000000")),f(12,ts,"sint"),f(13,queryBodyTs),f(14,querySm3.copyOf(6)),f(15,nested),f(16,"AXYQOS6n2m60x1fVZHIrH3iol"),f(17,ts,"sint"),f(19,queryHash),f(20,"none"),f(21,312,"sint"),f(23,env),f(24,"""{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"""))
        val r = rand(); val signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val hash = sm3(signKey+le32(r)+signKey); val d1 = (r shr 16) and 255
        val seed = le32((((d1 shl 11) or (r ushr 24)) xor (d1 shr 5) xor d1).inv())
        val transformed = (hex("4001000000000000")+xmxor(message,hash)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = ((querySm3[0].u() and 63) shl 14) or 0x18000001 or ((queryBodyTs[0].u() and 63) shl 8)
        val packed = byteArrayOf(0x35)+le32(rand())+le32(check)+transformed+byteArrayOf((r shr 16).toByte(),(r shr 24).toByte())
        val encrypted = AesV3(hex("f1593376766ea98d34f31b057a9d5be4"),ts).encrypt(packed,hex("1fe109a4125283f418de9e051a969e12"))
        val versionBytes = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix = (0 until 20 step 4).fold(byteArrayOf()) { a,i -> a+le32(versionBytes.i32(i,true) xor ts) }
        return (prefix+byteArrayOf(r.toByte(),(r shr 8).toByte(),0,1)+encrypted).base64()
    }
    fun helios(ts: Int): String {
        val random = rand()
        val ascii = digest("MD5",le32(random)+"8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it*8,true) }.toMutableList(); val table = mutableListOf(words[0])
        var b0 = words.removeAt(0); var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8,8)+b0) xor i.toLong()
            words.add(x8); x8 = x8 xor java.lang.Long.rotateRight(b0,61); table.add(x8); b0=x8; b8=words.removeAt(0)
        }
        val text = "$ts-1588093228-8662".toByteArray(); val padding = 16 - text.size % 16
        val data = text+ByteArray(padding) { padding.toByte() }
        var output = le32(random)
        for (at in data.indices step 16) {
            var a = data.i64(at,true); var b = data.i64(at+8,true)
            for (i in 0 until 34) { b = table[i] xor (a + java.lang.Long.rotateRight(b,8)); a = b xor java.lang.Long.rotateRight(a,61) }
            output += le64(a)+le64(b)
        }
        return output.base64()
    }
    fun sign(base: String, values: Map<String,String>, body: ByteArray? = null): Request {
        val now = clock(); val ts = (now / 1000).toInt()
        var ticket = now; var query: String
        for (offset in 0..127) {
            ticket = now + offset; query = encode(values + mapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            if (branch(query,body,ts) == 0) {
                val url = "$base?$query"
                val random = (random()*65536).toInt()
                val headers = linkedMapOf("User-Agent" to (if (body != null) VendorConstants.VIDEO_UA else VendorConstants.APP_UA),"Accept" to "application/json; charset=utf-8,application/x-protobuf", "x-ss-req-ticket" to ticket.toString(),"x-tt-request-tag" to "t=0;n=0","sdk-version" to "2","passport-sdk-version" to "50561","x-xs-from-web" to "0","x-khronos" to ts.toString(),"x-ladon" to be32(ts).base64(),"x-argus" to le32(ts).base64(),"x-gorgon" to gorgon(query,body,ts,random),"x-helios" to helios(ts),"x-medusa" to medusa(url,body,ts),"x-tt-dt" to "")
                if (body != null) { headers["Content-Type"]="application/json; charset=UTF-8"; headers["x-vc-bdturing-sdk-version"]="3.7.2.cn"; headers["x-ss-stub"]=digest("MD5",body).hex().uppercase() }
                return Request(url,headers,body)
            }
        }
        error("无法生成请求签名")
    }
    /**
     * 播放接口签名（2026-11-05 平台变更后的新算法，移植自 PhoenixReadApi 的 SignPlayer：
     * https://github.com/LanZhan-Harmony/PhoenixReadApi ）。
     * 旧六头（含 helios/medusa）已被风控废弃（一律回 110001）；新签名只保留五头：
     * X-Khronos / X-SS-Req-Ticket / X-Gorgon / X-Argus / X-Ladon，body 非空时附 X-SS-STUB。
     * 比 [sign] 少一个 `ts` 查询参数（播放接口不接受）；Argus/Ladon 绑定查询串里的
     * device_id / aid，指纹固定为 7.3.5.32。
     */
    fun signPlayback(base: String, values: Map<String,String>, body: ByteArray?, userAgent: String): Request {
        val now = clock(); val ts = now / 1000
        val query = encode(values + mapOf("_rticket" to now.toString()))
        val url = "$base?$query"
        val nonce = ByteArray(11) { (random() * 256).toInt().toByte() }
        val deviceId = values["device_id"] ?: VendorConstants.device.getValue("device_id")
        val aid = values["aid"] ?: "8662"
        val headers = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to now.toString(),
            "x-khronos" to ts.toString(),
            "x-gorgon" to playerGorgon(query,body,ts),
            "x-argus" to playerArgus(query,body,ts,deviceId,nonce),
            "x-ladon" to playerLadon(aid,ts,nonce.copyOfRange(2,6)),
        )
        if (body != null && body.isNotEmpty()) {
            headers["Content-Type"]="application/json; charset=utf-8"
            headers["x-ss-stub"]=digest("MD5",body).hex().uppercase()
        }
        return Request(url,headers,body)
    }
    // ---- 以下为 PhoenixReadApi 新播放签名私有实现 ----
    private fun playerGorgon(query: String, body: ByteArray?, ts: Long): String {
        val payload = ByteArray(20)
        digest("MD5",query.toByteArray()).copyInto(payload,0,0,4)
        if (body != null && body.isNotEmpty()) digest("MD5",body).copyInto(payload,4,0,4)
        payload[13] = 6; payload[14] = 11; payload[15] = 28
        be32(ts.toInt()).copyInto(payload,16)
        for (i in payload.indices) payload[i] = (payload[i].u() xor PLAYER_REQUEST_KEY[i].u()).toByte()
        for (j in payload.indices) {
            val rotated = ((payload[j].u() shl 4) or (payload[j].u() ushr 4)) and 255
            val mixed = rotated xor payload[(j + 1) % payload.size].u()
            payload[j] = ((Integer.reverse(mixed) ushr 24) xor 0xFF xor payload.size).toByte()
        }
        return (hex("8404401c0000") + payload).hex()
    }
    private fun playerArgus(query: String, body: ByteArray?, ts: Long, deviceId: String, nonce: ByteArray): String {
        val bodyHash = if (body == null || body.isEmpty()) sm3(ByteArray(16)) else sm3(digest("MD5",body))
        val queryHash = sm3(query.toByteArray())
        val out = ByteArrayOutputStream()
        fun pvTo(s: ByteArrayOutputStream, field: Long, value: Long) { s.write(varint(field shl 3)); s.write(varint(value)) }
        fun pbTo(s: ByteArrayOutputStream, field: Long, value: ByteArray) { s.write(varint((field shl 3) or 2)); s.write(varint(value.size.toLong())); s.write(value) }
        fun pv(field: Long, value: Long) = pvTo(out,field,value)
        fun pb(field: Long, value: ByteArray) = pbTo(out,field,value)
        fun pb(field: Long, value: String) = pb(field,value.toByteArray())
        val counters = ByteArrayOutputStream().apply { pvTo(this,1,1); pvTo(this,2,1); pvTo(this,3,1); pvTo(this,7,3348294860L) }.toByteArray()
        val environment = ByteArrayOutputStream().apply { pbTo(this,1,"25053RT47C".toByteArray()); pbTo(this,2,"16".toByteArray()); pbTo(this,3,"update_64".toByteArray()); pvTo(this,4,2147483648L) }.toByteArray()
        pv(1,0x20200929L * 2); pv(2,2)
        pv(3,(nonce.i32(6,true) and 0x7fffffff).toLong())
        pb(4,"8662"); pb(5,deviceId); pb(6,"1611921764"); pb(7,"7.3.5.32"); pb(8,"v04.07.01-ml-android")
        pv(9,135135744); pb(10,ByteArray(8)); pv(11,0); pv(12,ts * 2)
        pb(13,bodyHash.copyOf(6)); pb(14,queryHash.copyOf(6))
        pb(15,counters)
        pb(16,ByteArray(0)); pb(20,"none"); pv(21,738)
        pb(23,environment)
        pv(25,2)
        val data = pad(out.toByteArray())
        val rounds = LongArray(72)
        for (i in 0..3) rounds[i] = PLAYER_ARGUS_DERIVED.i64(i * 8,true)
        for (k in 4 until 72) {
            var mixed = java.lang.Long.rotateRight(rounds[k - 1],3) xor rounds[k - 3]
            mixed = mixed xor java.lang.Long.rotateRight(mixed,1)
            val bit = (0x3dc94c3a046d678bL ushr ((k - 4) % 62)) and 1L
            rounds[k] = rounds[k - 4].inv() xor mixed xor (bit xor 3L)
        }
        var at = 0
        while (at < data.size) {
            var left = data.i64(at,true); var right = data.i64(at + 8,true)
            for (round in rounds) {
                val nextRight = left xor (java.lang.Long.rotateLeft(right,1) and java.lang.Long.rotateLeft(right,8)) xor java.lang.Long.rotateLeft(right,2) xor round
                left = right; right = nextRight
            }
            le64(left).copyInto(data,at); le64(right).copyInto(data,at + 8)
            at += 16
        }
        val mask = hex("d04ffdffd04ffdff")
        for (n in data.indices) data[n] = (data[n].u() xor mask[n % 8].u()).toByte()
        val combined = (mask + data).reversedArray()
        val container = pad(hex("a6e783ee7001100918") + combined + hex("567b"))
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE,SecretKeySpec(digest("MD5",PLAYER_ARGUS_KEY.copyOfRange(0,16)),"AES"),IvParameterSpec(digest("MD5",PLAYER_ARGUS_KEY.copyOfRange(16,32))))
            doFinal(container)
        }
        return (byteArrayOf(0x3c,0xcc.toByte()) + encrypted).base64()
    }
    private fun playerLadon(aid: String, ts: Long, prefix: ByteArray): String {
        val ascii = digest("MD5",prefix + aid.toByteArray()).hex().toByteArray(Charsets.US_ASCII)
        var num = ascii.i64(0,true); var value = ascii.i64(8,true)
        val queue = ArrayDeque(listOf(ascii.i64(16,true),ascii.i64(24,true)))
        val table = LongArray(34)
        for (i in table.indices) {
            table[i] = num
            val num2 = (java.lang.Long.rotateRight(value,8) + num) xor i.toLong()
            queue.addLast(num2)
            num = num2 xor java.lang.Long.rotateLeft(num,3)
            value = queue.removeFirst()
        }
        val data = pad("$ts-1611921764-$aid".toByteArray())
        var at = 0
        while (at < data.size) {
            num = data.i64(at,true); value = data.i64(at + 8,true)
            for (k in table) {
                value = k xor (num + java.lang.Long.rotateRight(value,8))
                num = value xor java.lang.Long.rotateLeft(num,3)
            }
            le64(num).copyInto(data,at); le64(value).copyInto(data,at + 8)
            at += 16
        }
        return (prefix + data).base64()
    }
    private fun pad(data: ByteArray): ByteArray {
        val n = 16 - data.size % 16
        return data + ByteArray(n) { n.toByte() }
    }
    companion object {
        fun encode(values: Map<String,String>) = values.entries.joinToString("&") { (k,v) -> "${encode(k)}=${encode(v)}" }
        fun encode(value: String): String = URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("%7E","~")
        // PhoenixReadApi 播放签名常量
        private val PLAYER_REQUEST_KEY = byteArrayOf(68,185.toByte(),185.toByte(),217.toByte(),164.toByte(),174.toByte(),249.toByte(),252.toByte(),164.toByte(),147.toByte(),170.toByte(),117.toByte(),124.toByte(),163.toByte(),194.toByte(),196.toByte(),164.toByte(),150.toByte(),147.toByte(),143.toByte())
        private val PLAYER_ARGUS_KEY = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
        private val PLAYER_ARGUS_DERIVED = sm3(PLAYER_ARGUS_KEY + hex("3ccc567b") + PLAYER_ARGUS_KEY)
    }
}
