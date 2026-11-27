// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.L10n
import com.hongguotv.core.PlaybackProfile
import com.hongguotv.core.PlaybackProfileRepository
import com.hongguotv.core.PlaybackProfileStore
import com.hongguotv.core.PlaybackProfiles
import java.io.File
import java.util.concurrent.Executors

/**
 * 取址协议档案的刷新。
 *
 * 规矩与更新包一致：**前台拉、后台拉、拉不到就当没有**。拉不到 / 验签不过 / 解析不过 /
 * 版本号不新 —— 一律继续用当前生效的那份（内置默认或上次缓存），绝不阻塞启动、绝不弹错误。
 *
 * 刷新节流单独一套（6 小时）：与取址链路的冷却（20s→60s→120s→300s）完全解耦，
 * 免得一次失败把播放也一起拖住。
 */
class PlaybackProfileUpdater(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = PlaybackProfileRepository()
    private val preferences = activity.getSharedPreferences("playback_profile", Activity.MODE_PRIVATE)
    private val store = PlaybackProfileStore(File(activity.applicationContext.filesDir, "playback-profile.json"))

    @Volatile
    private var busy = false

    private val ttl get() = 6 * 60 * 60 * 1000L

    /** 冷启动：先换上上次缓存的档案（同步、几毫秒），读不到就继续用内置默认。 */
    fun loadCached() {
        val profile = try {
            store.load()
        } catch (_: Exception) {
            null
        } ?: return
        PlaybackProfiles.apply(profile)
    }

    /** 到点才刷新，避免每次冷启动都打一遍网络。 */
    fun refreshIfDue() {
        val now = System.currentTimeMillis()
        if (now - preferences.getLong("last_attempt", 0) < ttl) return
        refresh(null)
    }

    /**
     * 拉一次远端档案。[done] 只用于设置页的手动检查：`(是否采纳, 说明)`。
     * 自动刷新不传回调，失败也静默 —— 取址照旧跑在内置默认上。
     */
    fun refresh(done: ((Boolean, String) -> Unit)?) {
        if (busy) {
            done?.let { main.post { it(false, L10n.tr("上次检查还没结束")) } }
            return
        }
        busy = true
        val now = System.currentTimeMillis()
        preferences.edit().putLong("last_attempt", now).apply()
        worker.execute {
            var adopted = false
            var message = L10n.tr("已是最新档案")
            try {
                val document = repository.fetch()
                adopted = PlaybackProfiles.apply(document.profile)
                if (adopted) {
                    store.save(document.raw)
                    message = L10n.trFmt("已更新到 rev{0}", document.profile.revision.toString())
                } else {
                    message = if (document.profile.revision <= PlaybackProfiles.revision) {
                        L10n.trFmt("已是最新 rev{0}", PlaybackProfiles.revision.toString())
                    } else {
                        L10n.tr("档案被总开关拒绝")
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: L10n.tr("拉取失败")
            } finally {
                busy = false
            }
            done?.let { callback -> main.post { callback(adopted, message) } }
        }
    }

    /** 恢复内置默认：清掉缓存并立刻生效，不联网。 */
    fun resetToBuiltin() {
        store.clear()
        PlaybackProfiles.reset()
    }

    /** 设置行用的短状态：`内置默认 rev1` / `远端 rev7`。 */
    fun statusLine(): String {
        val source = if (PlaybackProfiles.currentSource == PlaybackProfiles.Source.BUILTIN) "内置默认" else "远端档案"
        return L10n.tr(source) + " rev" + PlaybackProfiles.revision
    }

    /** 设置行弹窗正文：把当前生效的档案摊开，出问题时一眼能看出是哪份在跑。 */
    fun summary(): String {
        val profile = PlaybackProfiles.snapshot()
        val gates = profile.gates.entries.joinToString("\n") { (name, gate) ->
            "  $name  ${gate.host}${gate.path}"
        }
        val origin = if (PlaybackProfiles.currentSource == PlaybackProfiles.Source.BUILTIN) {
            L10n.tr("内置默认（随包发布）")
        } else {
            L10n.tr("远端下发（已缓存）")
        }
        return buildString {
            appendLine(L10n.tr("来源：") + origin)
            appendLine(L10n.tr("版本：") + "rev${profile.revision}  ·  ${profile.updatedAt}")
            appendLine(L10n.tr("播放 UA：") + profile.ua.playback.substringBefore(' ').substringAfter('/'))
            appendLine(L10n.tr("签名常量：") + "magic ${profile.v5.magic.size} B  ·  sbox ${profile.v5.sbox.size} B  ·  field21 ${profile.v5.field21}")
            append(L10n.tr("门地址：") + "\n" + gates)
        }
    }

    fun destroy() {
        worker.shutdownNow()
        repository.close()
    }

    /** 只读校验：档案里每一项都必须能取到值，供设置页做自检提示。 */
    fun sanityError(): String? = try {
        PlaybackProfile.parse(PlaybackProfiles.snapshot().toJson())
        null
    } catch (e: Exception) {
        e.message
    }
}
