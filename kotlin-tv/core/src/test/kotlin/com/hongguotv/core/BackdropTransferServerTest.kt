package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackdropTransferServerTest {
    private val png=byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A)+ByteArray(64)
    private val jpg=byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte(),0xE0.toByte())+ByteArray(64)
    private fun request(server: BackdropTransferServer,path: String="",body: ByteArray?=null,headers: String="",host: String?=null,contentType: String="image/png"): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val head="${if(body==null) "GET" else "POST"} ${uri.path}$path HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n$headers"+(if(body==null) "\r\n" else "Content-Type: $contentType\r\nContent-Length: ${body.size}\r\n\r\n")
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII)+(body ?: byteArrayOf()))
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `valid upload is delivered once with its extension then the listener closes`() {
        val latch=CountDownLatch(1); var received: ByteArray?=null; var extension=""
        BackdropTransferServer("127.0.0.1",{ bytes,ext -> received=bytes; extension=ext; latch.countDown() }).use { server ->
            assertTrue(request(server).contains("frame-ancestors 'none'"))
            assertTrue(request(server,"/upload",png).startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2,TimeUnit.SECONDS)); assertArrayEquals(png,received); assertEquals("png",extension)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `jpeg and webp magic are recognized while random bytes are rejected`() {
        assertEquals("jpg",BackdropTransferServer.imageType(jpg))
        assertEquals("png",BackdropTransferServer.imageType(png))
        assertEquals("webp",BackdropTransferServer.imageType("RIFF....WEBP".toByteArray()+ByteArray(8)))
        assertNull(BackdropTransferServer.imageType(ByteArray(64)))
        assertNull(BackdropTransferServer.imageType(byteArrayOf(1,2)))
    }
    @Test fun `wrong type foreign origin duplicate headers and bad magic do not consume the session`() {
        val latch=CountDownLatch(1); var uploaded=false
        BackdropTransferServer("127.0.0.1",{ _,_ -> uploaded=true; latch.countDown() }).use { server ->
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"/upload",png,"Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server,"/upload",png,"Sec-Fetch-Site: cross-site\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server,"/upload",png,"Host: other\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server,"/upload",png,contentType="text/plain").startsWith("HTTP/1.1 400"))
            assertTrue(request(server,"/upload",ByteArray(64)).startsWith("HTTP/1.1 400"))
            assertFalse(uploaded)
            assertTrue(request(server,"/upload",jpg).startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2,TimeUnit.SECONDS)); assertTrue(uploaded)
        }
    }
    @Test fun `oversized uploads are rejected and expiry closes idle sessions`() {
        BackdropTransferServer("127.0.0.1",{ _,_ -> fail("must not upload") }).use { server ->
            val uri=URI(server.url)
            Socket("127.0.0.1",uri.port).use { s ->
                s.soTimeout=2000; s.getOutputStream().write("POST ${uri.path}/upload HTTP/1.1\r\nHost: ${uri.authority}\r\nContent-Type: image/png\r\nContent-Length: ${BackdropTransferServer.MAX_BYTES+1}\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
        }
        val expired=CountDownLatch(1)
        BackdropTransferServer("127.0.0.1",{ _,_ -> },{ expired.countDown() },50).use { expiring ->
            assertTrue(expired.await(2,TimeUnit.SECONDS)); assertTrue(runCatching { request(expiring) }.isFailure)
        }
    }
}
