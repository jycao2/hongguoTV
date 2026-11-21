// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hongguotv.nativeapp.vc2

import android.content.Context
import android.view.Surface
import androidx.media3.extractor.ExtractorsFactory

/**
 * ByteVC2 软解能力的抽象边界（编译开关）。
 *
 * 真实实现只存在于带 -Pvc2test 的构建里（src/vc2/kotlin，永不入库——入库等于公开解码
 * 实现的调用契约与 so 本体，见 2026-11-21 资产隔离决策）。无解码器构建（CI/正式版）
 * 使用 [Vc2ApiNoop]，本文件与所有调用方只依赖此接口，不出现任何 decoder 包引用。
 *
 * 无解码器构建的运行时行为：bvc2 档位由调用方按能力判断优雅跳过，
 * 整集只剩 bvc2 时走既有「不兼容片源 + 自动跳过」链路，与线上历史版本一致。
 */
interface Vc2Api {
    /** 融合开关（自建 Extractor/Renderer 融进同一个 ExoPlayer）。 */
    val fused: Boolean

    /** NDK 解码器 so 是否可加载。 */
    val decoderAvailable: Boolean

    /** 融合 ExtractorsFactory（嗅探式接管 bvc2 码流）；融合关闭或无解码器返回 null。 */
    fun extractorsFactory(): ExtractorsFactory?

    /** 融合 RenderersFactory；融合关闭或无解码器返回 null（调用方回落 DefaultRenderersFactory）。
     *  返回 DefaultRenderersFactory 而非接口：setEnableDecoderFallback 定义在具体类上。 */
    fun renderersFactory(context: Context): androidx.media3.exoplayer.DefaultRenderersFactory?

    /** 软解性能看门狗：软解渲染持续落后时回调 (width,height)，接降档链。 */
    var onVideoTooSlow: ((Int, Int) -> Unit)?

    /** 原生软解模式：打开解码器。返回 null = 无解码器或打开失败。 */
    fun openDecoder(path: String, keyHex: String): Vc2NativeDecoder?
}

/** 原生软解模式的解码器句柄（JNI 门面的最小接口，隐藏 Bvc2Decoder 具体类型）。 */
interface Vc2NativeDecoder : AutoCloseable {
    fun setSurface(surface: Surface?)
    /** 解出下一帧，返回 pts（微秒）；<0 表示流结束或出错。 */
    fun decodeVideo(): Long
    fun renderFrame()
    fun seek(positionUs: Long)
}

/** 无解码器构建的空实现：所有能力不可用。 */
object Vc2ApiNoop : Vc2Api {
    override val fused = false
    override val decoderAvailable = false
    override fun extractorsFactory(): ExtractorsFactory? = null
    override fun renderersFactory(context: Context): androidx.media3.exoplayer.DefaultRenderersFactory? = null
    override var onVideoTooSlow: ((Int, Int) -> Unit)? = null
    override fun openDecoder(path: String, keyHex: String): Vc2NativeDecoder? = null
}

/** 统一入口：vc2test 构建通过反射拿到真实实现（R8 由 keep 规则保护），否则回落空实现。 */
object Vc2 {
    val api: Vc2Api by lazy {
        try {
            val provider = Class.forName("com.hongguotv.nativeapp.vc2.Vc2ApiProvider")
            provider.getDeclaredField("INSTANCE").get(null) as Vc2Api
        } catch (_: Throwable) {
            Vc2ApiNoop
        }
    }
}
