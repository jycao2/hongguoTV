package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.RemoteProtocol

/**
 * 遥控门禁密钥与端口的持久持有者。
 *
 * 语义是「**永久有效**」：
 * - 密钥首次使用时生成并写入本机 SharedPreferences，之后所有冷启动复用同一把；
 * - 端口同理持久化，下次启动优先复用；被别的进程抢走才换新（换新后回写）。
 * 这样手机里存住的旧地址任何时候打开都能直接连上，电视端不需要再开二维码窗。
 *
 * 安全边界：门禁靠 token 本身（长随机值，不可枚举），监听只绑内网地址；用户明确选择
 * 「扫码一次、永久直连」的便利性，接受密钥长期留存在本机。
 */
object RemoteAccess {
    private const val PREFS_FILE = "remote_access"
    private var prefs: android.content.SharedPreferences? = null

    /** 门禁密钥。[init] 之前为空串，此时不允许构造遥控会话。 */
    @Volatile var token: String = ""
        private set

    /**
     * 上次成功监听的端口，下次开新会话时优先复用它。
     *
     * 只固定 token 不够：`ServerSocket(0)` 每次给的端口都不同，手机上那个
     * `ip:port/token` 依然会失效。端口被别的进程占用时会自动换一个，
     * 所以这里是「尽量复用」，不是强保证。
     */
    @Volatile var port: Int = 0
        set(value) { field = value; prefs?.edit()?.putInt("port", value)?.apply() }

    /** 冷启动只初始化一次，重复调用是 no-op。必须在第一次构造 PhoneRemoteSession 之前调用。 */
    fun init(context: Context) {
        if(token.isNotEmpty()) return
        val p = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val savedToken = p.getString("token", null)?.takeIf { it.length >= RemoteProtocol.MIN_TOKEN_LENGTH }
            ?: RemoteProtocol.newToken().also { p.edit().putString("token", it).apply() }
        val savedPort = p.getInt("port", 0)
        token = savedToken
        prefs = p
        port = savedPort // 回写同值，无副作用；让后续 hub 绑定结果的落盘路径保持单一
    }
}
