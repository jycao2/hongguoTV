// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志记录（用户 2026-11-21，默认关闭）：
 * 开启后 Java 层未捕获异常写入 cacheDir/crash/，文件含版本 / 系统 / 电视型号 / 崩溃时刻 / 完整堆栈，
 * 供用户扫码导出发回来定位「闪退」反馈。
 *
 * 设计约束：
 * - 放在 cacheDir 下，「清理缓存」递归删除 cacheDir 时自动带走，无需单独挂钩（设置页另有单独清理入口）。
 * - 只捕 Java 层崩溃：native 硬崩（SIGSEGV 等）进程直接死，标准 handler 来不及写文件，第一版不覆盖。
 * - 写文件必须在崩溃线程上同步完成（进程马上要死），不能开线程、不能碰 UI。
 * - 保留最近 10 份、单份堆栈截断 16KB，防极端情况下无限膨胀。
 */
object CrashLogger {
    private const val MAX_FILES=10
    private const val MAX_STACK_CHARS=16_000

    fun dir(context: Context)=File(context.cacheDir,"crash")

    /** 开关存 native-library-v1 prefs（device-local，不进备份——崩溃诊断配置跟着设备走没有意义）。 */
    fun enabled(context: Context)=context.getSharedPreferences("native-library-v1",Context.MODE_PRIVATE)
        .getBoolean("crashLogEnabled",false)
    fun setEnabled(context: Context,value: Boolean) {
        context.getSharedPreferences("native-library-v1",Context.MODE_PRIVATE)
            .edit().putBoolean("crashLogEnabled",value).apply()
    }

    /** 在 MainActivity.onCreate 里调用：包住默认 handler，链回原 handler 保证系统崩溃流程不变。 */
    fun install(context: Context) {
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if(enabled(context)) runCatching { write(context,thread,throwable) }
            previous?.uncaughtException(thread,throwable)
        }
    }

    private fun write(context: Context,thread: Thread,throwable: Throwable) {
        val time=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date())
        var stack=throwable.stackTraceText()
        if(stack.length>MAX_STACK_CHARS) stack=stack.take(MAX_STACK_CHARS)+"\n…(已截断)"
        val text=buildString {
            appendLine("=== 红果TV 崩溃日志 ===")
            appendLine("时间: $time")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("线程: ${thread.name}")
            appendLine("堆栈:")
            append(stack)
            appendLine()
        }
        val dir=dir(context); dir.mkdirs()
        val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.CHINA).format(Date())
        File(dir,"crash-$stamp.txt").writeText(text)
        prune(dir)
    }

    private fun Throwable.stackTraceText(): String=buildString {
        var current: Throwable?=this@stackTraceText
        while(current!=null) {
            appendLine(current.javaClass.name+": "+current.message.orEmpty())
            current.stackTrace.forEach { appendLine("    at $it") }
            current=current.cause?.takeIf { it!==this@stackTraceText }
            if(current!=null) appendLine("Caused by:")
        }
    }

    /** 超量删最旧的：10 份上限 + 总量 2MB 双保险。 */
    private fun prune(dir: File) {
        val files=dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: return
        var total=0L
        for(file in files) {
            total+=file.length()
            if(files.indexOf(file)>=MAX_FILES || total>2_000_000) file.delete()
        }
    }

    fun files(context: Context): List<File> = dir(context).listFiles()?.filter { it.isFile }
        ?.sortedByDescending { it.name }.orEmpty()
    fun sizeBytes(context: Context)=files(context).sumOf { it.length() }
    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    /** 合并全部日志为一份 txt（最新在前，总量封顶 2MB），供扫码导出。 */
    fun combined(context: Context): String? {
        val list=files(context); if(list.isEmpty()) return null
        var total=0L; val parts= mutableListOf<String>()
        for(file in list) {
            if(total+file.length()>2_000_000 && parts.isNotEmpty()) break
            total+=file.length(); parts+=file.readText()
        }
        return parts.joinToString("\n")
    }
}
