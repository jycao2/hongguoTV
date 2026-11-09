// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLoginTest {
    @Test
    fun parsesMainlandNumber() {
        assertEquals("13800138000", PhoneLogin.parse("13800138000"))
        assertEquals("13800138000", PhoneLogin.parse(" 138-0013-8000 "))
    }

    @Test
    fun parsesInternationalNumber() {
        assertEquals("+886 912345678", PhoneLogin.parse("+886 0912345678"))
        assertEquals("+886 912345678", PhoneLogin.parse("+886-912345678"))
        assertEquals("+1 2345678", PhoneLogin.parse("+1 2345678"))
    }

    @Test
    fun formatsValidateShapes() {
        assertEquals("13800138000", PhoneLogin.format("86", "13800138000"))
        assertEquals("+886 912345678", PhoneLogin.format("886", "0912345678"))
    }

    @Test
    fun rejectsBadNumbers() {
        for (bad in listOf("23800138000", "1380013800", "+886", "abc", "+886 123")) {
            try {
                PhoneLogin.parse(bad)
                throw AssertionError("「$bad」不应通过校验")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun verifyRequiresSendFirst() {
        val login = PhoneLogin(okhttp3.OkHttpClient())
        try {
            login.verify("123456")
            throw AssertionError("未发码就验码应当被拒绝")
        } catch (expected: IllegalStateException) {
            assertEquals("请先获取验证码", expected.message)
        }
    }
}
