// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 优先复用上次的端口，拿不到就退回随机端口。
 *
 * 端口稳定的意义：关掉二维码窗口再点开，手机上那个地址还能继续用。
 * 端口被别的进程抢走时不能死磕，直接换一个，宁可这条地址失效也不能起不来。
 */
private fun openListener(preferredPort: Int): ServerSocket {
    if(preferredPort > 0) runCatching {
        return ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(preferredPort), 8)
        }
    }
    return ServerSocket(0, 8)
}

/**
 * 手机遥控的局域网服务。
 *
 * 和 [PhoneInputServer] 的关键区别：那边是「一次请求-响应即关闭」，这里是长连接，
 * 所以不能有 `consumed` 一次性原子位，也不能有绝对 TTL——只用空闲回收。
 *
 * 线程模型：
 * - accept 线程 1 条，`soTimeout=500` 轮询，顺带做空闲检查；
 * - 每个 SSE 连接一条线程，各自带一条看门狗；同时连几台手机就有几条；
 * - POST /cmd 由 accept 线程内的临时线程处理，处理完立即退出。
 *
 * 连接模型从「单槽位抢占」改成「多设备并存」：家里几个人同时拿着手机看同一台电视，
 * 谁的命令都生效，不再互相顶掉。命令去重按页面会话（sid）分桶，各设备的 seq 互不干扰。
 *
 * [post] 由调用方提供「切到主线程」的实现（Android 是 `Handler::post`，
 * 单测直接传 `{ it.run() }`），core 不依赖 Android。
 */
class LanRemoteHub(
    address: String,
    private val post: (Runnable) -> Unit,
    private val target: () -> RemoteTarget?,
    private val controller: () -> RemoteController?,
    private val resumeItems: () -> List<ResumeItem> = { emptyList() },
    /** 「更多」弹层的全量观看记录（本机全部 + 云端补后），GET /resume/all 时取。 */
    private val resumeAllItems: () -> List<ResumeItem> = { emptyList() },
    /**
     * 封面字节源。手机端不直接加载封面原始地址：部分 CDN 会校验 Referer/UA，
     * 手机那头拿不到，只能由电视代取。上层通常把「磁盘缓存优先、未命中再下载」塞进来。
     * 返回 null 表示取不到，路由回 404，页面保留占位块。
     */
    private val coverBytes: ((String) -> ByteArray?)? = null,
    private val onExpired: () -> Unit = {},
    /**
     * 外部注入的门禁密钥；null 则随机生成。
     *
     * 默认随机构造是为了让「一次扫码一个 token」；但关掉二维码窗口再重新打开时，
     * 用户手机里还留着旧地址，随机换新会让页面立刻 404，等于逼他再扫一遍。
     * 上层改成传进程级固定值，就能做到「不杀进程不换锁」。
     */
    fixedToken: String? = null,
    /**
     * 上次用过的端口。关掉二维码窗口再打开时会新建 Hub，
     * 端口若每次都随机，token 固定也没用——手机里那个地址一样会失效。
     * 传 0 或不传就随机挑。
     */
    preferredPort: Int = 0,
    private val idleTimeoutMs: Long = 30 * 60_000,
    private val heartbeatMs: Long = 15_000,
    private val watchdogMs: Long = 40_000,
    private val snapshotIntervalMs: Long = 400,
    private val cmdRateLimit: Int = 20,
    /** 诊断出口：连上后把连接生命周期的关键节点吐出去，Android 侧接到 logcat。默认关闭。 */
    private val trace: ((String) -> Unit)? = null
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val localAddress = InetAddress.getByName(address).also {
        require(it.isLoopbackAddress || it.isSiteLocalAddress) { "遥控只监听内网地址" }
    }.hostAddress
    // 仍然绑通配地址：lanAddress() 可能挑错网卡，绑死会让手机直接连不上。
    // 真正的门禁是 token，不是监听范围。
    private val listener = openListener(preferredPort).apply { soTimeout = 500 }
    private val token = fixedToken?.takeIf { it.length >= RemoteProtocol.MIN_TOKEN_LENGTH }
        ?: RemoteProtocol.newToken()
    /** `ip:port`，用于在电视上展示给用户手输，以及手机上判断是不是同一台电视。 */
    val host = "$localAddress:${listener.localPort}"
    val url: String = "http://$host/$token"
    val address: String = localAddress
    val port: Int get() = listener.localPort

    /** 主线程写、socket 线程读。绝不能在 socket 线程直接碰播放器。 */
    @Volatile var snapshot: PlaybackSnapshot = PlaybackSnapshot()

    /**
     * 会话标识。每次扫码都会新建一个 Hub，所以它天然「一次扫码一个值」。
     * 手机端只用来判断「对面是不是同一个会话」，不需要用户手动管理。
     */
    val epoch: Long = System.currentTimeMillis()

    /**
     * 同时在线的手机页面。并发集合：accept 线程加、各 SSE 线程删，
     * 遍历时还会被心跳/快照线程读到，不能用一个普通字段。
     */
    private val clients = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<SseClient, Boolean>()
    )
    /**
     * 每个页面会话各自的命令水位。
     *
     * 以前是单个全局值，多设备并存后必须按 sid 分桶：两台手机各自从 1 开始发，
     * 共用一个水位会让后发的那台整串命令都被判成「已过期」。
     */
    private val commandSeq = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastActivity = System.nanoTime()
    private val seqCounter = AtomicLong()
    private val pendingLock = Any()
    private var pendingIntent: RemoteCommand? = null
    private val rateLock = Any()
    private var rateWindowStart = 0L
    private var rateCount = 0

    private val acceptThread = Thread({ serve() }, "hongguotv-remote").apply { isDaemon = true }

    init { acceptThread.start() }

    /**
     * 在线设备数，不是连接数。
     *
     * 用客户端内网 IP 去重：同一台手机不管开几个标签页、用哪个浏览器（Chrome /
     * 系统浏览器 / PWA），在 LAN 上都是同一个 IP，只算一台设备；不同手机 IP
     * 不同，各算各的。遥控本身要求和电视同一局域网，所以 IP 能稳定区分物理设备。
     */
    val clientCount: Int get() = clients.map { it.socket.inetAddress.hostAddress }.distinct().size

    override fun close() {
        stopped.set(true)
        dropAllClients(notify = true)
        runCatching { listener.close() }
    }

    /** 取走断线/后台期间缓存的最后一条幂等意图；只有一条，绝不回放队列。 */
    fun takePending(): RemoteCommand? = synchronized(pendingLock) {
        val value = pendingIntent
        pendingIntent = null
        value
    }

    /** 只看不取：调用方先确认「现在能不能处理」，处理不了就继续留着。 */
    fun peekPending(): RemoteCommand? = synchronized(pendingLock) { pendingIntent }

    // ---------------------------------------------------------------- 监听

    private fun serve() {
        try {
            while(!stopped.get()) {
                val now = System.nanoTime()
                if(clients.isEmpty() && now - lastActivity > idleTimeoutMs * 1_000_000) {
                    stop("空闲回收")
                    return
                }
                val client = try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                Thread({ handle(client) }, "hongguotv-remote-req").apply { isDaemon = true }.start()
            }
        } catch(_: Exception) {
            // close() 打断 accept()
        } finally {
            if(!stopped.get()) stop("监听结束")
        }
    }

    private fun stop(reason: String) {
        if(!stopped.compareAndSet(false, true)) return
        dropAllClients(notify = true)
        runCatching { listener.close() }
        post { controller()?.onSessionEnded() }
        onExpired()
    }

    // ---------------------------------------------------------------- HTTP

    private fun handle(socket: Socket) {
        socket.use {
            try {
                if(!socket.inetAddress.let { a -> a.isLoopbackAddress || a.isSiteLocalAddress }) return
                lastActivity = System.nanoTime()
                socket.soTimeout = 4000
                route(socket)
            } catch(_: SocketTimeoutException) {
                reply(socket, 408, "请求超时")
            } catch(_: Exception) {
                // 客户端主动断开、畸形请求：丢弃即可，不断服务
            }
        }
    }

    private fun route(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val line = readLine(input, 1024)
        val parts = line.split(' ')
        if(parts.size != 3) return reply(socket, 400, "请求格式错误")
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")
        val headers = readHeaders(input) ?: return reply(socket, 431, "请求过大")
        if(headers["host"] != host) return reply(socket, 404, "页面不存在")
        if(!path.startsWith("/$token")) return reply(socket, 404, "页面不存在")
        val route = path.removePrefix("/$token").ifEmpty { "/" }
        when {
            method == "GET" && route == "/" -> reply(socket, 200, LanRemotePage.html, "text/html")
            method == "GET" && route == "/events" -> serveEvents(socket, headers, query)
            method == "GET" && route == "/episodes" -> {
                allowOrigin(socket, headers) ?: return
                reply(socket, 200, episodesOnMain().toEpisodesJson(), "application/json")
            }
            method == "GET" && route == "/resume" -> {
                allowOrigin(socket, headers) ?: return
                reply(socket, 200, resumeOnMain().toResumeJson(), "application/json")
            }
            method == "GET" && route == "/resume/all" -> {
                allowOrigin(socket, headers) ?: return
                reply(socket, 200, onMain(emptyList()) { resumeAllItems() }.toResumeJson(), "application/json")
            }
            method == "GET" && route == "/cover" -> {
                allowOrigin(socket, headers) ?: return
                serveCover(socket, query)
            }
            method == "POST" && route == "/cmd" -> handleCommand(socket, input, headers)
            else -> reply(socket, 405, "不支持的操作")
        }
    }

    /** Origin 是纵深防御，token 才是真门禁。SSE 的 GET 也必须校验，否则等于开了个裸读口。 */
    private fun allowOrigin(socket: Socket, headers: Map<String, String>): Unit? {
        if(foreignOrigin(headers["origin"], localAddress)) {
            reply(socket, 403, "来源不匹配")
            return null
        }
        return Unit
    }

    /** 页面会话标识只允许短字母数字，避免当成任意字符串塞进正则/比较里。 */
    private fun validSid(value: String): String? =
        value.takeIf { it.length in 6..40 && it.all { ch -> ch.isLetterOrDigit() } }

    private fun handleCommand(socket: Socket, input: InputStream, headers: Map<String, String>) {
        allowOrigin(socket, headers) ?: return
        if(headers.containsKey("transfer-encoding")) return reply(socket, 400, "请求格式错误")
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..RemoteProtocol.MAX_BODY }
            ?: return reply(socket, 413, "命令过长")
        if(!allowRate()) return reply(socket, 429, "操作过于频繁")
        val body = ByteArray(length)
        var offset = 0
        while(offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if(read < 0) return
            offset += read
        }
        val text = String(body, Charsets.UTF_8)
        val command = RemoteProtocol.parse(text) ?: return reply(socket, 400, "命令无效")
        val seq = RemoteProtocol.seqOf(text)
        val sid = RemoteProtocol.sidOf(text)
        if(command is RemoteCommand.Detach) {
            // 只断自己那一台。多设备并存时，一台手机点「断开遥控」不该把别人一起收掉。
            val own = sid?.let { value -> clients.firstOrNull { it.sid == value } }
            if(own != null) own.close() else dropAllClients(notify = true)
            return reply(socket, 200, "{\"ok\":true}")
        }
        if(seq != null) {
            // 水位按 sid 分桶：两台手机各自从 1 数起，共用一个计数器会互相判过期。
            // 不带 sid 的（curl、脚本）共用兜底桶，重复投递照样能挡住。
            val key = sid ?: ""
            val previous = commandSeq.put(key, seq)
            if(previous != null && seq <= previous) {
                commandSeq.put(key, previous)
                return reply(socket, 409, "命令已过期")
            }
            pruneCommandSeq()
        }
        dispatch(command)
        reply(socket, 200, """{"ok":true${if(seq != null) ",\"seq\":$seq" else ""}}""", "application/json")
    }

    /** 多设备共用一条限速窗口：真有人刷屏时，无论从哪台手机来都该被挡住。 */
    private fun allowRate(): Boolean = synchronized(rateLock) {
        val now = System.nanoTime()
        if(now - rateWindowStart >= 1_000_000_000L) { rateWindowStart = now; rateCount = 0 }
        ++rateCount <= cmdRateLimit
    }

    // ---------------------------------------------------------------- 命令

    private fun dispatch(command: RemoteCommand) {
        post {
            val accepted = if(command.isPlayback) target()?.apply(command) == true
            else controller()?.navigate(command) == true
            if(accepted) synchronized(pendingLock) { pendingIntent = null }
            else if(command.durable) synchronized(pendingLock) { pendingIntent = command }
        }
    }

    private fun <T> onMain(fallback: T, block: () -> T): T {
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        post { result = block(); latch.countDown() }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result ?: fallback
    }

    private fun episodesOnMain(): List<EpisodeGroupDetail> = onMain(emptyList()) { target()?.episodes() ?: emptyList() }
    private fun resumeOnMain(): List<ResumeItem> = onMain(emptyList()) { resumeItems() }

    // ---------------------------------------------------------------- SSE

    private inner class SseClient(val socket: Socket, val sid: String?) {
        val lock = Any()
        @Volatile var lastWriteAt = System.nanoTime()
        @Volatile var stopRequested = false
        @Volatile var closed = false
        fun close() { stopRequested = true; closed = true; runCatching { socket.close() } }
    }

    private fun serveEvents(socket: Socket, headers: Map<String, String>, query: String) {
        if(foreignOrigin(headers["origin"], localAddress)) return reply(socket, 403, "来源不匹配")
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        val sid = validSid(Regex("(?:^|&)sid=([^&]+)").find(query)?.groupValues?.get(1) ?: "")
        val client = SseClient(socket, sid)
        clients.add(client)
        notifyClients()

        val watchdog = Thread({ watch(client) }, "hongguotv-remote-wdt").apply { isDaemon = true }
        watchdog.start()
        try {
            // 必须是标准 HTTP 响应。少了状态行与 text/event-stream，浏览器 EventSource
            // 会直接判定为网络错误并无限重连，页面上看不出任何原因。
            write(client, buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream; charset=utf-8\r\n")
                append("Cache-Control: no-store, no-transform\r\n")
                append("Connection: keep-alive\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("X-Content-Type-Options: nosniff\r\n\r\n")
            })
            write(client, "retry: 1500\n\n")
            var sentAt = 0L
            var lastJson = ""
            var structural = ""
            while(!stopped.get() && !client.stopRequested) {
                // 手机锁屏、关标签页都会发 FIN。不主动探测就只能等下一次写失败，
                // 最坏要等一个心跳周期，槽位白占 15 秒，电视也跟着白亮 15 秒。
                if(peerGone(socket)) { trace?.invoke("sse break: peer gone"); break }
                val snap = snapshot
                val mark = structuralOf(snap)
                val now = System.nanoTime()
                // 比较用的指纹里 seq/ts 必须固定，否则「每次都不一样」会让节流完全失效
                val fingerprint = renderSnapshot(0L, 0L)
                if(fingerprint != lastJson && (mark != structural || now - sentAt >= snapshotIntervalMs * 1_000_000)) {
                    // seq 只在实际下发时递增，手机端据此判断有没有漏帧
                    write(client, "event: snap\ndata: ${renderSnapshot(seqCounter.incrementAndGet(), System.currentTimeMillis())}\n\n")
                    lastJson = fingerprint
                    structural = mark
                    sentAt = now
                } else if(now - client.lastWriteAt >= heartbeatMs * 1_000_000) {
                    write(client, ":hb\n\n")
                }
                Thread.sleep(120)
            }
            trace?.invoke("sse loop end: stopRequested=${client.stopRequested} stopped=${stopped.get()}")
        } catch(t: Exception) {
            // 断线、看门狗 close、服务停止都走这里
            trace?.invoke("sse error: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            trace?.invoke("sse closed")
            client.close()
            clients.remove(client)
            // 空闲计时从「最后一台手机断开」开始算，否则连着看了一小时再断开，
            // 会话会在同一秒被回收，用户想重连还得跑到电视前重新扫码。
            if(clients.isEmpty()) lastActivity = System.nanoTime()
            notifyClients()
        }
    }

    /**
     * 用 1 毫秒的读超时探测对端是否已经关闭。手机端在这条连接上永远不发数据，
     * 所以读到 -1 就一定是 FIN；抛超时说明连接还活着。
     *
     * 注意这里**只能**把「读到 EOF」当成断开。以前把所有异常都当成已关闭，
     * 结果 Android 上 1ms 探测读超时抛的并不总是 SocketTimeoutException，
     * 连接就被自己每两秒掐一次——手机端表现为「连上就断、断了又连」的死循环，
     * 页面上却因为每帧都能收到快照而看不出异常。
     */
    private fun peerGone(socket: Socket): Boolean = try {
        socket.soTimeout = 1
        socket.getInputStream().read() < 0
    } catch(_: SocketTimeoutException) {
        false
    } catch(t: Exception) {
        // 探测本身没结论，不代表对端走了；真断了会在下一次心跳写入时抛出来。
        trace?.invoke("probe failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /** 位置以外的一切。结构变化立即下发，只有位置变化才走 400ms 节流。 */
    private fun structuralOf(snap: PlaybackSnapshot): String = buildString {
        append(snap.source).append('|').append(snap.title).append('|').append(snap.group).append('|')
        append(snap.episode).append('|').append(snap.playing).append('|').append(snap.buffering).append('|')
        append(snap.speed).append('|').append(snap.muted).append('|').append(snap.danmaku).append('|')
        append(snap.error).append('|').append(snap.foreground).append('|').append(snap.screen).append('|')
        append(snap.durationMs).append('|').append(epoch)
    }

    private fun renderSnapshot(seq: Long, ts: Long): String =
        snapshot.toJson(epoch, seq, ts, clientCount, 0)

    private fun write(client: SseClient, text: String) {
        synchronized(client.lock) {
            if(client.closed) throw SocketException("closed")
            client.socket.getOutputStream().apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
            client.lastWriteAt = System.nanoTime()
        }
    }

    /**
     * 写看门狗。Java 没有 SO_SNDTIMEO，TCP 半开时 write() 会永久阻塞，
     * 只能靠「多久没成功写入」来判断并从另一条线程 close 掉，让阻塞的 write 抛异常退出。
     * 阈值必须明显大于心跳间隔，否则健康连接会在两次心跳之间被误杀。
     */
    private fun watch(client: SseClient) {
        while(!client.stopRequested && !stopped.get()) {
            try { Thread.sleep(3_000) } catch(_: InterruptedException) { return }
            if(System.nanoTime() - client.lastWriteAt > watchdogMs * 1_000_000) {
                client.close()
                return
            }
        }
    }

    private fun notifyClients() {
        val count = clientCount
        post { controller()?.onClientsChanged(count) }
    }

    private fun dropAllClients(notify: Boolean) {
        val snapshot = clients.toList()
        clients.clear()
        snapshot.forEach { it.close() }
        if(notify) notifyClients()
    }

    /**
     * 水位表不能无限长：手机每刷新一次页面就是一个新 sid，一晚上能攒几十个。
     * 超过阈值时只保留仍在线的那几个——断线重连在意的是「同一页面接着用」，
     * 已经关掉的页面不需要再记住水位。
     */
    private fun pruneCommandSeq() {
        if(commandSeq.size <= 64) return
        // "" 是工具/测试共用的兜底桶，不能当成「已关掉的页面」清掉
        val live = clients.mapNotNullTo(HashSet<String>()) { it.sid }.apply { add("") }
        commandSeq.keys.removeAll { it !in live }
    }

    // ---------------------------------------------------------------- 报文

    private fun readLine(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        while(out.size() < max) {
            val next = input.read()
            if(next < 0) throw IOException("unexpected eof")
            if(next == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw IOException("header too large")
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var bytes = 0
        while(true) {
            val line = readLine(input, 4096)
            bytes += line.length + 2
            if(bytes > 8192) return null
            if(line.isEmpty()) return headers
            val at = line.indexOf(':')
            if(at <= 0) return null
            val key = line.substring(0, at).lowercase(Locale.ROOT)
            if(headers.put(key, line.substring(at + 1).trim()) != null) return null
        }
    }

    /**
     * 封面代取。手机端直接加载原始地址会撞上 CDN 的 Referer/UA 校验，
     * 而且有的地址只在电视侧可访问，所以统一走电视转发。
     */
    private fun serveCover(socket: Socket, query: String) {
        val raw = Regex("(?:^|&)u=([^&]+)").find(query)?.groupValues?.get(1)
            ?: return reply(socket, 404, "封面不存在")
        val url = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
        // 只代理 http(s) 且卡长度：token 已经挡住了外人，这里再挡掉被当成任意协议探针的可能。
        // 上限给到 1024：红果云端的 thumb_url 带完整签名参数，经常超过 512 字符，卡太紧云端海报会全军覆没。
        if(url == null || url.length > 1024 || !(url.startsWith("http://") || url.startsWith("https://")))
            return reply(socket, 404, "封面不存在")
        val bytes = coverBytes?.invoke(url)
        if(bytes == null || bytes.isEmpty()) return reply(socket, 404, "封面不存在")
        replyBytes(socket, 200, bytes, imageType(bytes))
    }

    /** 按文件头判断图片类型，别把 jpeg 当成 octet-stream 让浏览器不渲染。 */
    private fun imageType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun replyBytes(socket: Socket, code: Int, payload: ByteArray, contentType: String) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            // 封面基本不变，让手机自己留一天，来回切浏览模式不用每次重下
            append("Cache-Control: private, max-age=86400\r\n")
            append("X-Content-Type-Options: nosniff\r\n\r\n")
        }
        runCatching {
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(payload); flush() }
        }
    }

    private fun reply(socket: Socket, code: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: ").append(contentType).append(if(contentType.contains("charset")) "" else "; charset=utf-8").append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: ").append(CSP).append("\r\n\r\n")
        }
        runCatching {
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(payload); flush() }
        }
    }

    private fun statusText(code: Int): String = when(code) {
        200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 409 -> "Conflict"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    companion object {
        /**
         * 与手机填写页不同，这里必须放开 script-src 与 connect-src：
         * EventSource 受 connect-src 约束，漏了会静默失败且没有任何报错。
         */
        const val CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"
    }
}
