// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 门地址：若干等价主机（按顺序试）+ 路径 + 该门专用的 UA。主机只存裸域名，协议固定 https。 */
data class GateProfile(val hosts: List<String>, val path: String, val userAgent: String) {
    val host: String get() = hosts.first()
    val baseUrl: String get() = "https://$host"
    val url: String get() = baseUrl + path
}

/** 各链路的 UA。站方改版本指纹时，靠档案换，不用发版。 */
data class UaProfile(val app: String, val video: String, val playback: String, val tab: String)

/** v5（v04.10.03）签名常量与 Medusa 报文明文。 */
data class V5Profile(
    val magic: ByteArray,
    val license: String,
    val signKey: ByteArray,
    val sbox: ByteArray,
    val field21: Int,
    val envVersion: String,
    val messageVersion: String,
    val numericVersion: Int,
    /** Medusa 报文里的 aid（设备 proto 与 message 各出现一次）。 */
    val aid: String,
    /** 设备 proto f6：权限占位串。 */
    val perm: String,
    /** 设备 proto f12：时区串。 */
    val tz: String,
    /** 设备 proto f13：语言串。 */
    val lang: String,
    /** 设备 proto f31/f34：厂商串。 */
    val deviceMake: String,
    /** message f24：播放器指标 JSON（原样进报文）。 */
    val metricsJson: String,
    /** message f20：媒体标签。 */
    val mediaTag: String,
)

/**
 * 旧六头链路（搜索/评论/发现/推荐/计划等）查询串的设备档案字段表。
 *
 * 值支持运行时占位符（见 [PLACEHOLDERS]），取用时由 [PlaybackProfiles.deviceTable]
 * 以当前安装身份解析。键集合必须与内置完全一致：多一键、少一键都整份拒收 ——
 * 站方按字段全集校验指纹，静默少一个字段就是 200 + 0。
 */
data class DeviceProfile(val fields: Map<String, String>) {
    fun resolve(bindings: Map<String, String>): Map<String, String> = linkedMapOf<String, String>().apply {
        for ((key, value) in fields) {
            var resolved = value
            for ((name, binding) in bindings) resolved = resolved.replace("{$name}", binding)
            put(key, resolved)
        }
    }
}

/**
 * 取址协议档案：把「站方会改的那部分数据」从代码里搬出来，端上可远程覆盖。
 *
 * 边界刻意划死：**只描述「说什么」，不描述「怎么算」**。AesV3/sm3/xmxor 的表与位序、
 * 随机量的取法与顺序、protobuf 编码、路由降级顺序与冷却，全部留在代码里 —— 那些站方
 * 一次都没改过，外置它们等于给签名多加一个出错的地方。
 *
 * 坏档案一律整份拒收（不做局部修补），因为半对的档案比没有档案更危险：
 * 它会静默产出 HTTP 200 + 0 字节，而端上没有任何报错可看。
 */
class PlaybackProfile(
    val revision: Int,
    val updatedAt: String,
    val note: String,
    val ua: UaProfile,
    val v5: V5Profile,
    val device: DeviceProfile,
    val gates: Map<String, GateProfile>,
) {
    fun gate(name: String): GateProfile =
        gates[name] ?: throw IllegalArgumentException("协议档案缺少门：$name")

    /** 规范化序列化：键序固定、缩进固定，导出器与一致性测试都以此为准。 */
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"schema\": ").append(SCHEMA).append(",\n")
        append("  \"revision\": ").append(revision).append(",\n")
        append("  \"updatedAt\": ").append(quote(updatedAt)).append(",\n")
        append("  \"note\": ").append(quote(note)).append(",\n")
        append("  \"ua\": {\n")
        append("    \"app\": ").append(quote(ua.app)).append(",\n")
        append("    \"video\": ").append(quote(ua.video)).append(",\n")
        append("    \"playback\": ").append(quote(ua.playback)).append(",\n")
        append("    \"tab\": ").append(quote(ua.tab)).append("\n")
        append("  },\n")
        append("  \"v5\": {\n")
        append("    \"magic\": ").append(quote(v5.magic.hex())).append(",\n")
        append("    \"license\": ").append(quote(v5.license)).append(",\n")
        append("    \"signKey\": ").append(quote(v5.signKey.hex())).append(",\n")
        append("    \"sbox\": ").append(quote(v5.sbox.hex())).append(",\n")
        append("    \"field21\": ").append(v5.field21).append(",\n")
        append("    \"envVersion\": ").append(quote(v5.envVersion)).append(",\n")
        append("    \"messageVersion\": ").append(quote(v5.messageVersion)).append(",\n")
        append("    \"numericVersion\": ").append(v5.numericVersion).append(",\n")
        append("    \"aid\": ").append(quote(v5.aid)).append(",\n")
        append("    \"perm\": ").append(quote(v5.perm)).append(",\n")
        append("    \"tz\": ").append(quote(v5.tz)).append(",\n")
        append("    \"lang\": ").append(quote(v5.lang)).append(",\n")
        append("    \"deviceMake\": ").append(quote(v5.deviceMake)).append(",\n")
        append("    \"metricsJson\": ").append(quote(v5.metricsJson)).append(",\n")
        append("    \"mediaTag\": ").append(quote(v5.mediaTag)).append("\n")
        append("  },\n")
        append("  \"device\": {\n")
        device.fields.entries.forEachIndexed { index, entry ->
            append("    ").append(quote(entry.key)).append(": ").append(quote(entry.value))
            if (index != device.fields.size - 1) append(",")
            append("\n")
        }
        append("  },\n")
        append("  \"gates\": {\n")
        val names = GATE_KEYS.filter { gates.containsKey(it) } + gates.keys.filter { it !in GATE_KEYS }.sorted()
        names.forEachIndexed { index, name ->
            val gate = gates.getValue(name)
            append("    ").append(quote(name)).append(": {\n")
            append("      \"hosts\": [")
            gate.hosts.forEachIndexed { i, host ->
                if (i > 0) append(", ")
                append(quote(host))
            }
            append("],\n")
            append("      \"path\": ").append(quote(gate.path)).append(",\n")
            append("      \"userAgent\": ").append(quote(gate.userAgent)).append("\n")
            append("    }")
            if (index != names.lastIndex) append(",")
            append("\n")
        }
        append("  }\n")
        append("}\n")
    }

    companion object {
        const val SCHEMA = 1
        /** 档案大小上限：正常一份不到 8 KB，超过这个数一定是坏东西。 */
        const val MAX_BYTES = 64 * 1024
        const val MAX_REVISION = 1_000_000
        /** 远端档案的地址（客户端会依次套 [AppUpdate.MIRRORS] 里的镜像重试）。 */
        const val REMOTE_PATH = "jycao2/hongguoTV/main/playback-profile.json"

        val GATE_KEYS = listOf("model", "flat", "preload", "current", "detail", "tab")

        /** 设备档案允许的运行时占位符。 */
        val PLACEHOLDERS = listOf("iid", "device_id", "version_code", "version_name")

        /** 设备档案字段表：键序固定（= 官方客户端抓包序），键集合必须与内置完全一致。 */
        val DEVICE_KEYS = listOf(
            "iid", "device_id", "ac", "channel", "aid", "app_name", "version_code", "version_name",
            "device_platform", "os", "ssmix", "device_type", "device_brand", "language", "os_api",
            "os_version", "manifest_version_code", "resolution", "dpi", "update_version_code",
            "host_abi", "dragon_device_type", "pv_player", "compliance_status",
            "need_personal_recommend", "player_so_load", "is_android_pad_screen",
        )

        private val HOST_PATTERN = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val VERSION_PATTERN = Regex("^[A-Za-z0-9._-]{1,40}$")
        private val TZ_PATTERN = Regex("^[A-Za-z0-9/_,+-]{1,40}$")
        private val LANG_PATTERN = Regex("^[a-z]{2}(_[A-Za-z]+)?$")

        /**
         * 解析并校验。任何一处不合法都抛异常，调用方据此整份丢弃 ——
         * 宁可继续用内置默认，也不拿半对的档案去签名。
         */
        fun parse(text: String): PlaybackProfile {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "协议档案过大" }
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("协议档案不是合法 JSON：${e.message}")
            }
            require(root.optInt("schema", -1) == SCHEMA) { "协议档案版本不认识" }
            val revision = root.optInt("revision", -1)
            require(revision in 1..MAX_REVISION) { "协议档案版本号非法：$revision" }
            val updatedAt = root.optString("updatedAt", "")
            require(updatedAt.isEmpty() || DATE_PATTERN.matches(updatedAt)) { "协议档案日期非法：$updatedAt" }
            val note = root.optString("note", "")
            require(note.length <= 400) { "协议档案说明过长" }

            val ua = root.optJSONObject("ua") ?: throw IllegalArgumentException("协议档案缺 ua")
            val uaProfile = UaProfile(
                app = ua.requireUa("app"),
                video = ua.requireUa("video"),
                playback = ua.requireUa("playback"),
                tab = ua.requireUa("tab"),
            )

            val v5 = root.optJSONObject("v5") ?: throw IllegalArgumentException("协议档案缺 v5")
            val v5Profile = V5Profile(
                magic = v5.requireHex("magic", 16),
                license = v5.requireText("license", 1, 32) { VERSION_PATTERN.matches(it) },
                signKey = v5.requireHex("signKey", 32),
                sbox = v5.requireHex("sbox", 1024),
                field21 = v5.optInt("field21", -1).also { require(it in 0..0xffff) { "v5.field21 非法：$it" } },
                envVersion = v5.requireText("envVersion", 1, 40) { VERSION_PATTERN.matches(it) },
                messageVersion = v5.requireText("messageVersion", 1, 40) { VERSION_PATTERN.matches(it) },
                numericVersion = v5.optInt("numericVersion", -1)
                    .also { require(it in 1..Int.MAX_VALUE) { "v5.numericVersion 非法：$it" } },
                aid = v5.requireText("aid", 1, 10) { it.all { c -> c in '0'..'9' } },
                perm = v5.requireText("perm", 1, 20) { it.all { c -> c.code in 33..126 } },
                tz = v5.requireText("tz", 1, 40) { TZ_PATTERN.matches(it) },
                lang = v5.requireText("lang", 1, 20) { LANG_PATTERN.matches(it) },
                deviceMake = v5.requireText("deviceMake", 1, 20) { it.all { c -> c.code in 33..126 } },
                metricsJson = v5.requireText("metricsJson", 2, 300) {
                    it.startsWith("{") && it.endsWith("}") && it.all { c -> c.code in 32..126 }
                },
                mediaTag = v5.requireText("mediaTag", 1, 20) { it.all { c -> c.code in 33..126 } },
            )

            val deviceJson = root.optJSONObject("device") ?: throw IllegalArgumentException("协议档案缺 device")
            val device = parseDevice(deviceJson)

            val gatesJson = root.optJSONObject("gates") ?: throw IllegalArgumentException("协议档案缺 gates")
            val gates = linkedMapOf<String, GateProfile>()
            for (key in GATE_KEYS) {
                val gate = gatesJson.optJSONObject(key)
                    ?: throw IllegalArgumentException("协议档案缺少门：$key")
                gates[key] = parseGate(key, gate)
            }

            return PlaybackProfile(revision, updatedAt, note, uaProfile, v5Profile, device, gates)
        }

        private fun parseDevice(json: JSONObject): DeviceProfile {
            val keys = json.keys().asSequence().toList()
            require(keys.toSet() == DEVICE_KEYS.toSet()) {
                val unknown = keys.filter { it !in DEVICE_KEYS }
                val missing = DEVICE_KEYS.filter { it !in keys }
                "device 字段表键集合不一致（多 $unknown / 缺 $missing）"
            }
            val fields = linkedMapOf<String, String>()
            for (key in DEVICE_KEYS) {
                val value = json.optString(key, "")
                require(value.isNotEmpty() && value.length <= 80) { "device.$key 长度非法" }
                require(value.all { it.code in 32..126 }) { "device.$key 含非 ASCII 字符" }
                // 占位符只允许已知身份字段，防止把未知 {xxx} 原样发出去当指纹。
                val placeholders = Regex("\\{([a-z_]+)\\}").findAll(value).map { it.groupValues[1] }.toList()
                require(placeholders.all { it in PLACEHOLDERS }) { "device.$key 含未知占位符：$placeholders" }
                fields[key] = value
            }
            return DeviceProfile(fields)
        }

        private fun parseGate(name: String, json: JSONObject): GateProfile {
            val hostsJson: JSONArray = json.optJSONArray("hosts")
                ?: throw IllegalArgumentException("门 $name 缺 hosts")
            require(hostsJson.length() in 1..4) { "门 $name 的 hosts 数量非法：${hostsJson.length()}" }
            val hosts = (0 until hostsJson.length()).map { i ->
                val host = hostsJson.optString(i, "")
                require(HOST_PATTERN.matches(host)) { "门 $name 的 host 非法：$host" }
                host
            }
            require(hosts.distinct().size == hosts.size) { "门 $name 的 hosts 有重复" }
            val path = json.optString("path", "")
            require(path.startsWith("/") && path.length in 2..300) { "门 $name 的 path 非法：$path" }
            require(path.none { it.isWhitespace() || it == '?' || it == '#' }) { "门 $name 的 path 含非法字符：$path" }
            val userAgent = json.requireUa("userAgent", "门 $name")
            return GateProfile(hosts, path, userAgent)
        }

        private fun JSONObject.requireUa(key: String, where: String = "ua"): String {
            val value = optString(key, "")
            require(value.length in 8..512) { "$where.$key 长度非法" }
            require(value.all { it.code in 32..126 }) { "$where.$key 含非 ASCII 字符" }
            require(value.startsWith("com.phoenix.read/") || value.startsWith("Mozilla/")) {
                "$where.$key 形态不认识：${value.take(32)}"
            }
            return value
        }

        private fun JSONObject.requireText(
            key: String,
            min: Int,
            max: Int,
            allow: (String) -> Boolean,
        ): String {
            val value = optString(key, "")
            require(value.length in min..max) { "$key 长度非法" }
            require(allow(value)) { "$key 形态非法：$value" }
            return value
        }

        private fun JSONObject.requireHex(key: String, bytes: Int): ByteArray {
            val value = optString(key, "")
            require(value.length == bytes * 2) { "$key 必须是 ${bytes} 字节（${bytes * 2} 位十六进制）" }
            require(value.all { it in "0123456789abcdefABCDEF" }) { "$key 不是合法的十六进制" }
            return hex(value)
        }

        /** 内置默认：当前实测形态。档案改坏了也退回到这里，行为与上一版完全一致。 */
        fun builtin(): PlaybackProfile = PlaybackProfile(
            revision = 2,
            updatedAt = "2026-11-27",
            note = "内置默认：2026-11-27 实网形态（v5 三头 + reading.snssdk.com 四门 + 27 字段设备档案）",
            ua = UaProfile(
                app = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)",
                video = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
                playback = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                tab = "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
            ),
            v5 = V5Profile(
                magic = hex("2d4b4fca49750d433fb5ae2c226dcc56"),
                license = "646637794",
                signKey = hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d"),
                sbox = hex(V5_SBOX_HEX),
                field21 = 312,
                envVersion = "v04.10.03.04-bugfix",
                messageVersion = "v04.10.03-ml-android",
                numericVersion = 0x040a0300,
                aid = "8662",
                perm = "!noperm!",
                tz = "Asia/Shanghai,8",
                lang = "zh_CN",
                deviceMake = "HONOR",
                metricsJson = """{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}""",
                mediaTag = "none",
            ),
            device = DeviceProfile(linkedMapOf(
                "iid" to "{iid}",
                "device_id" to "{device_id}",
                "ac" to "wifi",
                "channel" to "xiaomi_8662_64",
                "aid" to "8662",
                "app_name" to "novelread",
                "version_code" to "{version_code}",
                "version_name" to "{version_name}",
                "device_platform" to "android",
                "os" to "android",
                "ssmix" to "a",
                "device_type" to "23127PN0CC",
                "device_brand" to "Xiaomi",
                "language" to "zh",
                "os_api" to "34",
                "os_version" to "14",
                "manifest_version_code" to "{version_code}",
                "resolution" to "1200*2670",
                "dpi" to "460",
                "update_version_code" to "{version_code}",
                "host_abi" to "arm64-v8a",
                "dragon_device_type" to "phone",
                "pv_player" to "{version_code}",
                "compliance_status" to "0",
                "need_personal_recommend" to "1",
                "player_so_load" to "1",
                "is_android_pad_screen" to "0",
            )),
            gates = linkedMapOf(
                "model" to GateProfile(
                    listOf("api5-normal-sinfonlinea.fqnovel.com"),
                    "/novel/player/multi_video_model/player/v1",
                    "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                ),
                "flat" to GateProfile(
                    listOf("api5-normal-sinfonlinea.fqnovel.com"),
                    "/novel/player/multi_video_model/v1",
                    "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                ),
                "preload" to GateProfile(
                    listOf("reading.snssdk.com"),
                    "/novel/player/multi_video_model/preload/v1",
                    "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                ),
                "current" to GateProfile(
                    listOf("reading.snssdk.com"),
                    "/novel/player/video_model/v1/",
                    "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                ),
                "detail" to GateProfile(
                    listOf("reading.snssdk.com"),
                    "/novel/player/video_detail/v1/",
                    "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                ),
                "tab" to GateProfile(
                    listOf("reading.snssdk.com"),
                    "/reading/bookapi/video_tab/multi_video_model/v1/",
                    "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                ),
            ),
        )

        /** 1024 字节替换盒（4 组 × 256），v5 的 AesV3 用。 */
        private const val V5_SBOX_HEX =
            "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0df37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1dd12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912" +
                    "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c894545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc" +
                    "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8de0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd288fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5" +
                    "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac91fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443fd830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f44591681335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e"
    }
}

private fun quote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
