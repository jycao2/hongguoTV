// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 手机快传收到的第三方安装包：只负责拉起系统安装器，装不装由用户在系统界面决定。
 *  与自更新共用同一个 FileProvider 授权（authority 固定为 `<packageId>.updates`）。 */
object ApkInstaller {
    enum class Result {
        /** 系统安装界面已打开。 */
        LAUNCHED,
        /** 缺少「安装未知应用」授权，已跳到系统设置页。 */
        NEEDS_PERMISSION,
        /** 电视没有可用的安装入口。 */
        FAILED,
    }

    fun install(activity: Activity, apk: File): Result = runCatching {
        if(!apk.isFile) return Result.FAILED
        if(Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            Result.NEEDS_PERMISSION
        } else {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("安装包", uri) })
            Result.LAUNCHED
        }
    }.getOrDefault(Result.FAILED)
}
