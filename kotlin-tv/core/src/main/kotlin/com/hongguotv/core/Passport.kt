// SPDX-License-Identifier: GPL-3.0-only
// Passport（登录平面）Kotlin 移植，源自 aston314/hongguo-tv core/cmd/hongguotv/passport_*.go
// （GPL-3.0-only）。用途：官方 App 原生会话（短信登录）→ plan/v1 show_type=160 解锁
// 与登录态个性化。与 drpys 血统的 [Signer] 并存：passport 端点认的是这一套
// X-Khronos / X-SS-Req-Ticket / X-Helios / X-Medusa / X-Gorgon（F13 为 MD5 系、分支 2）。
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

/** 登录会话：设备身份 + 平台下发的 token 与 Cookie。App 层负责持久化。 */
data class PassportSession(
    var deviceId: String = "",
    var installId: String = "",
    var cdid: String = "",
    var openudid: String = "",
    var clientUdid: String = "",
    var token: String = "",
    /** 发码返回的 mobile_ticket，验码必须复用；持久化后发码与验码可分两次进程完成。 */
    var mobileTicket: String = "",
    val cookies: Map<String, String> = emptyMap(),
) {
    /** 有登录态（token 或 sessionid cookie）即可携带会话请求。 */
    val loggedIn: Boolean get() = token.isNotEmpty() || cookies["sessionid"]?.isNotBlank() == true || cookies["sessionid_ss"]?.isNotBlank() == true
    val registered: Boolean get() = deviceId.isNotBlank() && installId.isNotBlank()
}

/** passport 签名全套；随机数全部参数化以便黄金向量校验。 */
internal object PassportSign {
    private val SIGN_KEY = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
    private val AES_KEY = hex("f1593376766ea98d34f31b057a9d5be4")
    private val AES_IV_SEED = hex("1fe109a4125283f418de9e051a969e12")
    private val MEDUSA_HEADER_MAGIC = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
    private val F13_PAD = hex("8496779dd4150bf8")

    /** aston314 passport_constants.go：AES V3 sbox 与 VendorConstants 同源；以下两张表为 passport 专属。 */
    private val ORDERS = hex(
        "0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04060500070c000a04080f010b0d09020e060b02050403080101070a000d0c090e0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e0c090f07060f030e020d0405010b0a000c050a090e0802040407030f01060b000b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d0001040906080f050800040a0b030d01020c0e090d0c0604070a03030f00080105020e01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d04080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c09030a08040f000b01060d0c0709030e05020a070b050f00020e0108030d0c0609040d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003",
    )
    private val MD5_CONSTANTS = intArrayOf(
        0xa7aefe20.toInt(), 0x7149f1d6.toInt(), 0x47e4ca07.toInt(), 0xe9b58f67.toInt(), 0x93b924de.toInt(), 0xc614d0f5.toInt(), 0x38afe0ef.toInt(), 0xb2bbad73.toInt(), 0xe24444c3.toInt(), 0x9d3aec9b.toInt(), 0xdf7b37e4.toInt(), 0xd8b16d40.toInt(), 0xf8ac31b8.toInt(), 0x76b9a90b.toInt(), 0x31d833ee.toInt(), 0x953fce64.toInt(),
        0x353595a4.toInt(), 0x4609c13b.toInt(), 0x36925008.toInt(), 0x8c6d0925.toInt(), 0x5df5c177.toInt(), 0x1cfbf52b.toInt(), 0x8a4fa7f0.toInt(), 0x114ca35e.toInt(), 0x8193f984.toInt(), 0x7a7a8733.toInt(), 0x316ab4d5.toInt(), 0x3c20cfc9.toInt(), 0xa6d84453.toInt(), 0x3a18500c.toInt(), 0x798ec47a.toInt(), 0x97a76b28.toInt(),
        0x66c4ff96.toInt(), 0x51716443.toInt(), 0x0dd2fc3b.toInt(), 0xb5696da7.toInt(), 0xbbeb3ac5.toInt(), 0x5c53d204.toInt(), 0xd32608ce.toInt(), 0x7279b9ec.toInt(), 0xf4188ecf.toInt(), 0xf7d793db.toInt(), 0x332cc491.toInt(), 0xab76ae15.toInt(), 0x9bebe727.toInt(), 0x18a01384.toInt(), 0x5be9f8a7.toInt(), 0x5f90a754.toInt(),
        0x39b663c0.toInt(), 0x36673c83.toInt(), 0x7c92f514.toInt(), 0x9d7d94d7.toInt(), 0xe2e8d9aa.toInt(), 0x5f7e9ea9.toInt(), 0x7abd4551.toInt(), 0x569e05da.toInt(), 0x40a25632.toInt(), 0x3df5a9a5.toInt(), 0xbab37d80.toInt(), 0x454286dc.toInt(), 0x3f5d4e78.toInt(), 0x03d7b75d.toInt(), 0xb1fe4af7.toInt(), 0xa5ab26a3.toInt(),
    )

    /** passport 分支判定：调 `_rticket`（毫秒微调）使命中 `(iv and 15) % 3 == 2`（F13 分支 2）。 */
    fun iv(query: String, body: ByteArray, ts: Long): Int {
        var value = 0x20230928
        val feed = fun(data: ByteArray) {
            for ((index, x) in data.withIndex()) {
                value = if (index and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor x.u()
                else ((value ushr 7) xor value xor (x.u() or (value shl 12))).inv()
            }
        }
        feed(sm3(query.toByteArray()))
        feed(if (body.isEmpty()) ByteArray(16) else digest("MD5", body))
        feed(le32(ts.toInt()))
        return value
    }

    /** 调谐 `_rticket`：返回命中分支 2 的最终查询串。 */
    fun tuneQuery(queryBase: Map<String, String>, body: ByteArray, stampMs: Long): String {
        for (offset in 0 until 256) {
            val query = Signer.encode(queryBase + ("_rticket" to (stampMs + offset).toString()))
            if ((iv(query, body, stampMs / 1000) and 15) % 3 == 2) return query
        }
        error("无法选择 passport 签名分支")
    }

    /** aston314 `passportF13`：MD5 轮函数系摘要，分支 2。 */
    fun f13(ivValue: Int, ts: Long, q: ByteArray, m: ByteArray): ByteArray {
        val group = ((ivValue and 15) - 2) / 3
        val seeds = intArrayOf(0x8980f29b.toInt(), 0xeb549c7f.toInt(), 0xb08726db.toInt(), 0xd40cb5e6.toInt(), 0xe8f559e4.toInt())
        val count = seeds[group] + ts.toInt()
        val shift = (count + 5) and 7
        val msg = ByteArrayOutputStream().apply {
            write(q); write(m); write(le32(ts.toInt()))
            for (x in F13_PAD) write(((x.u() * 257) shr shift) and 255)
            write(byteArrayOf(0xa0.toByte(), 1, 0, 0))
        }.toByteArray()
        val start = intArrayOf(0x79e0f2fb.toInt(), 0xc8b52570.toInt(), 0xebc2f8cd.toInt(), 0x7c104d93.toInt())
        val end = intArrayOf(0x19be4866.toInt(), 0xe85986b4.toInt(), 0xe19b326e.toInt(), 0x71d1d7d4.toInt())
        for (i in start.indices) {
            start[i] = rol(start[i], -(count and 31))
            end[i] = rol(end[i], -((count + 6) and 31))
        }
        var a = start[0]; var b = start[1]; var c = start[2]; var d = start[3]
        val shifts = arrayOf(intArrayOf(7, 12, 17, 22), intArrayOf(5, 9, 14, 20), intArrayOf(4, 11, 16, 23), intArrayOf(6, 10, 15, 21))
        for (i in 0 until 64) {
            val f = when (i / 16) {
                0 -> (b and c) or (b.inv() and d)
                1 -> (b and d) or (c and d.inv())
                2 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val word = msg.i32(ORDERS[group * 64 + i].u() * 4, true)
            val x = b + rol(a + f + word + rol(MD5_CONSTANTS[i], -((count + 1) and 31)), shifts[i / 16][i and 3])
            // Go: a,b,c,d = d,x,b,c（同时赋值）。Kotlin 必须先用旧值再落位。
            val oldD = d; val oldB = b; val oldC = c
            a = oldD; b = x; c = oldB; d = oldC
        }
        val out = ByteArrayOutputStream()
        for (i in 0 until 4) out.write(le32((start[i] + intArrayOf(a, b, c, d)[i]) xor end[i]))
        val raw = out.toByteArray()
        var check = 0x20220420
        for (i in 0 until 12) {
            val x = raw[i].u()
            check = if (i and 1 == 0) ((check ushr 3) xor check) xor x xor (check shl 7)
            else ((check ushr 5) xor check) xor (x or (check shl 11)).inv()
        }
        return raw + le32((check or 4) xor 0x1000000)
    }

    /** aston314 `passportHelios`：MD5(LE(nonce)+"8662") 驱动的 33 轮 Feistel，填充 `ts-1588093228-8662`。 */
    fun helios(ts: Long, nonce: Int): String {
        val ascii = digest("MD5", le32(nonce) + "8662".toByteArray()).hex().toByteArray()
        val rounds = ArrayList<Long>()
        var k = ascii.i64(0, true)
        val pending = ArrayDeque(listOf(ascii.i64(8, true), ascii.i64(16, true), ascii.i64(24, true)))
        rounds.add(k)
        repeat(33) { i ->
            val x = (java.lang.Long.rotateRight(pending.removeFirst(), 8) + k) xor i.toLong()
            pending.addLast(x)
            k = x xor java.lang.Long.rotateLeft(k, 3)
            rounds.add(k)
        }
        val text = "$ts-1588093228-8662".toByteArray()
        val pad = 16 - text.size % 16
        val plain = text + ByteArray(pad) { pad.toByte() }
        var out = le32(nonce)
        var at = 0
        while (at < plain.size) {
            var a = plain.i64(at, true); var b = plain.i64(at + 8, true)
            for (r in rounds) {
                b = r xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateLeft(a, 3)
            }
            out += le64(a) + le64(b)
            at += 16
        }
        return out.base64()
    }

    /** aston314 `passportProtoVersion`：设备/环境/计数字段与版本串全部固定 7.3.5.32 画像。 */
    fun proto(query: String, ts: Long, nonce: Int, deviceId: String, q: ByteArray, m: ByteArray, f13Bytes: ByteArray, version: String = "7.3.5.32"): ByteArray {
        val device = ProtoBuf()
            .putSint(1, 1).putSint(2, 2).putUtf8(3, "8662").putUtf8(4, deviceId).putUtf8(5, "Ai6svO3PyrwDOUSmO6ZcResxu").putUtf8(6, "!noperm!")
            .putSint(7, -888888).putSint(8, -888888).putSint(9, 3).putSint(10, -888888).putUtf8(11, "!notset!")
            .putUtf8(12, "Asia/Shanghai,8").putUtf8(13, "zh_CN").putSint(14, 4)
            .putFloat(16, 255.24993896484375f).putFloat(17, 35.58599090576172f).putFloat(18, 3.467449188232422f)
            .putFloat(19, 3.467449188232422f).putFloat(20, 255.1754913330078f).putFloat(21, 42.17544174194336f)
            .putUtf8(22, "16").putSint(23, 41).putSint(24, 36)
        for (i in 25..28) device.putSint64(i, 1728388016635L + if (i == 28) 2 else 0)
        device.putSint(29, -1)
        listOf("25053RT47C", "Redmi", "25053RT47C", "25053RT47C", "Redmi", "Redmi", "Redmi").forEachIndexed { index, text ->
            device.putUtf8(30 + index, text)
        }
        device.putSint(38, 31)
        val report = ProtoBuf().putSint(1, ts).putSint(2, -2).putSint(4, 200)
        val env = ProtoBuf()
            .putSint(1, 110).putSint(2, 146331399).putSint(3, 146331396).putSint(5, 7)
            .putUtf8(6, "v04.06.04.03-bugfix").putSint(7, 11001)
            .putProtobuf(12, device).putProtobuf(13, report).putUtf8(14, version)
        val count = ProtoBuf().putSint(1, 111).putSint(2, 10).putSint(3, 694367).putSint(5, 586952199)
        val hash = sm3(query.toByteArray() + m + "none".toByteArray())
        val p = ProtoBuf()
            .putBytes(1, hex("f7e85ffad7d7dc3bd62ac87057cf6118"))
            .putSint(2, 3).putSint(3, nonce.toLong() and 0xffffffffL)
            .putUtf8(4, "8662").putUtf8(5, deviceId).putUtf8(6, "1588093228").putUtf8(7, version).putUtf8(8, "v04.06.04-ml-android")
            .putSint(9, 67503104).putBytes(10, hex("4001000000000000")).putSint(12, ts)
            .putBytes(13, f13Bytes).putBytes(14, q.copyOf(6)).putProtobuf(15, count)
            .putUtf8(16, "AXYQOS6n2m60x1fVZHIrH3iol").putSint(17, ts)
            .putBytes(19, hash).putUtf8(20, "none").putSint(21, 312)
            .putProtobuf(23, env)
            .putUtf8(24, """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""")
        return p.toBuf()
    }

    /** aston314 `passportXMXOR`：与 [Signer] 的实现同构，独立副本避免耦合其私有状态。 */
    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28; val d0 = key[at].u(); val d1 = key[at + 1].u(); val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1
            d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255
            d2 = (d2 xor d0) and 255
            encoded[data.size - i - 1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size - 2]; val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] = (encoded[2] + ((last - first) xor (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor 2)) and 255
        for (i in 0 until encoded.size - 4) {
            val temp = (((encoded[i + 2] shl 3) or (encoded[i + 2] ushr 5)) and 255) xor encoded[i + 1] xor (i + 3)
            encoded[i + 3] = (temp.inv() + encoded[i + 3]) and 255
        }
        encoded[encoded.size - 1] = encoded.last() xor encoded[encoded.size - 2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }

    /** aston314 `passportMedusaVersion`：报文变换 + AES V3 + 帧头，全部随机数参数化。 */
    fun medusa(
        query: String, ts: Long, nonce: Int, hashRand: Int, xmRand: Int, deviceId: String, ivValue: Int,
        q: ByteArray, m: ByteArray, version: String = "7.3.5.32",
    ): String {
        val f13Bytes = f13(ivValue, ts, q, m)
        val message = proto(query, ts, nonce, deviceId, q, m, f13Bytes, version)
        val key = sm3(SIGN_KEY + le32(hashRand) + SIGN_KEY)
        val d1 = (hashRand shr 16) and 255
        val seed = le32((((d1 shl 11) or (hashRand ushr 24)) xor (d1 shr 5) xor d1).inv())
        var transformed = (hex("4001000000000000") + xmxor(message, key)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[(i.inv()) and 3].u()).toByte()
        val check = 0x18000001 or ((q[0].u() and 63) shl 14) or ((f13Bytes[0].u() and 63) shl 8)
        val packed = byteArrayOf(0x35) + le32(xmRand) + le32(check) + transformed + le32(hashRand).copyOfRange(2, 4)
        val encrypted = AesV3(AES_KEY, ts.toInt()).encrypt(packed, AES_IV_SEED)
        var prefix = MEDUSA_HEADER_MAGIC.copyOf()
        for (at in prefix.indices step 4) prefix = prefix.copyOf().also { le32(prefix.i32(at, true) xor ts.toInt()).copyInto(it, at) }
        val header = prefix + le32(hashRand).copyOfRange(0, 2) + byteArrayOf(0, 1)
        return (header + encrypted).base64()
    }

    /** aston314 `passportGorgon`：与 [Signer.gorgon] 同构（nonce 进 RC4 密钥与帧头）。直接复用。 */
    fun gorgon(query: String, body: ByteArray, ts: Long, nonce: Int): String = Signer().gorgon(query, body, ts.toInt(), nonce)

    /** 组装 passport 请求签名（aston314 `signPassportRequest`）。返回签名后的 query 与头。 */
    fun sign(queryBase: Map<String, String>, body: ByteArray, stampMs: Long, deviceId: String): Pair<String, Map<String, String>> {
        val query = tuneQuery(queryBase, body, stampMs)
        val ts = stampMs / 1000
        val ivValue = iv(query, body, ts)
        val random = SecureRandom()
        fun rnd() = random.nextInt()
        val nonce = rnd(); val hashRand = rnd(); val xmRand = rnd(); val heliosNonce = rnd()
        val headers = linkedMapOf(
            "X-Khronos" to ts.toString(),
            "X-SS-Req-Ticket" to query.substringAfter("_rticket=").substringBefore('&'),
            "X-Helios" to helios(ts, heliosNonce),
            "X-Medusa" to medusa(query, ts, nonce, hashRand, xmRand, deviceId, ivValue, sm3(query.toByteArray()), if (body.isEmpty()) ByteArray(16) else digest("MD5", body)),
            "X-Gorgon" to gorgon(query, body, ts, nonce and 0xffff),
        )
        if (body.isNotEmpty()) headers["X-SS-STUB"] = digest("MD5", body).hex().uppercase()
        return query to headers
    }
}

/**
 * passport 登录客户端：设备注册（7.3.5.32 画像）→ 短信发码 → 验码登录 → 携会话请求。
 * 独立于 [DeviceRegister]（播放平面 7.3.9.32 画像）：登录平面整套走 aston314 参考画像，
 * 避免与线上已验证的播放身份互相污染。
 */
class PassportClient(private val http: okhttp3.OkHttpClient, private val session: PassportSession) {
    private val random = SecureRandom()
    private val cookies = LinkedHashMap(session.cookies)

    companion object {
        const val API_BASE = "https://api5-normal-lq.fqnovel.com"
        const val DEVICE_BASE = "https://log.snssdk.com"
        /** 内容接口域（参考实现 defaultHongguoAPI，取 b 域）。 */
        const val CONTENT_HOST = "https://api5-normal-sinfonlineb.fqnovel.com"
        const val VERSION = "7.3.5.32"
        const val VERSION_CODE = "73532"
        const val UA =
            "com.phoenix.read/$VERSION_CODE (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
        /** 播放请求的 Cronet UA（openhongguo `PlaybackUserAgent`，7.4.3.32 抓包口径）。 */
        const val PLAYBACK_UA =
            "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
        /** V5 播放 POST 专用 UA（PhoenixReadApi `V5PlaybackUserAgent`）。
         *  官方在这里的版本号 73932 与 query 的 74332 **故意不一致**（客户端编译期常量 vs 服务端下发），
         *  此前我们强行「UA 与 query 逐字一致」改成 74532，反而偏离真实形态。 */
        const val V5_PLAYBACK_UA =
            "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
        private val KDF_SALT = hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
        private val TT_MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)

        /** 手机号混淆（aston314 `passportXOR`+`passportWirePhone`）：逐字节异或 5 后 hex。 */
        fun xorWire(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8).map { (it.u() xor 5).toByte() }.toByteArray()
            return bytes.hex()
        }

        /** 大陆手机号 → 平台线格式（+86 前缀）。 */
        fun wirePhone(raw: String): String {
            val digits = raw.filter(Char::isDigit)
            require(digits.matches(Regex("1[3-9]\\d{9}"))) { "请输入正确的 11 位大陆手机号" }
            return "+86$digits"
        }

        /** [toJson] 的逆操作。 */
        fun fromJson(text: String): PassportSession {
            val json = org.json.JSONObject(text)
            val cookies = linkedMapOf<String, String>()
            json.optJSONObject("cookies")?.let { obj -> obj.keys().forEach { cookies[it] = obj.optString(it) } }
            return PassportSession(
                deviceId = json.optString("device_id"),
                installId = json.optString("install_id"),
                cdid = json.optString("cdid"),
                openudid = json.optString("openudid"),
                clientUdid = json.optString("clientudid"),
                token = json.optString("token"),
                mobileTicket = json.optString("mobile_ticket"),
                cookies = cookies,
            )
        }
    }

    /** 当前会话快照（App 层持久化用）。 */
    fun snapshot(): PassportSession = session.copy(cookies = cookies.toMap())

    private fun uuid(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        b[6] = ((b[6].u() and 15) or 64).toByte()
        b[8] = ((b[8].u() and 63) or 128).toByte()
        val hexs = b.hex()
        return "${hexs.substring(0, 8)}-${hexs.substring(8, 12)}-${hexs.substring(12, 16)}-${hexs.substring(16, 20)}-${hexs.substring(20)}"
    }

    private fun queryBase(includeDevice: Boolean): Map<String, String> {
        val base = linkedMapOf(
            "ac" to "wifi", "aid" to "8662", "app_name" to "novelread", "channel" to "update_64",
            "device_brand" to "Redmi", "device_platform" to "android", "device_type" to "25053RT47C",
            "dpi" to "520", "language" to "zh", "manifest_version_code" to VERSION_CODE, "os" to "android",
            "os_api" to "36", "os_version" to "16", "resolution" to "1280*2772", "ssmix" to "a",
            "update_version_code" to VERSION_CODE, "version_code" to VERSION_CODE, "version_name" to VERSION,
        )
        if (includeDevice && session.registered) {
            base["device_id"] = session.deviceId
            base["iid"] = session.installId
            base["cdid"] = session.cdid
            base["openudid"] = session.openudid
        }
        return base
    }

    /**
     * 注册登录平面设备（aston314 `registerDeviceProfile`）。已注册则直接返回。
     * 成功后 [session] 的 deviceId/installId 被填充，调用方须 [snapshot] 持久化。
     */
    fun registerDevice(): PassportSession {
        if (session.registered) return snapshot()
        val cdid = session.cdid.ifBlank { uuid() }
        val clientUdid = session.clientUdid.ifBlank { uuid() }
        val openudid = session.openudid.ifBlank { ByteArray(8).also { random.nextBytes(it) }.hex() }
        val now = System.currentTimeMillis()
        val seed = ByteArray(32).also { random.nextBytes(it) }
        val payload = devicePayload(cdid, clientUdid, openudid, now)
        val body = ttEncryptFixedHeader(gzipFixedHeader(payload), seed)
        val query = Signer.encode(
            queryBase(includeDevice = false) +
                mapOf("tt_data" to "a", "ts" to (now / 1000).toString(), "_rticket" to now.toString(), "package" to "com.phoenix.read"),
        )
        val request = okhttp3.Request.Builder()
            .url("$DEVICE_BASE/service/2/device_register/?$query")
            .post(okhttp3.RequestBody.create(null, body))
            .apply {
                header("User-Agent", UA)
                header("Content-Type", "application/octet-stream;tt-data=a")
                header("Content-Encoding", "gzip")
                header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                header("X-Tt-Request-Tag", "t=0;n=1")
                header("Log-Encode-Type", "gzip")
                header("Lc", "102")
                header("Sdk-Version", "2")
                header("Passport-Sdk-Version", "60755")
                header("Passport-Sdk-Settings", "device_transfer_s_0,device_transfer_ab_1")
                header("X-SS-DP", "8662")
                header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
                header("X-Tt-Store-Region", "cn")
                header("X-Tt-Store-Region-Src", "did")
                header("X-Khronos", (now / 1000).toString())
                header("X-Argus", le32((now / 1000).toInt()).base64())
                header("X-SS-Req-Ticket", now.toString())
                header("X-SS-STUB", digest("MD5", body).hex())
                header("X-Gorgon", PassportSign.gorgon(query, body, now / 1000, random.nextInt() and 0xffff))
            }
            .build()
        val response = http.newCall(request).execute().use { resp ->
            val raw = resp.body?.bytes() ?: ByteArray(0)
            check(resp.code == 200) { "设备注册返回 HTTP ${resp.code}" }
            val text = if (raw.size >= 38 && raw.copyOfRange(0, 6).contentEquals(TT_MAGIC)) ttDecrypt(raw) else raw
            val json = org.json.JSONObject(text.toString(Charsets.UTF_8))
            val did = json.optString("device_id_str", "").ifBlank { json.opt("device_id")?.toString().orEmpty() }
            val iid = json.optString("install_id_str", "").ifBlank { json.opt("install_id")?.toString().orEmpty() }
            check(did.isNotBlank() && iid.isNotBlank() && did != "0" && iid != "0") { "设备注册未返回有效身份" }
            session.deviceId = did
            session.installId = iid
            session.cdid = cdid
            session.openudid = openudid
            session.clientUdid = clientUdid
            resp.headers("Set-Cookie").forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name in setOf("store-region", "store-region-src", "ttreq", "odin_tt") && name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            cookies["install_id"] = iid
            snapshot()
        }
        return response
    }

    /** aston314 设备注册画像（7.3.5.32 / Redmi 25053RT47C / Android 16）。 */
    private fun devicePayload(cdid: String, clientUdid: String, openudid: String, now: Long): ByteArray {
        val header = org.json.JSONObject()
            .put("aid", 8662).put("access", "wifi").put("app_version", VERSION).put("app_version_minor", "")
            .put("channel", "update_64").put("device_brand", "Redmi").put("device_manufacturer", "Redmi")
            .put("device_model", "25053RT47C").put("device_category", "phone").put("device_platform", "android")
            .put("os", "Android").put("os_api", 36).put("os_version", "16").put("density_dpi", 520)
            .put("display_density", "xxxhdpi").put("resolution", "2772x1280").put("language", "zh")
            .put("timezone", 8).put("tz_name", "Asia/Shanghai").put("tz_offset", 28800).put("region", "CN")
            .put("package", "com.phoenix.read").put("version_code", 73532).put("manifest_version_code", 73532)
            .put("update_version_code", 73532).put("apk_first_install_time", now)
            .put("cdid", cdid).put("clientudid", clientUdid).put("openudid", openudid)
            .put("cpu_abi", "arm64-v8a").put("display_name", "红果短剧")
            .put("sdk_version", "3.7.2").put("sdk_target_version", 29).put("sdk_flavor", "china")
            .put("guest_mode", 0).put("is_system_app", 0).put("not_request_sender", 0).put("oaid_may_support", true)
            .put("ipv6_list", org.json.JSONArray()).put("sim_serial_number", org.json.JSONArray())
            .put("custom", org.json.JSONObject().put("is_android_fold", 0).put("filter_warn", 0).put("web_ua", "").put("is_android_pad", 0).put("client_ipv4", ""))
        return org.json.JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", now)
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** gzip（固定 10 字节头，aston314 `passportTTEncrypt`）。 */
    private fun gzipFixedHeader(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        val z = out.toByteArray()
        System.arraycopy(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0), 0, z, 0, 10)
        return z
    }

    /** TTEncrypt V5（ aston314 变体：密文前拼 `sha512(密文 gzip)`）。 */
    private fun ttEncryptFixedHeader(z: ByteArray, seed: ByteArray): ByteArray {
        val hash = digest("SHA-512", z)
        val p = hash + z
        val padding = 16 - p.size % 16
        val padded = p + ByteArray(padding) { padding.toByte() }
        val derived = digest("SHA-512", digest("SHA-512", seed) + KDF_SALT)
        val encrypted = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(derived.copyOfRange(0, 16), "AES"), javax.crypto.spec.IvParameterSpec(derived.copyOfRange(16, 32)))
            doFinal(padded)
        }
        return TT_MAGIC + seed + encrypted
    }

    /** 设备注册响应解密（aston314 `passportTTDecrypt`）。 */
    private fun ttDecrypt(raw: ByteArray): ByteArray {
        require(raw.size >= 118 && (raw.size - 38) % 16 == 0) { "设备注册加密响应无效" }
        val derived = digest("SHA-512", digest("SHA-512", raw.copyOfRange(6, 38)) + KDF_SALT)
        val p = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(derived.copyOfRange(0, 16), "AES"), javax.crypto.spec.IvParameterSpec(derived.copyOfRange(16, 32)))
            doFinal(raw.copyOfRange(38, raw.size))
        }
        val padding = p.last().u()
        require(padding in 1..16 && p.copyOfRange(p.size - padding, p.size).all { it.u() == padding }) { "设备注册加密响应无效" }
        val content = p.copyOfRange(0, p.size - padding)
        require(content.size >= 64) { "设备注册加密响应无效" }
        require(digest("SHA-512", content.copyOfRange(64, content.size)).contentEquals(content.copyOfRange(0, 64))) { "设备注册响应校验失败" }
        val gz = java.util.zip.GZIPInputStream(content.copyOfRange(64, content.size).inputStream()).readBytes()
        return gz
    }

    /** 发送短信验证码。返回重发冷却秒数。 */
    fun sendCode(phone: String): Int {
        val form = linkedMapOf(
            "mobile" to xorWire(wirePhone(phone)),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            "mix_mode" to "1",
            "auto_read" to "0",
            "unbind_exist" to "34",
            "type" to xorWire("24"),
        )
        val reply = post("/passport/mobile/send_code/v1/", form)
        val data = reply.optJSONObject("data") ?: org.json.JSONObject()
        if (data.has("verify_center_decision_conf") || data.optString("captcha").isNotBlank() || data.optInt("error_code", 0) == 1105) {
            error("平台要求滑块等额外验证，电视上无法完成；请改用扫码登录")
        }
        check(reply.optString("message") == "success") { failureMessage(reply) }
        ticket = data.optString("mobile_ticket")
        session.mobileTicket = ticket
        lastReply = reply
        return data.optInt("retry_time", 60).coerceIn(1, 3600)
    }

    private var ticket = ""

    /** 最近一次 passport 响应原文（诊断用）。 */
    var lastReply: org.json.JSONObject? = null
        private set

    /**
     * 验证码登录。成功返回带登录态的会话快照。
     * [ticketMode] 控制票据编码：`none`（实测有效，平台只认 code）/`xor`（参考实现，实测 1066）/
     * `raw`/`noprefix`。带上 sms_code_key 会让服务端判定「流程已过期」，故默认不带。
     */
    fun smsLogin(phone: String, code: String, ticketMode: String = "none"): PassportSession {
        if (ticketMode != "none" && ticket.isBlank()) ticket = session.mobileTicket
        if (ticketMode != "none") require(ticket.isNotBlank()) { "请先获取验证码" }
        val form = linkedMapOf(
            "mobile" to xorWire(wirePhone(phone)),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            "mix_mode" to "1",
            "code" to xorWire(code),
        )
        if (ticketMode != "none") {
            form["passport_mfa_retry_tag"] = "1"
            form["sms_code_key"] = when (ticketMode) {
                "raw" -> ticket
                "noprefix" -> xorWire(ticket.removePrefix("mobile_ticket_"))
                else -> xorWire(ticket)
            }
        }
        val reply = post("/passport/mobile/sms_login/", form)
        if (!cookies.containsKey("sessionid") && !cookies.containsKey("sessionid_ss")) {
            val data = reply.optJSONObject("data") ?: org.json.JSONObject()
            if (data.optString("sms_code_key").isNotBlank() || data.optString("passport_scene").isNotBlank()) {
                error("平台要求短信上行验证（MFA），电视上无法完成；请改用扫码登录")
            }
            error(failureMessage(reply))
        }
        ticket = ""
        return snapshot()
    }

    /** 携会话发签名 GET（plan/v1 等登录态接口）。[host] 缺省为 passport 域，内容接口传 sinfonline 域。 */
    fun signedGet(path: String, extraQuery: Map<String, String> = emptyMap(), host: String = API_BASE): org.json.JSONObject =
        signedRequest("GET", path, extraQuery, emptyMap(), host)

    /**
     * 通用签名请求：query 参与 F13/Gorgon/Medusa 的 query 段，form 编码为 body 参与其 body 段。
     * video_detail 这类老播放接口疑似只吃 POST，用 [form] 传业务参数。
     */
    fun signedRequest(
        method: String,
        path: String,
        extraQuery: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        host: String = API_BASE,
    ): org.json.JSONObject {
        val stamp = System.currentTimeMillis()
        val body = if (form.isEmpty()) ByteArray(0) else Signer.encode(form).toByteArray(Charsets.UTF_8)
        val base = queryBase(includeDevice = true) + extraQuery
        val (query, signedHeaders) = PassportSign.sign(base, body, stamp, session.deviceId)
        val builder = okhttp3.Request.Builder().url("$host$path?$query")
        if (body.isEmpty()) builder.get() else builder.post(okhttp3.RequestBody.create(null, body))
        val request = builder
            .apply {
                if (body.isNotEmpty()) header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                header("User-Agent", UA)
                header("Accept", "application/json")
                header("X-XS-From-Web", "0")
                header("Sdk-Version", "2")
                header("Passport-Sdk-Version", "6031190")
                header("X-SS-DP", "8662")
                header("X-Tt-Store-Region", "cn")
                header("X-Tt-Store-Region-Src", "did")
                cookies["store-region"]?.let { header("X-Tt-Store-Region", it) }
                if (session.loggedIn) header("X-Tt-Token", session.token)
                for ((name, value) in signedHeaders) header(name, value)
                val cookieHeader = cookies.entries.filter { it.key != "install_id" }.joinToString("; ") { "${it.key}=${it.value}" }
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            .build()
        return http.newCall(request).execute().use { resp ->
            resp.headers("Set-Cookie").forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            resp.header("X-Tt-Token")?.takeIf { it.isNotBlank() }?.let { session.token = it }
            val text = resp.body?.string().orEmpty()
            check(text.isNotBlank()) { "空响应（HTTP ${resp.code}）" }
            org.json.JSONObject(text)
        }
    }

    /**
     * 取流专用：JSON body（默认 gzip 压缩），对齐 openhongguo `PlaybackV5Body`。
     * 服务端对播放接口的 body 形态卡得很死——form 编码一律 200+0。
     */
    fun signedJson(
        path: String,
        json: String,
        extraQuery: Map<String, String> = emptyMap(),
        host: String = CONTENT_HOST,
        compress: Boolean = true,
        ua: String = PLAYBACK_UA,
    ): org.json.JSONObject {
        val stamp = System.currentTimeMillis()
        val plain = json.toByteArray(Charsets.UTF_8)
        val body = if (!compress) plain else java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(plain) }
        }.toByteArray()
        val (query, signedHeaders) = PassportSign.sign(queryBase(includeDevice = true) + extraQuery, body, stamp, session.deviceId)
        val request = okhttp3.Request.Builder()
            .url("$host$path?$query")
            .post(okhttp3.RequestBody.create(null, body))
            .apply {
                header("User-Agent", ua)
                header("Content-Type", "application/json; charset=utf-8")
                if (compress) header("Content-Encoding", "gzip")
                header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                header("x-xs-from-web", "0")
                header("X-SS-DP", "8662")
                header("X-Tt-Store-Region", "cn")
                header("X-Tt-Store-Region-Src", "did")
                if (session.loggedIn) header("X-Tt-Token", session.token)
                for ((name, value) in signedHeaders) header(name, value)
                val cookieHeader = cookies.entries.filter { it.key != "install_id" }.joinToString("; ") { "${it.key}=${it.value}" }
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            .build()
        return http.newCall(request).execute().use { resp ->
            resp.headers("Set-Cookie").forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            resp.header("X-Tt-Token")?.takeIf { it.isNotBlank() }?.let { session.token = it }
            val text = resp.body?.string().orEmpty()
            check(text.isNotBlank()) { "空响应（HTTP ${resp.code}）" }
            org.json.JSONObject(text)
        }
    }

    /** plan/v1 原始响应（诊断用）。 */
    fun planRaw(bookId: String, from: String, filterIds: String? = null, host: String = CONTENT_HOST): org.json.JSONObject {
        val extra = linkedMapOf(
            "book_id" to bookId,
            "from" to from,
            "scene" to "10",
            "user_is_login" to if (session.loggedIn) "1" else "0",
            "session_uuid" to java.util.UUID.randomUUID().toString().replace("-", ""),
        )
        filterIds?.let { extra["filter_ids"] = it }
        return signedGet("/reading/bookapi/plan/v1/", extra, host)
    }

    /** plan/v1 全量 cell（登录态验证用）。 */
    fun planCells(bookId: String, from: String, filterIds: String? = null, host: String = CONTENT_HOST): org.json.JSONArray {
        val data = planRaw(bookId, from, filterIds, host)
        if (data.optInt("code", -1) != 0) return org.json.JSONArray()
        return data.optJSONArray("data") ?: data.optJSONObject("data")?.optJSONArray("data") ?: org.json.JSONArray()
    }

    /** 会话 JSON（探针落盘 / App 持久化共用）。 */
    fun toJson(session: PassportSession = snapshot()): String = org.json.JSONObject()
        .put("device_id", session.deviceId)
        .put("install_id", session.installId)
        .put("cdid", session.cdid)
        .put("openudid", session.openudid)
        .put("clientudid", session.clientUdid)
        .put("token", session.token)
        .put("mobile_ticket", session.mobileTicket)
        .put("cookies", org.json.JSONObject(session.cookies as Map<*, *>))
        .toString()

    private fun post(path: String, form: Map<String, String>): org.json.JSONObject {
        val stamp = System.currentTimeMillis()
        val body = Signer.encode(form).toByteArray(Charsets.UTF_8)
        val (query, signedHeaders) = PassportSign.sign(queryBase(includeDevice = true), body, stamp, session.deviceId)
        val request = okhttp3.Request.Builder()
            .url("$API_BASE$path?$query")
            .post(okhttp3.RequestBody.create(null, body))
            .apply {
                header("User-Agent", UA)
                header("Accept", "application/json")
                header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                header("X-XS-From-Web", "0")
                header("Sdk-Version", "2")
                header("Passport-Sdk-Version", "5051452")
                header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
                header("Lc", "101")
                header("X-SS-DP", "8662")
                header("X-Tt-Store-Region", "cn")
                header("X-Tt-Store-Region-Src", "did")
                for ((name, value) in signedHeaders) header(name, value)
            }
            .build()
        return http.newCall(request).execute().use { resp ->
            resp.headers("Set-Cookie").forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            resp.header("X-Tt-Token")?.takeIf { it.isNotBlank() }?.let { session.token = it }
            val text = resp.body?.string().orEmpty()
            check(text.isNotBlank()) { "passport 空响应（HTTP ${resp.code}）" }
            return org.json.JSONObject(text).also { lastReply = it }
        }
    }

    private fun failureMessage(reply: org.json.JSONObject): String {
        val data = reply.optJSONObject("data") ?: org.json.JSONObject()
        val code = data.optString("error_code", "").ifBlank { reply.optString("error_code", "未知") }
        val description = data.optString("description", "").ifBlank { reply.optString("description", "") }
            .replace(Regex("[0-9]{7,}"), "***").take(120)
        return if (description.isBlank()) "passport 请求失败（$code）" else "$description（$code）"
    }
}
