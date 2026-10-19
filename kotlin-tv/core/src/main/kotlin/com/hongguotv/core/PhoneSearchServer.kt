// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The random token in the URL path is the real access gate; Origin is only defense in depth.
 *  In-app WebViews and privacy tools often post forms with "null" or port-less origins,
 *  so only a genuinely different host counts as foreign. */
internal fun foreignOrigin(origin: String?, localAddress: String): Boolean {
    if(origin == null || origin == "null") return false
    val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: return true
    return host.removePrefix("[").removeSuffix("]") != localAddress
}

/** 手机填写页的单个输入项；key 为表单字段名，只使用 ASCII，避免编码歧义。 */
data class PhoneField(
    val key: String,
    val label: String,
    val hint: String,
    val maxLength: Int = 80,
    val required: Boolean = true
)

/** 手机填写页表单定义：搜索剧名、添加三方片源、导入配置等扫码场景共用同一套服务，只替换表单与文案。 */
data class PhoneForm(
    val heading: String,
    val description: String,
    val fields: List<PhoneField>,
    val submitLabel: String = "发送到电视",
    val doneMessage: String = "已发送到电视",
    val doneHint: String = "请回到电视查看结果。",
    val invalidMessage: String = "请检查填写内容"
) {
    companion object {
        /** 电视搜索页的手机输入（沿用原有文案与校验规则）。 */
        fun search() = PhoneForm(
            heading = "在电视上搜索",
            description = "输入剧名，电视会按当前选择的漫剧或短剧分类搜索。",
            fields = listOf(PhoneField("query", "剧名或关键词", "输入剧名或关键词", 80)),
            doneHint = "请回到电视查看搜索结果。",
            invalidMessage = "请输入 1—80 个字符的剧名"
        )
    }
}

/** A one-use, short-lived LAN form. No cookies, platform credentials or external resources. */
class PhoneInputServer(
    address: String,
    private val form: PhoneForm,
    private val onSubmit: (Map<String, String>)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000
): Closeable {
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=ServerSocket(0,4).apply { soTimeout=500 }
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="$localAddress:${listener.localPort}"
    val url="http://$host/$token"
    private val thread=Thread({ serve() },"hongguotv-phone-input").apply { isDaemon=true }
    init {
        thread.start()
    }
    override fun close() { stopped.set(true); runCatching { listener.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                client.use { socket ->
                    socket.soTimeout=2000
                    try { handle(socket) } catch(_: Exception) { /* Discard malformed or disconnected requests. */ }
                }
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            val expired=!stopped.get() && !consumed.get()
            close()
            if(expired) onExpired()
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            val next=line(input,4096); bytes+=next.length+2
            if(bytes>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || consumed.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已失效，请在电视上重新打开")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/submit")) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,formPage())
        if(request[0]!="POST" || request[1]!="/$token/submit") return respond(socket,405,"不支持的操作")
        if(foreignOrigin(headers["origin"], localAddress)) return respond(socket,403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded") return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4096 } ?: return respond(socket,413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val values=parseFields(String(body,Charsets.UTF_8)) ?: return respond(socket,400,form.invalidMessage)
        if(stopped.get() || System.nanoTime()>=deadline || !consumed.compareAndSet(false,true)) return respond(socket,410,"二维码已失效")
        try { respond(socket,200,"<h1>${escapeHtml(form.doneMessage)}</h1><p>${escapeHtml(form.doneHint)}</p>") }
        finally { close(); onSubmit(values) }
    }
    /** 表单校验：字段名必须与表单定义完全一致；重复、缺失、超长、控制字符一律拒绝且不消耗会话。 */
    private fun parseFields(body: String): Map<String,String>? {
        val values=LinkedHashMap<String,String>()
        for(pair in body.split('&')) {
            val at=pair.indexOf('=')
            if(at<=0) return null
            val key=pair.substring(0,at)
            val value=runCatching { URLDecoder.decode(pair.substring(at+1),"UTF-8") }.getOrNull() ?: return null
            if(values.put(key,value)!=null) return null
        }
        val expected=form.fields.mapTo(LinkedHashSet<String>()) { it.key }
        if(values.keys!=expected) return null
        for(field in form.fields) {
            val value=values[field.key].orEmpty().trim()
            if(value.any { it.isISOControl() }) return null
            if(field.required && value.isEmpty()) return null
            if(value.length>field.maxLength) return null
            values[field.key]=value
        }
        return values
    }
    private fun formPage(): String = buildString {
        append("<h1>").append(escapeHtml(form.heading)).append("</h1>")
        append("<p>").append(escapeHtml(form.description)).append("</p>")
        append("<form method=\"post\" action=\"/").append(token).append("/submit\">")
        form.fields.forEachIndexed { index, field ->
            append("<label for=\"field").append(index).append("\">").append(escapeHtml(field.label)).append("</label>")
            append("<input id=\"field").append(index).append("\" name=\"").append(field.key).append('"')
            append(" maxlength=\"").append(field.maxLength.coerceIn(1,400)).append('"')
            append(" placeholder=\"").append(escapeHtml(field.hint)).append('"')
            if(field.required) append(" required")
            append(" autocomplete=\"off\"")
            if(index==0) append(" autofocus")
            append('>')
        }
        append("<button>").append(escapeHtml(form.submitLabel)).append("</button></form>")
        append("<p>页面仅在电视的填写窗口打开期间有效，最长 5 分钟。</p>")
    }
    private fun respond(socket: Socket, code: Int, message: String) {
        val body="""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 手机填写</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:500px;margin:40px auto;padding:20px}label{display:block;margin-top:12px;font-size:16px;color:#c9cedb}input,button{box-sizing:border-box;width:100%;font:inherit;padding:16px;margin:8px 0;border-radius:8px}button{background:#ff634c;color:white;border:0}p{line-height:1.6}</style></head><body>$message</body></html>""".toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}

private fun escapeHtml(value: String): String = value
    .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

/** 「输入剧名」场景的薄封装：等价于 PhoneInputServer + PhoneForm.search()。 */
class PhoneSearchServer(
    address: String,
    onQuery: (String)->Unit,
    onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000
): Closeable {
    private val server=PhoneInputServer(address,PhoneForm.search(),{ values->onQuery(values["query"].orEmpty()) },onExpired,ttlMillis)
    val url: String get()=server.url
    override fun close() { server.close() }
}
