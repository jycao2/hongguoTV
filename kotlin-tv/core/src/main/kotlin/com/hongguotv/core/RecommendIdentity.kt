// SPDX-License-Identifier: GPL-3.0-only
// 官方推荐流的设备身份：匿名个性化按设备维度画像，device_id/iid 必须持久化复用。
// 属设备绑定信息，独立存一份、不进云端备份（与片头片尾跳过、主题同口径）。
package com.hongguotv.core

import okhttp3.OkHttpClient
import kotlin.random.Random

/**
 * 「猜你喜欢」（`bookmall/tab/v`）要求的设备身份。
 *
 * 2026-11-17 实测：这个接口是唯一强制校验设备注册的读接口——裸随机身份或内置共享档案
 * 一律 `code=110 ILLEGAL_ACCESS`（同签名同参数），用 [DeviceRegister] 现注册的干净身份
 * 立刻 SUCCESS。身份必须持久化：每次重新注册会被判成身份漂移，是典型风控特征。
 *
 * 注册失败（离线、被拦）时回退随机持久身份：个性化流会退化成榜单兜底，但不会崩。
 * core 是纯 JVM 模块，落盘由调用方通过 [IdentityStore] 注入（Android 侧用 SharedPreferences）。
 */
class RecommendIdentity(private val http: OkHttpClient, private val store: IdentityStore) {

    interface IdentityStore {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun getAll(): Map<String, String> = emptyMap()
    }

    @Volatile private var cached: Map<String, String>? = null

    /** 官方设备参数全集 + 稳定 device_id/iid；首次调用可能发起一次注册请求（后台线程调用）。 */
    fun device(): Map<String, String> {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            var deviceId = store.get(KEY_DEVICE_ID)
            var installId = store.get(KEY_INSTALL_ID)
            if (deviceId.isNullOrBlank() || installId.isNullOrBlank()) {
                val registered = runCatching { CloudAccountClient(http).registerDevice() }.getOrNull()
                if (registered != null) {
                    deviceId = registered.deviceId
                    installId = registered.installId
                    store.put(KEY_DEVICE_ID, deviceId)
                    store.put(KEY_INSTALL_ID, installId)
                    store.put(KEY_COOKIE, registered.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" })
                } else {
                    deviceId = randomId()
                    installId = randomId()
                    store.put(KEY_DEVICE_ID, deviceId)
                    store.put(KEY_INSTALL_ID, installId)
                }
            }
            VendorConstants.device.toMutableMap().apply {
                put("device_id", deviceId!!)
                put("iid", installId!!)
            }.also { cached = it }
        }
    }

    /** 注册身份附带的 Cookie（部分接口靠它认设备）；没注册成功时为空。 */
    fun cookies(): Map<String, String> {
        val raw = store.get(KEY_COOKIE) ?: return emptyMap()
        return raw.split("; ").mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
    }

    private fun randomId() = buildString(16) {
        append('1'); repeat(15) { append(Random.nextInt(10)) }
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_INSTALL_ID = "iid"
        const val KEY_COOKIE = "device_cookie"
    }
}
