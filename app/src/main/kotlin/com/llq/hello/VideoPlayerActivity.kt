package com.llq.hello

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource

/**
 * 原生全屏播放器：带 Referer/UA 请求头播 HLS(m3u8) 直播流。
 *
 * 用 Media3(ExoPlayer) 而不是原生 MediaPlayer：
 * 系统 MediaPlayer 对 m3u8 的支持依赖厂商实现，多数机型既不出画面也不回调 onPrepared，
 * 表现为「一直黑屏」。ExoPlayer 自带 HLS 解析，分片请求同样带上请求头。
 *
 * 起播速度的三处关键处理（对齐 CCTV 那套「秒播」思路）：
 * 1. 播放器在 onCreate 里就建好，界面先出来；取流（网络往返）在后台并行跑，
 *    地址一到直接装源起播 —— 不再「等取流完成才打开界面」白白串行等两段。
 * 2. 直播起播缓冲压到 800ms（默认 2500ms），够解码一帧就出画面。
 * 3. 起播点钉在直播边缘（targetOffsetMs=2000），少拖历史分片。
 */
class VideoPlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"

        /** 传 native 频道的 liveId：由本页自己后台取流，省掉一次界面启动等待 */
        const val EXTRA_LIVE_ID = "live_id"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private var player: ExoPlayer? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var tip: TextView
    private lateinit var loading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val liveId = intent.getStringExtra(EXTRA_LIVE_ID).orEmpty()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ExoPlayer 直接输出到 SurfaceView，画幅按 PLAYER 的默认比例拉伸铺满
        surfaceView = SurfaceView(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        loading = ProgressBar(this).apply { visibility = View.VISIBLE }
        root.addView(loading, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        tip = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(tip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)
        fullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 播放器先建：界面冷启动与取流并行，不必等地址到手才建实例
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // 起播只要 800ms 数据（点播默认 2500ms），直播出首帧更快
                    .setBufferDurationsMs(5000, 20000, 800, 1000)
                    .build()
            )
            .build()
        player = exo
        // 绑定画面输出，否则只有解码没有显示（表现为黑屏）
        exo.setVideoSurfaceView(surfaceView)
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        if (loading.visibility != View.VISIBLE) loading.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        loading.visibility = View.GONE
                        tip.text = ""
                    }
                    Player.STATE_ENDED -> {
                        loading.visibility = View.GONE
                        tip.text = "直播已结束"
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                loading.visibility = View.GONE
                tip.text = "播放失败：${error.errorCodeName}\n${error.message ?: ""}"
            }
        })

        // 轻点画面暂停/继续（直播暂停后从缓冲处继续）
        surfaceView.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
        }

        fun play(streamUrl: String, streamReferer: String) {
            val headers = HashMap<String, String>()
            if (streamReferer.isNotEmpty()) headers["Referer"] = streamReferer
            headers["User-Agent"] = UA

            // 请求头交给数据源：m3u8 清单与 ts 分片请求都会带上，避免被防盗链拦下
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)

            // 直播配置：起播点钉在直播边缘附近，不从头拉滑窗历史分片
            val item = MediaItem.Builder()
                .setUri(streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(2000)
                        .build()
                )
                .build()

            val source = HlsMediaSource.Factory(httpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)

            exo.setMediaSource(source)
            exo.prepare()
        }

        when {
            liveId.isNotEmpty() -> {
                tip.text = "正在取流…"
                Thread {
                    val result = runCatching {
                        kotlinx.coroutines.runBlocking {
                            com.llq.hello.player.SztvResolver(liveId).resolve()
                        }
                    }
                    runOnUiThread {
                        result.onSuccess { rs -> play(rs.url, rs.referer.orEmpty()) }
                            .onFailure { e ->
                                loading.visibility = View.GONE
                                tip.text = "取流失败：${e.message}\n按返回键换线路"
                            }
                    }
                }.start()
            }
            url.isNotEmpty() -> play(url, referer)
            else -> {
                loading.visibility = View.GONE
                tip.text = "没有可播放的地址"
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    /** 全屏：隐藏系统栏并进刘海区。 */
    @Suppress("DEPRECATION")
    private fun fullscreen() {
        val decor: View? = window.decorView
        if (decor == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }
}