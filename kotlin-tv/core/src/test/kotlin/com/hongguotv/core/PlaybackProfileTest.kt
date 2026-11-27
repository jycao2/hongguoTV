// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 档案的解析与拒收边界：坏档案必须整份拒绝，绝不能半用。 */
class PlaybackProfileTest {
    @After
    fun restore() {
        PlaybackProfiles.enabled = true
        PlaybackProfiles.reset()
    }

    private fun builtinJson(): JSONObject = JSONObject(PlaybackProfile.builtin().toJson())

    private fun mutated(block: (JSONObject) -> Unit): String = builtinJson().also(block).toString()

    private fun reject(what: String, json: String) {
        val failed = try {
            PlaybackProfile.parse(json); false
        } catch (_: Exception) {
            true
        }
        assertTrue("应当拒收：$what", failed)
    }

    @Test
    fun `内置默认能被自己序列化后再解析回来`() {
        val parsed = PlaybackProfile.parse(PlaybackProfile.builtin().toJson())
        assertEquals(PlaybackProfile.builtin().toJson(), parsed.toJson())
    }

    @Test
    fun `未知 schema 一律拒收`() {
        reject("schema=2", mutated { it.put("schema", 2) })
        reject("schema=0", mutated { it.put("schema", 0) })
    }

    @Test
    fun `revision 必须落在合法区间`() {
        reject("revision=0", mutated { it.put("revision", 0) })
        reject("revision=-1", mutated { it.put("revision", -1) })
        reject("revision 过大", mutated { it.put("revision", PlaybackProfile.MAX_REVISION + 1) })
        assertEquals(9, PlaybackProfile.parse(mutated { it.put("revision", 9) }).revision)
    }

    @Test
    fun `v5 十六进制长度必须精确`() {
        reject("magic 少一字节", mutated { it.getJSONObject("v5").put("magic", "2d4b4f") })
        reject("signKey 多一字节", mutated { it.getJSONObject("v5").put("signKey", "ab".repeat(33)) })
        reject("sbox 不足 1024 字节", mutated { it.getJSONObject("v5").put("sbox", "ab".repeat(512)) })
        reject("sbox 非十六进制", mutated { it.getJSONObject("v5").put("sbox", "zz".repeat(1024)) })
    }

    @Test
    fun `v5 数值与版本串必须合法`() {
        reject("field21 越界", mutated { it.getJSONObject("v5").put("field21", 70000) })
        reject("numericVersion=0", mutated { it.getJSONObject("v5").put("numericVersion", 0) })
        reject("envVersion 带空格", mutated { it.getJSONObject("v5").put("envVersion", "v04 10 03") })
        reject("messageVersion 为空", mutated { it.getJSONObject("v5").put("messageVersion", "") })
        reject("license 为空", mutated { it.getJSONObject("v5").put("license", "") })
    }

    @Test
    fun `门地址必须形如裸域名加绝对路径`() {
        reject("host 带 scheme", mutated { it.getJSONObject("gates").getJSONObject("model").put("hosts", JSONArray().put("https://a.example.com")) })
        reject("host 带端口", mutated { it.getJSONObject("gates").getJSONObject("model").put("hosts", JSONArray().put("a.example.com:443")) })
        reject("host 大写", mutated { it.getJSONObject("gates").getJSONObject("model").put("hosts", JSONArray().put("API.example.com")) })
        reject("hosts 为空", mutated { it.getJSONObject("gates").getJSONObject("model").put("hosts", JSONArray()) })
        reject("hosts 重复", mutated { it.getJSONObject("gates").getJSONObject("model").put("hosts", JSONArray().put("a.example.com").put("a.example.com")) })
        reject("path 不以 / 开头", mutated { it.getJSONObject("gates").getJSONObject("model").put("path", "novel/player") })
        reject("path 含查询串", mutated { it.getJSONObject("gates").getJSONObject("model").put("path", "/novel?x=1") })
        reject("path 含空格", mutated { it.getJSONObject("gates").getJSONObject("model").put("path", "/novel player") })
    }

    @Test
    fun `缺门或缺 UA 一律拒收`() {
        reject("缺 tab 门", mutated { it.getJSONObject("gates").remove("tab") })
        reject("缺 ua", mutated { it.remove("ua") })
        reject("缺 gates", mutated { it.remove("gates") })
        reject("playback UA 太短", mutated { it.getJSONObject("ua").put("playback", "x") })
        reject("UA 含中文", mutated { it.getJSONObject("ua").put("app", "com.phoenix.read/72232 测试 (Linux)") })
    }

    @Test
    fun `超大档案在解析前就被拒`() {
        val huge = "{\"schema\":1,\"revision\":1,\"note\":\"" + "x".repeat(PlaybackProfile.MAX_BYTES) + "\"}"
        reject("超过 64KB", huge)
    }

    @Test
    fun `非 JSON 内容不会抛未捕获异常`() {
        reject("纯文本", "not json at all")
        reject("空串", "")
        reject("截断的 JSON", "{\"schema\":1,")
    }

    @Test
    fun `只有 revision 严格变大才生效`() {
        val builtin = PlaybackProfile.builtin()
        assertFalse("同 revision 不采纳", PlaybackProfiles.apply(PlaybackProfile.parse(mutated { it.put("revision", builtin.revision) })))
        assertTrue("更大的 revision 采纳", PlaybackProfiles.apply(PlaybackProfile.parse(mutated { it.put("revision", builtin.revision + 1) })))
        assertEquals(PlaybackProfiles.Source.CACHE, PlaybackProfiles.currentSource)
        assertFalse("回退的小 revision 不采纳", PlaybackProfiles.apply(PlaybackProfile.parse(mutated { it.put("revision", builtin.revision) })))
        assertEquals(builtin.revision + 1, PlaybackProfiles.revision)
    }

    @Test
    fun `总开关关掉后远端档案不再生效`() {
        PlaybackProfiles.enabled = false
        assertFalse(PlaybackProfiles.apply(PlaybackProfile.parse(mutated { it.put("revision", 99) })))
        assertEquals(PlaybackProfile.builtin().revision, PlaybackProfiles.revision)
    }

    @Test
    fun `生效后的取值确实来自档案`() {
        val json = mutated {
            it.put("revision", 42)
            it.getJSONObject("v5").put("magic", "00".repeat(16))
            it.getJSONObject("v5").put("field21", 624)
            it.getJSONObject("ua").put("playback", "com.phoenix.read/99999 (Linux; U; Android 12)")
            it.getJSONObject("gates").getJSONObject("model").put("path", "/novel/player/changed/v1")
        }
        assertTrue(PlaybackProfiles.apply(PlaybackProfile.parse(json)))
        assertEquals("00000000000000000000000000000000", PlaybackProfiles.v5.magic.hex())
        assertEquals(624, PlaybackProfiles.v5.field21)
        assertEquals("com.phoenix.read/99999 (Linux; U; Android 12)", PlaybackProfiles.ua.playback)
        assertEquals("/novel/player/changed/v1", PlaybackProfiles.gate("model").path)
        assertEquals("https://api5-normal-sinfonlinea.fqnovel.com", PlaybackProfiles.gate("model").baseUrl)
    }

    @Test
    fun `重置后回到内置默认`() {
        PlaybackProfiles.apply(PlaybackProfile.parse(mutated { it.put("revision", 50) }))
        PlaybackProfiles.reset()
        assertEquals(PlaybackProfile.builtin().revision, PlaybackProfiles.revision)
        assertEquals(PlaybackProfiles.Source.BUILTIN, PlaybackProfiles.currentSource)
        assertEquals(PlaybackProfile.builtin().toJson(), PlaybackProfiles.snapshot().toJson())
    }
}
