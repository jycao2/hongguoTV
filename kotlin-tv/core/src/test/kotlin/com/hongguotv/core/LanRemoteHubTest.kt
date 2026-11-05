package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanRemoteHubTest {
    private fun hub(
        target: RemoteTarget? = null,
        controller: RemoteController? = null,
        resume: List<ResumeItem> = emptyList(),
        rateLimit: Int = 20,
        idleTimeoutMs: Long = 30 * 60_000,
        onExpired: () -> Unit = {}
    ): LanRemoteHub = LanRemoteHub(
        address = "127.0.0.1",
        post = { it.run() },
        target = { target },
        controller = { controller },
        resumeItems = { resume },
        onExpired = onExpired,
        idleTimeoutMs = idleTimeoutMs,
        cmdRateLimit = rateLimit
    )

    private fun token(hub: LanRemoteHub): String = hub.url.substringAfterLast('/')

    /** [path] 是完整路径，方便测 token 门禁；常规用例传 null 走 `/<token><route>`。 */
    private fun request(
        hub: LanRemoteHub,
        method: String = "GET",
        route: String = "/",
        body: String = "",
        headers: String = "",
        host: String? = null,
        path: String? = null
    ): String {
        val uri = URI(hub.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 4000
            val payload = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append(method).append(' ').append(path ?: "/${token(hub)}$route").append(" HTTP/1.1\r\n")
                append("Host: ").append(host ?: uri.authority).append("\r\n")
                append(headers)
                if(method == "POST") append("Content-Type: application/json\r\nContent-Length: ").append(payload.size).append("\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun code(response: String): Int =
        response.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1

    private class EventStream(socket: Socket) {
        private val reader: BufferedReader = socket.getInputStream().bufferedReader()
        fun await(prefix: String, timeoutMs: Long = 5000): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while(System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                if(line.startsWith(prefix)) return line
            }
            throw AssertionError("没有等到以「$prefix」开头的行")
        }
        fun nextSnapshot(): JSONObject = JSONObject(await("data: ").removePrefix("data: "))
    }

    private fun events(hub: LanRemoteHub, headers: String = "", sid: String? = null): Pair<Socket, EventStream> {
        val uri = URI(hub.url)
        val query = if(sid == null) "" else "?sid=$sid"
        val socket = Socket(uri.host, uri.port).apply { soTimeout = 6000 }
        socket.getOutputStream().apply {
            write(("GET /${token(hub)}/events$query HTTP/1.1\r\nHost: ${uri.authority}\r\n$headers\r\n").toByteArray())
            flush()
        }
        return socket to EventStream(socket)
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while(System.currentTimeMillis() < deadline) {
            if(condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun officialTarget(applied: MutableList<RemoteCommand>? = null) = object : RemoteTarget {
        override val source = PlaybackSource.OFFICIAL
        override fun apply(cmd: RemoteCommand): Boolean { applied?.add(cmd); return true }
        override fun episodes(): List<EpisodeGroupDetail> = emptyList()
    }

    @Test fun `page is served with sse friendly csp`() {
        hub().use { server ->
            val page = request(server)
            assertEquals(200, code(page))
            // EventSource 受 connect-src 约束，漏了会静默失败且没有任何报错
            assertTrue(page.contains("connect-src 'self'"))
            assertTrue(page.contains("script-src 'unsafe-inline'"))
            assertTrue(page.contains("Cache-Control: no-store"))
            assertTrue(page.contains("/events"))
            assertTrue(page.contains("EventSource"))
        }
    }

    @Test fun `wrong host and wrong token are rejected before routing`() {
        hub().use { server ->
            assertEquals(404, code(request(server, host = "evil.example")))
            assertEquals(404, code(request(server, path = "/deadbeef/")))
            assertEquals(404, code(request(server, path = "/")))
        }
    }

    @Test fun `commands reach the target and bad ones are refused`() {
        val applied = CopyOnWriteArrayList<RemoteCommand>()
        hub(officialTarget(applied)).use { server ->
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"seek","to":3000,"seq":1}""")))
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"speed","value":1.5,"seq":2}""")))
            assertEquals(listOf(RemoteCommand.Seek(3000), RemoteCommand.Speed(1.5f)), applied.toList())

            assertEquals(400, code(request(server, "POST", "/cmd", """{"cmd":"seek","to":-1}""")))
            assertEquals(400, code(request(server, "POST", "/cmd", "not json")))
            assertEquals(400, code(request(server, "POST", "/cmd", """{"cmd":"win"}""")))
            assertEquals(2, applied.size)
        }
    }

    @Test fun `stale sequence numbers are dropped as duplicates`() {
        val applied = CopyOnWriteArrayList<RemoteCommand>()
        hub(officialTarget(applied)).use { server ->
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"muted","on":true,"seq":10}""")))
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"muted","on":false,"seq":11}""")))
            // 同一条命令被重复投递（重连、代理重发）时不能执行第二次
            assertEquals(409, code(request(server, "POST", "/cmd", """{"cmd":"muted","on":true,"seq":10}""")))
            assertEquals(2, applied.size)
        }
    }

    @Test fun `navigation commands bypass the target so the home screen still works`() {
        val navigated = CopyOnWriteArrayList<RemoteCommand>()
        val controller = object : RemoteController {
            override fun navigate(cmd: RemoteCommand): Boolean { navigated += cmd; return true }
        }
        // 故意不提供 target：浏览模式下 activeTarget 就是 null，但方向键恰恰只在这时才有意义
        hub(null, controller).use { server ->
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"dpad","key":"down"}""")))
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"back"}""")))
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"menu","long":true}""")))
            assertEquals(listOf(RemoteCommand.Dpad("down", 0), RemoteCommand.Back, RemoteCommand.Menu(true)), navigated.toList())
        }
    }

    @Test fun `only the last idempotent intent survives a disconnected window`() {
        val controller = object : RemoteController {
            override fun navigate(cmd: RemoteCommand) = false
        }
        hub(null, controller).use { server ->
            request(server, "POST", "/cmd", """{"cmd":"seek","to":1000}""")
            request(server, "POST", "/cmd", """{"cmd":"seek","to":2000}""")
            assertEquals(RemoteCommand.Seek(2000), server.takePending())
            // 只留一条：回放队列会让用户回到前台时瞬间跳好几个位置
            assertNull(server.takePending())
        }
    }

    @Test fun `transient commands are never replayed later`() {
        val controller = object : RemoteController {
            override fun navigate(cmd: RemoteCommand) = false
        }
        hub(null, controller).use { server ->
            // step/back 有时效性，重放会变成「一下子退好几集」
            request(server, "POST", "/cmd", """{"cmd":"step","by":10000}""")
            request(server, "POST", "/cmd", """{"cmd":"back"}""")
            assertNull(server.takePending())
        }
    }

    @Test fun `cross site posts and sse streams are refused`() {
        val applied = CopyOnWriteArrayList<RemoteCommand>()
        hub(officialTarget(applied)).use { server ->
            assertEquals(403, code(request(server, "POST", "/cmd", """{"cmd":"play","on":true}""", "Origin: http://evil.example\r\n")))
            assertTrue(applied.isEmpty())
            // SSE 是长连接读口，不校验 Origin 等于开了个裸读口
            val (socket, stream) = events(server, "Origin: http://evil.example\r\n")
            socket.use { assertTrue(stream.await("HTTP/1.1").contains("403")) }
        }
    }

    @Test fun `command flood is rate limited without killing the session`() {
        hub(officialTarget(), rateLimit = 5).use { server ->
            val codes = (1..9).map { code(request(server, "POST", "/cmd", """{"cmd":"play","on":true}""")) }
            assertTrue(codes.toString(), codes.contains(429))
            assertEquals(200, code(request(server)))
        }
    }

    @Test fun `oversized command body is refused`() {
        hub(officialTarget()).use { server ->
            val huge = """{"cmd":"search","query":"x${"y".repeat(RemoteProtocol.MAX_BODY)}"}"""
            assertEquals(413, code(request(server, "POST", "/cmd", huge)))
        }
    }

    @Test fun `snapshot is pushed to the phone`() {
        val server = hub()
        try {
            server.snapshot = PlaybackSnapshot(
                source = PlaybackSource.OFFICIAL, title = "总裁的替身新娘",
                groups = listOf(EpisodeGroup("正片", 88)), episode = 11,
                positionMs = 123_456, durationMs = 1_450_000, playing = true
            )
            val (socket, stream) = events(server)
            socket.use {
                val json = stream.nextSnapshot()
                assertEquals("OFFICIAL", json.getString("source"))
                assertEquals("总裁的替身新娘", json.getString("title"))
                assertEquals(11, json.getInt("episode"))
                assertEquals(1, json.getJSONObject("remote").getInt("clients"))
                assertTrue(json.getJSONObject("tv").getBoolean("foreground"))
                assertTrue(json.getLong("epoch") >= 1)
            }
        } finally {
            server.close()
        }
    }

    @Test fun `structural change is pushed immediately instead of waiting for the throttle`() {
        val server = hub()
        try {
            val (socket, stream) = events(server)
            socket.use {
                stream.nextSnapshot()
                server.snapshot = PlaybackSnapshot(source = PlaybackSource.OFFICIAL, title = "A", episode = 0, playing = true)
                assertEquals("A", stream.nextSnapshot().getString("title"))
                server.snapshot = PlaybackSnapshot(source = PlaybackSource.OFFICIAL, title = "A", episode = 1, playing = true)
                // 切集属于结构变化：等 400ms 节流会让手机上的集数明显慢半拍
                assertEquals(1, stream.nextSnapshot().getInt("episode"))
            }
        } finally {
            server.close()
        }
    }

    @Test fun `second phone joins instead of taking over`() {
        val server = hub()
        try {
            val (first, firstStream) = events(server, sid = "phonea")
            firstStream.nextSnapshot()
            val (second, secondStream) = events(server, sid = "phoneb")
            second.use {
                secondStream.nextSnapshot()
                // 两台都在线，谁也不踢谁：家里人同时拿手机操作是常态，不是异常
                assertTrue(waitUntil { server.clientCount == 2 })
                // 第一台不能收到 evicted。收到了页面会停掉自动重连，那台手机就废了
                server.snapshot = PlaybackSnapshot(source = PlaybackSource.OFFICIAL, title = "双端", episode = 3)
                assertEquals(3, firstStream.nextSnapshot().getInt("episode"))
            }
            first.close()
        } finally {
            server.close()
        }
    }

    @Test fun `one phone detaching leaves the others connected`() {
        val server = hub()
        try {
            server.snapshot = PlaybackSnapshot()
            val (first, firstStream) = events(server, sid = "phonea")
            firstStream.nextSnapshot()
            val (second, secondStream) = events(server, sid = "phoneb")
            secondStream.nextSnapshot()
            assertTrue(waitUntil { server.clientCount == 2 })
            // 带 sid 的 detach 只断自己：另一台还在看电视，不该被一起收掉
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"detach","sid":"phonea"}""")))
            assertTrue(waitUntil { server.clientCount == 1 })
            // token 依然有效，剩下的那台还能继续收快照
            server.snapshot = PlaybackSnapshot(source = PlaybackSource.OFFICIAL, title = "还在", episode = 2)
            assertEquals(2, secondStream.nextSnapshot().getInt("episode"))
            second.close()
        } finally {
            server.close()
        }
    }

    @Test fun `episodes and resume endpoints answer with provider data`() {
        val target = object : RemoteTarget {
            override val source = PlaybackSource.THIRD
            override fun apply(cmd: RemoteCommand) = true
            override fun episodes() = listOf(
                EpisodeGroupDetail("线路1", listOf(EpisodeEntry(0, "第 1 集"), EpisodeEntry(1, "第 2 集"))),
                EpisodeGroupDetail("线路2", listOf(EpisodeEntry(0, "第 1 集")))
            )
        }
        val resume = listOf(ResumeItem("a1", "剧名", "第 3 集 · 05:12", 312_000, 1_400_000))
        hub(target, resume = resume).use { server ->
            val episodes = request(server, route = "/episodes")
            assertEquals(200, code(episodes))
            assertTrue(episodes.contains("application/json"))
            val groups = JSONObject(episodes.substringAfter("\r\n\r\n")).getJSONArray("groups")
            assertEquals(2, groups.length())
            assertEquals("线路2", groups.getJSONObject(1).getString("name"))

            val items = JSONObject(request(server, route = "/resume").substringAfter("\r\n\r\n")).getJSONArray("items")
            assertEquals("a1", items.getJSONObject(0).getString("id"))
            assertEquals("第 3 集 · 05:12", items.getJSONObject(0).getString("caption"))
        }
    }

    @Test fun `closing the hub makes the session unreachable`() {
        val server = hub()
        server.close()
        assertTrue(runCatching { request(server) }.isFailure)
    }

    @Test fun `clients changed fires so the tv knows when to stay awake`() {
        val counts = CopyOnWriteArrayList<Int>()
        val first = CountDownLatch(1)
        val controller = object : RemoteController {
            override fun navigate(cmd: RemoteCommand) = true
            override fun onClientsChanged(clients: Int) { counts += clients; if(clients == 1) first.countDown() }
        }
        hub(null, controller).use { server ->
            server.snapshot = PlaybackSnapshot()
            val (socket, stream) = events(server)
            stream.nextSnapshot()
            assertTrue(first.await(3, TimeUnit.SECONDS))
            // 挂断后必须回到 0，否则电视会一直常亮
            socket.close()
            assertTrue(counts.toString(), waitUntil { counts.contains(0) })
        }
    }

    @Test fun `a fixed token keeps the phone url valid across sessions`() {
        val token = "0123456789abcdef0123456789abcdef"
        val first = LanRemoteHub("127.0.0.1", { it.run() }, { null }, { null }, fixedToken = token)
        val second = LanRemoteHub("127.0.0.1", { it.run() }, { null }, { null }, fixedToken = token)
        try {
            assertEquals(token, first.url.substringAfterLast('/'))
            assertEquals(token, second.url.substringAfterLast('/'))
        } finally {
            first.close(); second.close()
        }
    }

    @Test fun `a short injected token is ignored instead of weakening the gate`() {
        val server = LanRemoteHub("127.0.0.1", { it.run() }, { null }, { null }, fixedToken = "abc")
        try {
            // 回退到 16 字节随机，hex 展开是 32 个字符
            assertEquals(32, server.url.substringAfterLast('/').length)
        } finally { server.close() }
    }

    @Test fun `preferred port is reused so closing the window does not move the url`() {
        val token = "0123456789abcdef0123456789abcdef"
        val first = LanRemoteHub("127.0.0.1", { it.run() }, { null }, { null }, fixedToken = token)
        val url = first.url
        val port = first.port
        first.close()
        val second = LanRemoteHub(
            "127.0.0.1", { it.run() }, { null }, { null },
            fixedToken = token, preferredPort = port
        )
        try {
            assertEquals(port, second.port)
            assertEquals(url, second.url)
        } finally { second.close() }
    }

    @Test fun `page resource is packaged instead of the fallback`() {        assertTrue(LanRemotePage.html.contains("EventSource"))
        assertTrue(LanRemotePage.html.contains("红果TV"))
        assertFalse(LanRemotePage.html.contains("遥控页面未打包"))
    }

    @Test fun `event stream starts with a real http response so browsers accept it`() {
        val server = hub()
        try {
            val (socket, stream) = events(server)
            socket.use {
                // 少了状态行与 text/event-stream，EventSource 直接报网络错误，页面上看不出原因
                assertTrue(stream.await("HTTP/1.1").contains("200"))
                assertTrue(stream.await("Content-Type:").contains("text/event-stream"))
                assertTrue(stream.await("Cache-Control:").contains("no-store"))
                server.snapshot = PlaybackSnapshot(source = PlaybackSource.OFFICIAL, title = "A")
                assertEquals("A", stream.nextSnapshot().getString("title"))
            }
        } finally {
            server.close()
        }
    }

    @Test fun `page reload restarts its counter instead of locking the phone out`() {
        val applied = CopyOnWriteArrayList<RemoteCommand>()
        hub(officialTarget(applied)).use { server ->
            assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"play","on":true,"seq":42,"sid":"aaaaaa"}""")))
            // 刷新页面后 sid 换新、seq 从 1 重新开始。旧会话的水位不能顺延到新会话，
            // 否则用户刷一下页面，之后每条命令都会被判成过期。
            val (socket, stream) = events(server, sid = "bbbbbb")
            socket.use {
                stream.nextSnapshot()
                assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"play","on":false,"seq":1,"sid":"bbbbbb"}""")))
            }
            assertEquals(listOf(RemoteCommand.Play(true), RemoteCommand.Play(false)), applied.toList())
        }
    }

    @Test fun `snapshot seq grows so the phone can spot dropped frames`() {
        val server = hub()
        try {
            val (socket, stream) = events(server)
            socket.use {
                val first = stream.nextSnapshot().getLong("seq")
                server.snapshot = PlaybackSnapshot(source = PlaybackSource.OFFICIAL, title = "B")
                val second = stream.nextSnapshot().getLong("seq")
                assertTrue("$first -> $second", second > first)
            }
        } finally {
            server.close()
        }
    }

    @Test fun `each page keeps its own command watermark`() {
        val applied = CopyOnWriteArrayList<RemoteCommand>()
        hub(officialTarget(applied)).use { server ->
            val (socket, stream) = events(server, sid = "aaaaaa")
            socket.use {
                stream.nextSnapshot()
                assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"play","on":true,"seq":1,"sid":"aaaaaa"}""")))
                // 第二台手机也从 1 数起。共用一个全局水位的话，这一条会被 A 的 1 顶成过期，
                // 于是后连上来的那台手机所有按键都没反应——多设备并存必须按 sid 分桶。
                assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"play","on":false,"seq":1,"sid":"bbbbbb"}""")))
                // 同一个 sid 内旧命令重发仍然要挡掉，否则断线重连会把动作再做一遍
                assertEquals(409, code(request(server, "POST", "/cmd", """{"cmd":"muted","on":true,"seq":1,"sid":"aaaaaa"}""")))
                assertEquals(200, code(request(server, "POST", "/cmd", """{"cmd":"muted","on":true,"seq":2,"sid":"aaaaaa"}""")))
            }
            assertEquals(
                listOf(RemoteCommand.Play(true), RemoteCommand.Play(false), RemoteCommand.Muted(true)),
                applied.toList()
            )
        }
    }

    @Test fun `idle session expires and shuts the listener down`() {
        val expired = CountDownLatch(1)
        val server = hub(idleTimeoutMs = 200) { expired.countDown() }
        try {
            assertTrue(expired.await(5, TimeUnit.SECONDS))
            // 回收后必须真的不再监听，否则电视上留着个没人管的端口
            assertTrue(runCatching { request(server) }.isFailure)
        } finally {
            server.close()
        }
    }

    @Test fun `peek leaves the cached intent for the caller to decide`() {
        val controller = object : RemoteController {
            override fun navigate(cmd: RemoteCommand) = false
        }
        hub(null, controller).use { server ->
            request(server, "POST", "/cmd", """{"cmd":"speed","value":1.5}""")
            // 电视在首页时没有播放器可接，意图要一直留着，等播放器出现再兑现
            assertEquals(RemoteCommand.Speed(1.5f), server.peekPending())
            assertEquals(RemoteCommand.Speed(1.5f), server.peekPending())
            assertEquals(RemoteCommand.Speed(1.5f), server.takePending())
            assertNull(server.peekPending())
        }
    }
}
