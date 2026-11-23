// SPDX-License-Identifier: GPL-3.0-only
// passport 签名黄金向量校验（aston314/hongguo-tv testdata/passport-vectors.json）。
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PassportSignTest {
    private val vectors: JSONObject =
        JSONObject(javaClass.getResource("/passport-vectors.json")!!.readText())

    private val query: String = vectors.getString("query")
    private val body: ByteArray = vectors.getString("body").toByteArray()
    private val ts: Long = vectors.getLong("ts").let { if (it > 100000000000L) it / 1000 else it }
    private val ivValue: Int = vectors.getInt("iv").toInt()
    private val deviceId = "7000000000000000001"

    @Test
    fun sm3Standard() {
        assertEquals(vectors.getString("sm3"), sm3(query.toByteArray()).hex())
    }

    @Test
    fun branchVector() {
        assertEquals(ivValue, PassportSign.iv(query, body, ts))
    }

    @Test
    fun f13Vector() {
        val q = sm3(query.toByteArray())
        val m = digest("MD5", body)
        assertEquals(vectors.getString("f13"), PassportSign.f13(ivValue, ts, q, m).hex())
    }

    @Test
    fun protoVector() {
        val q = sm3(query.toByteArray())
        val m = digest("MD5", body)
        val f13Bytes = PassportSign.f13(ivValue, ts, q, m)
        // 向量里 nonce 取自 medusa 测试的固定值 0x12345678（passport_sign_test.go:54）。
        assertEquals(
            vectors.getString("proto"),
            PassportSign.proto(query, ts, 0x12345678, deviceId, q, m, f13Bytes).hex(),
        )
    }

    @Test
    fun gorgonVector() {
        // 帧头字节 2-3 是 nonce 的 LE（低字节在前）：从期望输出反推后重算比对。
        val expected = vectors.getString("gorgon")
        val nonce = expected.substring(4, 6).toInt(16) or (expected.substring(6, 8).toInt(16) shl 8)
        assertEquals(expected, PassportSign.gorgon(query, body, ts, nonce))
    }

    @Test
    fun heliosVector() {
        val expected = vectors.getString("helios")
        // 输出前 4 字节 = LE(nonce)，反推后重算比对。
        val decoded = decode64(expected)
        val nonce = decoded.i32(0, true)
        assertEquals(expected, PassportSign.helios(ts, nonce))
    }

    @Test
    fun medusaVector() {
        // 固定随机数取自 passport_sign_test.go:54：nonce=0x12345678, hashRand=0x87654321, xmRand=0x33445566。
        val q = sm3(query.toByteArray())
        val m = digest("MD5", body)
        assertEquals(
            vectors.getString("medusa"),
            PassportSign.medusa(query, ts, 0x12345678, 0x87654321.toInt(), 0x33445566, deviceId, ivValue, q, m),
        )
    }

    @Test
    fun tuneQueryHitsBranchTwo() {
        val query = PassportSign.tuneQuery(mapOf("aid" to "8662", "version_code" to "73532"), body, System.currentTimeMillis())
        assertTrue((PassportSign.iv(query, body, System.currentTimeMillis() / 1000) and 15) % 3 == 2)
    }

    @Test
    fun wirePhoneAndXor() {
        assertEquals("+8613800138000", PassportClient.wirePhone("13800138000"))
        // xor5 自反性：xorWire(x) 解码回来应等于原文。
        val wire = PassportClient.xorWire("24")
        val decoded = hex(wire).map { (it.u() xor 5).toByte() }.toByteArray().decodeToString()
        assertEquals("24", decoded)
    }
}
