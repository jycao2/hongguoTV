// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStatsReporterTest {
    private class FakeTransport(private val code: Int = 200) : StatsTransport {
        val urls = mutableListOf<String>()
        var throwNext = false
        override fun get(url: String): Int {
            if (throwNext) throw java.io.IOException("offline")
            urls += url
            return code
        }
    }

    @Test
    fun installIdIs32LowerHexAndUnique() {
        val a = InstallStats.newInstallId()
        val b = InstallStats.newInstallId()
        assertTrue(a, a.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(b, b.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals(a, b)
    }

    @Test
    fun validPingBuildsExpectedUrlAndSucceeds() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertTrue(reporter.ping("abcdef0123456789", "0.18.0", 10126))
        assertEquals(1, transport.urls.size)
        val url = transport.urls.single()
        assertTrue(url, url.startsWith("https://stats.example.com/ping?"))
        assertTrue(url, url.contains("u=abcdef0123456789"))
        assertTrue(url, url.contains("v=0.18.0"))
        assertTrue(url, url.contains("c=10126"))
    }

    @Test
    fun status204CountsAsSuccess() {
        val transport = FakeTransport(204)
        assertTrue(InstallStatsReporter("https://stats.example.com", transport).ping("abcdef0123", "1.0", 1))
    }

    @Test
    fun deviceKeyIsStableAcrossInstallsAndDiffersByDevice() {
        val key = InstallStats.deviceKey("9774d56d682e549c")
        assertTrue(key, key.matches(Regex("^[0-9a-f]{16}$")))
        // 同一个 ANDROID_ID 必须得到同一个键：重装换 install_id 后仍能认出同一台设备
        assertEquals(key, InstallStats.deviceKey("9774d56d682e549c"))
        assertEquals(key, InstallStats.deviceKey("  9774d56d682e549c  "))
        assertNotEquals(key, InstallStats.deviceKey("0000000000000000"))
    }

    @Test
    fun deviceKeyToleratesMissingAndroidId() {
        // 读不到 ANDROID_ID 时传空串，上报侧不下发该参数，不得因此拒发
        assertEquals("", InstallStats.deviceKey(null))
        assertEquals("", InstallStats.deviceKey(""))
        assertEquals("", InstallStats.deviceKey("   "))
    }

    @Test
    fun pingCarriesDeviceKeyAndSdk() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertTrue(reporter.ping("abcdef0123456789", "0.30.11+58", 10458, "0123456789abcdef", 28))
        val url = transport.urls.single()
        assertTrue(url, url.contains("a=0123456789abcdef"))
        assertTrue(url, url.contains("s=28"))
    }

    @Test
    fun pingWithoutOptionalFieldsKeepsOldShape() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertTrue(reporter.ping("abcdef0123456789", "0.28.0+50", 10250))
        val url = transport.urls.single()
        assertFalse(url, url.contains("a="))
        assertFalse(url, url.contains("s="))
    }

    @Test
    fun pingRejectsMalformedDeviceKey() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertFalse(reporter.ping("abcdef0123456789", "1.0", 1, "NOTHEX", 28))
        assertFalse(reporter.ping("abcdef0123456789", "1.0", 1, "abc", 28))
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun pingRejectsOutOfRangeSdk() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertFalse(reporter.ping("abcdef0123456789", "1.0", 1, "", 7))
        assertFalse(reporter.ping("abcdef0123456789", "1.0", 1, "", 101))
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun serverErrorReturnsFalse() {
        val transport = FakeTransport(500)
        assertFalse(InstallStatsReporter("https://stats.example.com", transport).ping("abcdef0123", "1.0", 1))
        assertEquals(1, transport.urls.size)
    }

    @Test
    fun invalidIdSkipsRequest() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertFalse(reporter.ping("bad id!", "1.0", 1))
        assertFalse(reporter.ping("short", "1.0", 1))
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun invalidVersionSkipsRequest() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertFalse(reporter.ping("abcdef0123", "1 0", 1))
        assertFalse(reporter.ping("abcdef0123", "", 1))
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun negativeVersionCodeSkipsRequest() {
        val transport = FakeTransport(200)
        assertFalse(InstallStatsReporter("https://stats.example.com", transport).ping("abcdef0123", "1.0", -7))
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun transportExceptionReturnsFalse() {
        val transport = FakeTransport(200).apply { throwNext = true }
        assertFalse(InstallStatsReporter("https://stats.example.com", transport).ping("abcdef0123", "1.0", 1))
    }

    @Test
    fun malformedEndpointReturnsFalse() {
        assertFalse(InstallStatsReporter("not a url", FakeTransport(200)).ping("abcdef0123", "1.0", 1))
    }

    @Test
    fun updateFailedBuildsExpectedUrl() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertTrue(reporter.updateFailed("abcdef0123456789", "0.28.0+50", "-", "parse"))
        val url = transport.urls.single()
        assertTrue(url, url.startsWith("https://stats.example.com/update_fail?"))
        assertTrue(url, url.contains("f=0.28.0%2B50") || url.contains("f=0.28.0+50"))
        assertTrue(url, url.contains("t=-"))
        assertTrue(url, url.contains("s=parse"))
    }

    @Test
    fun updateFailedRejectsUnknownStageAndBadVersion() {
        val transport = FakeTransport(200)
        val reporter = InstallStatsReporter("https://stats.example.com", transport)
        assertFalse(reporter.updateFailed("abcdef0123", "1.0", "2.0", "boom stage"))
        assertFalse(reporter.updateFailed("abcdef0123", "1 0", "2.0", "parse"))
        assertFalse(reporter.updateFailed("bad id!", "1.0", "2.0", "parse"))
        assertTrue(transport.urls.isEmpty())
    }
}
