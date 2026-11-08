// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * 指针设备（鼠标 / 触控板 / 手写笔）识别。
 *
 * 遥控和鼠标共用同一套播放界面，但「✕ 退出」这种按钮只对指针用户有意义：
 * 遥控用户按返回键就退出了，屏幕上挂一颗点不到的按钮，只会让人以为要换遥控器。
 *
 * 判断依据刻意**不看设备列表**：不少电视（这台 TCL 就是）带一个 `gIrTouch_Mouse` 之类的
 * 红外触摸设备，静态枚举会把「假装成鼠标的触摸层」也算成鼠标，结果按钮又冒出来了。
 * 事件来源才是真的——手指点屏幕是 SOURCE_TOUCHSCREEN，鼠标移动/点击是 SOURCE_MOUSE。
 */
object TvPointer {

    /** 这个事件是否由指针设备发出。触摸屏不算：手指没有 hover，也没有右键。 */
    fun isPointer(event: MotionEvent): Boolean {
        val source = event.source
        return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD ||
            (source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
    }

    /**
     * hover 只可能来自指针设备：收到就说明鼠标正在用。
     *
     * 不消费事件（返回 false），hover 照常往下派发，不影响其它手势。
     */
    fun onHover(view: View, action: () -> Unit) {
        view.setOnHoverListener { _, event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> action()
            }
            false
        }
    }
}
