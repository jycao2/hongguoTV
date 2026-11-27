// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库里那两份档案必须和代码里的内置默认严格对得上。
 *
 * 这是整个方案的地基：档案是「数据」，代码里的 [PlaybackProfile.builtin] 是唯一事实来源。
 * 只要这两者出现偏差，就说明有人手改了 JSON 却没跑导出器 —— 那种偏差在线上表现为
 * 「签名能生成但服务端回空」，几乎无法排查。
 */
class PlaybackProfileConsistencyTest {
    private val root: File = locateRoot()

    private fun locateRoot(): File {
        val candidates = listOf(File("../.."), File(".."), File("."))
        return candidates.firstOrNull { File(it, "playback-profile.src.json").isFile }
            ?: throw AssertionError("找不到仓库根的 playback-profile.src.json（当前目录 ${File(".").absolutePath}）")
    }

    @Test
    fun `源码档案与内置默认逐字一致`() {
        val expected = PlaybackProfile.builtin().toJson()
        val actual = File(root, "playback-profile.src.json").readText(Charsets.UTF_8)
        assertEquals("仓库里的 playback-profile.src.json 与 builtin() 不一致，请重跑导出器", expected, actual)
    }

    @Test
    fun `签名档案能验签且解出同一份档案`() {
        val envelope = File(root, "playback-profile.json").readText(Charsets.UTF_8)
        val decoded = PlaybackProfileEnvelope.decode(envelope)
        assertEquals(PlaybackProfile.builtin().toJson(), decoded.toJson())
    }

    @Test
    fun `档案里的门覆盖取址链路需要的每一个`() {
        val profile = PlaybackProfile.builtin()
        for (name in PlaybackProfile.GATE_KEYS) {
            val gate = profile.gate(name)
            assertTrue("门 $name 的 host 为空", gate.host.isNotBlank())
            assertTrue("门 $name 的 path 必须以 / 开头", gate.path.startsWith("/"))
            assertTrue("门 $name 的 UA 为空", gate.userAgent.isNotBlank())
        }
    }

    @Test
    fun `被篡改的 payload 一律验签失败`() {
        val envelope = File(root, "playback-profile.json").readText(Charsets.UTF_8)
        val payload = Base64Tool.decode(extract(envelope))
        payload[payload.size / 2] = (payload[payload.size / 2].toInt() xor 1).toByte()
        val tampered = envelope.replace(extract(envelope), Base64Tool.encode(payload))
        val failed = try {
            PlaybackProfileEnvelope.decode(tampered); false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("篡改后的档案必须被拒收", failed)
    }

    private fun extract(envelope: String): String =
        Regex("\"payload\"\\s*:\\s*\"([^\"]+)\"").find(envelope)!!.groupValues[1]

    private object Base64Tool {
        fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
        fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
