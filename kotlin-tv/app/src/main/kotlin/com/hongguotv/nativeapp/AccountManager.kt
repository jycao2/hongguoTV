// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 账号状态与云端记录：登录凭证安全存储、云端历史只读拉取、本机记录手动同步到云端。

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.hongguotv.core.*
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay in an Android Keystore encrypted partition, outside library exports. */
private class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_account_secure", Context.MODE_PRIVATE)
    private val alias = "hongguotv.cloud.account.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): AccountCredentials? {
        val raw = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return AccountCredentials.fromJson(
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        )
    }

    fun save(credentials: AccountCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val raw = cipher.iv + cipher.doFinal(credentials.toJson().toByteArray())
        check(
            prefs.edit().putString("session", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()
        ) { "保存登录态失败" }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "清除登录态失败" }
    }
}

/**
 * 账号管理器：登录、云端历史拉取、本机记录手动同步。
 * 本机与云端观看记录相互独立：云端记录只读展示、不合并不回写本机；
 * 本机记录由用户在最近观看页手动「同步到云端」。
 */
class AccountManager(private val context: Context, private val library: Library) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-account") }
    private val http =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val client = CloudAccountClient(http)
    private val store = AccountStore(context)
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var credentials: AccountCredentials? = null
    private var profile: CloudProfile? = null
    private var cloudHistory = emptyList<CloudProgress>()
    private var historyLoaded = false
    private var status = "未登录"
    private var busy = false
    var onAccountChanged: (() -> Unit)? = null

    val signedIn get() = credentials != null && profile != null

    /** 云端历史是否已成功拉取过；手机遥控等外部只读方用它判断要不要顺手触发一次 refreshHistory。 */
    val cloudHistoryLoaded get() = historyLoaded
    val accountLabel: String get() {
        val p = profile
        return when {
            credentials == null -> "红果账号 · 未登录"
            p == null -> "红果账号 · 验证中"
            else -> "${p.nickname} · 云端 ${cloudRows().size} 部"
        }
    }
    val syncStatus get() = status
    val isBusy get() = busy

    init {
        restore()
    }

    private fun restore() {
        worker.execute {
            val saved = runCatching { store.read() }
            main.post {
                if (closed) return@post
                credentials = saved.getOrNull()
                status = when {
                    credentials != null -> "登录态已恢复，正在验证…"
                    saved.isFailure -> "登录态无法读取，请重新登录"
                    else -> "未登录"
                }
                changed()
                if (credentials != null) validateAndRefresh()
            }
        }
    }

    private fun validateAndRefresh() {
        val account = credentials ?: return
        if (busy || closed) return
        busy = true
        status = "正在验证登录态…"
        changed()
        worker.execute {
            val result = runCatching { client.profile(account) }
            main.post {
                if (closed) return@post
                busy = false
                result
                    .onSuccess { user ->
                        profile = user
                        status = "已登录，正在读取云端历史…"
                        changed()
                        refreshHistory()
                    }
                    .onFailure { error ->
                        Log.w("HongguoTV", "Account validation failed", error)
                        // token 过期，清除登录态
                        credentials = null
                        profile = null
                        store.clear()
                        status = "登录态已过期，请重新登录"
                        changed()
                    }
            }
        }
    }

    /** 拉取云端历史，仅作只读展示：不合并不回写本机记录。 */
    fun refreshHistory(onDone: (() -> Unit)? = null) {
        val account = credentials ?: run { onDone?.invoke(); return }
        if (closed) { onDone?.invoke(); return }
        val token = generation
        status = "正在读取云端观看历史…"
        changed()
        worker.execute {
            if (closed || token != generation) { main.post { onDone?.invoke() }; return@execute }
            val result = runCatching { client.history(account) }
            main.post {
                if (closed || token != generation) { onDone?.invoke(); return@post }
                result
                    .onSuccess { rows ->
                        cloudHistory = rows.distinctBy { it.series.id }
                            .sortedByDescending { it.updatedAt }
                        historyLoaded = true
                        status = "已读取 ${cloudHistory.size} 部云端观看记录"
                        changed()
                    }
                    .onFailure { error ->
                        status = "云端历史读取失败：${safeError(error)}"
                        changed()
                    }
                onDone?.invoke()
            }
        }
    }

    /** 云端观看记录（只读）：按更新时间倒序，只保留有播放进度的。 */
    fun cloudRows(): List<CloudProgress> = cloudHistory.filter { it.position > 0 }

    /**
     * 手动同步本机记录到云端：逐部上传本机进度。
     * 云端已有同剧且云端更新时间不旧于本机的跳过（云端为准，不覆盖）；
     * 全程不写本机记录，完成后刷新云端缓存并在主线程回调 onDone(上传, 跳过, 失败)。
     */
    fun syncLocalToCloud(onDone: (pushed: Int, skipped: Int, failed: Int) -> Unit) {
        val account = credentials ?: run { toast("未登录"); Log.i("HongguoTV","syncLocalToCloud: no account"); return }
        if (closed || busy) {
            toast("账号操作进行中，请稍候")
            Log.i("HongguoTV","syncLocalToCloud: busy/closed")
            return
        }
        if (!signedIn) {
            toast("请先登录红果账号")
            Log.i("HongguoTV","syncLocalToCloud: not signedIn")
            return
        }
        if (!library.isLoaded) {
            toast("观看记录还在加载，请稍候再试")
            Log.i("HongguoTV","syncLocalToCloud: library not loaded")
            return
        }
        busy = true
        status = "正在把本机记录同步到云端…"
        Log.i("HongguoTV", "syncLocalToCloud started")
        // 手动同步期间不触发 onAccountChanged，避免 showCatalog 重建页面打断按钮
        val token = generation
        worker.execute {
            val localRows = library.state().history().filter { it.position > 0 }
            val cloudBySeries = cloudHistory.associateBy { it.series.id }
            Log.i("HongguoTV", "syncLocalToCloud: local=${localRows.size} cloudKnown=${cloudBySeries.size}")
            var pushed = 0
            var skipped = 0
            var failed = 0
            val uploaded = mutableListOf<CloudProgress>()
            for (local in localRows) {
                if (closed || token != generation) return@execute
                val cloud = cloudBySeries[local.series.id]
                if (cloud != null && cloud.updatedAt >= local.updatedAt) {
                    skipped++
                    continue
                }
                val episodes = local.series.badge.toIntOrNull() ?: 0
                val row = CloudProgress(
                    local.series,
                    local.episodeId,
                    local.episodeIndex,
                    local.position,
                    local.duration,
                    local.updatedAt,
                    episodes,
                )
                val ok = runCatching { client.upload(account, row, episodes) }.isSuccess
                if (ok) {
                    pushed++
                    uploaded += row
                } else failed++
            }
            main.post {
                if (closed || token != generation) return@post
                busy = false
                if (uploaded.isNotEmpty()) {
                    // 云端缓存就地更新：同剧替换、新剧补上，避免同步后立刻再拉一遍
                    val merged = cloudHistory.associateBy { it.series.id }.toMutableMap()
                    uploaded.forEach { merged[it.series.id] = it }
                    cloudHistory = merged.values.sortedByDescending { it.updatedAt }
                }
                status = if (failed > 0) "同步完成：上传 $pushed 部，失败 $failed 部"
                else "同步完成：上传 $pushed 部，跳过 $skipped 部"
                // 手动同步完成只回传计数给调用方弹 Toast/Dialog，不触发 onAccountChanged 重建页面
                Log.i("HongguoTV", "syncLocalToCloud invoking onDone($pushed,$skipped,$failed)")
                onDone(pushed, skipped, failed)
            }
        }
    }

    fun showLoginDialog(activity: android.app.Activity) {
        if (closed || busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        val choices = if (signedIn) listOf("刷新云端记录", "同步本机到云端", "退出红果账号") else listOf("扫码登录")
        TvDialog.choose(
            activity,
            profile?.let { "红果账号 · ${it.nickname}" } ?: "红果账号",
            choices,
            onChoice = { index ->
                if (!signedIn) {
                    qrLogin(activity)
                } else when (index) {
                    0 -> refreshHistory()
                    1 -> syncLocalToCloud { pushed, skipped, failed ->
                        toast(
                            when {
                                failed > 0 -> "同步完成：上传 $pushed 部，失败 $failed 部"
                                pushed > 0 -> "已上传 $pushed 部到云端${if (skipped > 0) "，$skipped 部云端已是最新" else ""}"
                                else -> "云端已是最新，无需上传"
                            }
                        )
                    }
                    2 -> logout()
                }
            },
        ).show()
    }

    /** 双按钮结果弹窗：主按钮执行 action，次按钮返回账号页（可选）。 */
    private fun actionDialog(
        activity: android.app.Activity,
        title: String,
        message: String,
        actionLabel: String,
        onAction: () -> Unit,
        backToAccount: Boolean = true,
    ) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(TvDialog.title(activity, title))
            addView(TvDialog.body(activity, message))
        }
        val buttons = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(TvDialog.card(activity, content))
            .create()
        if (backToAccount) {
            val back = TvDialog.capsuleButton(activity, "返回") {}
            buttons.addView(back, android.widget.LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
            back.setOnClickListener { dialog.dismiss(); showLoginDialog(activity) }
        }
        val action = TvDialog.capsuleButton(activity, actionLabel) {}
        buttons.addView(action)
        content.addView(buttons)
        TvDialog.styleWindow(activity, dialog) { action.requestFocus() }
        action.setOnClickListener { dialog.dismiss(); onAction() }
        dialog.show()
    }

    private fun qrLogin(activity: android.app.Activity) {
        val login = WebLogin(http)
        task(
            { login.qr() },
            { qr ->
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                if (bitmap == null) {
                    toast("登录二维码无法显示，请重试")
                    return@task
                }
                val size = (240 * activity.resources.displayMetrics.density).toInt()
                val image = android.widget.ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                }
                val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
                val panel = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(TvDialog.title(activity, "红果官方扫码登录"))
                    addView(image)
                    addView(
                        android.widget.TextView(activity).apply {
                            text = "用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录"
                            textSize = 13f
                            setTextColor(TvStyle.muted)
                            gravity = android.view.Gravity.CENTER
                            setLineSpacing(0f, 1.25f)
                            setPadding(0, dp(14), 0, 0)
                        }
                    )
                }
                val buttons = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                    setPadding(0, dp(14), 0, 0)
                }
                val dialog = android.app.AlertDialog.Builder(activity)
                    .setView(TvDialog.card(activity, panel))
                    .create()
                val cancel = TvDialog.capsuleButton(activity, "取消") { dialog.dismiss() }
                buttons.addView(cancel)
                panel.addView(buttons)
                TvDialog.styleWindow(activity, dialog, widthDp = 400) { cancel.requestFocus() }
                dialog.show()
                val token = generation
                val deadline = android.os.SystemClock.elapsedRealtime() + qr.expiresIn.coerceIn(1, 300) * 1000L
                var cancelled = false
                busy = true
                status = "等待手机确认扫码登录"
                changed()
                dialog.setOnDismissListener {
                    cancelled = true
                    login.cancel()
                    if (token == generation) {
                        busy = false
                        status = "已取消扫码登录"
                        changed()
                    }
                }
                fun closeQr() {
                    cancelled = true
                    dialog.setOnDismissListener(null)
                    login.cancel()
                    if (token == generation) busy = false
                    dialog.dismiss()
                }
                fun poll() {
                    if (cancelled || closed || token != generation) return
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        closeQr()
                        status = "二维码已过期，请重新扫码"
                        changed()
                        actionDialog(activity, "二维码已过期", "请刷新二维码，再用手机红果 App 扫码确认。", "刷新二维码", { qrLogin(activity) }, backToAccount = false)
                        return
                    }
                    worker.execute {
                        val result = runCatching {
                            if (login.poll()) validateAccount(login.credentials()) else null
                        }
                        main.post {
                            if (cancelled || closed || token != generation) return@post
                            result
                                .onSuccess { account ->
                                    if (account != null) {
                                        closeQr()
                                        status = "扫码已确认，正在保存登录状态"
                                        changed()
                                        task(
                                            { store.save(account.first); account },
                                            { acceptAccount(it.first, it.second) },
                                            { failure ->
                                                actionDialog(activity, "登录状态保存失败", safeError(failure), "重新扫码", { qrLogin(activity) })
                                            },
                                        )
                                    } else main.postDelayed({ poll() }, 2000)
                                }
                                .onFailure {
                                    closeQr()
                                    status = safeError(it)
                                    changed()
                                    actionDialog(activity, "扫码登录未完成", status, "重新扫码", { qrLogin(activity) })
                                }
                        }
                    }
                }
                main.postDelayed({ poll() }, 1500)
            },
        )
    }

    private fun validateAccount(web: AccountCredentials): Pair<AccountCredentials, CloudProfile> {
        val device = client.registerDevice() ?: throw java.io.IOException("账号设备初始化失败")
        val next = web.copy(deviceId = device.deviceId, installId = device.installId)
        val account = client.profile(next)
        return next to account
    }

    private fun acceptAccount(next: AccountCredentials, account: CloudProfile) {
        credentials = next
        profile = account
        cloudHistory = emptyList()
        historyLoaded = false
        generation++
        status = "已登录，正在读取云端历史…"
        changed()
        toast("已登录 ${account.nickname}")
        refreshHistory()
    }

    private fun logout() {
        generation++
        busy = false
        credentials = null
        profile = null
        cloudHistory = emptyList()
        historyLoaded = false
        http.dispatcher.cancelAll()
        status = "已退出账号"
        changed()
        worker.execute {
            val result = runCatching { store.clear() }
            main.post { toast(if (result.isSuccess) status else "清除登录态失败，请重试退出") }
        }
    }

    private fun <T> task(
        action: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit = { toast(safeError(it)) },
    ) {
        if (busy || closed) return
        busy = true
        val token = generation
        status = "正在连接红果账号…"
        changed()
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching(action)
            main.post {
                if (closed || token != generation) return@post
                busy = false
                result.onSuccess(success).onFailure {
                    status = safeError(it)
                    changed()
                    failure(it)
                }
            }
        }
    }

    private fun toast(value: String) {
        if (!closed) Toast.makeText(context, value, Toast.LENGTH_LONG).show()
    }

    private fun safeError(error: Throwable) = when (error) {
        is java.net.SocketTimeoutException -> "账号接口连接超时，请稍后重试"
        is java.net.UnknownHostException -> "无法连接账号接口，请检查网络"
        else -> error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(150).ifBlank { "账号操作失败" }
    }

    private fun changed() {
        onAccountChanged?.invoke()
    }

    /** 检测账号登录态：同步调 profile 接口验证 token 是否有效；未登录直接返回提示。 */
    fun checkAccountHealth(): String {
        val cred = credentials ?: return "未登录，跳过"
        val user = client.profile(cred)
        return "登录态有效 · ${user.nickname}"
    }

    /** 检测云端历史接口：同步调 history 接口；未登录直接返回提示。 */
    fun checkCloudHistory(): String {
        val cred = credentials ?: return "未登录，跳过"
        val rows = client.history(cred)
        return "返回 ${rows.size} 条云端观看记录"
    }

    fun destroy() {
        onAccountChanged = null
        closed = true
        generation++
        http.dispatcher.cancelAll()
        worker.execute {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        worker.shutdown()
    }
}
