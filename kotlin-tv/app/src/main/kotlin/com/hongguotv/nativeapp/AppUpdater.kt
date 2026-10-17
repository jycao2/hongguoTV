// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var dialog: AlertDialog? = null
    val showing get() = dialog?.isShowing == true
    private var statusView: TextView? = null
    private var anchor: View? = null
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"
    private var retryAfter = 0L
    private var promptedThisSession = false
    private var checkedThisSession = false
    private val auto get() = preferences.getBoolean("automatic", true)
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && auto) {
                val now = System.currentTimeMillis()
                val last = preferences.getLong("last_attempt", 0)
                val update = available
                when {
                    update != null && !promptedThisSession -> show(activity.currentFocus)
                    update != null && !ready && now >= retryAfter -> download()
                    !checkedThisSession -> checkedThisSession = check(false)
                    now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }
    fun resume() {
        active = true; main.removeCallbacks(poll); main.postDelayed(poll, 8000)
        if (permissionRequested) {
            permissionRequested = false
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "安装权限未开启，可在这里再次安装"; show(anchor) }
        } else if (!checkedThisSession && auto) {
            // Nag semantics need a fresh verdict on every app start, not the 6-hour poll cadence;
            // when the library is still loading the poll loop retries this below.
            checkedThisSession = check(false)
        }
    }
    fun pause() {
        active = false; main.removeCallbacks(poll); interrupt()
        dialog?.dismiss()
    }
    fun interrupt() {
        generation++; task?.cancel(true); task = null; repository.cancel(); busy = false
    }
    fun destroy() {
        pause(); worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }
    private fun updateStatus(text: String) { status = text; statusView?.text = text }
    private fun renderActions() {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = when { busy -> "处理中…"; ready -> "立即安装"; available != null -> "下载新版"; else -> "检查更新" }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
    }
    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        // One nag per process lifetime: the user may close it, but the next app restart re-prompts.
        val updatePending = available != null
        if (updatePending) promptedThisSession = true
        val dp = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((24*dp).toInt(), (12*dp).toInt(), (24*dp).toInt(), 0) }
        statusView = TextView(activity).apply { text = status; textSize = 17f; setTextColor(TvStyle.text) }
        body.addView(statusView)
        if (!updatePending) {
            body.addView(CheckBox(activity).apply {
                text = "自动检查并下载更新"; isChecked = auto
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("automatic", enabled).apply()
                    if (!enabled && busy) { interrupt(); updateStatus("自动更新已关闭，可手动检查"); renderActions() }
                }
            })
        }
        body.addView(TextView(activity).apply {
            text = if (updatePending) "发现新版本，点击即可更新" else "只在应用前台浏览时检查，播放中不打扰。下载后需确认系统安装，收藏与观看记录保留。"
            textSize = 13f; setTextColor(TvStyle.muted)
            setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
        })
        val next = AlertDialog.Builder(activity).setTitle("版本与更新 · ${BuildConfig.VERSION_NAME}").setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("稍后", null).setPositiveButton("检查更新", null).create()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) { dialog = null; statusView = null }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { when { ready -> install(); available != null -> download(); else -> check(true) } }
        next.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { next.dismiss() }
        renderActions()
        next.getButton(AlertDialog.BUTTON_POSITIVE).post { if (dialog === next) next.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
    }
    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true; renderActions(); val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post { if (active && generation == ticket) {
                    busy = false; ready = false; retryAfter = System.currentTimeMillis()+30*60*1000L
                    updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试"); renderActions()
                } }
            }
        }
    }
    /** @return true when the request actually started; callers may retry later otherwise. */
    private fun check(manual: Boolean): Boolean {
        if (busy || !active || !idle()) return false
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available = update.takeIf { it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }; ready = false
            if (available == null) {
                updateStatus(if (update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT) "新版需要 Android API ${update.minSdk}，当前电视继续使用本版" else "已是最新版本 ${BuildConfig.VERSION_NAME}")
                worker.execute { apk.delete() }
            } else {
                updateStatus("发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n${update.notes}")
                if (manual || auto) download()
            }
        }
        return true
    }
    private fun download() {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent -> main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%") } }
            validateApk(update)
        }) {
            ready = true; updateStatus("${update.versionName} 已下载并校验\n${update.notes}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && !promptedThisSession) show(activity.currentFocus)
        }
    }
    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        // API 28/29 的 getPackageArchiveInfo 只在带 GET_SIGNATURES 时才收集证书，
        // 仅传 GET_SIGNING_CERTIFICATES 会让 candidate.signingInfo 为 null（API 30 起修复）。
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            ((if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null) ?: info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != activity.packageName || version != update.versionCode || candidate.versionName != update.versionName || version <= BuildConfig.VERSION_CODE ||
            candidate.applicationInfo?.minSdkVersion != update.minSdk || signers(installed).isEmpty() || signers(installed) != signers(candidate))
            throw IOException("新版包名、版本或签名不匹配，已阻止安装")
    }
    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({ validateApk(update) }) {
            try {
                if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequested = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新安装包", uri) })
                }
            } catch (_: Exception) { permissionRequested = false; updateStatus("电视未提供安装或授权入口，请通过系统文件管理器安装新版") }
        }
    }
}
