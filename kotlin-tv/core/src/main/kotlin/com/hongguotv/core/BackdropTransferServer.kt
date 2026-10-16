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

/** One-use LAN image upload for the custom TV backdrop. Only JPEG/PNG/WebP bytes are accepted. */
class BackdropTransferServer(address: String,private val onImage: (ByteArray,String)->Unit,
    private val onExpired: ()->Unit={},ttlMillis: Long=300_000): Closeable {
    companion object {
        const val MAX_BYTES=10*1024*1024
        /** Returns the file extension for a supported image, or null when the bytes look wrong. */
        fun imageType(bytes: ByteArray): String? = when {
            bytes.size>=3 && bytes[0]==0xFF.toByte() && bytes[1]==0xD8.toByte() && bytes[2]==0xFF.toByte() -> "jpg"
            bytes.size>=8 && bytes[0]==0x89.toByte() && bytes[1]==0x50.toByte() && bytes[2]==0x4E.toByte() && bytes[3]==0x47.toByte() -> "png"
            bytes.size>=12 && bytes[0]==0x52.toByte() && bytes[1]==0x49.toByte() && bytes[2]==0x46.toByte() && bytes[3]==0x46.toByte() &&
                bytes[8]==0x57.toByte() && bytes[9]==0x45.toByte() && bytes[10]==0x42.toByte() && bytes[11]==0x50.toByte() -> "webp"
            else -> null
        }
    }
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hongguotv-backdrop-expiry",true)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/$token"
    init {
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,300_000))
        Thread({ serve() },"hongguotv-backdrop-transfer").apply { isDaemon=true; start() }
    }
    private fun expire() { if(stopped.compareAndSet(false,true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); expiry.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val socket=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                active=socket
                socket.use { it.soTimeout=2000; if(!stopped.get()) runCatching { handle(it) } }
                active=null
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
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/upload")) return respond(socket,404,"页面不存在")
        if(foreignOrigin(headers["origin"], local.hostAddress)) return respond(socket,403,"来源不匹配")
        if(headers["sec-fetch-site"]=="cross-site") return respond(socket,403,"来源不匹配")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,page())
        if(request[0]!="POST" || request[1]!="/$token/upload") return respond(socket,405,"不支持的操作")
        if(headers.containsKey("transfer-encoding")) return respond(socket,400,"请求格式错误")
        val type=headers["content-type"]?.substringBefore(';')?.lowercase(Locale.ROOT)
        if(type !in setOf("image/jpeg","image/png","image/webp")) return respond(socket,400,"请选择 JPEG、PNG 或 WebP 图片")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..MAX_BYTES } ?: return respond(socket,413,"图片不能超过 10 MB")
        val bytes=ByteArray(length); var offset=0
        // A total deadline also bounds slow clients that keep individual reads below the socket timeout.
        val uploadDeadline=minOf(deadline,System.nanoTime()+20_000_000_000)
        while(offset<length) {
            if(stopped.get() || System.nanoTime()>=uploadDeadline) return respond(socket,408,"上传超时，请重试")
            val count=input.read(bytes,offset,minOf(8192,length-offset)); if(count<0) return; offset+=count
        }
        val extension=imageType(bytes) ?: return respond(socket,400,"图片内容无效，请换一张试试")
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        try { respond(socket,200,"已发送到电视，请回到电视查看效果。") }
        finally { close(); onImage(bytes,extension) }
    }
    private fun page()="""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 自定义背景</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:540px;margin:32px auto;padding:20px;line-height:1.6}button{display:block;background:#ff634c;color:white;border:0;border-radius:8px;padding:14px;text-align:center;font:inherit;margin:16px 0;width:100%}input{max-width:100%;font:inherit}#preview{max-width:100%;border-radius:8px;margin-top:12px}</style><h1>自定义电视背景</h1><p>选择手机里的图片，上传到电视作为全局背景。横向大图效果更好，建议 1920×1080 或更大，不超过 10 MB。图片只保存在电视本机。</p><input id="file" type="file" accept="image/jpeg,image/png,image/webp"><img id="preview" alt="" style="display:none"><button id="send">上传到电视</button><p id="status">支持 JPEG / PNG / WebP。</p><p>关闭电视窗口即失效，最长 5 分钟。</p><script nonce="$token">var picked=null;document.getElementById('file').onchange=function(e){picked=e.target.files[0]||null;var preview=document.getElementById('preview'),status=document.getElementById('status');if(!picked){preview.style.display='none';status.textContent='支持 JPEG / PNG / WebP。';return}if(picked.size>10485760){picked=null;preview.style.display='none';status.textContent='图片不能超过 10 MB';return}preview.src=URL.createObjectURL(picked);preview.style.display='block';status.textContent='已选择 '+picked.name+'，点击上传到电视。'};document.getElementById('send').onclick=function(){var status=document.getElementById('status'),button=this;if(!picked){status.textContent='请先选择图片';return}button.disabled=true;fetch('/$token/upload',{method:'POST',headers:{'Content-Type':picked.type||'application/octet-stream'},body:picked}).then(function(r){return r.text().then(function(t){status.textContent=t;if(!r.ok)button.disabled=false})}).catch(function(){status.textContent='连接已失效，请在电视上重新打开二维码';button.disabled=false})};</script></html>"""
    private fun respond(socket: Socket,code: Int,message: String,type: String=if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8",extra: String="") {
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
