// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** 崩溃日志扫码导出：短寿命局域网下载，只出不进（与 LibraryTransferServer 同一套安全约束，去掉上传通道）。 */
class CrashLogServer(address: String,private val content: String,private val fileName: String,
    private val onExpired: ()->Unit={},ttlMillis: Long=300_000): Closeable {
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hongguotv-crash-expiry",true)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/$token"
    private var downloaded=false
    init {
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,300_000))
        Thread({ serve() },"hongguotv-crash-transfer").apply { isDaemon=true; start() }
    }
    private fun expire() { if(stopped.compareAndSet(false,true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); expiry.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val socket=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                Thread({
                    active=socket
                    runCatching { socket.use { it.soTimeout=10_000; if(!stopped.get()) handle(it) } }
                    active=null
                },"hongguotv-crash-transfer-conn").apply { isDaemon=true; start() }
            }
        } catch(_: Exception) { /* Closing the TV dialog interrupts accept/read. */ }
        finally { if(!stopped.get()) expire() else close() }
    }
    private fun line(input: InputStream,max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read(); if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered(); val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>(); var size=0
        while(true) {
            val next=line(input,4096); size+=next.length+2
            if(size>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            if(headers.put(next.substring(0,at).lowercase(Locale.ROOT),next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/download")) return respond(socket,404,"页面不存在")
        if(foreignOrigin(headers["origin"], local.hostAddress)) return respond(socket,403,"来源不匹配")
        if(headers["sec-fetch-site"]=="cross-site") return respond(socket,403,"来源不匹配")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,page(headers["user-agent"]))
        if((request[0]=="GET" || request[0]=="HEAD") && request[1]=="/$token/download") {
            val headOnly=request[0]=="HEAD"
            if(!headOnly && downloaded) return respond(socket,410,"日志已下载过，请在电视上重新打开二维码")
            respond(socket,200,if(headOnly) "" else content,"text/plain; charset=utf-8","Content-Disposition: attachment; filename=$fileName\r\n")
            if(!headOnly) downloaded=true
            return
        }
        return respond(socket,405,"不支持的操作")
    }
    private fun page(userAgent: String?): String {
        // 与备份恢复同策略：优先用页面内数据在手机本地生成文件，不依赖第二次网络请求（下载器容易被卡住）
        val inline=content.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        return PhoneServerPage.fill("""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 崩溃日志导出</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:540px;margin:32px auto;padding:20px;line-height:1.6}button{display:block;background:#ff634c;color:white;border:0;border-radius:8px;padding:14px;text-align:center;font:inherit;margin:16px 0;box-sizing:border-box;width:100%}p.small{font-size:14px;color:#a8b0c0}textarea{width:100%;height:110px;box-sizing:border-box;font:12px monospace;background:#1a1e28;color:#c9cedb;border:1px solid #2a3040;border-radius:8px;padding:10px}</style><h1>崩溃日志导出</h1>__WECHAT__<button id="save">下载日志文件</button><p id="saved" class="small">将保存为 $fileName。日志仅在本机与手机之间传输，不需要联网。</p><p class="small">如果没有弹出保存提示，请长按下面的文本全选复制。</p><textarea id="data" readonly>$inline</textarea><p class="small">关闭电视上的窗口即失效，最长 5 分钟。</p><script nonce="$token">document.getElementById('save').onclick=function(){try{var blob=new Blob([document.getElementById('data').value],{type:'text/plain'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='$fileName';document.body.appendChild(a);a.click();a.remove();setTimeout(function(){URL.revokeObjectURL(url)},4000);document.getElementById('saved').textContent='已生成文件，请到手机「下载」里查看。'}catch(e){document.getElementById('saved').textContent='当前浏览器不支持保存，请长按下方文本全选复制。'}};</script></html>""",userAgent)
    }
    private fun respond(socket: Socket,code: Int,message: String,type: String=if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8",extra: String="") {
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
