// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 账号状态与历史同步：登录凭证安全存储、云端历史合并、播放进度自动上传。

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
 * 账号管理器：登录、历史合并、进度上传。
 * 本地历史仍是单一数据源；登录后云端历史合并到本地，本地新增进度自动上传。
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
    private var lastUpload = 0L
    private var lastEpisode = ""
    private var pendingUpload: Pair<CloudProgress, Int>? = null
    private var draining = false
    var onAccountChanged: (() -> Unit)? = null

    val signedIn get() = credentials != null && profile != null
    val accountLabel get() = profile?.nickname ?: if (credentials != null) "红果账号" else "未登录"
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
                        status = "已登录，正在同步云端历史…"
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

    /** 拉取云端历史并合并到本地。 */
    fun refreshHistory() {
        val account = credentials ?: return
        if (closed) return
        val token = generation
        status = "正在读取云端观看历史…"
        changed()
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching { client.history(account) }
            main.post {
                if (closed || token != generation) return@post
                result
                    .onSuccess { rows ->
                        cloudHistory = rows.distinctBy { it.series.id }
                            .sortedByDescending { it.updatedAt }
                        historyLoaded = true
                        mergeHistory()
                        status = "已同步 ${cloudHistory.size} 部云端观看记录"
                        changed()
                    }
                    .onFailure { error ->
                        status = "云端历史读取失败：${safeError(error)}"
                        changed()
                    }
            }
        }
    }

    /** 合并云端历史到本地：云端有而本地没有的写入本地；两边都有的取 position 较大者。 */
    private fun mergeHistory(retry: Int = 0) {
        if (!signedIn || cloudHistory.isEmpty() || closed) return
        if (!library.isLoaded) {
            // 本地库还在加载：延迟重试，加载失败时放弃本轮合并（可手动刷新云端记录）
            if (retry < 10) main.postDelayed({ if (!closed) mergeHistory(retry + 1) }, 1000)
            return
        }
        val localIndex = library.state()
        val localProgress = localIndex.history()
        val localBySeries = localProgress.associateBy { it.series.id }
        val toUpload = mutableListOf<Pair<CloudProgress, Int>>()

        for (cloud in cloudHistory) {
            val local = localBySeries[cloud.series.id]
            when {
                local == null -> {
                    // 云端有、本地没有：写入本地
                    val backup = BackupProgress(
                        series = cloud.series,
                        episodeId = cloud.episodeId,
                        episodeIndex = cloud.episodeIndex,
                        position = cloud.position,
                        duration = cloud.duration,
                        completed = false,
                        updatedAt = cloud.updatedAt,
                    )
                    library.save(backup)
                }
                cloud.updatedAt > local.updatedAt && cloud.position > local.position -> {
                    // 云端更新：用云端覆盖本地
                    val backup = local.copy(
                        episodeId = cloud.episodeId,
                        episodeIndex = cloud.episodeIndex,
                        position = cloud.position,
                        duration = cloud.duration,
                        updatedAt = cloud.updatedAt,
                    )
                    library.save(backup)
                }
                local.updatedAt > cloud.updatedAt && local.position > cloud.position -> {
                    // 本地更新：标记待上传
                    toUpload += cloud.copy(
                        episodeId = local.episodeId,
                        episodeIndex = local.episodeIndex,
                        position = local.position,
                        duration = local.duration,
                        updatedAt = local.updatedAt,
                    ) to (local.series.badge.toIntOrNull() ?: 0)
                }
            }
        }
        library.flush()

        // 上传本地更新的记录（走单槽节流队列）
        for ((progress, episodeCount) in toUpload) {
            upload(progress, episodeCount, force = false)
        }

        // 本地有而云端没有的：后台逐条补齐到云端，绕开播放用的单槽节流队列
        val account = credentials ?: return
        val token = generation
        val cloudSeriesIds = cloudHistory.map { it.series.id }.toSet()
        val missing = localProgress.filter { it.series.id !in cloudSeriesIds && it.position > 0 }
        if (missing.isEmpty()) return
        worker.execute {
            var synced = 0
            for (local in missing) {
                if (closed || token != generation) return@execute
                val episodes = local.series.badge.toIntOrNull() ?: 0
                val ok = runCatching {
                    client.upload(
                        account,
                        CloudProgress(local.series, local.episodeId, local.episodeIndex, local.position, local.duration, local.updatedAt, episodes),
                        episodes,
                    )
                }.isSuccess
                if (ok) synced++
            }
            main.post {
                if (closed || token != generation) return@post
                status = "云端合并完成，另补齐 $synced 部本机记录"
                changed()
            }
        }
    }

    /** 保存播放进度时调用：本地保存 + 加入上传队列。 */
    fun onLocalProgressSaved(progress: BackupProgress, episodeCount: Int) {
        if (!signedIn) return
        val cloud = CloudProgress(
            series = progress.series,
            episodeId = progress.episodeId,
            episodeIndex = progress.episodeIndex,
            position = progress.position,
            duration = progress.duration,
            updatedAt = progress.updatedAt,
            episodeCount = episodeCount,
        )
        upload(cloud, episodeCount, force = false)
    }

    fun upload(progress: CloudProgress, episodes: Int, force: Boolean) {
        val account = credentials?.takeIf { profile != null } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && progress.episodeId == lastEpisode && now - lastUpload < 30000) return
        lastUpload = now
        lastEpisode = progress.episodeId
        pendingUpload = progress to episodes
        if (draining) return
        draining = true
        drain(account, generation)
    }

    private fun drain(account: AccountCredentials, token: Int) {
        val next = pendingUpload
        if (closed || token != generation) return
        if (next == null) {
            draining = false
            return
        }
        pendingUpload = null
        worker.execute {
            val result = runCatching {
                if (!closed && token == generation) client.upload(account, next.first, next.second)
            }
            main.post {
                if (closed) return@post
                if (token == generation) {
                    status = if (result.isSuccess) "播放进度已上传"
                    else "进度上传失败：${safeError(result.exceptionOrNull()!!)}"
                    changed()
                }
                drain(account, token)
            }
        }
    }

    fun showLoginDialog(activity: android.app.Activity) {
        if (closed || busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        val choices = if (signedIn) arrayOf("刷新云端记录", "退出红果账号") else arrayOf("扫码登录", "手机号登录")
        android.app.AlertDialog.Builder(activity)
            .setTitle(profile?.let { "红果账号 · ${it.nickname}" } ?: "红果账号")
            .setItems(choices) { _, index ->
                if (!signedIn) {
                    if (index == 0) qrLogin(activity) else phoneLogin(activity)
                } else when (index) {
                    0 -> refreshHistory()
                    1 -> logout()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
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
                val size = (300 * activity.resources.displayMetrics.density).toInt()
                val image = android.widget.ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                }
                val panel = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(24, 16, 24, 16)
                    addView(image)
                    addView(
                        android.widget.TextView(activity).apply {
                            text = "用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录"
                            textSize = 18f
                            gravity = android.view.Gravity.CENTER
                            setPadding(0, 12, 0, 0)
                        }
                    )
                }
                val dialog = android.app.AlertDialog.Builder(activity)
                    .setTitle("红果官方扫码登录")
                    .setView(panel)
                    .setNegativeButton("取消", null)
                    .show()
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
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("二维码已过期")
                            .setMessage("请刷新二维码，再用手机红果 App 扫码确认。")
                            .setPositiveButton("刷新二维码") { _, _ -> qrLogin(activity) }
                            .setNegativeButton("取消", null)
                            .show()
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
                                                android.app.AlertDialog.Builder(activity)
                                                    .setTitle("登录状态保存失败")
                                                    .setMessage(safeError(failure))
                                                    .setPositiveButton("重新扫码") { _, _ -> qrLogin(activity) }
                                                    .setNegativeButton("返回") { _, _ -> showLoginDialog(activity) }
                                                    .show()
                                            },
                                        )
                                    } else main.postDelayed({ poll() }, 2000)
                                }
                                .onFailure {
                                    closeQr()
                                    status = safeError(it)
                                    changed()
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("扫码登录未完成")
                                        .setMessage(status)
                                        .setPositiveButton("重新扫码") { _, _ -> qrLogin(activity) }
                                        .setNegativeButton("返回") { _, _ -> showLoginDialog(activity) }
                                        .show()
                                }
                        }
                    }
                }
                main.postDelayed({ poll() }, 1500)
            },
        )
    }

    private fun phoneLogin(activity: android.app.Activity) {
        val field = android.widget.EditText(activity).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "手机号"
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("手机号登录")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送验证码") { _, _ ->
                val raw = field.text.toString().trim()
                if (raw.isBlank()) return@setPositiveButton
                val mobile = runCatching { PhoneLogin.parse(raw) }.getOrElse {
                    toast(safeError(it))
                    return@setPositiveButton
                }
                val login = WebLogin(http)
                task(
                    { login.send(mobile) },
                    { sent -> inputCode(activity, login, sent.maskedMobile) },
                    { failure ->
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("短信登录未完成")
                            .setMessage(safeError(failure))
                            .setPositiveButton("改用扫码") { _, _ -> qrLogin(activity) }
                            .setNegativeButton("返回", null)
                            .show()
                    },
                )
            }
            .show()
    }

    private fun inputCode(activity: android.app.Activity, login: WebLogin, masked: String, error: String? = null) {
        val field = android.widget.EditText(activity).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "验证码"
        }
        val builder = android.app.AlertDialog.Builder(activity)
            .setTitle("输入发送至 $masked 的验证码")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认", null)
        if (error != null) builder.setMessage(error)
        val dialog = builder.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString().trim()
            if (!value.matches(Regex("[0-9]{4,8}"))) {
                inputCode(activity, login, masked, "请输入 4—8 位验证码")
                dialog.dismiss()
                return@setOnClickListener
            }
            dialog.dismiss()
            task(
                { validateAccount(login.verify(value)).also { store.save(it.first) } },
                { (next, account) -> acceptAccount(next, account) },
                { failure ->
                    if (failure is PhoneLogin.Failure && !failure.canRetryCode) {
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("登录未完成")
                            .setMessage(safeError(failure))
                            .setPositiveButton("返回账号页") { _, _ -> showLoginDialog(activity) }
                            .setNeutralButton("改用扫码") { _, _ -> qrLogin(activity) }
                            .show()
                    } else inputCode(activity, login, masked, safeError(failure))
                },
            )
        }
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
        status = "已登录，正在同步云端历史…"
        lastUpload = 0
        lastEpisode = ""
        pendingUpload = null
        draining = false
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
        pendingUpload = null
        draining = false
        http.dispatcher.cancelAll()
        status = "已退出账号，自动同步已停止"
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

    fun destroy() {
        val account = credentials
        val final = pendingUpload
        onAccountChanged = null
        closed = true
        generation++
        pendingUpload = null
        http.dispatcher.cancelAll()
        worker.execute {
            if (account != null && final != null) runCatching { client.upload(account, final.first, final.second) }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        worker.shutdown()
    }
}
