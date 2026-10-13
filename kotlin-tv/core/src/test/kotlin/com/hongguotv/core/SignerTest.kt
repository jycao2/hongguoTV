package com.hongguotv.core
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class SignerTest {
    @Before fun pinIdentity() {
        // 预言机按原抓包身份生成；运行时身份已改为每次 API 调用前随机（原身份被平台封禁）
        VendorConstants.installIid = "1905892595382586"
        VendorConstants.installDeviceId = "1905892595378490"
        VendorConstants.processNonce = "Ai6svO3PyrwDOUSmO6ZcResxu"
        VendorConstants.processStarted = 1728388016635L
    }
    @Test fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val ts=row.getInt("ts"); val url=row.getString("url"); val query=url.substringAfter('?')
            val body=if(row.isNull("body")) null else row.getString("body").toByteArray()
            var seed=17L
            val signer=Signer({ts.toLong()*1000}) { seed=(seed*1664525+1013904223) and 0xffffffffL; seed/4294967296.0 }
            assertEquals("branch $i",0,signer.branch(query,body,ts))
            assertEquals("hash $i",row.getString("hash"),signer.hashF13(sm3(query.toByteArray()),body?.let{digest("MD5",it)}?:ByteArray(16),ts).hex())
            assertEquals("gorgon $i",row.getString("gorgon"),signer.gorgon(query,body,ts,42131))
            assertEquals("helios $i",row.getString("helios"),signer.helios(ts))
            assertEquals("medusa $i",row.getString("medusa"),signer.medusa(url,body,ts))
        }
    }
}
