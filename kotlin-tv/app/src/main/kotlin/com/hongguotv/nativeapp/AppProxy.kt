// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 全局 HTTP 代理开关：部分片源域名在电视网络下被上游 DNS 污染（解析到假 IP）或
 * SNI 阻断（IP 可达但 TLS 握手被断），换 DNS / DoH 都救不了，唯一出路是让流量走
 * 局域网里的代理（如电脑上的 Clash）。
 *
 * 实现要点：进程启动最早处（attachBaseContext）把系统默认 ProxySelector 换成
 * [Switch] 单例。OkHttp 在构建客户端时会捕获「当时的默认 ProxySelector」，
 * ExoPlayer 的 DefaultHttpDataSource（HttpURLConnection）则每次连接都读默认值——
 * 两者都指向同一个单例后，运行中切换配置只需改单例状态，所有已存在的客户端立即生效，
 * 不用重建任何 OkHttpClient。
 *
 * 配置存在独立 SharedPreferences（native-proxy-v1），刻意不进备份导出：
 * 代理是本机网络环境信息，跨设备恢复没有意义。
 */
object AppProxy {
    data class Config(val enabled: Boolean, val host: String, val port: Int) {
        val valid: Boolean get() = host.isNotEmpty() && port in 1..65535
        /** 设置行展示文案：关闭 / 未填写完整 / host:port。 */
        val display: String get() = when {
            !enabled -> "关闭"
            !valid -> "未配置完整"
            else -> "$host:$port"
        }
    }

    private const val PREFS = "native-proxy-v1"

    @Volatile private var config = Config(false, "", 0)
    @Volatile private var cachedProxy: Proxy? = null

    /** 系统原始 ProxySelector（Android 上一般为 null），停用代理时交还给系统行为。 */
    private val original: ProxySelector? = runCatching { ProxySelector.getDefault() }.getOrNull()

    /**
     * 可动态切换的默认 ProxySelector：代理开启且配置完整时全部流量走代理；
     * 否则回退系统默认。局域网地址（手机快传 / 遥控 / WebDAV 等本机互通流量）
     * 直连，不绕道代理——代理进程挂了也不影响局域网功能。
     */
    object Switch : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val cfg = AppProxy.config
            if(cfg.enabled && cfg.valid && uri != null && !isLanHost(uri.host)) {
                AppProxy.cachedProxy?.let { return listOf(it) }
            }
            return original?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 代理连不上时不自动摘除：用户在设置里看得见失败原因，手动停用即可
        }
    }

    /** 在进程最早的 Context（attachBaseContext）里调用：装上 Switch 并加载已保存的配置。 */
    fun install(base: Context) {
        val app = base.applicationContext
        if(ProxySelector.getDefault() !== Switch) runCatching { ProxySelector.setDefault(Switch) }
        config = load(app).also { cachedProxy = it.proxyOrNull() }
    }

    fun current(): Config = config

    fun save(base: Context, cfg: Config) {
        config = cfg
        cachedProxy = cfg.proxyOrNull()
        base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", cfg.enabled)
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .apply()
    }

    private fun Config.proxyOrNull(): Proxy? =
        if(enabled && valid) Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)) else null

    private fun load(base: Context): Config {
        val prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean("enabled", false),
            host = prefs.getString("host", "").orEmpty(),
            port = prefs.getInt("port", 0))
    }

    /** IP 字面量且属于本机/私有网段 → 走直连。域名不做判断（解析与路由交给代理）。 */
    internal fun isLanHost(host: String?): Boolean {
        if(host.isNullOrEmpty()) return true
        if(host == "localhost" || host == "::1") return true
        val parts = host.split('.')
        if(parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
        val octets = parts.map { it.toInt() }
        return when {
            octets[0] == 127 -> true                       // 回环
            octets[0] == 10 -> true                        // 10.0.0.0/8
            octets[0] == 192 && octets[1] == 168 -> true   // 192.168.0.0/16
            octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
            octets[0] == 169 && octets[1] == 254 -> true   // 链路本地
            else -> false
        }
    }
}
