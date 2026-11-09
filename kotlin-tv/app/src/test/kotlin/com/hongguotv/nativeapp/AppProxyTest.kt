// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ProxySelector

class AppProxyTest {
    @Test
    fun configValidChecksHostAndPort() {
        assertTrue(AppProxy.Config(true, "192.168.31.23", 7890).valid)
        assertFalse("空地址".let { AppProxy.Config(true, "", 7890).valid })
        assertFalse("端口 0".let { AppProxy.Config(true, "1.2.3.4", 0).valid })
        assertFalse("端口越界".let { AppProxy.Config(true, "1.2.3.4", 65536).valid })
    }

    @Test
    fun configDisplayMatchesState() {
        assertEquals("关闭", AppProxy.Config(false, "1.2.3.4", 7890).display)
        assertEquals("未配置完整", AppProxy.Config(true, "", 7890).display)
        assertEquals("1.2.3.4:7890", AppProxy.Config(true, "1.2.3.4", 7890).display)
    }

    @Test
    fun lanHostBypassCoversPrivateRanges() {
        assertTrue(AppProxy.isLanHost(null))
        assertTrue(AppProxy.isLanHost(""))
        assertTrue(AppProxy.isLanHost("localhost"))
        assertTrue(AppProxy.isLanHost("127.0.0.1"))
        assertTrue(AppProxy.isLanHost("192.168.31.38"))
        assertTrue(AppProxy.isLanHost("10.0.0.2"))
        assertTrue(AppProxy.isLanHost("172.16.0.1"))
        assertTrue(AppProxy.isLanHost("172.31.255.255"))
        assertTrue(AppProxy.isLanHost("169.254.1.1"))
        assertFalse("边界外不算私网".let { AppProxy.isLanHost("172.32.0.1") })
        assertFalse(AppProxy.isLanHost("8.8.8.8"))
        assertFalse("公网域名交给代理".let { AppProxy.isLanHost("xingba111.com") })
        assertFalse("畸形输入不算私网".let { AppProxy.isLanHost("192.168.1") })
    }

    @Test
    fun selectorFallsBackWhenDisabled() {
        // 未启用代理（JVM 默认 Config enabled=false）时 select 不得返回空列表：
        // 空列表会让 HttpURLConnection 抛 "Can't connect to proxy" 之类的异常
        val routes = AppProxy.Switch.select(java.net.URI("http://example.com/"))
        assertFalse(routes.isEmpty())
        val lanRoutes = AppProxy.Switch.select(java.net.URI("http://192.168.31.38:37109/"))
        assertFalse(lanRoutes.isEmpty())
    }
}
