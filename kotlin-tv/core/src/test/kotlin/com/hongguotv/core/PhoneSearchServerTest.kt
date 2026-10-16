package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSearchServerTest {
    private fun request(server: PhoneSearchServer,method: String="GET",path: String?=null,body: String="",headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n"+headers+if(method=="POST") "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n" else "\r\n").toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `phone form accepts one unicode search without reflecting it in HTML`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it;received.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.contains("form-action 'self'"))
            val value="测试 <script>alert(1)</script>"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode(value,"UTF-8"))
            assertTrue(reply.startsWith("HTTP/1.1 200")); assertFalse(reply.contains(value))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(value,query)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `wrong token host and cross site posts cannot submit`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            assertTrue(request(server,path="/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=test","Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `in-app webview origin variants can still submit`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it;received.countDown() }).use { server ->
            // A port-less origin must pass the origin gate and reach body validation (400, not 403).
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=","Origin: http://127.0.0.1\r\n").startsWith("HTTP/1.1 400"))
            // In-app WebViews and privacy tools often post forms with Origin: null.
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode("测试","UTF-8"),"Origin: null\r\n")
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals("测试",query)
        }
    }
    @Test fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            for(body in listOf("query=","query="+"x".repeat(81),"query=a&query=b","query=hello%0Aworld","query=%zz")) {
                val response=request(server,"POST",URI(server.url).path+"/submit",body)
                assertTrue(response,response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query="+"x".repeat(4096)).startsWith("HTTP/1.1 413"))
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `closing or expiry makes a session unreachable`() {
        val closed=PhoneSearchServer("127.0.0.1",{}); closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ fail("expired session submitted") },{ expired.countDown() },1).use { server ->
            assertTrue(expired.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
}
