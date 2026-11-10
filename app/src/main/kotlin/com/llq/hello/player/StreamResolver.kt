package com.llq.hello.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 带时效的已解析流 */
data class ResolvedStream(
    val url: String,
    val expireAt: Long,
    val referer: String? = null
)

/** 取流器接口：每台一个实现 */
interface StreamResolver {
    suspend fun resolve(): ResolvedStream
}

/**
 * 深圳卫视取流（真网络实现）。
 * 实测公式：getCutvHlsLiveKey 返回 JSON 字符串密文，Z 解码得「流ID」(如 q7A7e0R)，
 * 失败返回裸 0。清晰度目录由频道信息接口下发（典型 500），此处可配。
 */
class SztvResolver(
    private val liveId: String,
    private val quality: String = "500"
) : StreamResolver {

    override suspend fun resolve(): ResolvedStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        // 1. 授权接口拿流ID密文
        val cipher = httpGet(SztvCodec.authUrl(liveId, now), referer = "https://www.sztv.com.cn/")
        val streamId = SztvCodec.parseQuality(cipher)
            ?: throw IllegalStateException("授权失败：$cipher")
        // 2. 拼流地址并签名（有效期 1h）
        val rawUrl = SztvCodec.liveUrl(liveId, quality, streamId)
        val (signed, expireAt) = SztvCodec.signedUrl(rawUrl, SztvCodec.pathOf(rawUrl), now * 1000)
        ResolvedStream(signed, expireAt, referer = "https://www.sztv.com.cn/")
    }

    private fun httpGet(urlStr: String, referer: String? = null): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${body.take(120)}")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
