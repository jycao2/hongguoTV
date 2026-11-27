// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 协议档案导出器：从 [PlaybackProfile.builtin] 生成仓库里那两份文件。
 *
 * 为什么用「从代码导出」而不是手写 JSON：档案一旦手写就一定会和代码漂移，
 * 而漂移的表现是「签名对了但字段少一个」——线上静默 200 + 0 字节，谁也看不出来。
 * 这里让代码做唯一事实来源，导出与签名一条命令完成。
 *
 * 用法（在 `kotlin-tv/` 下）：
 * ```
 * PROFILE_SRC=../playback-profile.src.json \
 * PROFILE_OUT=../playback-profile.json \
 * PROFILE_KEY=tools/profile-key.pem \
 * ./gradlew :core:test --tests '*PlaybackProfileExportTool*' --rerun-tasks
 * ```
 * 只改数据、忘了给 revision +1 时，端上不会采纳新档案 —— [PlaybackProfileConsistencyTest]
 * 会盯着 `revision` 与文件内容是否对得上。
 */
class PlaybackProfileExportTool {
    @Test
    fun export() {
        val srcPath = System.getenv("PROFILE_SRC")
        val outPath = System.getenv("PROFILE_OUT")
        val keyPath = System.getenv("PROFILE_KEY")
        assumeTrue("未设置 PROFILE_* 环境变量，跳过导出（正常单测不受影响）", srcPath != null && outPath != null && keyPath != null)

        val profile = PlaybackProfile.builtin()
        val source = profile.toJson()
        File(srcPath!!).writeText(source, Charsets.UTF_8)

        val payload = source.toByteArray(Charsets.UTF_8)
        val signature = sign(payload, File(keyPath!!))
        val envelope = buildString {
            append("{\n")
            append("  \"schema\": ").append(PlaybackProfileEnvelope.SCHEMA).append(",\n")
            append("  \"revision\": ").append(profile.revision).append(",\n")
            append("  \"updatedAt\": \"").append(profile.updatedAt).append("\",\n")
            append("  \"signature\": \"").append(signature).append("\",\n")
            append("  \"payload\": \"").append(Base64.getEncoder().encodeToString(payload)).append("\"\n")
            append("}\n")
        }
        File(outPath!!).writeText(envelope, Charsets.UTF_8)

        // 写完立刻自检一遍：导出器自己都解不开的档案，推上去就是事故。
        val decoded = PlaybackProfileEnvelope.decode(File(outPath).readText(Charsets.UTF_8))
        assertTrue(decoded.toJson() == source)
        println("协议档案已导出：revision=${profile.revision} src=$srcPath out=$outPath")
    }

    private fun sign(payload: ByteArray, keyFile: File): String {
        val pem = keyFile.readText(Charsets.UTF_8)
        val body = pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----")
            .replace("\n", "").replace("\r", "").trim()
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }
}
