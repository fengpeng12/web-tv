package com.llq.hello.player

import java.security.MessageDigest

/**
 * 深圳卫视（CUTV TideLivePlayer v2.1.3）取流算法。
 * 全部公式已用真实抓包验证：
 * - 授权 token：md5(秒 + liveId + "cutvLiveStream|Dream2017")
 * - 流签名：md5("ejow6p6p…g7" + 路径 + 过期hex)，过期 = now + 1h（hex）
 * 纯 JVM，无 Android 依赖（Base64 用 java.util）。
 */
object SztvCodec {

    /** Z 混淆解码：前后半对调 → 整串反转 → Base64 */
    fun zDecode(s: String): String {
        val b = s.length - Math.floor(s.length / 2.0).toInt()
        val swapped = s.substring(b) + s.substring(0, b)
        val reversed = swapped.reversed()
        val bytes = java.util.Base64.getDecoder().decode(reversed)
        return String(bytes, Charsets.UTF_8)
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 授权接口 token：md5(当前秒 + liveId + 密钥2) */
    fun authToken(nowSec: Long, channelId: String): String =
        md5(nowSec.toString() + channelId + AUTH_KEY_2)

    /**
     * 流签名拼 URL：sign = md5(密钥1 + 路径 + 过期hex)，t = (now + 1h) 的十六进制。
     * 返回 (完整带签名 URL, 过期时间戳ms)。
     */
    fun signedUrl(url: String, path: String, nowMs: Long): Pair<String, Long> {
        val expireSec = nowMs / 1000 + 3600
        val expireHex = java.lang.Long.toHexString(expireSec)
        val sign = md5(AUTH_KEY_1 + path + expireHex)
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}sign=$sign&t=$expireHex" to expireSec * 1000
    }

    /** 直播流地址：https://sztv-live.sztv.com.cn/<liveId>/<清晰度>/<流ID>.m3u8 */
    fun liveUrl(channelId: String, quality: String, streamId: String): String =
        "$LIVE_SERVER/$channelId/$quality/$streamId.m3u8"

    /** 授权接口密文解出「流ID」（如 q7A7e0R），"0"/空 = 失败 */
    fun parseQuality(cipher: String): String? {
        val q = zDecode(cipher)
        return if (q.isNotEmpty() && q != "0") q else null
    }

    /** 流路径（用于签名）：去掉 scheme://host，保留 / 开头 */
    fun pathOf(url: String): String = "/" + url.substringAfter("://").substringAfter('/')

    /** 授权接口完整 URL（含 query） */
    fun authUrl(channelId: String, nowSec: Long): String =
        "$AUTH_API?t=$nowSec&id=$channelId&token=${authToken(nowSec, channelId)}&at=1"

    /** 流 ID 从页面 EPG 侧配置来，暂由 resolver 抓 index.html 里的 liveId 关联配置 */
    const val AUTH_KEY_1 = "ejow6p6p6hmrm9g96beh2knecdq5kyw9bp0zxyg7"
    const val AUTH_KEY_2 = "cutvLiveStream|Dream2017"
    const val AUTH_API = "https://hls-api.sztv.com.cn/getCutvHlsLiveKey"
    const val LIVE_SERVER = "https://sztv-live.sztv.com.cn"
}
