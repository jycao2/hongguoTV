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

/** 源配置的手机扫码导入导出：手机浏览器可下载当前配置，或上传配置文件回到电视（只追加、不覆盖）。 */
class ThirdSourceTransferServer(address: String,private val config: String,private val onImport: (List<BackupThirdSource>)->Unit,
    private val onExpired: ()->Unit={},ttlMillis: Long=300_000): Closeable {
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hongguotv-source-transfer-expiry",true)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/$token"
    private var downloaded=false
    init {
        require(config.toByteArray(Charsets.UTF_8).size<=ThirdSourceConfig.MAX_BYTES)
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,300_000))
        Thread({ serve() },"hongguotv-source-transfer").apply { isDaemon=true; start() }
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
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/download","/$token/import")) return respond(socket,404,"页面不存在")
        if(foreignOrigin(headers["origin"], local.hostAddress)) return respond(socket,403,"来源不匹配")
        if(headers["sec-fetch-site"]=="cross-site") return respond(socket,403,"来源不匹配")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,page())
        if(request[0]=="GET" && request[1]=="/$token/download") {
            if(downloaded) return respond(socket,410,"本次配置已下载，请在电视上重新打开二维码")
            respond(socket,200,config,"application/json; charset=utf-8",
                "Content-Disposition: attachment; filename=hongguotv-third-sources.json\r\n")
            downloaded=true; return
        }
        if(request[0]!="POST" || request[1]!="/$token/import") return respond(socket,405,"不支持的操作")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/json") return respond(socket,400,"请选择 JSON 配置文件")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..ThirdSourceConfig.MAX_BYTES } ?: return respond(socket,413,"配置文件不能超过 256 KB")
        val bytes=ByteArray(length); var offset=0
        val uploadDeadline=minOf(deadline,System.nanoTime()+10_000_000_000)
        while(offset<length) {
            if(stopped.get() || System.nanoTime()>=uploadDeadline) return respond(socket,408,"上传超时，请重试")
            val count=input.read(bytes,offset,minOf(8192,length-offset)); if(count<0) return; offset+=count
        }
        val sources=ThirdSourceConfig.decode(bytes.toString(Charsets.UTF_8))
        if(sources.isEmpty()) return respond(socket,400,"配置格式无效，请选择本应用导出的 JSON 文件")
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        try { respond(socket,200,"已发送到电视：只追加缺失的源，已有源保持不变。") }
        finally { close(); onImport(sources) }
    }
    private fun page()="""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 源配置</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:540px;margin:32px auto;padding:20px;line-height:1.6}a,button{display:block;background:#ff634c;color:white;border:0;border-radius:8px;padding:14px;text-align:center;text-decoration:none;font:inherit;margin:16px 0}input{max-width:100%;font:inherit}button{width:100%}</style><h1>源配置导入/导出</h1><p>文件只包含三方片源的名称与接口地址，不含账号、播放进度或搜索记录。</p><a href="/$token/download" download>下载电视源配置</a><h2>从配置文件恢复</h2><input id="file" type="file" accept=".json,application/json"><button id="send">发送到电视</button><p id="status">已有源不会被覆盖，只追加缺失的源。最多 256 KB。</p><p>关闭电视窗口即失效，最长 5 分钟。</p><script nonce="$token">document.getElementById('send').onclick=function(){var file=document.getElementById('file').files[0],status=document.getElementById('status'),button=this;if(!file||file.size>262144){status.textContent='请选择不超过 256 KB 的配置文件';return}button.disabled=true;var reader=new FileReader();reader.onerror=function(){status.textContent='文件读取失败';button.disabled=false};reader.onload=function(){fetch('/$token/import',{method:'POST',headers:{'Content-Type':'application/json'},body:reader.result}).then(function(r){return r.text().then(function(t){status.textContent=t;if(!r.ok)button.disabled=false})}).catch(function(){status.textContent='连接已失效，请在电视上重新打开二维码';button.disabled=false})};reader.readAsText(file)};</script></html>"""
    private fun respond(socket: Socket,code: Int,message: String,type: String=if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8",extra: String="") {
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
