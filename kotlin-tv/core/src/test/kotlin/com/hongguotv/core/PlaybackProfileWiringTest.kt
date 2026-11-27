// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线验证：档案改了，签名与门地址必须**真的**跟着变。
 *
 * 只有 [SignerGoldenTest] 不够 —— 它只证明「没改的东西没变」。如果哪天有人把
 * `Signer` 里的取值改回写死常量，黄金向量照样全绿，但远端档案就形同虚设。
 */
class PlaybackProfileWiringTest {
    @After
    fun restore() {
        PlaybackProfiles.enabled = true
        PlaybackProfiles.reset()
    }

    private fun profile(mutate: (JSONObject) -> Unit): PlaybackProfile {
        val root = JSONObject(PlaybackProfile.builtin().toJson())
        root.put("revision", 7)
        mutate(root)
        return PlaybackProfile.parse(root.toString())
    }

    private fun signer(): Signer {
        var n = 0
        return Signer(
            clock = { 1_764_000_000_000L },
            device = mapOf(
                "device_id" to "1234567890", "os_version" to "12", "device_type" to "PGT-AN10",
                "channel" to "vivo_8662_64", "version_name" to "7.4.5.32",
            ),
            random = { n += 1; (n * 0.0123) % 1.0 },
        )
    }

    private fun medusa(): String = signer().signPlaybackV5(
        "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1",
        linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "74532",
            "version_name" to "7.4.5.32", "channel" to "vivo_8662_64",
            "device_platform" to "android", "device_type" to "PGT-AN10",
            "os_version" to "12", "update_version_code" to "74532",
            "device_id" to "1234567890", "iid" to "1", "cdid" to "2",
        ),
        """{"video_id":"7001"}""".toByteArray(),
    ).headers.getValue("x-medusa")

    @Test
    fun `改 magic 或 field21 会改变报文`() {
        val baseline = medusa()
        assertTrue(PlaybackProfiles.apply(profile { it.getJSONObject("v5").put("field21", 624) }))
        assertNotEquals("field21 改了，报文必须跟着变", baseline, medusa())
    }

    @Test
    fun `改 signKey 会改变报文`() {
        val baseline = medusa()
        PlaybackProfiles.apply(profile { it.getJSONObject("v5").put("signKey", "11".repeat(32)) })
        assertNotEquals("signKey 改了，报文必须跟着变", baseline, medusa())
    }

    @Test
    fun `改 sbox 会改变报文`() {
        val baseline = medusa()
        val sbox = "ab".repeat(1024)
        PlaybackProfiles.apply(profile { it.getJSONObject("v5").put("sbox", sbox) })
        assertNotEquals("sbox 改了，报文必须跟着变", baseline, medusa())
    }

    @Test
    fun `改 UA 会反映到 VendorConstants 与播放链路`() {
        val playback = "com.phoenix.read/88888 (Linux; U; Android 12; PGT-AN10)"
        val video = "com.phoenix.read/71332 (Linux; U; Android 16; 25053RT47C)"
        PlaybackProfiles.apply(profile {
            it.getJSONObject("ua").put("playback", playback)
            it.getJSONObject("ua").put("video", video)
        })
        assertEquals(playback, MobilePlayback.UA)
        assertEquals(video, VendorConstants.VIDEO_UA)
    }

    @Test
    fun `改门地址会反映到取址常量`() {
        PlaybackProfiles.apply(profile {
            it.getJSONObject("gates").getJSONObject("model").put("hosts", org.json.JSONArray().put("api5-alt.example.com"))
            it.getJSONObject("gates").getJSONObject("model").put("path", "/novel/player/next/v1")
            it.getJSONObject("gates").getJSONObject("tab").put("userAgent", "com.phoenix.read/99999 (Linux; U; Android 12)")
        })
        assertEquals("https://api5-alt.example.com", MobilePlayback.HOST)
        assertEquals("/novel/player/next/v1", MobilePlayback.PATH)
        assertEquals("com.phoenix.read/99999 (Linux; U; Android 12)", PlaybackProfiles.gate("tab").userAgent)
    }

    @Test
    fun `重置后接线回到内置默认`() {
        val baseline = medusa()
        PlaybackProfiles.apply(profile { it.getJSONObject("v5").put("field21", 624) })
        PlaybackProfiles.reset()
        assertEquals(baseline, medusa())
        assertEquals("https://api5-normal-sinfonlinea.fqnovel.com", MobilePlayback.HOST)
    }
}
