// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 当前生效的协议档案。
 *
 * 全部取值（签名常量、UA、门地址）都从这里读，默认是内置默认；远端档案只在
 * [apply] 且 revision 严格变大时才替换。替换是**整份引用原子换掉**，
 * 签名过程中途换档案不会出现「magic 用新版、signKey 用旧版」的撕裂读。
 */
object PlaybackProfiles {
    enum class Source { BUILTIN, CACHE }

    @Volatile
    private var active: PlaybackProfile = PlaybackProfile.builtin()

    @Volatile
    private var source: Source = Source.BUILTIN

    /** 是否允许远端档案生效。留一个编译期总开关，出问题时改一处即可全网回到内置默认。 */
    @Volatile
    var enabled: Boolean = true

    val revision: Int get() = active.revision
    val currentSource: Source get() = source

    /** 取一份不可变快照：签名这类需要一致性的路径应该拿一次快照用到底。 */
    fun snapshot(): PlaybackProfile = active

    val v5: V5Profile get() = active.v5
    val ua: UaProfile get() = active.ua

    fun gate(name: String): GateProfile = active.gate(name)

    /** 只接受 revision 严格变大的档案；返回是否真的换了。 */
    @Synchronized
    fun apply(profile: PlaybackProfile): Boolean {
        if (!enabled) return false
        if (profile.revision <= active.revision) return false
        active = profile
        source = Source.CACHE
        return true
    }

    @Synchronized
    fun reset() {
        active = PlaybackProfile.builtin()
        source = Source.BUILTIN
    }

    /** 仅测试用：把当前生效档案换成指定的一份，不受 revision 限制。 */
    @Synchronized
    internal fun replaceForTest(profile: PlaybackProfile) {
        active = profile
        source = Source.CACHE
    }
}
