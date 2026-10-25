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

/** Short-lived LAN transfer. Uploaded records are validated here, but only the TV may apply them. */
class LibraryTransferServer(address: String,private val backup: String,private val onImport: (BackupData)->Unit,
    private val onExpired: ()->Unit={},ttlMillis: Long=300_000): Closeable {
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hongguotv-transfer-expiry",true)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/$token"
    // 文件名带打开时间，手机「下载」列表里能一眼看出哪份最新
    private val fileName="hongguotv-library-${java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.CHINA).format(java.util.Date())}.json"
    private var downloaded=false
    init {
        require(backup.toByteArray(Charsets.UTF_8).size<=LibraryBackup.MAX_BYTES)
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,300_000))
        Thread({ serve() },"hongguotv-library-transfer").apply { isDaemon=true; start() }
    }
    private fun expire() { if(stopped.compareAndSet(false,true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); expiry.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val socket=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                // 每个连接单独处理：手机系统下载器可能排队很久才发请求，串行处理会把后续请求一起堵死
                Thread({
                    active=socket
                    runCatching { socket.use { it.soTimeout=10_000; if(!stopped.get()) handle(it) } }
                    active=null
                },"hongguotv-library-transfer-conn").apply { isDaemon=true; start() }
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
        if((request[0]=="GET" || request[0]=="HEAD") && request[1]=="/$token/download") {
            val headOnly=request[0]=="HEAD"
            if(!headOnly && downloaded) return respond(socket,410,"本次备份已下载，请在电视上重新打开二维码")
            respond(socket,200,if(headOnly) "" else backup,"application/json; charset=utf-8","Content-Disposition: attachment; filename=$fileName\r\n")
            if(!headOnly) downloaded=true
            return
        }
        if(request[0]!="POST" || request[1]!="/$token/import") return respond(socket,405,"不支持的操作")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/json") return respond(socket,400,"请选择 JSON 备份文件")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..LibraryBackup.MAX_BYTES } ?: return respond(socket,413,"备份不能超过 2 MB")
        val bytes=ByteArray(length); var offset=0
        // A total deadline also bounds slow clients that keep individual reads below the socket timeout.
        val uploadDeadline=minOf(deadline,System.nanoTime()+10_000_000_000)
        while(offset<length) {
            if(stopped.get() || System.nanoTime()>=uploadDeadline) return respond(socket,408,"上传超时，请重试")
            val count=input.read(bytes,offset,minOf(8192,length-offset)); if(count<0) return; offset+=count
        }
        val data=runCatching { LibraryBackup.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return respond(socket,400,"备份格式无效，请选择本应用导出的 JSON 文件")
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        try { respond(socket,200,"已发送到电视，请用遥控器确认恢复。确认前不会更改记录。") }
        finally { close(); onImport(data) }
    }
    private fun page(): String {
        // 浏览器直接下载会再连一次电视，容易被系统下载器卡在「连接中」；
        // 这里优先用页面内数据在手机本地生成文件，不依赖第二次网络请求。
        val inline=backup.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 备份与恢复</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:540px;margin:32px auto;padding:20px;line-height:1.6}a,button{display:block;background:#ff634c;color:white;border:0;border-radius:8px;padding:14px;text-align:center;text-decoration:none;font:inherit;margin:16px 0;box-sizing:border-box;width:100%}input{max-width:100%;font:inherit}textarea{width:100%;height:110px;box-sizing:border-box;font:12px monospace;background:#1a1e28;color:#c9cedb;border:1px solid #2a3040;border-radius:8px;padding:10px}h2{font-size:18px;margin-top:26px}p.small{font-size:14px;color:#a8b0c0}</style><h1>备份与恢复</h1><p>保存本机收藏、观看位置、稍后看、隐藏推荐、搜索记录和播放设置。文件仅在手机与电视之间传输，不包含平台账号。</p><button id="save">下载电视备份</button><p id="saved" class="small">将保存为 $fileName（时间戳为本次打开时间），不需要联网。</p><p class="small">如果没有弹出保存提示，请长按下面的文本全选复制，或点右上角「在浏览器打开」。</p><textarea id="data" readonly>$inline</textarea><h2>从备份恢复</h2><input id="file" type="file" accept="*/*"><button id="send">发送到电视确认</button><p id="status" class="small">最多 2 MB；需要在电视上确认后才会合并。</p><p class="small">关闭电视窗口即失效，最长 5 分钟。</p><script nonce="$token">document.getElementById('save').onclick=function(){var text=document.getElementById('data').value,hint=document.getElementById('saved');try{var blob=new Blob([text],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='$fileName';document.body.appendChild(a);a.click();a.remove();setTimeout(function(){URL.revokeObjectURL(url)},4000);hint.textContent='已生成文件，请到手机「下载」里查看。'}catch(e){hint.textContent='当前浏览器不支持保存，请长按下方文本全选复制。'}};document.getElementById('send').onclick=function(){var file=document.getElementById('file').files[0],status=document.getElementById('status'),button=this;if(!file){status.textContent='请先选择备份文件';return}if(!/\.json$/i.test(file.name)){status.textContent='请选择 .json 后缀的备份文件';return}if(file.size>2097152){status.textContent='备份文件不能超过 2 MB';return}button.disabled=true;var reader=new FileReader();reader.onerror=function(){status.textContent='文件读取失败';button.disabled=false};reader.onload=function(){fetch('/$token/import',{method:'POST',headers:{'Content-Type':'application/json'},body:reader.result}).then(function(r){return r.text().then(function(t){status.textContent=t;if(!r.ok)button.disabled=false})}).catch(function(){status.textContent='连接已失效，请在电视上重新打开二维码';button.disabled=false})};reader.readAsText(file)};</script></html>"""
    }
    private fun respond(socket: Socket,code: Int,message: String,type: String=if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8",extra: String="") {
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
