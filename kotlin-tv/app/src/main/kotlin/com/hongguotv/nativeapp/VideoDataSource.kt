// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo

/**
 * 播放数据源：整集一次性缓存进内存（解密流零重复解密、seek 零网络）。
 *
 * 2026-11-21 引入全量缓存是为 bvc2 融合路径：ExtractingAudioVideo 交错喂 sample 时
 * A/V 在 mdat 里分块交错，轨道切换 = 数据指针回退 = 每次 open() 都重新拉 2MB 解密块。
 *
 * 2026-11-23 修复「起播慢」：原实现把整集下载**塞在 open() 里同步做完**才放行 ExoPlayer，
 * 1080P 一集 8~16MB 在慢网要 3 秒起步（电视实测首帧 2.9~4.8 秒全耗在这）。改成
 * **渐进式填充**：后台线程按 2MB 块下载，read 先服务已到手的字节、不够再等下一块——
 * 首帧只要头部几百 KB（百毫秒级），其余边播边填。bvc2 的「整集在内存」语义不变，
 * 只是填充从阻塞变成后台；小文件几百毫秒内就填完，行为与旧版一致。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDataSource(private val video: RemoteVideo): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var opened=false
    private var uri: Uri?=null

    // 渐进缓存：后台线程按块填充，read 服务已填充前缀、不足时等下一块
    private var full: ByteArray?=null
    private var filled=0
    private var loader: Thread?=null
    private var loadError: java.io.IOException?=null
    private val lock=Object()

    /** 启动后台填充线程（幂等）。RemoteVideo.read 单次上限 2MB，必须分块拉满整集
     *  （2026-11-21 教训：一次传 3MB 直接 IllegalArgumentException → Source error）。 */
    private fun ensureLoader() {
        if(loader!=null) return
        val total=video.total
        if(total<=0) throw java.io.IOException("视频长度未知，无法缓存整集")
        val buf=ByteArray(total.toInt())
        full=buf
        loader=Thread({
            var p=0
            try {
                while(p<total) {
                    val n=minOf(2*1024*1024,(total-p).toInt())
                    val seg=video.read(p.toLong(),n)
                    if(seg.isEmpty()) throw java.io.EOFException("整集缓存提前结束 at $p/$total")
                    synchronized(lock) {
                        System.arraycopy(seg,0,buf,p,minOf(seg.size,(total-p).toInt()))
                        p+=seg.size
                        filled=p
                        lock.notifyAll()
                    }
                    if(seg.size<n) break
                }
                Log.i("HongguoTV","VideoDataSource full stream ${(total/1024)}KB cached")
            } catch(problem: java.io.IOException) {
                synchronized(lock) { loadError=problem; lock.notifyAll() }
            } catch(problem: Exception) {
                synchronized(lock) { loadError=java.io.IOException(problem.message ?: "缓存线程异常",problem); lock.notifyAll() }
            }
        },"VideoDataSource-loader").also { it.start() }
    }

    /** 等到 [pos] 位置的字节可用（或加载失败/流结束）。返回 false=已到文件尾且不再有数据。 */
    private fun await(pos: Long): Boolean {
        val deadline=android.os.SystemClock.elapsedRealtime()+30_000L
        while(true) {
            synchronized(lock) {
                if(filled>pos) return true
                if(filled>=video.total) return false          // 整集填完且 pos 越界
                loadError?.let { throw it }
                val wait=deadline-android.os.SystemClock.elapsedRealtime()
                if(wait<=0) throw java.io.IOException("起播数据等待超时（填充停滞于 $filled）")
                runCatching { lock.wait(wait) }
            }
        }
    }

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec); uri=spec.uri; position=spec.position
        ensureLoader()
        if(!await(spec.position)) throw java.io.IOException("读取位置超出视频长度 ${spec.position}")
        val data=full!!
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) data.size.toLong()-position else minOf(spec.length,data.size.toLong()-position)
        if(position>data.size.toLong()) throw java.io.IOException("读取位置超出视频长度")
        transferStarted(spec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        val data=full!!
        if(!await(position)) return C.RESULT_END_OF_INPUT
        val count=minOf(length.toLong(),remaining, filled-position).toInt()
        if(count<=0) return C.RESULT_END_OF_INPUT
        System.arraycopy(data,position.toInt(),target,targetOffset,count)
        position+=count; remaining-=count; bytesTransferred(count); return count
    }
    override fun getUri()=uri
    override fun close() { uri=null; if(opened) { opened=false; transferEnded() } }
}
