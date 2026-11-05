// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class NasWebDavTest {

    private val server = MockWebServer()

    private fun dav(path: String = "/dav") =
        NasWebDav(server.url(path).toString(), "u1", "p2", OkHttpClient())

    @After
    fun tearDown() = server.shutdown()

    private fun multistatus(vararg entries: String): String {
        val body = entries.joinToString("")
        return """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">$body</D:multistatus>"""
    }

    private fun dir(href: String) =
        """<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>"""

    private fun file(href: String, size: Long = 1024) =
        """<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>$size</D:getcontentlength></D:prop></D:propstat></D:response>"""

    private fun ok(body: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body)

    // ---------- 连接与认证 ----------

    @Test
    fun `连接测试发送认证头与Depth0`() {
        server.enqueue(ok(multistatus(dir("/dav/"))))
        dav().testConnection()
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("0", req.getHeader("Depth"))
        val expect = "Basic " + Base64.getEncoder().encodeToString("u1:p2".toByteArray())
        assertEquals(expect, req.getHeader("Authorization"))
    }

    @Test
    fun `认证失败给出友好提示`() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            dav().testConnection()
            fail("应当抛出 NasException")
        } catch (e: NasWebDav.NasException) {
            assertTrue(e.message!!.contains("账号或密码错误"))
        }
    }

    @Test
    fun `目录不存在给出友好提示`() {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            dav().testConnection()
            fail("应当抛出 NasException")
        } catch (e: NasWebDav.NasException) {
            assertTrue(e.message!!.contains("目录不存在"))
        }
    }

    // ---------- 目录列举 ----------

    @Test
    fun `递归列出并剥离合集自身`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/dav/" -> ok(multistatus(
                    dir("/dav/"),
                    dir("/dav/%E9%83%BD%E5%B8%82%E4%B9%8B%E6%9C%80%E5%BC%BA%E5%BE%8B%E5%B8%88/"),   // 都市之最强律师
                    file("/dav/%E6%B7%B1%E6%B5%B7%E7%BA%AA%E5%85%83.mkv", 4096),                  // 深海纪元.mkv
                ))
                "/dav/%E9%83%BD%E5%B8%82%E4%B9%8B%E6%9C%80%E5%BC%BA%E5%BE%8B%E5%B8%88/" -> ok(multistatus(
                    dir("/dav/%E9%83%BD%E5%B8%82%E4%B9%8B%E6%9C%80%E5%BC%BA%E5%BE%8B%E5%B8%88/"),
                    file("/dav/%E9%83%BD%E5%B8%82%E4%B9%8B%E6%9C%80%E5%BC%BA%E5%BE%8B%E5%B8%88/%E7%AC%AC01%E9%9B%86.mkv"), // 第01集.mkv
                    file("/dav/%E9%83%BD%E5%B8%82%E4%B9%8B%E6%9C%80%E5%BC%BA%E5%BE%8B%E5%B8%88/%E7%AC%AC02%E9%9B%86.mkv"), // 第02集.mkv
                ))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val entries = dav().listAll()
        assertEquals(
            setOf("都市之最强律师", "都市之最强律师/第01集.mkv", "都市之最强律师/第02集.mkv", "深海纪元.mkv"),
            entries.map { it.path }.toSet(),
        )
        assertEquals(4096, entries.first { it.path == "深海纪元.mkv" }.size)
        assertTrue(entries.first { it.path == "都市之最强律师" }.isDir)
    }

    @Test
    fun `href为绝对URL时也能解析`() {
        server.enqueue(ok(multistatus(
            dir("/dav/"),
            file("http://whatever.internal/dav/a%20b.mkv"),
        )))
        val entries = dav().listAll(maxDepth = 1)
        assertEquals(listOf("a b.mkv"), entries.map { it.path })
    }

    @Test
    fun `不属于自己的href被忽略`() {
        server.enqueue(ok(multistatus(
            dir("/dav/"),
            file("/other/share/x.mkv"),
        )))
        val entries = dav().listAll(maxDepth = 1)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `无命名空间前缀的响应也能解析`() {
        val xml = """<?xml version="1.0"?><multistatus xmlns="DAV:">
            <response><href>/dav/</href><propstat><prop><resourcetype><collection/></resourcetype></prop></propstat></response>
            <response><href>/dav/m.mkv</href><propstat><prop><resourcetype/><getcontentlength>7</getcontentlength></prop></propstat></response>
            </multistatus>"""
        server.enqueue(ok(xml))
        val entries = dav().listAll(maxDepth = 1)
        assertEquals(listOf("m.mkv"), entries.map { it.path })
        assertEquals(7, entries[0].size)
    }

    // ---------- 播放地址 ----------

    @Test
    fun `播放地址对中文与空格编码`() {
        val d = dav()
        assertEquals(
            server.url("/dav/%E9%83%BD%E5%B8%82/%E7%AC%AC01%20%E9%9B%86.mkv").toString(),
            d.fileUrl("都市/第01 集.mkv"),
        )
    }

    @Test
    fun `地址格式错误直接抛异常`() {
        try {
            NasWebDav("192.168.1.20:5005", "u", "p")
            fail("应当抛出 NasException")
        } catch (e: NasWebDav.NasException) {
            assertTrue(e.message!!.contains("地址格式"))
        }
    }
}
