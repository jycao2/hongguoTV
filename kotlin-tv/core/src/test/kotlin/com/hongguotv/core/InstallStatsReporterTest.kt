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
}
