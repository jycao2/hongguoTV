// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONObject

/**
 * 协议档案信封：`payload` 是档案原文的 base64，`signature` 是对 payload **原始字节** 的 ECDSA 签名。
 *
 * 为什么不是「直接给 JSON 签名」：JSON 重新序列化会改键序与空白，客户端无法可靠复现服务端
 * 的字节。这里把「被签名的字节」显式装进 payload，签名与解析各管一段，谁都别猜。
 *
 * 算法用 P-256（`SHA256withECDSA`）：Android 从 API 11 起就有，不依赖 BouncyCastle；
 * Ed25519 要到 API 33 才进 Conscrypt，会把这台电视挡在门外。
 */
object PlaybackProfileEnvelope {
    const val SCHEMA = 1

    /** 编译进 APK 的验签公钥（DER / SubjectPublicKeyInfo，base64）。 */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERPccEmsfyNcGKYx3K3CUZ7hIF4dwItxzbcOE2qPpjyz3/wpCCP5OrK/nCyv7Zfy/oDfbSIR+pDP8M7zadj0S4g=="

    fun decode(text: String): PlaybackProfile {
        require(text.toByteArray(Charsets.UTF_8).size <= PlaybackProfile.MAX_BYTES * 2) { "协议档案信封过大" }
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("协议档案信封不是合法 JSON：${e.message}")
        }
        require(root.optInt("schema", -1) == SCHEMA) { "协议档案信封版本不认识" }
        val payloadText = root.optString("payload", "")
        val signatureText = root.optString("signature", "")
        require(payloadText.isNotEmpty() && signatureText.isNotEmpty()) { "协议档案信封缺 payload 或 signature" }
        val payload = try {
            Base64.getDecoder().decode(payloadText)
        } catch (e: Exception) {
            throw IllegalArgumentException("协议档案 payload 不是合法的 base64")
        }
        require(payload.size <= PlaybackProfile.MAX_BYTES) { "协议档案 payload 过大" }
        require(verify(payload, signatureText)) { "协议档案签名校验不过" }
        val profile = PlaybackProfile.parse(payload.toString(Charsets.UTF_8))
        require(profile.revision == root.optInt("revision", -1)) { "协议档案信封与正文的 revision 不一致" }
        return profile
    }

    fun verify(payload: ByteArray, signatureText: String): Boolean = try {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey())
        verifier.update(payload)
        verifier.verify(Base64.getDecoder().decode(signatureText))
    } catch (_: Exception) {
        false
    }

    private fun publicKey(): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
}
