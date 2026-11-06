package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.LanRemoteHub
import com.hongguotv.core.RemoteController
import com.hongguotv.core.RemoteTarget
import com.hongguotv.core.ResumeItem
import java.io.Closeable

/**
 * 手机遥控的会话持有者：二维码窗口 + Hub 生命周期。
 *
 * **不能挂在 `TvTools` 上**。`TvTools.open()` 每次打开新弹窗都会 dismiss 上一个，
 * 而 dismiss 监听里会 `phone?.close()`；`onStop` 也会 `tvTools.close()`。
 * 遥控是长连接，必须由 Activity 级字段独立持有，否则用户点开「选集」或按返回就断连。
 */
class PhoneRemoteSession(
    private val activity: Activity,
    private val address: String,
    private val target: () -> RemoteTarget?,
    private val controller: RemoteController,
    private val resume: () -> List<ResumeItem>,
    /** 「更多」弹层的全量记录（本机全部 + 云端补后）。 */
    private val resumeAll: () -> List<ResumeItem> = { emptyList() },
    /** 封面代取：手机直接加载原始地址过不了 CDN 校验，交回电视这边取。 */
    private val cover: (String) -> ByteArray? = { null },
    /** 连接数变化。列表页顶栏那个入口要跟着改文案，否则永远显示「手机遥控」。 */
    private val onClients: (Int) -> Unit = {},
    private val onClosed: () -> Unit
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var status: TextView? = null
    private var closed = false

    val hub = LanRemoteHub(
        address = address,
        post = { main.post(it) },
        target = target,
        controller = { controller },
        resumeItems = resume,
        resumeAllItems = resumeAll,
        coverBytes = cover,
        onExpired = { main.post { close() } },
        // 进程内复用同一把密钥：关掉窗口再打开，手机上那个页面不用重扫
        fixedToken = RemoteAccess.token,
        // 端口也跟着复用，否则 token 没变、地址的端口变了，一样连不上
        preferredPort = RemoteAccess.port,
        // 永久遥控：空闲不回收（默认 30 分钟没人用就关服会让旧地址失效）。
        // 一年 = 实际上永不触发；真正断连由手机页面关闭（SSE 断开）自然完成。
        idleTimeoutMs = 365L * 24 * 60 * 60 * 1000,
        // 真机排查长连接问题用：adb logcat -s HongguoRemote（部分电视只放行 WARN 以上）
        trace = { android.util.Log.w("HongguoRemote", it) }
    )

    init {
        // 实际绑定的端口可能因为被占用而变了，回写以便下次优先用这个
        RemoteAccess.port = hub.port
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun show(anchor: View?) {
        if(dialog?.isShowing == true) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        content.addView(TvDialog.title(activity, "手机遥控"))

        // 右侧文案列固定与二维码同高（见 TvDialog.qrRow），文案量控制在 185dp 内
        content.addView(TvDialog.qrRow(activity, qrImage(hub.url),
            label("手机扫码打开，即可遥控播放、选集与搜索。", 16f),
            label("地址永久有效，下次在手机直接打开即可。", 13f).apply { setTextColor(TvStyle.muted) },
            TvDialog.urlLabel(activity, hub.url),
            label("等待手机连接…", 13f).apply { setTextColor(TvStyle.gold) }.also { status = it }
        ))

        // 配套的遥控器桌面工具（PWA）入口：点开展示安装二维码
        val tool = TextView(activity).apply {
            text = "配套的遥控器桌面工具  ›"
            textSize = 14f
            setTextColor(TvStyle.accent)
            background = TvStyle.shape(activity, TvStyle.surface)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            setOnClickListener { showToolDialog() }
        }
        content.addView(tool, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        val window = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        // 必须显式宽度：行里是「二维码 + weight 文字列」，自然测宽把文字列量成 0 宽（文字消失、弹窗虚高）
        TvDialog.styleWindow(activity, window, widthDp = 440)
        window.setOnDismissListener {
            // 会话是永久遥控的一部分，关窗口只收二维码这层 UI；服务继续挂着，
            // 手机随时能用旧地址直连。窗口随时可以从顶栏再打开。
            dialog = null
            if(anchor?.isAttachedToWindow == true) anchor.requestFocus()
        }
        dialog = window
        window.show()
    }

    /** 配套 PWA 遥控器桌面工具的安装地址。 */
    private val toolUrl = "https://hongguo-remote-h5.app.workbuddy.host/?sharecode=G3AWH3OYSU"

    /** 遥控器桌面工具（PWA）二维码弹窗：扫码打开后可添加到手机桌面当 App 用。 */
    private fun showToolDialog() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        content.addView(TvDialog.title(activity, "遥控器桌面工具"))
        // 标题已点明「遥控器桌面工具」，右侧说明不再重复；文案量控制在二维码同高内（见 qrRow）
        content.addView(TvDialog.qrRow(activity, qrImage(toolUrl).apply { contentDescription = "遥控器桌面工具二维码" },
            label("手机扫码打开，添加到手机桌面像 App 一样使用。", 15f),
            label("打开后扫本页遥控二维码连接，需与电视同一 Wi-Fi。", 13f).apply { setTextColor(TvStyle.muted) },
            TvDialog.urlLabel(activity, toolUrl)
        ))
        val window = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        TvDialog.styleWindow(activity, window, widthDp = 440)
        window.show()
    }

    /** 由 RemoteController.onClientsChanged 回调，连接数变化时刷新文案。 */
    fun setClients(count: Int) {
        status?.text = when {
            count<=0 -> "等待手机连接…"
            count==1 -> "手机已连接，可以开始遥控"
            else -> "已连接 $count 台设备，都能同时遥控"
        }
        onClients(count)
        // 手机已经连上了，二维码留在画面上只剩挡视线。窗口随时能从顶栏再打开，
        // 会话本身不动，手机那头完全无感。
        if(count > 0) dialog?.takeIf { it.isShowing }?.dismiss()
    }

    override fun close() {
        if(closed) return
        closed = true
        hub.close()
        dialog?.let { if(it.isShowing) it.dismiss() }
        dialog = null
        status = null
        onClosed()
    }

    private fun label(value: String, size: Float) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(TvStyle.text)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun qrImage(url: String) = TvDialog.qrView(activity, url, "遥控二维码")
}
