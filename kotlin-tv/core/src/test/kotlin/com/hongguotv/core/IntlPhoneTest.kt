// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 境外号 mode4 签名与号码序列化。
 *
 * 黄金向量来自 aston314/hongguo-tv 对红果官方 v1.2.4 客户端的静态逆向
 * （testdata/passport-international-v124.json），已用独立 Python RC4 复现核对。
 */
class IntlPhoneTest {
    @Test
    fun wirePhoneMatchesOfficialGrouping() {
        // 14 地区线格式，逐字对齐官方 v1.2.4
        val cases =
            mapOf(
                "13800138000" to "13800138000", // 大陆原样（大陆路径）
                "+852 61234567" to "+8526123 4567",
                "+853 66123456" to "+8536612 3456",
                "+886 912345678" to "+886912 3456 78",
                "+60 123456789" to "+60123 4567 89",
                "+65 81234567" to "+658123 4567",
                "+62 8123456789012" to "+62812 345 678 901 2",
                "+95 912345678" to "+95912 3456 78",
                "+63 9123456789" to "+63912 3456 789",
                "+84 912345678" to "+84912 3456 78",
                "+855 12345678" to "+855123 456 78",
                "+81 09012345678" to "+81090 1234 5678",
                "+82 01012345678" to "+82010 1234 5678",
                "+971 501234567" to "+971501 234 567",
            )
        for ((input, expected) in cases) assertEquals(input, expected, IntlPhone.wire(input))
    }

    @Test
    fun xorWireUsesOfficialXor5Hex() {
        // 官方向量 body 里 mobile 段：+8526123 4567 → 2e3d3037333437362531303332
        assertEquals("2e3d3037333437362531303332", IntlPhone.xorWire("+8526123 4567"))
    }

    @Test
    fun mode4GorgonMatchesGoldenVector() {
        val query = "aid=8662&_rticket=1700000000123"
        val body = "mobile=2e3d3037333437362531303332&mix_mode=1".toByteArray()
        val ts = 1700000000L
        val nonce = 17767
        val expected = "84046745400107befaaa090287763014e58993aa0d31128cabba"
        assertEquals(expected, IntlPhone.gorgon(query, body, ts, nonce))
    }

    @Test
    fun mode4ArgusLadonAreByteOrderHeaders() {
        val ts = 1700000000
        assertEquals("APFTZQ==", le32(ts).base64())
        assertEquals("ZVPxAA==", be32(ts).base64())
    }

    @Test
    fun mode4SignatureHasNoMedusa() {
        val queryBase =
            linkedMapOf(
                "ac" to "wifi", "aid" to "8662", "app_name" to "novelread",
                "device_platform" to "android", "version_code" to "73932",
            )
        val body = "mobile=${IntlPhone.xorWire("+886912 3456 78")}&mix_mode=1".toByteArray()
        val (query, headers) = IntlPhone.signInternational(queryBase, body, 1_700_000_000_123L)
        // branch-2 调参命中，_rticket 在 query 里
        assertTrue(query.contains("_rticket=1700000000"))
        assertEquals(query.substringAfter("_rticket=").substringBefore('&'), headers["X-SS-Req-Ticket"])
        // mode4 无 Medusa，标准头齐全
        assertTrue(!headers.containsKey("X-Medusa"))
        for (key in listOf("X-Khronos", "X-Argus", "X-Ladon", "X-Helios", "X-Gorgon", "X-TT-Trace-Id", "X-SS-STUB")) {
            assertTrue("缺少 $key", headers.containsKey(key))
        }
        assertTrue(headers.getValue("X-TT-Trace-Id").startsWith("00-"))
    }

    @Test
    fun mainlandParsingAndSigningPathUntouched() {
        // 大陆号解析仍返回纯数字，不含 + 前缀，因此 PhoneLogin.call() 不会分流到 mode4
        assertEquals("13800138000", PhoneLogin.parse("13800138000"))
        assertEquals("13800138000", PhoneLogin.parse(" 138-0013-8000 "))
        assertTrue(!PhoneLogin.parse("13800138000").startsWith("+"))
    }

    @Test
    fun japanKoreaKeepLeadingZeroThroughParseAndWire() {
        // 端到端：UI 传 `+81 09012345678` → parse 保留前导 0 → wire 分组对齐官方向量
        assertEquals("+81 09012345678", PhoneLogin.parse("+81 09012345678"))
        assertEquals("+81090 1234 5678", IntlPhone.wire(PhoneLogin.parse("+81 09012345678")))
        assertEquals("+82 01012345678", PhoneLogin.parse("+82 01012345678"))
        assertEquals("+82010 1234 5678", IntlPhone.wire(PhoneLogin.parse("+82 01012345678")))
    }

    @Test
    fun countryNamesLocalized() {
        // 区号展示走 L10n.tr(c.name)：英文/繁中模式不再露出简体中文地区名
        try {
            val cn = IntlPhone.COUNTRIES.first { it.code == "+86" }
            val hk = IntlPhone.COUNTRIES.first { it.code == "+852" }
            L10n.configure(L10n.EN)
            assertEquals("Mainland China", L10n.tr(cn.name))
            assertEquals("Hong Kong, China", L10n.tr(hk.name))
            L10n.configure(L10n.TW)
            assertEquals("中國大陸", L10n.tr(cn.name))
            assertEquals("中國香港", L10n.tr(hk.name))
            L10n.configure(L10n.ZH)
            assertEquals("中国大陆", L10n.tr(cn.name))
        } finally {
            L10n.configure(L10n.FOLLOW_SYSTEM)
        }
    }
}
