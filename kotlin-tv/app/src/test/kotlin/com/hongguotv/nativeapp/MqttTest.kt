// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** MQTT 报文编解码：这几个字节错了就连不上或收不到指令，值得钉住。 */
class MqttTest {

    @Test
    fun `剩余长度按七位一组编码`() {
        assertArrayEquals(byteArrayOf(0x7F), Mqtt.length(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), Mqtt.length(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), Mqtt.length(16383))
    }

    @Test
    fun `字符串带两字节长度前缀`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x04, 0x4D, 0x51, 0x54, 0x54),
            Mqtt.string("MQTT")
        )
    }

    @Test
    fun `CONNECT 用私钥当 clientID 且清会话`() {
        val bytes = Mqtt.connect("abc", 60)
        assertEquals(0x10, bytes[0].toInt())
        // 6(协议名) + 2(级别+标志) + 2(keepalive) + 5(长度前缀+clientID)
        assertEquals(15, bytes[1].toInt())
        // 头(1) + 长度(1) + 字符串长度前缀(2) 之后才是 "MQTT"
        assertEquals("MQTT", String(bytes, 4, 4))
        assertEquals(4, bytes[8].toInt())          // 协议级别 3.1.1
        assertEquals(0x02, bytes[9].toInt())       // cleanSession
        assertEquals("abc", String(bytes, 14, 3))
    }

    @Test
    fun `SUBSCRIBE 带报文号和 QoS0`() {
        val bytes = Mqtt.subscribe(1, "hgplay1001")
        assertEquals(0x82.toByte(), bytes[0])
        assertEquals(0x00, bytes[2].toInt())       // packetId 高字节
        assertEquals(0x01, bytes[3].toInt())       // packetId 低字节
        assertEquals(0, bytes.last().toInt())      // QoS0
    }

    @Test
    fun `PINGREQ 就两个字节`() {
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x00), Mqtt.pingRequest())
    }

    @Test
    fun `PUBLISH 拆出主题和载荷`() {
        val body = byteArrayOf(0x00, 0x0A) + "hgplay1001".toByteArray() + "on".toByteArray()
        assertEquals("hgplay1001" to "on", Mqtt.publish(Mqtt.Packet(3, body)))
    }

    @Test
    fun `不是 PUBLISH 的报文返回空`() {
        assertNull(Mqtt.publish(Mqtt.Packet(13, byteArrayOf())))
        assertNull(Mqtt.publish(Mqtt.Packet(3, byteArrayOf(0x00))))
    }

    @Test
    fun `从流里读一个完整报文`() {
        val bytes = Mqtt.subscribe(1, "hg001") + Mqtt.pingRequest()
        val input = ByteArrayInputStream(bytes)
        val first = Mqtt.read(input)!!
        assertEquals(8, first.type)               // 8 = SUBSCRIBE
        // 报文号 2 + 主题长度前缀 2 + "hg001" 5 + QoS 1
        assertEquals(10, first.body.size)
        val second = Mqtt.read(input)!!
        assertEquals(12, second.type)             // 12 = PINGREQ
        assertNull(Mqtt.read(input))              // 读完了
    }
}
