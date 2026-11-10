// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.hongguotv.core.InstallStats
import com.hongguotv.core.InstallStatsReporter
import java.util.concurrent.Executors

/**
 * 匿名安装/活跃统计：每个进程启动静默上报一次。
 * 仅携带随机安装 ID、应用版本号、设备去重键哈希与系统 API 级别；不重试、不缓存、不阻塞、失败即静默丢弃。
 */
object InstallStatsPing {
    private const val PREFS = "install_stats"
    private const val KEY_ID = "install_id"
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var started = false

    /** 个别机型读 ANDROID_ID 会抛异常；取不到就返回 null，上报侧当作未上报。 */
    private fun androidId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (_: Throwable) {
        null
    }

    fun startOnce(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        worker.execute {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                var id = prefs.getString(KEY_ID, null)
                if (id.isNullOrEmpty()) {
                    id = InstallStats.newInstallId()
                    prefs.edit().putString(KEY_ID, id).apply()
                }
                InstallStatsReporter().ping(
                    id, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    deviceKey = InstallStats.deviceKey(androidId(app)),
                    sdkInt = Build.VERSION.SDK_INT,
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 更新失败上报，独立线程 fire-and-forget：调用点在更新线程的 catch 里，不能再被网络阻塞。
     * [stage] 取 parse / download / verify / install，[to] 是目标版本（未知时传 "-"）。
     */
    fun reportUpdateFail(context: Context, stage: String, to: String) {
        val app = context.applicationContext
        Thread({
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val id = prefs.getString(KEY_ID, null)?.takeIf { it.isNotEmpty() }
                    ?: InstallStats.newInstallId().also { prefs.edit().putString(KEY_ID, it).apply() }
                InstallStatsReporter().updateFailed(id, BuildConfig.VERSION_NAME, to, stage)
            } catch (_: Throwable) {
            }
        }, "hongguotv-update-fail").start()
    }
}
