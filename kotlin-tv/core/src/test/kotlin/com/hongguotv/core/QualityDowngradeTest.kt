package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class QualityDowngradeTest {
    @Test fun `actual quality is read from playback text`() {
        assertEquals(1080,QualityDowngrade.parse("1080P"))
        assertEquals(720,QualityDowngrade.parse("720P（兼容资源）"))
        assertEquals(480,QualityDowngrade.parse("480P"))
        assertEquals(0,QualityDowngrade.parse("自动"))
        assertEquals(0,QualityDowngrade.parse(""))
    }
    @Test fun `downgrade steps down one level from what actually played`() {
        assertEquals(720,QualityDowngrade.next(1080,1080))
        assertEquals(480,QualityDowngrade.next(720,1080))
        assertNull(QualityDowngrade.next(480,1080))
    }
    @Test fun `downgrade never goes above the configured ceiling`() {
        // 配置 720 但实际给了 1080（源站回补）时，仍按 720 这个上限往下退。
        assertEquals(480,QualityDowngrade.next(1080,720))
        assertEquals(480,QualityDowngrade.next(720,720))
    }
    @Test fun `unknown actual quality falls back to configured level`() {
        assertEquals(720,QualityDowngrade.next(0,1080))
        assertEquals(480,QualityDowngrade.next(0,720))
        assertNull(QualityDowngrade.next(0,480))
    }
    @Test fun `already at the lowest level stops downgrading`() {
        assertNull(QualityDowngrade.next(480,480))
        assertNull(QualityDowngrade.next(360,1080))
    }
}
