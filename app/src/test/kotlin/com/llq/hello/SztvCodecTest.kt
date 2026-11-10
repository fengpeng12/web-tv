package com.llq.hello

import com.llq.hello.player.SztvCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 深圳取流算法单测：黄金值来自 2026-10-27 浏览器实抓。 */
class SztvCodecTest {

    @Test fun zDecode_解码混淆串() {
        // 源码里的 Z("1e9t0e9R3e9B") = "{K}{P}{t}"（签名模板）
        assertEquals("{K}{P}{t}", SztvCodec.zDecode("1e9t0e9R3e9B"))
        // Z("ygWZ…") = 密钥1
        assertEquals("ejow6p6p6hmrm9g96beh2knecdq5kyw9bp0zxyg7",
            SztvCodec.zDecode("ygWZiZTOnlTby1Ga2AnNwZzdvpWZ==wNnlHe6BDcilzd5tWNxR2Yl52a"))
    }

    @Test fun authToken_与实抓一致() {
        // 抓包：?t=1793107508&id=AxeFRth&token=b8c7f784c3418dcf02309cbee3ee727a
        assertEquals("b8c7f784c3418dcf02309cbee3ee727a",
            SztvCodec.authToken(1793107508L, "AxeFRth"))
    }

    @Test fun signedUrl_与实抓一致() {
        // 抓包：sign=d3aba4f5a6fafcd804165d7d41123871&t=6ae0c255
        // 0x6ae0c255 = 1793114709 = now + 3600，即签名时刻 now = 1793111109
        val (url, expireAt) = SztvCodec.signedUrl(
            "https://sztv-live.sztv.com.cn/AxeFRth/500/q7A7e0R.m3u8",
            "/AxeFRth/500/q7A7e0R.m3u8",
            1793111109000L
        )
        assertTrue(url.endsWith("sign=d3aba4f5a6fafcd804165d7d41123871&t=6ae0c255"))
        assertEquals(1793114709000L, expireAt)
    }

    @Test fun liveUrl_路径格式() {
        assertEquals("https://sztv-live.sztv.com.cn/AxeFRth/500/q7A7e0R.m3u8",
            SztvCodec.liveUrl("AxeFRth", "500", "q7A7e0R"))
    }

    @Test fun parseQuality_异常输入不抛错() {
        // 清晰度密文运行时才有；这里只验证失败分支与合法短向量
        assertNull(SztvCodec.parseQuality(""))
        assertEquals("s", SztvCodec.parseQuality("wc=="))
    }

    @Test fun authUrl_拼装() {
        val u = SztvCodec.authUrl("AxeFRth", 1793107508L)
        assertTrue(u.startsWith("https://hls-api.sztv.com.cn/getCutvHlsLiveKey?t=1793107508&id=AxeFRth&token=b8c7f784c3418dcf02309cbee3ee727a&at=1"))
    }

    @Test fun pathOf_去host留路径() {
        assertEquals("/AxeFRth/500/q7A7e0R.m3u8",
            SztvCodec.pathOf("https://sztv-live.sztv.com.cn/AxeFRth/500/q7A7e0R.m3u8"))
    }
}
