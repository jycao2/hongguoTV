// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PhoneInputServer
import com.hongguotv.core.QuickTransferServer
import com.hongguotv.core.ReceivedFile
import com.hongguotv.core.TransferFiles
import com.hongguotv.core.TransferKind
import com.hongguotv.core.TransferRules
import com.hongguotv.core.ThirdSourceTransferServer
import com.hongguotv.core.BackupThirdSource
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackdropTransferServer
import com.hongguotv.core.BackupData
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    /** 当前可用的局域网 IPv4：只认 Wi-Fi / 有线，手机必须和电视在同一网段才能扫码。 */
    fun lanAddress(): String? {
        val connectivity=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        if(Build.VERSION.SDK_INT>=23) {
            val network=connectivity.activeNetwork ?: return null
            val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
            if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
            return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }
        // API 21/22 没有 Network API，改为直接枚举网卡上的局域网地址
        return runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
    private fun freeBytes()=runCatching { android.os.StatFs(activity.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    /** 快传落盘目录：都在 filesDir 下，卸载应用时随应用数据一起清除。 */
    private fun quickDirectory(kind: TransferKind)=File(activity.filesDir,TransferRules.directoryName(kind))
    private fun quickDirectories()=TransferKind.entries.map { quickDirectory(it) }.distinct()

    /**
     * 手机遥控页「上传文件到电视」的接收端。
     *
     * 由遥控服务的 socket 线程调用，所以这里**必须阻塞**到用户在电视上做出选择：
     * 点「确认」才开始读文件内容，点「取消」直接返回 false，一个字节都不落盘。
     * 单文件与整批共用同一套校验、确认与落盘，区别只在确认次数与文案：一次选多个
     * 文件时整批只弹一次确认，确认后按顺序从同一条流里收完。
     * 保存位置与扫码快传一致，接收完就能在「快传文件」里看到。
     */
    fun receiveRemoteUpload(name: String,length: Long,input: java.io.InputStream): Pair<Boolean,String> =
        acceptUploads(listOf(name to length),input)

    fun receiveRemoteBatchUpload(items: List<Pair<String,Long>>,input: java.io.InputStream): Pair<Boolean,String> =
        acceptUploads(items,input)

    private fun acceptUploads(items: List<Pair<String,Long>>,input: java.io.InputStream): Pair<Boolean,String> {
        if(items.isEmpty()) return false to "请先选择文件"
        val prepared=items.map { (name,length) ->
            val safe=TransferRules.sanitize(name) ?: return false to "文件名不合法，请重试"
            val kind=TransferRules.kindOf(safe) ?: return false to "不支持的文件类型，请传 APK、视频、图片或压缩包"
            if(length<=0L || length>TransferRules.MAX_BYTES) return false to "文件大小不合法"
            Triple(safe,kind,length)
        }
        var total=0L
        for(item in prepared) total+=item.third
        if(freeBytes()<total+TransferRules.MIN_FREE_BYTES) return false to "电视剩余空间不足，请先清理"
        val message=if(prepared.size==1) {
            "手机想发送：\n\n${prepared[0].first}\n${TransferRules.humanBytes(prepared[0].third)}\n\n接收后可以在「快传文件」里打开。"
        } else buildString {
            append("手机想发送 ").append(prepared.size).append(" 个文件，共 ")
            append(TransferRules.humanBytes(total)).append("：\n\n")
            prepared.take(5).forEach { append("· ").append(it.first).append("  ").append(TransferRules.humanBytes(it.third)).append('\n') }
            if(prepared.size>5) append("…等 ").append(prepared.size).append(" 个文件\n")
            append("\n接收后可以在「快传文件」里打开。")
        }
        val title=if(prepared.size==1) "接收手机上传" else "接收 ${prepared.size} 个文件"
        if(!askOnMain(title,message)) return false to "已在电视上取消接收"
        var saved=0
        var failure=""
        // 整批共用一条流：某个文件写失败后流位置已经错位，后面的不能再读，直接收尾
        for((safe,kind,length) in prepared) {
            val (ok,why)=saveUpload(safe,kind,length,input)
            if(!ok) { failure=why; break }
            saved++
        }
        if(saved>0) main.post {
            android.widget.Toast.makeText(activity,"已接收 $saved 个文件到快传文件",android.widget.Toast.LENGTH_SHORT).show()
        }
        return when {
            saved==prepared.size -> true to "已保存 $saved 个文件"
            saved>0 -> true to "已接收 $saved/${prepared.size} 个文件，剩余未接收：$failure"
            else -> false to failure.ifEmpty { "电视写入文件失败" }
        }
    }

    /** 单文件落盘：调用方已经统一确认过，这里只按声明的字节数从流里切一段。 */
    private fun saveUpload(safe: String,kind: TransferKind,length: Long,input: java.io.InputStream): Pair<Boolean,String> {
        val directory=quickDirectory(kind)
        if(!directory.exists() && !directory.mkdirs()) return false to "无法创建保存目录"
        val part=File(directory,java.util.UUID.randomUUID().toString()+TransferRules.PART_SUFFIX)
        return try {
            java.io.FileOutputStream(part).use { output ->
                val buffer=ByteArray(64*1024)
                var received=0L
                while(received<length) {
                    val want=minOf(buffer.size.toLong(),length-received).toInt()
                    val read=input.read(buffer,0,want)
                    if(read<0) throw java.io.IOException("上传中断")
                    output.write(buffer,0,read)
                    received+=read
                }
                output.flush()
            }
            if(part.length()!=length) throw java.io.IOException("字节数不匹配")
            val target=File(directory,TransferRules.targetName(safe,System.currentTimeMillis()))
            if(!part.renameTo(target)) throw java.io.IOException("写入失败")
            true to "已保存 · ${target.name}"
        } catch(e: Throwable) {
            runCatching { part.delete() }
            false to "电视写入文件失败"
        }
    }

    /** 在电视上弹确认框并阻塞等结果；超时按「不接收」处理，同时把框收掉。 */
    private fun askOnMain(title: String,message: String,timeoutSeconds: Long=120L): Boolean {
        val latch=java.util.concurrent.CountDownLatch(1)
        val approved=java.util.concurrent.atomic.AtomicBoolean(false)
        val shown=java.util.concurrent.atomic.AtomicReference<AlertDialog?>()
        main.post {
            val next=TvDialog.confirm(activity,title,message) { approved.set(true) }
            next.setOnDismissListener { latch.countDown() }
            shown.set(next)
            next.show()
        }
        val answered=runCatching { latch.await(timeoutSeconds,java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
        if(!answered) main.post { runCatching { shown.get()?.takeIf { it.isShowing }?.dismiss() } }
        return answered && approved.get()
    }
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: AlertDialog,anchor: View?,fullscreen: Boolean=false,onDismiss: (()->Unit)?=null) {
        dialog?.dismiss(); dialog=next
        next.setOnDismissListener { if(dialog===next) { phone?.close(); phone=null; dialog=null }; if(anchor?.isAttachedToWindow==true) anchor.requestFocus(); onDismiss?.invoke() }
        if(fullscreen) {
            // 全屏预览：铺满整屏的纯黑底、去掉压暗层，图片/视频等比占满可用空间
            next.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
            next.show()
            next.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            next.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } else next.show()
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); worker.shutdownNow() }
    fun info(title: String,message: String,anchor: View?) = open(TvDialog.info(activity,title,message),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        open(TvDialog.confirm(activity,title,message,action),anchor)
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={},focusIndex: Int=-1,anchorToButton: Boolean=false) {
        // anchorToButton：弹窗悬浮到 anchor 按钮左侧（题材这类筛选器）；默认仍屏幕居中
        val next=TvDialog.choose(activity,title,labels,onChoice,extra,onExtra,focusIndex,anchorView=if(anchorToButton) anchor else null)
        open(next,anchor)
    }
    fun episodePicker(count: Int,current: Int,anchor: View?,select: (Int)->Unit) {
        val content=column()
        var digits=(current+1).toString(); var replace=true
        val value=label(digits,28f).apply { gravity=Gravity.CENTER; setTextColor(TvStyle.text) }; content.addView(value)
        val error=label("定位到该集，再按确认播放",14f).apply { setTextColor(TvStyle.muted) }; content.addView(error)
        fun digit(key: String) { digits=if(replace) key else (digits+key).take(6); replace=false; value.text=digits; error.text="定位到该集，再按确认播放" }
        fun erase() { digits=if(replace) "" else digits.dropLast(1); replace=false; value.text=digits.ifEmpty { "—" } }
        val buttons=mutableListOf<Button>()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("清空","0","退格")).forEach { keys ->
            val row=LinearLayout(activity)
            keys.forEach { key ->
                val button=Button(activity).apply {
                    id=View.generateViewId(); text=key; minHeight=dp(48)
                    textSize=15f
                    background=GradientDrawable().apply {
                        setColor(TvStyle.surface)
                        cornerRadius=dp(12).toFloat()
                        setStroke(dp(1),TvStyle.outline)
                    }
                    setTextColor(TvStyle.text)
                    setOnFocusChangeListener { _,focused ->
                        background=GradientDrawable().apply {
                            setColor(if(focused) TvStyle.accent else TvStyle.surface)
                            cornerRadius=dp(12).toFloat()
                            setStroke(dp(1),if(focused) TvStyle.accent else TvStyle.outline)
                        }
                        setTextColor(if(focused) TvStyle.background else TvStyle.text)
                    }
                    setOnClickListener { when(key) { "清空"->{ digits="";replace=false;value.text="—" }; "退格"->erase(); else->digit(key) } }
                }
                buttons+=button
                row.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
            }
            content.addView(row)
        }
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 键盘按钮是 weight 横排，自然测宽会把按钮量成 0 宽，必须固定宽度
        TvDialog.styleWindow(activity,dialog,widthDp=300)
        dialog.setOnKeyListener { _,key,event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if(event.action==KeyEvent.ACTION_DOWN) digit((key-KeyEvent.KEYCODE_0).toString()); true }
                key==KeyEvent.KEYCODE_DEL -> { if(event.action==KeyEvent.ACTION_DOWN) erase(); true }
                else -> false
            }
        }
        val actions=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.END
            setPadding(0,dp(14),0,0)
        }
        val cancel=TvDialog.capsuleButton(activity,"取消") { dialog.dismiss() }
        val locate=TvDialog.capsuleButton(activity,"定位") {
            val target=EpisodeTarget.parse(digits,count)
            if(target==null) error.text="请输入 1—$count 之间的集数"
            else { dialog.dismiss(); select(target) }
        }
        actions.addView(cancel,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
        actions.addView(locate,LinearLayout.LayoutParams(-2,-2))
        content.addView(actions)
        open(dialog,anchor)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=buttons[if(i%3==0) i else i-1].id
            button.nextFocusRightId=buttons[if(i%3==2) i else i+1].id
            button.nextFocusUpId=buttons[if(i<3) i else i-3].id
            button.nextFocusDownId=if(i<9) buttons[i+3].id else locate.id
        }
        locate.nextFocusUpId=buttons[10].id; cancel.nextFocusUpId=buttons[9].id
        buttons.first().requestFocus()
    }
    private fun qr(url: String)=TvDialog.qrView(activity,url)
    /** 搜索剧名场景的手机输入（文案固定为剧名）。 */
    fun phoneInput(anchor: View?,submit: (String)->Unit)=phoneForm(anchor,PhoneForm.search()) { values->submit(values["query"].orEmpty()) }
    /** 通用手机填写页：表单内容由调用方定义，填写完成后回传全部字段值。 */
    fun phoneForm(anchor: View?,form: PhoneForm,onSubmit: (Map<String,String>)->Unit) {
        val address=lanAddress()
        if(address==null) { info("手机填写","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,form.heading))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：自然测宽量不出文字列（0 宽消失），必须固定宽度
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=PhoneInputServer(address,form,{ values -> main.post { if(this@TvTools.dialog===dialog) { dialog.dismiss(); onSubmit(values) } } },{ main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("手机扫码打开，按提示填写，提交后发送到电视。\n仅限同一局域网，关闭即失效。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启手机填写，请检查网络后重试。" } }
        }
    }
    /** 源配置的手机扫码导入导出：手机页可下载当前源配置，也可上传配置文件追加到电视。 */
    fun sourceTransfer(config: String,anchor: View?,restore: (List<BackupThirdSource>)->Unit) {
        val address=lanAddress()
        if(address==null) { info("源配置导入/导出","请让电视和手机连接同一局域网后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"源配置导入/导出"))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=ThirdSourceTransferServer(address,config,{ sources -> main.post { if(this@TvTools.dialog===dialog) { dialog.dismiss(); restore(sources) } } },
                    { main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("扫码打开：可下载当前源配置，或上传配置恢复到电视。\n已有源不覆盖，仅追加缺失。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启源配置传输，请检查网络后重试。" } }
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val address=lanAddress()
        if(address==null) { info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"手机备份与恢复"))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(this@TvTools.dialog!==dialog) return@post
                    dialog.dismiss()
                    val preview=column()
                    preview.addView(TvDialog.title(activity,"确认恢复本机记录"))
                    preview.addView(label("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",16f))
                    val settings=CheckBox(activity).apply { text="同时恢复播放设置和内容分类"; isChecked=false; setTextColor(TvStyle.text) }; preview.addView(settings)
                    val confirmDialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,preview)).create()
                    TvDialog.styleWindow(activity,confirmDialog)
                    val actions=LinearLayout(activity).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=Gravity.END
                        setPadding(0,dp(14),0,0)
                    }
                    val cancel=TvDialog.capsuleButton(activity,"取消") { confirmDialog.dismiss() }
                    val ok=TvDialog.capsuleButton(activity,"合并恢复") { confirmDialog.dismiss(); restore(data,settings.isChecked) }
                    actions.addView(cancel,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
                    actions.addView(ok,LinearLayout.LayoutParams(-2,-2))
                    preview.addView(actions)
                    open(confirmDialog,anchor); cancel.requestFocus()
                } },{ main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("扫码用浏览器下载备份，或选择备份文件恢复。\n仅限同一局域网，恢复需在电视确认。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
    fun backdropTransfer(anchor: View?,onImage: (ByteArray,String)->Unit) {
        val address=lanAddress()
        if(address==null) { info("自定义背景","请让电视和手机连接同一无线网后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"自定义背景"))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=BackdropTransferServer(address,{ bytes,ext -> main.post { if(this@TvTools.dialog===dialog) dialog.dismiss(); onImage(bytes,ext) } },
                    { main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("扫码或打开下方地址，上传图片设为电视背景。\n手机与电视需同一无线网。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启图片上传，请检查网络后重试。" } }
        }
    }
    /** 手机快传：扫码直传文件。APK 收到后交给调用方引导安装，其余文件落私有目录。 */
    fun quickTransfer(anchor: View?,onReceived: (File,TransferKind)->Unit,onClose: ()->Unit={},onShowFiles: ()->Unit={}) {
        val address=lanAddress()
        if(address==null) { info("手机快传","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"手机快传"))
        val status=label("正在生成二维码…").apply { isFocusable=true }; content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        var views: QuickViews?=null
        var receivedFiles=0
        var session: QuickTransferServer?=null
        var countdown: TextView?=null
        val sessionStart=java.util.concurrent.atomic.AtomicLong(0)
        val TTL_MS=600_000L
        val spaceLabel={ "电视可用 ${TransferRules.humanBytes(freeBytes())}  ·  单个文件不超过 ${TransferRules.humanBytes(TransferRules.MAX_BYTES)}" }
        val countdownTick=object: Runnable {
            override fun run() {
                val cd=countdown ?: return
                if(this@TvTools.dialog!==dialog) return
                val elapsed=(System.nanoTime()-sessionStart.get())/1_000_000L
                val remain=(TTL_MS-elapsed).coerceAtLeast(0)
                val mm=remain/60_000; val ss=(remain%60_000)/1000
                cd.text=if(remain>0) "二维码有效期 · 剩余 %02d:%02d".format(mm,ss) else "二维码已过期，请刷新"
                cd.setTextColor(if(remain>0) TvStyle.accent else TvStyle.muted)
                if(remain>0) main.postDelayed(this,1000L)
            }
        }
        open(dialog,anchor) { main.removeCallbacks(countdownTick); onClose() }
        dialog.window?.decorView?.post { status.requestFocus() }
        /** [refocusRefresh] 为 true 表示这次重建是「刷新二维码」触发的，焦点要留在原按钮上。 */
        fun startSession(refocusRefresh: Boolean=false) {
            status.text="正在生成二维码…"
            worker.execute {
                try {
                    val s=QuickTransferServer(address,{ kind -> quickDirectory(kind) },{ freeBytes() },
                        { name,received,total,index,count -> main.post {
                            if(this@TvTools.dialog!==dialog) return@post
                            val current=views ?: return@post
                            current.name.text="$name  ·  第 $index/$count 个"
                            current.bar.progress=((received*100)/total.coerceAtLeast(1)).toInt()
                            current.detail.text="${TransferRules.humanBytes(received)} / ${TransferRules.humanBytes(total)}"
                        } },
                        { file,kind -> main.post {
                            if(this@TvTools.dialog===dialog) {
                                receivedFiles++
                                views?.let { current->
                                    current.received.text="本次已接收 $receivedFiles 个文件"
                                    current.name.text="${file.name} 已保存到电视"
                                    current.bar.progress=100
                                    current.space.text=spaceLabel()
                                }
                            }
                            onReceived(file,kind)
                        } },
                        { main.post { if(this@TvTools.dialog===dialog) { countdown?.text="二维码已过期，请刷新"; countdown?.setTextColor(TvStyle.muted) } } })
                    main.post {
                        if(this@TvTools.dialog!==dialog) { s.close(); return@post }
                        session?.close(); session=s; phone=s; sessionStart.set(System.nanoTime())
                        content.removeAllViews()
                        content.addView(TvDialog.qrRow(activity, qr(s.url),
                            label("手机扫码打开，选择文件发送到电视。\nAPK 会提示安装，视频图片存入电视。",16f),
                            TvDialog.urlLabel(activity, s.url)))
                        countdown=label("二维码有效期 · 剩余 10:00",13f).apply { setTextColor(TvStyle.accent); setPadding(0,dp(6),0,dp(2)) }; content.addView(countdown)
                        val space=label(spaceLabel(),13f).apply { setTextColor(TvStyle.muted) }; content.addView(space)
                        val name=label("等待手机发送…",15f); content.addView(name)
                        val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                            max=100; progress=0
                            progressTintList=android.content.res.ColorStateList.valueOf(TvStyle.accent)
                            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(TvStyle.outline)
                        }
                        content.addView(bar,LinearLayout.LayoutParams(-1,dp(6)))
                        val detail=label("",13f).apply { setTextColor(TvStyle.muted); setPadding(dp(8),0,dp(8),0) }; content.addView(detail)
                        // 进度条下的接收计数：紧贴进度条，不要默认 4dp 上下内边距撑出空隙
                        val received=label("本次已接收 0 个文件",13f).apply { setTextColor(TvStyle.muted); setPadding(dp(8),dp(1),dp(8),dp(2)) }; content.addView(received)
                        // 四个按钮按权重铺满整行：此前 wrap_content + 34dp 高，宽度只比高度多一点，
                        // 24dp 圆角被裁成半圆，看起来全是圆球；且行靠右，左侧空出一大块。
                        // 拉长后配 10dp 圆角变成长条圆角按钮，左侧空间也用上，焦点目标更大。
                        val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(12),0,0) }
                        val filesBtn=TvDialog.capsuleButton(activity,"快传文件",compact=true,radiusDp=10) { onShowFiles() }
                        val refresh=TvDialog.capsuleButton(activity,"刷新二维码",compact=true,radiusDp=10) { startSession(true) }
                        // 文案压短：这一行四个按钮，6 个字会挤到换行
                        val clear=TvDialog.capsuleButton(activity,"清空文件",compact=true,radiusDp=10) {
                            TvDialog.confirm(activity,"清空快传文件","删除通过手机快传收到的全部文件，不影响收藏、观看记录和已安装的应用。") {
                                worker.execute {
                                    listOf(TransferKind.APK,TransferKind.OTHER).map { quickDirectory(it) }.distinct().forEach { dir ->
                                        dir.listFiles()?.filter { !it.name.endsWith(TransferRules.PART_SUFFIX) }?.forEach { it.deleteRecursively() }
                                    }
                                    main.post {
                                        if(this@TvTools.dialog!==dialog) return@post
                                        receivedFiles=0
                                        received.text="本次已接收 0 个文件"
                                        space.text=spaceLabel()
                                        name.text="等待手机发送…"; bar.progress=0; detail.text=""
                                    }
                                }
                            }.show()
                        }
                        val close=TvDialog.capsuleButton(activity,"完成",compact=true,radiusDp=10) { dialog.dismiss() }
                        listOf(filesBtn,refresh,clear,close).forEach { it.setPadding(dp(10),0,dp(10),0) }
                        filesBtn.id=View.generateViewId(); refresh.id=View.generateViewId(); clear.id=View.generateViewId(); close.id=View.generateViewId()
                        filesBtn.nextFocusRightId=refresh.id; refresh.nextFocusLeftId=filesBtn.id; refresh.nextFocusRightId=clear.id
                        clear.nextFocusLeftId=refresh.id; clear.nextFocusRightId=close.id; close.nextFocusLeftId=clear.id
                        // 按字数微调权重：五个字的「刷新二维码」多分一点，避免等宽时折成两行、
                        // 把整排按钮撑高；「完成」字少略短一点，但都比原来的圆形按钮宽得多。
                        // setSingleLine 兜住各家电视字体偏宽的情况，宁可紧凑也不让按钮变两行。
                        val weights=floatArrayOf(1f,1.25f,1f,0.9f)
                        listOf(filesBtn,refresh,clear,close).forEachIndexed { index, button ->
                            button.setSingleLine(true)
                            actions.addView(button,LinearLayout.LayoutParams(0,-2,weights[index]).apply { if(index<3) rightMargin=dp(8) })
                        }
                        content.addView(actions)
                        views=QuickViews(name,bar,detail,received,space)
                        main.removeCallbacks(countdownTick); main.post(countdownTick)
                        // 刷新二维码后焦点留在「刷新二维码」上，别每次跳回「完成」
                        dialog.window?.decorView?.post { if(refocusRefresh) refresh.requestFocus() else close.requestFocus() }
                    }
                } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启手机快传，请检查网络后重试。" } }
            }
        }
        startSession()
    }
    /**
     * 快传图片全屏预览：整屏黑底等比显示，进入时只显示图片本身，
     * 确认键或点击画面才唤出顶部信息条（文件名+路径）与底部操作条，再按一次隐藏。
     * 唤出时焦点落在「关闭」，返回键先收起浮层、再按退出。
     */
    fun previewImage(anchor: View?,file: File,onSetBackdrop: ()->Unit) {
        val status=label("正在加载…",13f).apply { setTextColor(0x99FFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(dp(24),dp(12),dp(24),dp(12)) }
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
        val root=FrameLayout(activity).apply { setBackgroundColor(Color.BLACK); isClickable=true }
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val info=fullscreenInfoBar(file.name,file.absolutePath)
        root.addView(info,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        // 底部操作条：左提示、右按钮，压在全屏图上
        val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(12)) }
        lateinit var next: AlertDialog
        val close=TvDialog.capsuleButton(activity,"关闭") { next.dismiss() }
        val set=TvDialog.capsuleButton(activity,"设为电视背景") { next.dismiss(); onSetBackdrop() }
        close.id=View.generateViewId(); set.id=View.generateViewId()
        close.nextFocusRightId=set.id; set.nextFocusLeftId=close.id
        actions.addView(fullscreenHint("返回键退出"),LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(close,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
        actions.addView(set,LinearLayout.LayoutParams(-2,-2))
        root.addView(actions,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        next=AlertDialog.Builder(activity).setView(root).create()
        val chrome=toggleChrome(next,info,actions,onShow={ close.requestFocus() })
        root.setOnClickListener { chrome(info.visibility!=View.VISIBLE) }
        open(next,anchor,fullscreen=true)
        worker.execute {
            val bitmap=runCatching { previewBitmap(file) }.getOrNull()
            main.post {
                if(this@TvTools.dialog!==next) return@post
                if(bitmap==null) status.text="无法预览这张图片"
                else { status.text=""; image.setImageBitmap(bitmap) }
            }
        }
    }
    /**
     * 快传视频全屏播放：整屏黑底等比播放，进入时只显示画面，
     * 确认键或点击画面唤出/收起顶部信息与底部提示；浮层显示时确认键改为暂停/继续，
     * 播放/暂停键随时可用；返回键先收起浮层、再按退出；播完自动退出。
     */
    fun playVideo(anchor: View?,file: File) {
        val view=PlayerView(activity).apply { useController=false; isFocusable=false; setBackgroundColor(Color.BLACK) }
        val root=FrameLayout(activity).apply { setBackgroundColor(Color.BLACK); isClickable=true }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        val info=fullscreenInfoBar(file.name,file.absolutePath)
        root.addView(info,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val hint=fullscreenHint("确认键暂停 / 继续 · 返回键退出")
        root.addView(hint,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        val next=AlertDialog.Builder(activity).setView(root).create()
        val player=ExoPlayer.Builder(activity).build()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare(); player.playWhenReady=true
        view.player=player
        player.addListener(object: androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if(playbackState==androidx.media3.common.Player.STATE_ENDED) next.dismiss()
            }
        })
        fun syncHint() {
            hint.text=if(player.playWhenReady) "确认键唤出浮层后暂停 / 继续 · 返回键退出" else "已暂停 · 确认键继续 · 返回键退出"
        }
        val chrome=toggleChrome(next,info,hint,
            onCenterVisible={ player.playWhenReady=!player.playWhenReady; syncHint(); true },
            onMediaKey={ player.playWhenReady=!player.playWhenReady; syncHint(); true })
        root.setOnClickListener { chrome(info.visibility!=View.VISIBLE) }
        open(next,anchor) { player.release(); view.player=null }
    }
    /**
     * 全屏预览浮层开关：初始隐藏全部浮层，统一接管对话框按键——
     * 确认键在浮层隐藏时唤出（[onCenterVisible] 处理浮层显示时的确认键），
     * 返回键先收起浮层、再按才退出。返回 (visible)->Unit 供点击画面时切换。
     */
    private fun toggleChrome(dialog: AlertDialog,vararg layers: View,
        onCenterVisible: (()->Boolean)?=null,onMediaKey: (()->Boolean)?=null,onShow: (()->Unit)?=null): (Boolean)->Unit {
        layers.forEach { it.visibility=View.GONE }
        var visible=false
        fun show(value: Boolean) {
            if(dialog!==this@TvTools.dialog) return
            visible=value
            layers.forEach { it.visibility=if(value) View.VISIBLE else View.GONE }
            onShow?.takeIf { value }?.invoke()
        }
        dialog.setOnKeyListener { _,key,event ->
            if(event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when(key) {
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER ->
                    if(!visible) { show(true); true } else onCenterVisible?.invoke() ?: false
                KeyEvent.KEYCODE_BACK -> if(visible) { show(false); true } else false
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> onMediaKey?.invoke() ?: false
                else -> false
            }
        }
        return ::show
    }
    /** 全屏预览顶部信息条：半透明黑底，文件名加亮、路径弱化，深浅主题都可读。 */
    private fun fullscreenInfoBar(name: String,path: String)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(10))
        background=GradientDrawable().apply { setColor(0x88000000.toInt()) }
        addView(label(name,16f).apply { setTextColor(Color.WHITE) })
        addView(label("位置 · $path",11f).apply { setTextColor(0x99FFFFFF.toInt()) })
    }
    /** 全屏预览底部弱化提示文字。 */
    private fun fullscreenHint(value: String)=TextView(activity).apply {
        text=value; textSize=12f; setTextColor(0x99FFFFFF.toInt())
        setPadding(dp(20),dp(8),dp(20),dp(12))
    }
    /** 按屏幕宽度采样解码，避免超大图片直接把内存打满。 */
    private fun previewBitmap(file: File): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        val screen=activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var sample=1
        while(bounds.outWidth/(sample*2)>=screen) sample*=2
        return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample })
    }
    /**
     * 语音「清空文件」：删掉快传目录里的成品文件，回报删了多少个、腾出多少字节。
     *
     * 这里不弹二次确认——话说出口就是确认，再让人摸遥控器按一次，语音入口就白做了。
     * 删的只是手机传过来的文件，收藏、观看记录、已安装应用都不动。
     *
     * 先 [close]：面板上写着「本次已接收 N 个」，文件被别处删掉后那个数字就不实了，
     * 留着比关掉更让人困惑。顺带把快传会话一起收掉，下次打开是一张新码。
     */
    fun clearQuickFiles(onDone: (count: Int,bytes: Long)->Unit) {
        close()
        worker.execute {
            var count=0
            var bytes=0L
            quickDirectories().forEach { dir ->
                dir.listFiles()?.filter { !it.name.endsWith(TransferRules.PART_SUFFIX) }?.forEach { file ->
                    val size=if(file.isDirectory) 0L else file.length()
                    if(runCatching { file.deleteRecursively() }.getOrDefault(false)) { count++; bytes+=size }
                }
            }
            main.post { onDone(count,bytes) }
        }
    }
    /** 已收文件列表：查看历史上传、按类型筛选、单条删除；确认键执行各类型的主操作。 */
    /**
     * 语音 / 遥控入口：取最近一个快传文件（kind 为 null 时不挑类型），回调在主线程。
     * 「预览快传文件」「设为电视背景」都走这里，和文件列表共用同一套排序与保留策略。
     */
    fun recentQuickFile(kind: TransferKind?,onResult: (ReceivedFile?)->Unit) {
        val directories=quickDirectories()
        worker.execute {
            val found=runCatching { TransferFiles.prune(directories); TransferFiles.list(directories) }
                .getOrNull()?.firstOrNull { kind==null || it.kind==kind }
            main.post { onResult(found) }
        }
    }

    fun quickFiles(anchor: View?,onPrimary: (File,TransferKind)->Unit,onTransfer: ()->Unit,onClose: ()->Unit={}) {
        val content=column(); content.addView(TvDialog.title(activity,"快传文件"))
        val status=label("正在读取…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        TvDialog.styleWindow(activity,dialog,widthDp=520)
        open(dialog,anchor,onDismiss=onClose)
        val directories=quickDirectories()
        worker.execute {
            val files=runCatching { TransferFiles.prune(directories); TransferFiles.list(directories) }.getOrNull()
            main.post {
                if(this@TvTools.dialog!==dialog) return@post
                if(files==null) { status.text="读取快传文件失败，请重试"; return@post }
                paintQuickFiles(dialog,content,directories,files,onPrimary,onTransfer)
            }
        }
    }
    private fun paintQuickFiles(
        dialog: AlertDialog,
        content: LinearLayout,
        directories: List<File>,
        initial: List<ReceivedFile>,
        onPrimary: (File,TransferKind)->Unit,
        onTransfer: ()->Unit,
    ) {
        var all=initial
        var filter: TransferKind?=null
        var removeFile: (ReceivedFile)->Unit={}

        fun chipBackground(active: Boolean,focused: Boolean)=GradientDrawable().apply {
            setColor(if(focused) TvStyle.raised else TvStyle.background)
            cornerRadius=dp(999).toFloat()
            if(active || focused) setStroke(dp(1),TvStyle.accent) else setStroke(dp(1),TvStyle.outline)
        }
        fun chipLabel(kind: TransferKind?)=when(kind) {
            null->"全部"; TransferKind.APK->"安装包"; TransferKind.VIDEO->"视频"; TransferKind.IMAGE->"图片"; TransferKind.OTHER->"其它"
        }
        /** focusRows=true 焦点落在第一条文件，否则落在当前筛选标签上。 */
        fun paint(focusRows: Boolean) {
            content.removeAllViews()
            content.addView(TvDialog.title(activity,"快传文件"))
            val total=all.sumOf { it.file.length() }
            content.addView(label("共 ${all.size} 个 · 合计 ${TransferRules.humanBytes(total)} · 电视可用 ${TransferRules.humanBytes(freeBytes())}",13f).apply { setTextColor(TvStyle.muted) })
            if(all.isEmpty()) {
                content.addView(label("还没有收到文件。\n手机快传扫码上传后，文件会出现在这里。",15f).apply {
                    gravity=Gravity.CENTER; setTextColor(TvStyle.muted); setPadding(0,dp(46),0,dp(46))
                })
                val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.END; setPadding(0,dp(12),0,0) }
                val transfer=TvDialog.capsuleButton(activity,"手机快传") { onTransfer() }
                val close=TvDialog.capsuleButton(activity,"关闭") { dialog.dismiss() }
                transfer.id=View.generateViewId(); close.id=View.generateViewId()
                transfer.nextFocusRightId=close.id; close.nextFocusLeftId=transfer.id
                actions.addView(transfer,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
                actions.addView(close,LinearLayout.LayoutParams(-2,-2))
                content.addView(actions)
                transfer.post { if(transfer.isAttachedToWindow) transfer.requestFocus() }
                return
            }
            // 筛选标签：只列实际存在的类型，数量直接写在标签上
            val options=listOf<TransferKind?>(null)+TransferKind.entries.filter { kind -> all.any { it.kind==kind } }
            val chipViews=mutableListOf<TextView>()
            val chips=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(10),0,dp(10)) }
            options.forEach { kind ->
                val active=filter==kind
                val count=if(kind==null) all.size else all.count { it.kind==kind }
                val chip=TextView(activity).apply {
                    id=View.generateViewId(); text="${chipLabel(kind)} $count"; textSize=12f; gravity=Gravity.CENTER
                    isFocusable=true; isClickable=true
                    setPadding(dp(12),dp(5),dp(12),dp(5))
                    setTextColor(if(active) TvStyle.accent else TvStyle.muted)
                    background=chipBackground(active,false)
                    setOnFocusChangeListener { view,_ ->
                        view.background=chipBackground(active,view.isFocused)
                        (view as TextView).setTextColor(if(active || view.isFocused) TvStyle.accent else TvStyle.muted)
                    }
                    setOnClickListener { filter=kind; paint(false) }
                }
                chips.addView(chip,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(8) })
                chipViews+=chip
            }
            chipViews.forEachIndexed { index,chip ->
                chip.nextFocusLeftId=chipViews.getOrNull(index-1)?.id ?: chip.id
                chip.nextFocusRightId=chipViews.getOrNull(index+1)?.id ?: chip.id
            }
            content.addView(chips)

            val adapter=QuickFilesAdapter(activity,{ item -> onPrimary(item.file,item.kind) },{ item -> removeFile(item) })
            val list=RecyclerView(activity).apply {
                id=View.generateViewId()
                layoutManager=LinearLayoutManager(activity); itemAnimator=null
            }
            list.adapter=adapter
            adapter.submit(if(filter==null) all else all.filter { it.kind==filter })
            content.addView(list,LinearLayout.LayoutParams(-1,dp(290)))
            val footer=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(10),0,0) }
            footer.addView(label("确认 = 安装 / 预览 · 右移到「删除」",12f).apply { setTextColor(TvStyle.muted) },LinearLayout.LayoutParams(0,-2,1f))
            val clear=TvDialog.capsuleButton(activity,"清空全部") {
                TvDialog.confirm(activity,"清空快传文件","删除全部 ${all.size} 个文件，合计 ${TransferRules.humanBytes(total)}，不影响收藏、观看记录和已安装的应用。") {
                    worker.execute {
                        directories.forEach { dir ->
                            dir.listFiles()?.filter { !it.name.endsWith(TransferRules.PART_SUFFIX) }?.forEach { it.deleteRecursively() }
                        }
                        main.post { if(this@TvTools.dialog===dialog) { all=emptyList(); filter=null; paint(true) } }
                    }
                }.show()
            }
            val close=TvDialog.capsuleButton(activity,"关闭") { dialog.dismiss() }
            clear.id=View.generateViewId(); close.id=View.generateViewId()
            clear.nextFocusRightId=close.id; close.nextFocusLeftId=clear.id
            footer.addView(clear,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
            footer.addView(close,LinearLayout.LayoutParams(-2,-2))
            content.addView(footer)
            val wanted=options.indexOf(filter)
            list.post {
                if(this@TvTools.dialog!==dialog) return@post
                // 上下方向的跨区跳转交给系统按位置查找：标签在上、列表在下、按钮在底部
                val target=if(focusRows) list.getChildAt(0) else chipViews.getOrNull(wanted)
                target?.requestFocus()
            }
        }
        removeFile={ item ->
            TvDialog.confirm(activity,"删除文件","删除「${item.name}」，删除后无法恢复。") {
                worker.execute {
                    val deleted=runCatching { item.file.delete() }.getOrDefault(false)
                    main.post {
                        if(this@TvTools.dialog!==dialog) return@post
                        if(!deleted) Toast.makeText(activity,"文件已被删除或无法访问",Toast.LENGTH_SHORT).show()
                        all=all.filterNot { it.file==item.file }
                        paint(true)
                    }
                }
            }.show()
        }
        paint(true)
    }
    private class QuickViews(val name: TextView,val bar: ProgressBar,val detail: TextView,val received: TextView,val space: TextView)
}
