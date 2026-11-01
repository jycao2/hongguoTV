// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
    private fun lanAddress(): String? {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork ?: return null
        val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
        if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
    private fun freeBytes()=runCatching { android.os.StatFs(activity.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    /** 快传落盘目录：都在 filesDir 下，卸载应用时随应用数据一起清除。 */
    private fun quickDirectory(kind: TransferKind)=File(activity.filesDir,TransferRules.directoryName(kind))
    private fun quickDirectories()=TransferKind.entries.map { quickDirectory(it) }.distinct()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: AlertDialog,anchor: View?,onDismiss: (()->Unit)?=null) {
        dialog?.dismiss(); dialog=next
        next.setOnDismissListener { if(dialog===next) { phone?.close(); phone=null; dialog=null }; if(anchor?.isAttachedToWindow==true) anchor.requestFocus(); onDismiss?.invoke() }
        next.show()
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); worker.shutdownNow() }
    fun info(title: String,message: String,anchor: View?) = open(TvDialog.info(activity,title,message),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        open(TvDialog.confirm(activity,title,message,action),anchor)
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={}) {
        val next=TvDialog.choose(activity,title,labels,onChoice,extra,onExtra)
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
        TvDialog.styleWindow(activity,dialog)
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
    private fun qr(url: String)=ImageView(activity).apply {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
        contentDescription="二维码"
    }
    /** 搜索剧名场景的手机输入（文案固定为剧名）。 */
    fun phoneInput(anchor: View?,submit: (String)->Unit)=phoneForm(anchor,PhoneForm.search()) { values->submit(values["query"].orEmpty()) }
    /** 通用手机填写页：表单内容由调用方定义，填写完成后回传全部字段值。 */
    fun phoneForm(anchor: View?,form: PhoneForm,onSubmit: (Map<String,String>)->Unit) {
        val address=lanAddress()
        if(address==null) { info("手机填写","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,form.heading))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        TvDialog.styleWindow(activity,dialog)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=PhoneInputServer(address,form,{ values -> main.post { if(this@TvTools.dialog===dialog) { dialog.dismiss(); onSubmit(values) } } },{ main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码后用浏览器打开，按页面提示填写，提交后内容会发送到电视。\n需连接同一局域网；关闭窗口即失效，最长 5 分钟。",16f))
                    instructions.addView(label(session.url,12f).apply { setTextColor(TvStyle.muted) })
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
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
        TvDialog.styleWindow(activity,dialog)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=ThirdSourceTransferServer(address,config,{ sources -> main.post { if(this@TvTools.dialog===dialog) { dialog.dismiss(); restore(sources) } } },
                    { main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码后用浏览器打开：可下载当前源配置，或选择配置文件恢复到电视。\n已有源不会被覆盖，只追加缺失的源；仅限同一局域网，最长 5 分钟。",16f))
                    instructions.addView(label(session.url,12f).apply { setTextColor(TvStyle.muted) })
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
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
        TvDialog.styleWindow(activity,dialog)
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
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",16f))
                    instructions.addView(label(session.url,12f).apply { setTextColor(TvStyle.muted) })
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
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
        TvDialog.styleWindow(activity,dialog)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=BackdropTransferServer(address,{ bytes,ext -> main.post { if(this@TvTools.dialog===dialog) dialog.dismiss(); onImage(bytes,ext) } },
                    { main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码，或用浏览器打开下方地址，上传图片设为电视背景。\n手机和电视需连接同一无线网；关闭窗口即失效，最长 5 分钟。",16f))
                    instructions.addView(label(session.url,12f).apply { setTextColor(TvStyle.muted) })
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启图片上传，请检查网络后重试。" } }
        }
    }
    /** 手机快传：扫码直传文件。APK 收到后交给调用方引导安装，其余文件落私有目录。 */
    fun quickTransfer(anchor: View?,onReceived: (File,TransferKind)->Unit,onClose: ()->Unit={}) {
        val address=lanAddress()
        if(address==null) { info("手机快传","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"手机快传"))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        TvDialog.styleWindow(activity,dialog)
        open(dialog,anchor,onClose)
        var views: QuickViews?=null
        var receivedFiles=0
        val spaceLabel={ "电视可用 ${TransferRules.humanBytes(freeBytes())}  ·  单个文件不超过 ${TransferRules.humanBytes(TransferRules.MAX_BYTES)}" }
        worker.execute {
            try {
                val session=QuickTransferServer(address,{ kind -> quickDirectory(kind) },{ freeBytes() },
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
                    { main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码后用浏览器打开，选择文件发送到电视。\nAPK 会提示安装，视频和图片存入电视；失败的文件不留痕迹。\n关闭窗口即失效，最长 5 分钟。",16f))
                    instructions.addView(label(session.url,12f).apply { setTextColor(TvStyle.muted) })
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                    val space=label(spaceLabel(),13f).apply { setTextColor(TvStyle.muted) }; content.addView(space)
                    val name=label("等待手机发送…",15f); content.addView(name)
                    val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                        max=100; progress=0
                        progressTintList=android.content.res.ColorStateList.valueOf(TvStyle.accent)
                        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(TvStyle.outline)
                    }
                    content.addView(bar,LinearLayout.LayoutParams(-1,dp(6)))
                    val detail=label("",13f).apply { setTextColor(TvStyle.muted) }; content.addView(detail)
                    val received=label("本次已接收 0 个文件",13f).apply { setTextColor(TvStyle.muted) }; content.addView(received)
                    val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.END; setPadding(0,dp(12),0,0) }
                    val clear=TvDialog.capsuleButton(activity,"清空快传文件") {
                        TvDialog.confirm(activity,"清空快传文件","删除通过手机快传收到的全部文件，不影响收藏、观看记录和已安装的应用。") {
                            worker.execute {
                                // 进行中的 .part 保留，避免把手机端正在传的文件打断
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
                    val close=TvDialog.capsuleButton(activity,"完成") { dialog.dismiss() }
                    clear.id=View.generateViewId(); close.id=View.generateViewId()
                    clear.nextFocusRightId=close.id; close.nextFocusLeftId=clear.id
                    actions.addView(clear,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
                    actions.addView(close,LinearLayout.LayoutParams(-2,-2))
                    content.addView(actions)
                    views=QuickViews(name,bar,detail,received,space)
                    close.post { if(close.isAttachedToWindow) close.requestFocus() }
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启手机快传，请检查网络后重试。" } }
        }
    }
    /** 快传图片预览：后台解码大图，弹窗内显示实际保存路径，并可直接设为电视背景。 */
    fun previewImage(anchor: View?,file: File,onSetBackdrop: ()->Unit) {
        val content=column(); content.addView(TvDialog.title(activity,file.name))
        content.addView(label("位置 · ${file.absolutePath}",11f).apply { setTextColor(TvStyle.muted) })
        val status=label("正在加载…",12f).apply { text="正在加载…"; setTextColor(TvStyle.muted) }
        content.addView(status)
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(0,dp(8),0,dp(8)) }
        content.addView(image,LinearLayout.LayoutParams(-1,dp(400)))
        val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.END; setPadding(0,dp(10),0,0) }
        lateinit var next: AlertDialog
        val close=TvDialog.capsuleButton(activity,"关闭") { next.dismiss() }
        val set=TvDialog.capsuleButton(activity,"设为电视背景") { next.dismiss(); onSetBackdrop() }
        close.id=View.generateViewId(); set.id=View.generateViewId()
        close.nextFocusRightId=set.id; set.nextFocusLeftId=close.id
        actions.addView(close,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
        actions.addView(set,LinearLayout.LayoutParams(-2,-2))
        content.addView(actions)
        next=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        TvDialog.styleWindow(activity,next,0.8f,560)
        open(next,anchor)
        close.post { if(this@TvTools.dialog===next) close.requestFocus() }
        worker.execute {
            val bitmap=runCatching { previewBitmap(file) }.getOrNull()
            main.post {
                if(this@TvTools.dialog!==next) return@post
                if(bitmap==null) status.text="无法预览这张图片"
                else { status.text=""; image.setImageBitmap(bitmap) }
            }
        }
    }
    /** 快传视频播放：内置播放器弹窗，确认键暂停/继续，返回键退出。 */
    fun playVideo(anchor: View?,file: File) {
        val content=column(); content.addView(TvDialog.title(activity,file.name))
        content.addView(label("位置 · ${file.absolutePath}",11f).apply { setTextColor(TvStyle.muted) })
        val status=label("正在播放 · 确认键暂停，返回键退出",12f).apply { setTextColor(TvStyle.muted) }
        val view=PlayerView(activity).apply { useController=false; isFocusable=false; setBackgroundColor(Color.BLACK) }
        content.addView(view,LinearLayout.LayoutParams(-1,dp(380)))
        content.addView(status)
        val next=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        TvDialog.styleWindow(activity,next,0.9f,620)
        val player=ExoPlayer.Builder(activity).build()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare(); player.playWhenReady=true
        view.player=player
        next.setOnKeyListener { _,key,event ->
            if(event.action==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                player.playWhenReady=!player.playWhenReady
                status.text=if(player.playWhenReady) "正在播放 · 确认键暂停，返回键退出" else "已暂停 · 确认键继续，返回键退出"
                true
            } else false
        }
        open(next,anchor) { player.release(); view.player=null }
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
    /** 已收文件列表：查看历史上传、按类型筛选、单条删除；确认键执行各类型的主操作。 */
    fun quickFiles(anchor: View?,onPrimary: (File,TransferKind)->Unit,onTransfer: ()->Unit,onClose: ()->Unit={}) {
        val content=column(); content.addView(TvDialog.title(activity,"快传文件"))
        val status=label("正在读取…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        TvDialog.styleWindow(activity,dialog,0.6f,560)
        open(dialog,anchor,onClose)
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
