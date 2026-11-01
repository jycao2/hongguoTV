// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QuickTransferServerTest {
    private val apkHead = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03.toByte(), 0x04.toByte())
    private val apk = apkHead + ByteArray(60)
    private val plain = ByteArray(64)

    private fun request(
        server: QuickTransferServer,
        path: String = "",
        body: ByteArray? = null,
        headers: String = "",
        host: String? = null,
        length: Long? = null,
        omitLength: Boolean = false,
        contentType: String = "application/octet-stream",
    ): String {
        val uri = URI(server.url)
        val declared = when {
            omitLength -> null
            length != null -> length
            else -> body?.size?.toLong()
        }
        return Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 4000
            val method = if(body == null) "GET" else "POST"
            val head = "$method ${uri.path}$path HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n$headers" +
                (if(body == null) "\r\n" else {
                    val lengthLine = if(declared != null) "Content-Length: $declared\r\n" else ""
                    "Content-Type: $contentType\r\n$lengthLine\r\n"
                })
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII) + (body ?: byteArrayOf()))
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun server(dir: File, free: () -> Long = { Long.MAX_VALUE / 4 }, received: (File) -> Unit = {}): QuickTransferServer =
        QuickTransferServer("127.0.0.1", { dir }, free, onReceived = { file, _ -> received(file) })

    @Test fun `page is served with security headers and injected space`() {
        val dir = Files.createTempDirectory("quick-page").toFile()
        server(dir).use { server ->
            val page = request(server)
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("frame-ancestors 'none'"))
            assertTrue(page.contains("电视可用"))
            assertTrue(page.contains("nonce-${URI(server.url).path.trimStart('/')}"))
            assertFalse(page.contains("__TOKEN__"))
            assertFalse(page.contains("__FREE__"))
            assertFalse(page.contains("__LIMIT__"))
            dir.deleteRecursively()
        }
    }

    @Test fun `apk upload lands with a stamped name and reports progress to the end`() {
        val dir = Files.createTempDirectory("quick-apk").toFile()
        val latch = CountDownLatch(1); var got: File? = null
        val progress = mutableListOf<Pair<Long, Long>>()
        val server = QuickTransferServer("127.0.0.1", { dir }, { Long.MAX_VALUE / 4 },
            onProgress = { _, received, total, _, _ -> progress += received to total },
            onReceived = { file, _ -> got = file; latch.countDown() })
        server.use {
            val response = request(it, "/upload?name=app-release.apk", apk)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val file = requireNotNull(got)
            assertTrue(file.name.endsWith("-app-release.apk"))
            assertArrayEquals(apk, file.readBytes())
            assertEquals(apk.size.toLong() to apk.size.toLong(), progress.last())
            dir.deleteRecursively()
        }
    }

    @Test fun `failed transfers never leave files behind`() {
        val dir = Files.createTempDirectory("quick-fail").toFile()
        server(dir, { 0 }).use { server ->
            // 空间不足：在收字节之前就拒绝
            assertTrue(request(server, "/upload?name=a.apk", apk).startsWith("HTTP/1.1 413"))
            assertEquals(0, dir.listFiles()?.size ?: 0)
        }
        val dir2 = Files.createTempDirectory("quick-bad").toFile()
        server(dir2).use { server ->
            // 扩展名不符的 APK 内容：收完再校验，必须删除临时文件
            assertTrue(request(server, "/upload?name=x.apk", plain).startsWith("HTTP/1.1 400"))
            assertEquals(0, dir2.listFiles()?.size ?: 0)
            dir2.deleteRecursively()
        }
        val dir3 = Files.createTempDirectory("quick-cancel").toFile()
        server(dir3).use { server ->
            // 声明长度但只发一半再断开写端：读取中断，不能留下半成品
            val uri = URI(server.url)
            Socket("127.0.0.1", uri.port).use { socket ->
                socket.soTimeout = 4000
                socket.getOutputStream().write(
                    "POST ${uri.path}/upload?name=x.apk HTTP/1.1\r\nHost: ${uri.authority}\r\nContent-Type: application/octet-stream\r\nContent-Length: ${apk.size}\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII) + apk.copyOf(10)
                )
                socket.shutdownOutput()
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 408"))
            }
            assertEquals(0, dir3.listFiles()?.size ?: 0)
            dir3.deleteRecursively()
        }
        dir.deleteRecursively()
    }

    @Test fun `bad names types and headers are rejected`() {
        val dir = Files.createTempDirectory("quick-reject").toFile()
        val latch = CountDownLatch(1); var uploaded = false
        val server = QuickTransferServer("127.0.0.1", { dir }, { Long.MAX_VALUE / 4 }, onReceived = { _, _ -> uploaded = true; latch.countDown() })
        server.use { server ->
            assertTrue(request(server, host = "evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server, "/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server, "/upload?name=a.apk", apk, "Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "/upload?name=a.apk", apk, "Sec-Fetch-Site: cross-site\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "/upload?name=../../etc/passwd", apk).startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "/upload?name=tool.exe", apk).startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "/upload?name=a.apk", apk, omitLength = true).startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "/upload?name=a.apk", apk, length = TransferRules.MAX_BYTES + 1).startsWith("HTTP/1.1 413"))
            assertFalse(uploaded)
            // 非法请求不消耗会话：随后合法上传仍成功
            assertTrue(request(server, "/upload?name=ok.apk", apk).startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2, TimeUnit.SECONDS)); assertTrue(uploaded)
            dir.deleteRecursively()
        }
    }

    @Test fun `path separators collapse to a base name and repeated uploads are allowed`() {
        val dir = Files.createTempDirectory("quick-multi").toFile()
        val names = mutableListOf<String>()
        val latch = CountDownLatch(2)
        val server = QuickTransferServer("127.0.0.1", { dir }, { Long.MAX_VALUE / 4 },
            onReceived = { file, _ -> names += file.name; latch.countDown() })
        server.use { server ->
            assertTrue(request(server, "/upload?name=sub/one.apk", apk).startsWith("HTTP/1.1 200"))
            assertTrue(request(server, "/upload?name=two.zip", plain).startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertTrue(names.any { it.endsWith("-one.apk") })
            assertTrue(names.any { it.endsWith("-two.zip") })
            assertEquals(2, dir.listFiles()?.count { !it.name.endsWith(TransferRules.PART_SUFFIX) })
            dir.deleteRecursively()
        }
    }

    @Test fun `expiry closes the session and reports once`() {
        val dir = Files.createTempDirectory("quick-expire").toFile()
        val expired = CountDownLatch(1)
        val server = QuickTransferServer("127.0.0.1", { dir }, { Long.MAX_VALUE / 4 }, onExpired = { expired.countDown() }, ttlMillis = 50)
        server.use { server ->
            assertTrue(expired.await(2, TimeUnit.SECONDS))
            assertTrue(runCatching { request(server, "/upload?name=a.apk", apk) }.isFailure)
            dir.deleteRecursively()
        }
    }

    @Test fun `rules keep names safe and reject unsupported extension`() {
        assertEquals("b.apk", TransferRules.sanitize("a/b.apk"))
        assertEquals("b.apk", TransferRules.sanitize("/tmp/b.apk"))
        assertNull(TransferRules.sanitize("../passwd"))
        assertNull(TransferRules.sanitize(""))
        assertNull(TransferRules.sanitize(".apk"))
        assertEquals(TransferKind.APK, TransferRules.kindOf("x.APK"))
        assertEquals(TransferKind.VIDEO, TransferRules.kindOf("show.MKV"))
        assertNull(TransferRules.kindOf("tool.exe"))
        assertEquals("1790000000-a.apk", TransferRules.targetName("a.apk", 1_790_000_000_000L))
        assertFalse(TransferRules.looksLikeApk(ByteArray(4)))
        assertTrue(TransferRules.looksLikeApk(apkHead))
    }
}
