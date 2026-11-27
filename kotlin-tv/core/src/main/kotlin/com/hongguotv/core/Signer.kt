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

class Signer(private val clock: () -> Long = System::currentTimeMillis, private val device: Map<String, String> = VendorConstants.device, private val random: () -> Double = Math::random) {
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
    /** v5（v04.10.03）摘要：固定分支 12、seed 0x9d4a97c2、96 轮状态选择 (1,3,4,0,6,5,2,0,7,7)，
     *  末位校验或上 0x20。轮函数与 v3 完全相同，只是入口表与选中项换了。 */
    fun hashF13V5(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        require((iv(query, body, ts) and 15) == 12) { "Unsupported hash branch" }
        val ivV1 = 0x9d4a97c2.toInt()
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
        val chosen = intArrayOf(96,1,3,4,0,6,5,2,0,7,7)
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
        return folded + le32(sumMd5(folded) or 0x20)
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
    private fun deviceProto() = proto(f(1,1,"sint"),f(2,2,"sint"),f(3,"8662"),f(4,device.getValue("device_id")),f(5,VendorConstants.processNonce),f(6,"!noperm!"),f(7,-888888,"sint"),f(8,-888888,"sint"),f(9,3,"sint"),f(10,-888888,"sint"),f(11,"!notset!"),f(12,"Asia/Shanghai,8"),f(13,"zh_CN"),f(14,4,"sint"),f(16,255.24993896484375,"float"),f(17,35.58599090576172,"float"),f(18,3.467449188232422,"float"),f(19,3.467449188232422,"float"),f(20,255.1754913330078,"float"),f(21,42.17544174194336,"float"),f(22,"16"),f(23,41,"sint"),f(24,36,"sint"),f(25,VendorConstants.processStarted,"sint"),f(26,VendorConstants.processStarted,"sint"),f(27,VendorConstants.processStarted,"sint"),f(28,VendorConstants.processStarted+2,"sint"),f(29,-1,"sint"),f(30,"25053RT47C"),f(31,"Redmi"),f(32,"25053RT47C"),f(33,"25053RT47C"),f(34,"Xiaomi"),f(35,"Redmi"),f(36,"Redmi"),f(38,31,"sint"))
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
    fun medusa(url: String, body: ByteArray?, ts: Int, deviceId: String = device.getValue("device_id"), versionName: String = device.getValue("version_name")): String {
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
    /** Android v5（v04.10.03）的 Medusa：报文结构、魔数、license、设备串与帧头全部换新，
     *  AES 走 [AesV3] 的 v5 表。上游 2026-11-16 用纯 Kotlin 复刻后实测可恢复取址。
     *
     *  [p] 是调用方取的那一份协议档案快照：整条报文的 magic/license/signKey/sbox/版本号
     *  必须来自同一份档案，中途换档案不能出现「魔数用新版、密钥用旧版」的撕裂。 */
    private fun medusaV5(url: String, bodyMd5: ByteArray?, ts: Int, who: Map<String,String>, p: V5Profile): String {
        val query = sm3(url.substringAfter('?').toByteArray(Charsets.UTF_8))
        val f13 = hashF13V5(query, bodyMd5 ?: ByteArray(16), ts)
        val flags = ByteArray(8)
        val version = who.getValue("version_name")
        val deviceInfo = proto(
            f(1,1,"sint"),f(2,2,"sint"),f(3,p.aid),f(4,who.getValue("device_id")),
            f(5,VendorConstants.processNonce),f(6,p.perm),f(12,p.tz),f(13,p.lang),
            f(22,who.getValue("os_version")),f(25,VendorConstants.processStarted,"sint"),
            f(30,who.getValue("device_type")),f(31,p.deviceMake),
            f(32,who.getValue("device_type")),f(34,p.deviceMake),
        )
        val env = proto(f(6,p.envVersion),f(8,who.getValue("channel")),f(12,deviceInfo),f(14,version))
        val message = proto(
            f(1,p.magic),f(2,5,"sint"),f(3,unsigned(rand()),"sint"),
            f(4,p.aid),f(5,who.getValue("device_id")),f(6,p.license),
            f(7,version),f(8,p.messageVersion),f(9,p.numericVersion,"sint"),
            f(10,flags),f(12,ts,"sint"),f(13,f13),f(14,query.copyOf(6)),
            f(15,proto(f(1,1,"sint"),f(2,1,"sint"),f(3,1,"sint"),f(5,1,"sint"))),
            f(17,ts,"sint"),f(20,p.mediaTag),f(21,p.field21,"sint"),f(23,env),
            f(24,p.metricsJson),
        )
        val nonce = rand()
        val key = p.signKey
        val hash = sm3(key+le32(nonce)+key)
        val high = (nonce ushr 16) and 255
        val seed = le32((((high shl 11) or (nonce ushr 24)) xor (high ushr 5) xor high).inv())
        val transformed = (flags+xmxor(message,hash)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = 0x18000001 or ((query[0].u() and 63) shl 14) or ((f13[0].u() and 63) shl 8)
        val packed = byteArrayOf(0xca.toByte())+le32(rand())+le32(check)+transformed+
            byteArrayOf((nonce ushr 16).toByte(),(nonce ushr 24).toByte())
        val encrypted = AesV3(digest("MD5",key.copyOfRange(0,16)),ts,v5=true,boxOverride=p.sbox)
            .encrypt(packed,digest("MD5",key.copyOfRange(16,32)))
        val prefix = le32(5)+p.magic
        for (i in prefix.indices) prefix[i] = (prefix[i].u() xor le32(ts)[i and 3].u()).toByte()
        return (prefix+byteArrayOf(nonce.toByte(),(nonce ushr 8).toByte(),0,1)+encrypted).base64()
    }
    fun helios(ts: Int): String = helios(ts,"1588093228")
    private fun helios(ts: Int, license: String): String {
        val random = rand()
        val ascii = digest("MD5",le32(random)+"8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it*8,true) }.toMutableList(); val table = mutableListOf(words[0])
        var b0 = words.removeAt(0); var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8,8)+b0) xor i.toLong()
            words.add(x8); x8 = x8 xor java.lang.Long.rotateRight(b0,61); table.add(x8); b0=x8; b8=words.removeAt(0)
        }
        val text = "$ts-$license-8662".toByteArray(); val padding = 16 - text.size % 16
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
        val deviceId = values["device_id"] ?: device.getValue("device_id")
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
    /**
     * 播放签名 v5（Android `v04.10.03`）：2026-11-16 15:00 之后 v3 六头与 v1/v2 五头一律 HTTP 200 + 0 字节，
     * 官方改成按 ticket 选分支 —— 必须让 `iv(query,md5(body),ts) and 15 == 12` 命中后才签名，
     * 且只发 X-Khronos / X-Helios / X-Medusa 三头（不再带 argus/ladon/gorgon）。
     * 这条链路对「剧集后段」同样有效，不再受官网游客前 3 集限制。
     */
    fun signPlaybackV5(base: String, values: Map<String,String>, body: ByteArray?): Request {
        val now=clock(); val ts=(now/1000).toInt()
        val p=PlaybackProfiles.v5
        val bodyMd5=body?.let { digest("MD5",it) }
        for(offset in 0..1023) {
            val ticket=now+offset
            val query=encode(values+("_rticket" to ticket.toString()))
            if((iv(sm3(query.toByteArray()),bodyMd5?:ByteArray(16),ts) and 15)!=12) continue
            val url="$base?$query"
            val headers=linkedMapOf(
                "x-khronos" to ts.toString(),
                "x-helios" to helios(ts,p.license),
                "x-medusa" to medusaV5(url,bodyMd5,ts,values,p),
            )
            if(bodyMd5!=null && body!=null && body.isNotEmpty()) {
                headers["Content-Type"]="application/json; charset=utf-8"
                headers["x-ss-stub"]=bodyMd5.hex().uppercase()
            }
            return Request(url,headers,body)
        }
        error("无法生成播放请求签名（v5 分支未命中）")
    }
    /**
     * 评论接口签名（移植 PhoenixReadApi 的 `SignComment`）。
     * 评论服务不认播放那套五头/三头，也不认旧六头（[sign]，一律 `110001 未知异常`），自己一套：
     * - `X-Gorgon`：8 字节密钥 + 26 字节报文，**只吃查询串、不含 body**；
     * - `X-Argus`：指纹固定 `6.8.1.32`、license `3019`、不写设备号；
     * - `X-Ladon`：哈希输入是查询串里的 aid，但报文标识固定 `3019`；
     * - 额外头 `lc: 101` / `Comment-Source` / `Server-Channel` 决定评论来源分集还是剧评。
     */
    fun signComment(
        base: String,
        values: Map<String, String>,
        body: ByteArray?,
        commentSource: Int,
        serverChannel: Int?,
        userAgent: String = VendorConstants.APP_UA,
    ): Request {
        val now = clock(); val ts = now / 1000
        val query = encode(values + ("_rticket" to now.toString()))
        val url = "$base?$query"
        val nonce = ByteArray(11) { (random() * 256).toInt().toByte() }
        val aid = values["aid"] ?: "8662"
        val headers = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "x-xs-from-web" to "0",
            "sdk-version" to "2",
            "lc" to "101",
            "Comment-Source" to commentSource.toString(),
            "x-ss-req-ticket" to now.toString(),
            "x-khronos" to ts.toString(),
            "x-ss-stub" to "",
            "x-gorgon" to commentGorgon(query, ts, nonce),
            "x-argus" to commentArgus(query, ts, nonce),
            "x-ladon" to commentLadon(aid, ts, nonce.copyOfRange(2, 6)),
        )
        if (serverChannel != null) headers["Server-Channel"] = serverChannel.toString()
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        return Request(url, headers, body)
    }

    private fun commentGorgon(query: String, ts: Long, nonce: ByteArray): String {
        val b = ((nonce[0].u() % 7) + 1) shl 5
        val b2 = 0xE0 or (nonce[1].u() and 0xF)
        val payload = ByteArray(20)
        digest("MD5", query.toByteArray()).copyInto(payload, 0, 0, 16)
        payload[12] = 0; payload[13] = 1; payload[14] = 7; payload[15] = 4
        be32(ts.toInt()).copyInto(payload, 16)
        val key = byteArrayOf(74, 0, 22, b2.toByte(), 71, 108, 0, b.toByte())
        val box = ByteArray(256) { it.toByte() }
        var k = 0
        for (i in box.indices) {
            k = (k + box[i].u() + key[i and 7].u()) and 255
            val swap = box[i]; box[i] = box[k]; box[k] = swap
        }
        k = 0
        for (i in payload.indices) {
            val index = i + 1
            k = (k + box[index].u()) and 255
            val swap = box[index]; box[index] = box[k]; box[k] = swap
            payload[i] = (payload[i].u() xor box[(box[index].u() + box[k].u()) and 255].u()).toByte()
        }
        for (i in payload.indices) {
            val rotated = ((payload[i].u() shl 4) or (payload[i].u() ushr 4)) and 255
            payload[i] = (reverseByte(rotated xor payload[(i + 1) % payload.size].u()) xor 235).toByte()
        }
        return (byteArrayOf(132.toByte(), 4, b.toByte(), b2.toByte(), 0, 0) + payload).hex()
    }

    private fun reverseByte(value: Int): Int {
        var v = value and 255
        v = ((v and 0x55) shl 1) or ((v and 0xAA) ushr 1)
        v = ((v and 0x33) shl 2) or ((v and 0xCC) ushr 2)
        return ((v shl 4) or (v ushr 4)) and 255
    }

    private fun commentArgus(query: String, ts: Long, nonce: ByteArray): String {
        val out = ByteArrayOutputStream()
        fun pv(field: Long, value: Long) { out.write(varint(field shl 3)); out.write(varint(value)) }
        fun pb(field: Long, value: ByteArray) { out.write(varint((field shl 3) or 2)); out.write(varint(value.size.toLong())); out.write(value) }
        fun pb(field: Long, value: String) = pb(field, value.toByteArray())
        pv(1, 1077940818L); pv(2, 2)
        pv(3, ByteBuffer.wrap(nonce, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL)
        pb(4, "3019"); pb(6, "1611921764"); pb(7, "6.8.1.32"); pb(8, "v04.07.01-ml-android")
        pv(9, 135135744); pb(10, ByteArray(8)); pv(12, ts * 2)
        pb(13, sm3(ByteArray(16)).copyOf(6))
        pb(14, sm3(query.toByteArray()).copyOf(6))
        val counters = ByteArrayOutputStream().apply {
            write(8); write(2 + (nonce[10].u() % 49) * 2)
            for (field in longArrayOf(2, 3, 4)) { write(varint(field * 8)); write(varint(1388734)) }
        }.toByteArray()
        pb(15, counters)
        pb(20, "none"); pv(21, 738)
        val data = pad(out.toByteArray())
        // 与播放 Argus 同一把派生密钥（CommentArgusKey 就是 PLAYER_ARGUS_KEY），只是报文字段与指纹不同
        val rounds = LongArray(72)
        for (i in 0..3) rounds[i] = PLAYER_ARGUS_DERIVED.i64(i * 8, true)
        for (k in 4 until 72) {
            var mixed = java.lang.Long.rotateRight(rounds[k - 1], 3) xor rounds[k - 3]
            mixed = mixed xor java.lang.Long.rotateRight(mixed, 1)
            val bit = (0x3dc94c3a046d678bL ushr ((k - 4) % 62)) and 1L
            rounds[k] = rounds[k - 4].inv() xor mixed xor (bit xor 3L)
        }
        var at = 0
        while (at < data.size) {
            var left = data.i64(at, true); var right = data.i64(at + 8, true)
            for (round in rounds) {
                val nextRight = left xor (java.lang.Long.rotateLeft(right, 1) and java.lang.Long.rotateLeft(right, 8)) xor java.lang.Long.rotateLeft(right, 2) xor round
                left = right; right = nextRight
            }
            le64(left).copyInto(data, at); le64(right).copyInto(data, at + 8)
            at += 16
        }
        val mask = hex("d04ffdffd04ffdff")
        for (n in data.indices) data[n] = (data[n].u() xor mask[n % 8].u()).toByte()
        val combined = (mask + data).reversedArray()
        val container = pad(hex("a6e783ee7001100918") + combined + hex("567b"))
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest("MD5", PLAYER_ARGUS_KEY.copyOfRange(0, 16)), "AES"), IvParameterSpec(digest("MD5", PLAYER_ARGUS_KEY.copyOfRange(16, 32))))
            doFinal(container)
        }
        return (byteArrayOf(0x3c, 0xcc.toByte()) + encrypted).base64()
    }

    private fun commentLadon(aid: String, ts: Long, prefix: ByteArray): String {
        val ascii = digest("MD5", prefix + aid.toByteArray()).hex().toByteArray(Charsets.US_ASCII)
        var num = ascii.i64(0, true); var value = ascii.i64(8, true)
        val queue = ArrayDeque(listOf(ascii.i64(16, true), ascii.i64(24, true)))
        val table = LongArray(34)
        for (i in table.indices) {
            table[i] = num
            val next = (java.lang.Long.rotateRight(value, 8) + num) xor i.toLong()
            queue.addLast(next)
            num = next xor java.lang.Long.rotateLeft(num, 3)
            value = queue.removeFirst()
        }
        val data = pad("$ts-1611921764-3019".toByteArray())
        var at = 0
        while (at < data.size) {
            num = data.i64(at, true); value = data.i64(at + 8, true)
            for (k in table) {
                value = k xor (num + java.lang.Long.rotateRight(value, 8))
                num = value xor java.lang.Long.rotateLeft(num, 3)
            }
            le64(num).copyInto(data, at); le64(value).copyInto(data, at + 8)
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
        /** 无符号 varint（companion 常量初始化用，类内 varint 是实例方法）。 */
        private fun uv(value: Long): ByteArray {
            var n=value
            val out=ByteArrayOutputStream()
            while(n>127) { out.write(((n and 127) or 128).toInt()); n=n ushr 7 }
            out.write(n.toInt())
            return out.toByteArray()
        }
    }
}
