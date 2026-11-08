// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 只做 subscribe 的 MQTT 3.1.1 实现，够用就行。
 *
 * 为什么非得有它：米家下发语音指令前会先看设备在不在线，而巴法云的「在线」= 有客户端
 * 订阅了这个主题。只轮询 HTTP 的电视永远算离线，小爱会直接回「设备不在线」，指令连
 * 发都发不出来。连上以后顺带把 2 秒轮询的延迟也省了。
 *
 * 用到的部分只有 CONNECT / SUBSCRIBE(QoS0) / PINGREQ / 收 PUBLISH，没有发布需求，
 * 所以不引第三方库。
 */
object Mqtt {

    /** 剩余长度：每字节 7 位，最高位表示还有后续。 */
    fun length(value: Int): ByteArray {
        var rest = value
        val out = ArrayList<Byte>(4)
        do {
            var byte = rest % 128
            rest /= 128
            if(rest > 0) byte = byte or 0x80
            out.add(byte.toByte())
        } while(rest > 0)
        return out.toByteArray()
    }

    /** MQTT 字符串：两字节长度 + UTF-8。 */
    fun string(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf((raw.size shr 8).toByte(), (raw.size and 0xFF).toByte()) + raw
    }

    fun packet(header: Int, body: ByteArray): ByteArray =
        byteArrayOf(header.toByte()) + length(body.size) + body

    /** clientID 用私钥，cleanSession=1，不要遗嘱和账号密码。 */
    fun connect(clientId: String, keepAliveSeconds: Int = 60): ByteArray {
        val variable = string("MQTT") + byteArrayOf(4, 0x02) +
            byteArrayOf((keepAliveSeconds shr 8).toByte(), (keepAliveSeconds and 0xFF).toByte())
        return packet(0x10, variable + string(clientId))
    }

    fun subscribe(packetId: Int, topic: String): ByteArray =
        packet(0x82, byteArrayOf((packetId shr 8).toByte(), (packetId and 0xFF).toByte()) + string(topic) + byteArrayOf(0))

    fun pingRequest(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

    data class Packet(val type: Int, val body: ByteArray)

    /** 阻塞读一个完整报文；连接关闭返回 null。 */
    fun read(input: InputStream): Packet? {
        val first = input.read()
        if(first < 0) return null
        var remaining = 0
        var multiplier = 1
        while(true) {
            val byte = input.read()
            if(byte < 0) return null
            remaining += (byte and 0x7F) * multiplier
            if(byte and 0x80 == 0) break
            multiplier *= 128
            if(multiplier > 128 * 128 * 128) return null
        }
        val body = ByteArray(remaining)
        var offset = 0
        while(offset < remaining) {
            val read = input.read(body, offset, remaining - offset)
            if(read < 0) return null
            offset += read
        }
        return Packet(first shr 4, body)
    }

    /** PUBLISH 的载荷：主题 + 消息体。QoS0 没有 packetId。 */
    fun publish(packet: Packet): Pair<String, String>? {
        if(packet.type != 3 || packet.body.size < 4) return null
        val size = ((packet.body[0].toInt() and 0xFF) shl 8) or (packet.body[1].toInt() and 0xFF)
        if(packet.body.size < 2 + size) return null
        val topic = String(packet.body, 2, size, Charsets.UTF_8)
        val payload = String(packet.body, 2 + size, packet.body.size - 2 - size, Charsets.UTF_8)
        return topic to payload
    }
}

/**
 * 一条常驻 MQTT 连接：连上 → 通知调用方订阅主题 → 收消息 → 断了按退避重连。
 *
 * 只在电视前台跑（跟着 Activity 的 onStart/onStop），后台常驻既会被系统掐，也没人喊话。
 */
class BemfaSession(
    private val host: String = "bemfa.com",
    private val port: Int = 9501,
    private val clientId: String,
    private val onOpen: () -> Unit,
    private val onMessage: (topic: String, payload: String) -> Unit,
    private val onState: (online: Boolean) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var output: OutputStream? = null
    private var thread: Thread? = null
    private var packetId = 1
    private val writeLock = Any()

    /** 订阅过的主题，重连后自动重订（重连时调用方还没重新拉到列表）。 */
    private val subscribed = LinkedHashSet<String>()

    fun start() {
        if(running) return
        running = true
        thread = Thread({ loop() }, "hongguotv-mqtt").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        // 先关 socket：读线程阻塞在 read 上，只有关掉才会立刻返回
        runCatching { socket?.close() }
        socket = null
        thread = null
        onState(false)
    }

    /** 订阅全量主题：先记账，连上就把账本发一遍（调用方每次都传全量，不传增量）。 */
    fun subscribe(topics: Collection<String>) {
        synchronized(writeLock) {
            subscribed.clear()
            subscribed.addAll(topics)
            val stream = output ?: return
            subscribed.forEach { topic -> runCatching { stream.write(Mqtt.subscribe(nextId(), topic)) } }
            runCatching { stream.flush() }
        }
    }

    private fun nextId(): Int {
        packetId = (packetId % 0xFFFE) + 1
        return packetId
    }

    private fun loop() {
        var backoff = 1_000L
        while(running) {
            var connected = false
            try {
                val link = Socket()
                link.connect(InetSocketAddress(host, port), 8_000)
                link.soTimeout = 5_000
                link.tcpNoDelay = true
                socket = link
                val stream = link.getOutputStream()
                synchronized(writeLock) { output = stream }
                stream.write(Mqtt.connect(clientId))
                stream.flush()

                val hello = Mqtt.read(link.getInputStream()) ?: throw IllegalStateException("没有 CONNACK")
                if(hello.type != 2) throw IllegalStateException("CONNACK 异常")
                val code = hello.body.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                if(code != 0) throw IllegalStateException("巴法云拒绝连接（code=$code）")

                connected = true
                backoff = 1_000L
                onState(true)
                // 先请调用方拉主题列表（异步），再把手上的账本补订阅一遍：重连时列表往往还没回来
                onOpen()
                synchronized(writeLock) {
                    subscribed.forEach { topic -> stream.write(Mqtt.subscribe(nextId(), topic)) }
                    stream.flush()
                }
                readLoop(link.getInputStream(), stream)
            } catch(error: Exception) {
                if(!running) break
                // 连上以后断开就重连得快一点，连不上则退避到 30 秒
                if(error !is SocketTimeoutException) onState(false)
            } finally {
                synchronized(writeLock) { output = null }
                runCatching { socket?.close() }
                socket = null
            }
            if(!running) break
            if(!connected) {
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            } else {
                onState(false)
                Thread.sleep(1_000)
            }
        }
    }

    private fun readLoop(input: InputStream, stream: OutputStream) {
        var lastPing = System.currentTimeMillis()
        while(running) {
            val packet = try {
                Mqtt.read(input)
            } catch(_: SocketTimeoutException) {
                // 空闲：到点就 ping，keepalive 是 60 秒，30 秒一次足够
                if(System.currentTimeMillis() - lastPing >= 30_000L) {
                    synchronized(writeLock) { stream.write(Mqtt.pingRequest()); stream.flush() }
                    lastPing = System.currentTimeMillis()
                }
                continue
            } ?: break
            when(packet.type) {
                3 -> Mqtt.publish(packet)?.let { (topic, payload) -> onMessage(topic, payload) }
                // 13 = PINGRESP，其它（SUBACK 等）不用管
            }
        }
    }
}
