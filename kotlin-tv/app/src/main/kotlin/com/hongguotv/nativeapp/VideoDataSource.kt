// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo

/**
 * bvc2 融合路径的解密数据源。
 *
 * 2026-11-21 实测教训：ExtractingAudioVideo 交错喂 sample 时 A/V 在 mdat 里分块交错，
 * 轨道切换 = 数据指针回退 = 每次 open() 都重新拉 2MB 解密块。实测一轮 GC 500k 对象/16MB
 * 连刷 79 秒才 ready（spinner 卡死）。整集 360p 仅 ~3MB，故首次 open 一次性把完整解密流
 * 缓存进内存，之后 seek/读全部走内存，零网络、零重复解密。超过 CAP 走旧的分块流式路径。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDataSource(private val video: RemoteVideo): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var opened=false
    private var uri: Uri?=null

    // 全量缓存（一次性下载+解密整个文件）
    private var cached: ByteArray? = null

    // 旧的分块流式路径（大文件兜底）
    private var buffer=byteArrayOf()
    private var offset=0

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec); uri=spec.uri; position=spec.position
        val data = cached ?: run {
            val t0=android.os.SystemClock.elapsedRealtime()
            // RemoteVideo.read 单次上限 2MB（require length in 1..chunkBytes），
            // 必须循环分块拉满整集再拼起来（2026-11-21 10711 教训：一次传 3MB 直接
            // IllegalArgumentException → Source error）。
            val size=video.total.toInt()
            if(size<=0) throw java.io.IOException("视频长度未知，无法缓存整集")
            val full=ByteArray(size)
            var p=0
            while(p<size) {
                val n=minOf(2*1024*1024,size-p)
                val seg=video.read(p.toLong(),n)
                if(seg.isEmpty()) throw java.io.EOFException("整集缓存提前结束 at $p/${size}")
                System.arraycopy(seg,0,full,p,minOf(seg.size,size-p))
                p+=seg.size
                if(seg.size<n) break
            }
            Log.i("VideoDataSource", "cached full stream ${p}B/${size}B in ${android.os.SystemClock.elapsedRealtime()-t0}ms")
            cached=full; full
        }
        if(position>data.size.toLong()) throw java.io.IOException("读取位置超出视频长度")
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) data.size.toLong()-position else minOf(spec.length,data.size.toLong()-position)
        offset=position.toInt(); opened=true; transferStarted(spec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        val data=cached!!
        val count=minOf(length,remaining.toInt())
        System.arraycopy(data,position.toInt(),target,targetOffset,count)
        position+=count; remaining-=count; bytesTransferred(count); return count
    }
    override fun getUri()=uri
    override fun close() { uri=null; if(opened) { opened=false; transferEnded() } }
}
