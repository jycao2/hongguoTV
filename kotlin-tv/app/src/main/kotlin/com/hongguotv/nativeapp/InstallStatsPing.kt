// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.InstallStats
import com.hongguotv.core.InstallStatsReporter
import java.util.concurrent.Executors

/**
 * 匿名安装/活跃统计：每个进程启动静默上报一次。
 * 仅携带随机安装 ID 与应用版本号；不重试、不缓存、不阻塞、失败即静默丢弃。
 */
object InstallStatsPing {
    private const val PREFS = "install_stats"
    private const val KEY_ID = "install_id"
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var started = false

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
                InstallStatsReporter().ping(id, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            } catch (_: Throwable) {
            }
        }
    }
}
