// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.hongguotv.core.BackupData
import com.hongguotv.core.BackupFiles
import com.hongguotv.core.DirectoryBackupFiles
import com.hongguotv.core.LocalBackupStore
import java.io.File

/**
 * Android storage adapter for the rotating local backup.
 * API 26-28: legacy external storage after WRITE permission.
 * API 29: MediaStore.Downloads, because scoped storage is enforced for targetSdk 36.
 * API 30+: MANAGE_EXTERNAL_STORAGE and a plain directory.
 */
class LocalBackupStorage(private val context: Context) {
    companion object {
        const val REQUEST_MANAGE = 7101
        const val REQUEST_READ = 7102
        const val REQUEST_WRITE = 7103
        private const val DIR_NAME = "hongguotv"
    }
    val supported: Boolean
        get() = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    val backupAllowed: Boolean
        get() = when {
            !supported -> false
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT == 29 -> true
            else -> context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    val restoreAllowed: Boolean
        get() = when {
            !supported -> false
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT == 29 -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            else -> backupAllowed
        }
    val needsPermission: Boolean get() = !backupAllowed || !restoreAllowed
    /** Opens the system permission UI. Returns false when the device exposes no such screen. */
    fun request(activity: Activity): Boolean {
        if (!supported) return false
        return when {
            Build.VERSION.SDK_INT >= 30 -> {
                val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
                if (runCatching { activity.startActivityForResult(app, REQUEST_MANAGE); true }.getOrDefault(false)) true
                else runCatching { activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQUEST_MANAGE); true }.getOrDefault(false)
            }
            Build.VERSION.SDK_INT == 29 -> {
                activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ); true
            }
            else -> {
                activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE); true
            }
        }
    }
    fun store(): LocalBackupStore? {
        if (!supported || (!backupAllowed && !restoreAllowed)) return null
        return when {
            Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 30 -> LocalBackupStore(MediaStoreBackupFiles(context.contentResolver))
            Build.VERSION.SDK_INT >= 30 || Build.VERSION.SDK_INT <= 28 ->
                LocalBackupStore(DirectoryBackupFiles(File(Environment.getExternalStorageDirectory(), DIR_NAME)))
            else -> null
        }
    }
    fun load(): BackupData? = store()?.also { it.cleanup() }?.load()
}

/** MediaStore.Downloads backend for Android 10, where direct /sdcard writes are blocked. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreBackupFiles(private val resolver: ContentResolver) : BackupFiles {
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/hongguotv/"
    private fun uri(name: String): Uri? = runCatching {
        resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, relativePath), null)?.use { cursor ->
            if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) else null
        }
    }.getOrNull()
    override fun exists(name: String) = uri(name) != null
    override fun read(name: String): String? = runCatching {
        val target = uri(name) ?: return null
        resolver.openInputStream(target)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > LocalBackupStore.MAX_FILE_BYTES) null else String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()
    override fun write(name: String, value: String): Boolean = runCatching {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val existing = uri(name)
        if (existing != null) {
            resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) } ?: return false
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(target)?.use { it.write(bytes) } ?: return false
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        true
    }.getOrDefault(false)
    override fun rename(from: String, to: String): Boolean = runCatching {
        val source = uri(from) ?: return false
        delete(to)
        resolver.update(source, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, to) }, null, null) == 1
    }.getOrDefault(false)
    override fun delete(name: String): Boolean = runCatching {
        val target = uri(name) ?: return true
        resolver.delete(target, null, null) >= 1
    }.getOrDefault(false)
}
