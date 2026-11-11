// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 手机扫码 → 局域网直传文件到电视，只写应用私有目录（卸载即清）。
 *  大文件全程流式：
 *   - 先写 `<uuid>.part` 临时文件，字节数与格式校验通过后才 rename 到正式名；
 *   - 任何失败（中断 / 超时 / 过期 / 校验不过）都删除临时文件，正式目录不会出现半成品。
 *  与既有 TransferServer 一致的会话模型：随机 token、只绑内网、TTL 到期自毁。 */
class QuickTransferServer(
    address: String,
    private val pickDirectory: (TransferKind) -> File,
    private val availableBytes: () -> Long,
    private val onProgress: (name: String, received: Long, total: Long, index: Int, count: Int) -> Unit = { _, _, _, _, _ -> },
    private val onReceived: (File, TransferKind) -> Unit = { _, _ -> },
    private val onExpired: () -> Unit = {},
    ttlMillis: Long = 600_000,
) : Closeable {
    companion object {
        /** 单次会话接收上限：防止误选整个文件夹把电视写满。 */
        const val MAX_FILES_PER_SESSION = 20
        /** 单次 read 的阻塞上限，等价于“多久没有数据算中断”。 */
        const val IDLE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val BUFFER_BYTES = 64 * 1024
        /** 进度回调节流：局域网速度高，按块回调会把 UI 打爆。 */
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }

    /** 传输中被 TTL 打断，单独区分以便回 410。 */
    private class ExpiredException : IOException("二维码已过期")
    /** 内容与扩展名不符（目前只校验 APK 的 zip 头）。 */
    private class BadContentException(message: String) : IOException(message)

    private val stopped = AtomicBoolean(false)
    private val expiry = Timer("hongguotv-quick-expiry", true)
    private val local = InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener = ServerSocket(0, 4, local).apply { soTimeout = 250 }
    @Volatile private var active: Socket? = null
    private val deadline = System.nanoTime() + ttlMillis.coerceIn(1, 600_000) * 1_000_000
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host = "${local.hostAddress}:${listener.localPort}"
    val url = "http://$host/$token"

    /** 同一时刻只允许一个上传，避免并发写盘拖垮低端电视。 */
    private val uploadActive = AtomicBoolean(false)
    private val receivedCount = AtomicInteger(0)
    /** 尚未 rename 的临时文件：关闭会话时统一删除。 */
    private val pending = java.util.Collections.synchronizedSet(mutableSetOf<File>())

    init {
        cleanStaleParts()
        expiry.schedule(object : TimerTask() { override fun run() = expire() }, ttlMillis.coerceIn(1, 600_000))
        Thread({ serve() }, "hongguotv-quick-transfer").apply { isDaemon = true; start() }
    }

    private fun expire() { if(stopped.compareAndSet(false, true)) { close(); onExpired() } }

    override fun close() {
        stopped.set(true); expiry.cancel()
        runCatching { listener.close() }; runCatching { active?.close() }
        synchronized(pending) { pending.toList().forEach { runCatching { it.delete() } }; pending.clear() }
    }

    /** 上次异常退出留下的 .part：只清理足够旧的，避免误删正在进行的传输。 */
    private fun cleanStaleParts() {
        val cutoff = System.currentTimeMillis() - TransferRules.STALE_PART_MILLIS
        runCatching {
            TransferKind.entries.map { pickDirectory(it) }.distinct().forEach { directory ->
                directory.listFiles { file -> file.isFile && file.name.endsWith(TransferRules.PART_SUFFIX) && file.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }
        }
    }

    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime() < deadline) {
                val socket = try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                // 每个连接单独处理：手机浏览器会并行建立多个连接，串行处理会把后续请求堵死
                Thread({
                    active = socket
                    runCatching { socket.use { it.soTimeout = CONNECT_TIMEOUT_MS; if(!stopped.get()) handle(it) } }
                    active = null
                }, "hongguotv-quick-transfer-conn").apply { isDaemon = true; start() }
            }
        } catch(_: Exception) { /* 关闭对话框会打断 accept/read */ }
        finally { if(!stopped.get()) expire() else close() }
    }

    private fun line(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        while(out.size() < max) {
            val next = input.read(); if(next < 0) throw java.io.EOFException()
            if(next == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw IOException("Header too large")
    }

    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input = socket.getInputStream().buffered()
        val request = line(input, 1024).split(' ')
        if(request.size != 3) return
        val headers = mutableMapOf<String, String>()
        var size = 0
        while(true) {
            val next = line(input, 4096); size += next.length + 2
            if(size > 8192) return respond(socket, 431, "请求过大")
            if(next.isEmpty()) break
            val at = next.indexOf(':'); if(at <= 0) return respond(socket, 400, "请求格式错误")
            if(headers.put(next.substring(0, at).lowercase(Locale.ROOT), next.substring(at + 1).trim()) != null) return respond(socket, 400, "重复请求头")
        }
        if(stopped.get() || System.nanoTime() >= deadline) return respond(socket, 410, "二维码已过期，请在电视上重新打开快传")
        if(headers["host"] != host) return respond(socket, 404, "页面不存在")
        val path = request[1].substringBefore('?')
        if(path != "/$token" && path != "/$token/upload" && path != "/$token/state") return respond(socket, 404, "页面不存在")
        if(foreignOrigin(headers["origin"], local.hostAddress)) return respond(socket, 403, "来源不匹配")
        if(headers["sec-fetch-site"] == "cross-site") return respond(socket, 403, "来源不匹配")
        when {
            request[0] == "GET" && path == "/$token" -> respond(socket, 200, page(headers["user-agent"]))
            request[0] == "GET" && path == "/$token/state" -> respond(socket, 200, state(), "application/json; charset=utf-8")
            request[0] == "POST" && path == "/$token/upload" -> receive(socket, input, request[1], headers)
            else -> respond(socket, 405, "不支持的操作")
        }
    }

    private fun state(): String {
        val free = availableBytes().coerceAtLeast(0)
        return """{"available":$free,"max":${TransferRules.MAX_BYTES},"files":${receivedCount.get()}}"""
    }

    private fun receive(socket: Socket, input: InputStream, requestTarget: String, headers: Map<String, String>) {
        if(headers.containsKey("transfer-encoding")) return respond(socket, 400, "请求格式错误")
        val params = requestTarget.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { part ->
            val key = part.substringBefore('=')
            key to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        val name = params["name"]?.let(TransferRules::sanitize) ?: return respond(socket, 400, "文件名不合法，请重试")
        val kind = TransferRules.kindOf(name) ?: return respond(socket, 400, "不支持的文件类型，请传 APK、视频、图片或压缩包")
        // 流式落盘依赖预先知道长度：chunked 与缺失长度一律拒绝
        val declared = headers["content-length"] ?: return respond(socket, 400, "请求格式错误")
        val length = declared.toLongOrNull()?.takeIf { it in 1..TransferRules.MAX_BYTES }
            ?: return respond(socket, 413, "单个文件不能超过 ${TransferRules.humanBytes(TransferRules.MAX_BYTES)}")
        if(receivedCount.get() >= MAX_FILES_PER_SESSION) return respond(socket, 429, "本次已接收 $MAX_FILES_PER_SESSION 个文件，请重新扫码")
        // 先看空间再收字节：传到最后才发现写不下，等于白传一次大文件
        if(availableBytes() < length + TransferRules.MIN_FREE_BYTES) return respond(socket, 413, "电视剩余空间不足，请先清理")
        if(!uploadActive.compareAndSet(false, true)) return respond(socket, 409, "电视正在接收另一个文件，请稍候")
        val index = params["index"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = params["count"]?.toIntOrNull()?.coerceAtLeast(index) ?: index
        val directory = pickDirectory(kind)
        val part = File(directory, UUID.randomUUID().toString() + TransferRules.PART_SUFFIX)
        var accepted: File? = null
        var code = 200
        var message = "已接收 $name，正在电视上处理…"
        try {
            if(!directory.exists() && !directory.mkdirs()) throw IOException("无法创建保存目录")
            pending += part
            socket.soTimeout = IDLE_TIMEOUT_MS
            val buffer = ByteArray(BUFFER_BYTES)
            var received = 0L
            var notifiedAt = System.nanoTime() - PROGRESS_INTERVAL_NANOS
            FileOutputStream(part).use { output ->
                while(received < length) {
                    if(stopped.get() || System.nanoTime() >= deadline) throw ExpiredException()
                    val want = minOf(buffer.size.toLong(), length - received).toInt()
                    val read = input.read(buffer, 0, want)
                    if(read < 0) throw java.io.EOFException("上传中断")
                    output.write(buffer, 0, read)
                    received += read
                    val now = System.nanoTime()
                    if(now - notifiedAt >= PROGRESS_INTERVAL_NANOS) {
                        notifiedAt = now
                        onProgress(name, received, length, index, count)
                    }
                }
                output.flush()
            }
            if(received != length) throw IOException("上传字节数不匹配")
            if(kind == TransferKind.APK && !TransferRules.looksLikeApk(headOf(part))) throw BadContentException("不是有效的 APK 文件")
            val target = File(directory, TransferRules.targetName(name, System.currentTimeMillis()))
            if(!part.renameTo(target)) throw IOException("电视写入文件失败")
            accepted = target
            receivedCount.incrementAndGet()
            // 只回显文件名：完整绝对路径又长又对手机用户没用，还会把状态行顶出屏幕。
            // 保存位置已在页面顶部单独展示，这里保持一行短文案。
            message = "已保存 · ${target.name}"
            onProgress(name, length, length, index, count)
        } catch(_: ExpiredException) {
            code = 410; message = "二维码已过期，请在电视上重新打开快传"
        } catch(e: BadContentException) {
            code = 400; message = e.message ?: "文件内容无效"
        } catch(e: SocketTimeoutException) {
            code = 408; message = "上传中断（${IDLE_TIMEOUT_MS / 1000} 秒无数据），请重试"
        } catch(e: java.io.EOFException) {
            // 手机断网或浏览器取消：客户端已断开，不需要再写响应，但错误码仍按中断归类
            code = 408; message = "上传中断，请重试"
        } catch(e: Throwable) {
            code = 500; message = "电视写入文件失败"
        } finally {
            pending -= part
            uploadActive.set(false)
        }
        if(code != 200) runCatching { part.delete() }
        respond(socket, code, message)
        accepted?.let { onReceived(it, kind) }
    }

    /** 只读文件头：APK 校验不需要把整个包读回来。
     *  不用 readNBytes：它在 Android 13 以下不存在，低版本电视上会抛 NoSuchMethodError。 */
    private fun headOf(file: File): ByteArray = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(4)
            var read = 0
            while(read < buffer.size) {
                val next = stream.read(buffer, read, buffer.size - read)
                if(next <= 0) break
                read += next
            }
            buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    private fun respond(socket: Socket, code: Int, message: String, type: String = if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8") {
        val body = message.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        runCatching {
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        }
    }

    private fun page(userAgent: String?): String {
        val free = TransferRules.humanBytes(availableBytes().coerceAtLeast(0))
        val limit = TransferRules.humanBytes(TransferRules.MAX_BYTES)
        val minutes = (deadline - System.nanoTime()) / 60_000_000_000L
        val dirs = TransferKind.entries.map { pickDirectory(it).absolutePath }.distinct().joinToString("、")
        return PAGE
            .replace("__TOKEN__", token)
            .replace("__FREE__", free)
            .replace("__LIMIT__", limit)
            .replace("__MINUTES__", minutes.coerceAtLeast(1).toString())
            .replace("__DIR__", dirs)
            .let { PhoneServerPage.fill(it, userAgent) }
    }
}

private val PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 手机快传</title><style>
body{font:16px/1.55 sans-serif;background:#10131b;color:#f4f5f8;max-width:560px;margin:0 auto;padding:20px 18px 40px;overflow-x:hidden}
h1{font-size:22px;margin:8px 0 4px}
p{color:#a8b0c0;margin:8px 0}
p.space{color:#ffb4a4;font-weight:600;font-size:17px}
button{display:block;width:100%;box-sizing:border-box;background:#ff634c;color:#2a0b06;border:0;border-radius:10px;padding:15px;text-align:center;font:inherit;font-weight:600;margin:14px 0}
button:disabled{opacity:.45}
input{width:100%;box-sizing:border-box;font:inherit;color:#f4f5f8;background:#1a1e28;border:1px solid #2a3040;border-radius:10px;padding:12px}
.pick{position:relative;display:block;margin:14px 0;background:#ff634c;border-radius:10px;overflow:hidden}
.pick span{display:block;padding:15px;text-align:center;color:#2a0b06;font-weight:600;-webkit-tap-highlight-color:transparent;pointer-events:none}
input.native{position:absolute;left:0;top:0;width:100%;height:100%;margin:0;padding:0;border:0;border-radius:0;background:transparent;opacity:0;font-size:16px;cursor:pointer}
/* 文件名可能是没有空格的长串（中文/数字/下划线），默认不会断行，会把卡片撑出屏幕。
   break-all 兜住老浏览器（微信 X5），anywhere 让现代浏览器优先按容器宽度折行。 */
p.dir{color:#a8b0c0;font-size:12px;word-break:break-all;overflow-wrap:anywhere}
p.picked{color:#ffb4a4;font-size:14px;word-break:break-all;overflow-wrap:anywhere}
.item{background:#1a1e28;border:1px solid #2a3040;border-radius:10px;padding:12px;margin:10px 0;box-sizing:border-box;max-width:100%;word-break:break-all;overflow-wrap:anywhere}
.item .name{font-size:14px;word-break:break-all;overflow-wrap:anywhere}
.item .meta{color:#a8b0c0;font-size:13px;margin-top:6px;word-break:break-all;overflow-wrap:anywhere}
.track{height:6px;background:#2a3040;border-radius:3px;overflow:hidden;margin-top:8px}
.fill{height:100%;width:0;background:#ff634c;transition:width .18s linear}
.item.ok .fill{background:#1dc981}
.item.bad .fill{background:#e8463a}
.item.ok .meta{color:#1dc981}
.item.bad .meta{color:#e8463a}
</style><h1>手机快传</h1><p class="space">电视可用 __FREE__</p><p>选择手机里的文件后发送。APK 会提示安装，视频与图片保存到电视。<br>单个文件不超过 __LIMIT__；失败的传输不会占用电视空间。</p>__WECHAT__<p class="dir">保存位置 · __DIR__</p><div class="pick"><span>选择文件</span><input id="file" class="native" type="file" multiple></div><p id="picked" class="picked">尚未选择文件</p><button id="send">发送到电视</button><div id="list"></div><p class="small">关闭电视窗口即失效，最长 __MINUTES__ 分钟。仅限同一局域网。</p><script nonce="__TOKEN__">
(function(){
var token='__TOKEN__';
var picked=[];
var pickedNames='';
var input=document.getElementById('file');
var pickedText=document.getElementById('picked');
var send=document.getElementById('send');
var list=document.getElementById('list');
var spaceText=document.querySelector('.space');
function human(n){if(n<1024)return n+' B';if(n<1048576)return Math.round(n/1024)+' KB';if(n<1073741824)return(n/1048576).toFixed(1)+' MB';return(n/1073741824).toFixed(1)+' GB'}
function makeRow(name){var item=document.createElement('div');item.className='item';var n=document.createElement('div');n.className='name';n.textContent=name;var track=document.createElement('div');track.className='track';var fill=document.createElement('div');fill.className='fill';track.appendChild(fill);var meta=document.createElement('div');meta.className='meta';meta.textContent='准备发送';item.appendChild(n);item.appendChild(track);item.appendChild(meta);list.appendChild(item);return{fill:fill,meta:meta,item:item}}
function refreshSpace(){fetch('/'+token+'/state').then(function(r){return r.json()}).then(function(d){spaceText.textContent='电视可用 '+human(d.available)}).catch(function(){})}
function progress(row,ratio,text){row.fill.style.width=(Math.max(0,Math.min(1,ratio))*100).toFixed(1)+'%';row.meta.textContent=text}
function syncPicked(){var next=Array.prototype.slice.call(input.files||[]);
var names=next.map(function(f){return f.name}).join('、');
if(names===pickedNames) return;
pickedNames=names; picked=next; list.textContent='';
if(!picked.length){pickedText.textContent='尚未选择文件';return}
pickedText.textContent='已选择 '+picked.length+' 个文件：'+names;
picked.forEach(function(f){var row=makeRow(f.name);progress(row,0,'待发送 · '+human(f.size))})}
// 直接用 label[for] 触发系统文件选择器；个别手机内置浏览器返回后不派发 change，回到页面时再同步一次
input.addEventListener('change',syncPicked);
window.addEventListener('focus',function(){setTimeout(syncPicked,400)});
// 兜底轮询：有些手机浏览器返回后既不派发 change 也不触发 focus
setInterval(syncPicked,800);
send.onclick=function(){syncPicked();if(!picked.length){pickedText.textContent='请先点「选择文件」选取要发送的文件';return}send.disabled=true;list.textContent='';upload(0,picked.length)}
function upload(i,total){if(i>=total){send.disabled=false;picked=[];pickedNames='';input.value='';pickedText.textContent='尚未选择文件';refreshSpace();return}
var file=picked[i];var row=makeRow(file.name);var xhr=new XMLHttpRequest();
xhr.open('POST','/'+token+'/upload?name='+encodeURIComponent(file.name)+'&index='+(i+1)+'&count='+total);
xhr.setRequestHeader('Content-Type','application/octet-stream');
xhr.upload.onprogress=function(e){if(e.lengthComputable)progress(row,e.loaded/e.total,human(e.loaded)+' / '+human(e.total))};
xhr.onload=function(){var ok=xhr.status===200;row.item.className='item '+(ok?'ok':'bad');progress(row,1,ok?xhr.responseText:'发送失败：'+xhr.responseText);upload(i+1,total)};
xhr.onerror=function(){row.item.className='item bad';progress(row,1,'连接已失效，请在电视上重新打开二维码');upload(i+1,total)};
xhr.send(file)}
refreshSpace();
})();
</script></html>"""
