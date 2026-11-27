// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 签名黄金向量：把「改造前」的实测输出钉死。
 *
 * 协议档案（playback-profile）把常量搬到可远程下发的数据里之后，签名结果必须与改造前
 * **逐字节一致** —— 否则线上会从「能播」变成「HTTP 200 + 0 字节」，而且没有任何报错可看。
 * 这里用固定时钟 + 固定随机序列，锁住 x-medusa / x-helios / 旧六头的输出。
 *
 * 设环境变量 `GOLDEN_OUT` 时把当前值写到该文件（用于重新生成基线），否则做断言。
 */
class SignerGoldenTest {
    private val clock = 1_764_000_000_000L

    /** 进程级指纹是随机量，必须钉死，否则黄金向量每次跑都不一样。 */
    @org.junit.Before
    fun pinProcessFingerprint() {
        VendorConstants.processNonce = "golden-nonce-000000"
        VendorConstants.processStarted = 1_764_000_000_000L
        VendorConstants.versionCode = "74332"
        VendorConstants.versionName = "7.4.3.32"
    }

    private fun signer(): Signer {
        var n = 0
        return Signer(
            clock = { clock },
            device = mapOf(
                "device_id" to "1234567890",
                "os_version" to "12",
                "device_type" to "PGT-AN10",
                "channel" to "vivo_8662_64",
                "version_name" to "7.4.5.32",
            ),
            random = { n += 1; (n * 0.0123) % 1.0 },
        )
    }

    private val values = linkedMapOf(
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "74532",
        "version_name" to "7.4.5.32",
        "channel" to "vivo_8662_64",
        "device_platform" to "android",
        "device_type" to "PGT-AN10",
        "os_version" to "12",
        "update_version_code" to "74532",
        "device_id" to "1234567890",
        "iid" to "1111111111111111111",
        "cdid" to "22222222-2222-2222-2222-222222222222",
    )

    private val body = """{"biz_param":"{\"caller_scene\":\"single_col\"}","video_id":"7001"}""".toByteArray()

    private fun current(): String = buildString {
        val v5 = signer().signPlaybackV5(
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1",
            values, body,
        )
        appendLine("v5.url=" + v5.url.substringAfter('?'))
        appendLine("v5.medusa=" + v5.headers.getValue("x-medusa"))
        appendLine("v5.helios=" + v5.headers.getValue("x-helios"))

        val legacy = signer().sign("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1", values, body)
        appendLine("legacy.url=" + legacy.url.substringAfter('?'))
        appendLine("legacy.medusa=" + legacy.headers.getValue("x-medusa"))
        appendLine("legacy.gorgon=" + legacy.headers.getValue("x-gorgon"))
        appendLine("legacy.argus=" + legacy.headers.getValue("x-argus"))
        appendLine("legacy.ladon=" + legacy.headers.getValue("x-ladon"))
    }

    @Test
    fun `签名输出与改造前逐字节一致`() {
        val actual = current()
        val out = System.getenv("GOLDEN_OUT")
        if (out != null) {
            File(out).writeText(actual)
            println("golden 基线已写入 $out")
            return
        }
        assertEquals(GOLDEN, actual.trimEnd('\n'))
    }

    private companion object {
        val GOLDEN = """v5.url=aid=8662&app_name=novelread&version_code=74532&version_name=7.4.5.32&channel=vivo_8662_64&device_platform=android&device_type=PGT-AN10&os_version=12&update_version_code=74532&device_id=1234567890&iid=1111111111111111111&cdid=22222222-2222-2222-2222-222222222222&_rticket=1764000000045
v5.medusa=BYEkaS3Ka6NJ9CkqPzSKRSLs6D9FRwABgoomX5gMgZgHWEeGdDrQcBpMlvaynqoGLwpQNwo119jTvCwSY23Q8frkQK1hSjg1pbhRnsaEnrElynOtgFre+l5bPrhdJFiBSCWc56vBb2h0pbPSPwB3DBuTiYjv6kjKWeu58T9FUxhB+yjdZwNRjWPzX9j4o0iCJlt2RJNUGr/wTq3oGrC0AOmILu7nfanfYVEJlivn7gBMnVqJuiFg1t0T4GXbLWrHRxmkbVgKy4uamSabzyUOfgcvhTyzoxgb+OtzBR8XhcwVf9MVZf7Q0JdXAJJEw8s/ZlGZU2Ox3qaQNRpRfC6y2J5VB/wuap155czfp0B2/R4i6bQsD+Vlayxd/CHsHx9VR2mlvx1T4tNHJ0KndqC37Y4Rj/GrpLn1aJqAiHlIl/ed7JJ5vQkuAjQGUmi/XYZv50NvDVilzpH1Ic7QWvBZmwQ/bMsEPDC8HCS3p34K/gtb5hMUcOiSlLoz0t+Q35VD0lEfbHgHT/p7yNgCaHU6QLruAfyOsFSLnRwsYb/EnFTMCBHIVKIuev+KrGPep2CPltqfsFPCGrR4x8oyYdV4vcc2mbn2ho0jWXV4fFPSUNHxh//8b4f//G9yCQ==
v5.helios=wRcmAz33YbLsrsdp4HWyQ4KDRnpcMxpFgdphQfelFppCiQUq
legacy.url=aid=8662&app_name=novelread&version_code=74532&version_name=7.4.5.32&channel=vivo_8662_64&device_platform=android&device_type=PGT-AN10&os_version=12&update_version_code=74532&device_id=1234567890&iid=1111111111111111111&cdid=22222222-2222-2222-2222-222222222222&ts=1764000000&_rticket=1764000000000
legacy.medusa=A4Ekafdpe5PXVvhS1qvsGVdORXGKjgABARUMogoWAw0COPYYtRVvesoO6LMjCGv3W3zz36Xq5qYTx0FULoqBESMvoxUI/BQ1HS6kzAlgr1HO82Gf2R2NnVfuv63hdtg2ITSZxRRw5NiLuM73+dMoBR6C5FEQVEDB+wR5C8UNTP7H4DpfGHOB8MpS//3wPJo+jE+2bhNaQfgfFoLJabFwPAsSPrTKLNB11K1Ksa3/Y8R5SP5F1HULMod4997V27+jmdqRtUhCkc6fMIoT8FNYUW/cqY6B3bvqkJZ/75xaF8i4owmGdTH3zy2wJGwo6V1gI+A1AWEwUt6L8GvYsjvoSYOkirlGXtAEPvXHPMjuhDf9g7pWwSBCozO/A86eqWnPWkRVoBSJAamleSeZ43HreHpt5ig9FubNkw2Y7Zs6Qz3ggsNvSBM9fh5I1m3MNhy79+/Y8KyV9VxMi29UjyKp8IsvDZryiG2fHWolbYZQlu3uhyXPaGvyVkTU9x1/Kv1ph1DqiTwN2golBMwantix3AtMcCi5OZZPSbBzMXo32ytFt746bM998ap2Hr7QnKD+aidYuFS3KHPhjv4U/mcd4EZYu9SHBCVysXSjGzxz/llBMeeOXPgKWCkpi/3cRnDek5pYBijwwQ1Sr7geQNNou6iu3Ca9LNzJS0vGfiU41SUY7y5tK354A6pOIbx2tRiwMNHS7AO4aV2M7sLiRAeliz7U/36zwC/3wIIU+ZgFXSaweYfaFs/xg+sGuahfb4W4ntpUPkzt886Fcmf9f8WdscwvDXTKonR/1I8axc3KEk3Wk6PzG9IQX6Xr7TSH5zf+84RrHFzBJ+E5iUL/JIerivQD5nBhvLlI0wEx24PRqSz82kK5H3qttiSeQNxEmdXhOEDcAqZsEJrLFz3fy+lBgVroDZZD5zEB5peB0N3mbFhI5VpsdyTKo/4cLhEHU9lwaVDGFbB+cgzIrwf43w7/+N8Pv+QS
legacy.gorgon=840426034001e6d7dd4a3fc189addc72527072a8bfc2b0adabe6
legacy.argus=AIEkaQ==
legacy.ladon=aSSBAA=="""
    }
}
