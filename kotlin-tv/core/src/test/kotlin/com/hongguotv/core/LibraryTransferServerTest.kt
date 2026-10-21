package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LibraryTransferServerTest {
    private val data=BackupData(listOf(Series("1","测试")),emptyList(),emptySet(),emptyList(),BackupSettings())
    private val backup=LibraryBackup.encode(data)
    private fun request(server: LibraryTransferServer,path: String="",body: String?=null,headers: String="",host: String?=null,method: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            val head="${method ?: if(body==null) "GET" else "POST"} ${uri.path}$path HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n$headers"+(if(body==null) "\r\n" else "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n")
            socket.getOutputStream().write(head.toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `download is one use and validated import is delivered once then listener closes`() {
        val latch=CountDownLatch(1); var received: BackupData?=null
        LibraryTransferServer("127.0.0.1",backup,{ received=it; latch.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.contains("frame-ancestors 'none'"))
            // 页面内联备份，手机本地生成文件，避免系统下载器二次请求电视
            assertTrue(page.contains("textarea id=\"data\" readonly"))
            assertTrue(page.contains("getElementById('save').onclick"))
            assertTrue(page.contains("hongguotv-library-"))
            assertTrue(request(server,"/download",method="HEAD").startsWith("HTTP/1.1 200"))
            val download=request(server,"/download")
            assertTrue(download.contains("Content-Disposition: attachment")); assertEquals(backup,download.substringAfter("\r\n\r\n"))
            assertTrue(request(server,"/download").startsWith("HTTP/1.1 410"))
            assertTrue(request(server,"/import",backup).startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2,TimeUnit.SECONDS)); assertEquals(data,received)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `invalid input cross origin host duplicate headers and oversized bodies do not consume session`() {
        var imported=false
        LibraryTransferServer("127.0.0.1",backup,{ imported=true }).use { server ->
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"/import",backup,"Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server,"/download",headers="Sec-Fetch-Site: cross-site\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server,"/import",backup,"Host: other\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server,"/import","{}").startsWith("HTTP/1.1 400"))
            assertTrue(request(server,"/import",backup,"Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 400"))
            val uri=URI(server.url)
            Socket("127.0.0.1",uri.port).use { s ->
                s.soTimeout=2000; s.getOutputStream().write("POST ${uri.path}/import HTTP/1.1\r\nHost: ${uri.authority}\r\nContent-Type: application/json\r\nContent-Length: ${LibraryBackup.MAX_BYTES+1}\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
            assertFalse(imported); assertTrue(request(server,"/download").startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `close interrupts an active incomplete upload and expiry closes idle sessions`() {
        val server=LibraryTransferServer("127.0.0.1",backup,{ fail("must not import") })
        Socket("127.0.0.1",URI(server.url).port).use { socket ->
            socket.soTimeout=1500; socket.getOutputStream().write("POST /".toByteArray())
            Thread.sleep(80); server.close()
            assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1)==-1)
        }
        val expired=CountDownLatch(1)
        LibraryTransferServer("127.0.0.1",backup,{}, { expired.countDown() },50).use { expiring ->
            assertTrue(expired.await(2,TimeUnit.SECONDS)); assertTrue(runCatching { request(expiring) }.isFailure)
        }
    }
}
