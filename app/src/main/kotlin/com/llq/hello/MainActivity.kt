package com.llq.hello

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 浏览器：网页浏览 + 强化视频播放（自动播放、全屏、直链嗅探转内置播放器）。 */
class MainActivity : Activity() {

    companion object {
        private const val DEMO_URL = "https://live.jstv.com/"

        /**
         * 桌面版 Chrome 标识：站点返回 PC 版布局（频道栏、播放器区域更完整）。
         * 注意：部分站点（如 fjtv 一类）的 WAF 会对桌面 UA 直接返回 403
         * （响应头 Ws-Action: bot），若页面变成无样式堆叠可改回手机 UA。
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 手机 UA：个别站点 PC 版栅格太小或 WAF 拦桌面 UA 时切换使用。 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 对桌面 UA 返回 403 的站点（WAF 判 bot）：这些站点无论 PC 模式如何都发手机 UA，
         * 否则页面会退化成无样式堆叠（背景全黑、蓝色裸链接、图片破图）。
         */
        private val FORCE_MOBILE_UA_HOSTS = listOf(
            "fjtv.net",
            "live.fjtv.net"
        )

        /**
         * 只切换手机 UA、保留 viewport 缩放的站点。
         * B站直播桌面版是固定宽度 PC 布局，窄屏下播放器被黑边包住还要横向裁切；
         * 手机 UA 下页面按屏宽自适应，播放器铺满。
         */
        private val PREFER_MOBILE_UA_HOSTS = listOf(
            "live.bilibili.com",
            "bilibili.com"
        )

        /** 该地址是否只能走手机 UA。 */
        private fun forceMobileUa(url: String?): Boolean {
            if (url == null) return false
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return false
            return FORCE_MOBILE_UA_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** 该地址是否优先用手机 UA（但仍按屏宽缩放排版）。 */
        private fun preferMobileUa(url: String?): Boolean {
            if (url == null) return false
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return false
            return PREFER_MOBILE_UA_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var contentRoot: FrameLayout
    private lateinit var topBar: LinearLayout

    private var pageFullscreen = false

    /** 加载遮罩：点频道到真正起播之间显示动画，避免看到白屏和半成品布局。 */
    private var loadingOverlay: FrameLayout? = null
    private var loadingLabel: TextView? = null
    /** 起播探测令牌：换台时递增，旧的探测循环自行退出。 */
    private var playProbeToken = 0
    private var loadingTimeout: Runnable? = null

    /** 用户主动退出全屏的抑制截止时刻：这之前页面脚本的自动全屏请求一律忽略。 */
    private var userExitedFullscreenAt = 0L
    /** 是否使用手机 UA（默认 false = 桌面 UA）。 */
    private var useMobileUa = false
    /** 只加载视频：屏蔽网页图片（默认开），省流量并让页面骨架更快出现。 */
    private var videoOnlyMode = true
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var channelPanel: View? = null

    /** 分类下的频道网格弹窗：返回键先关它，回到分类列表。 */
    private var groupDialog: AlertDialog? = null

    /** 上次按返回键的时间（毫秒），用于「再按一次退出」。 */
    private var lastBackPress = 0L

    /** 当前正在播放的网页地址，用于在频道列表里标出当前频道。 */
    private var currentUrl: String = ""

    /** 当前频道键（分类 + 频道名），比 URL 稳定，页面跳转后仍指向原频道。 */
    private var currentChannelKey: String = ""

    private fun channelKey(group: String, name: String): String = "$group\u0000$name"

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var customHolder: FrameLayout? = null
    private var orientationBeforeFullscreen = 0
    /** 最近一次上报的视频宽高比（w/h）；0 表示未知，此时退回铺满。 */
    private var videoAspect = 0f

    /** 真实屏幕尺寸（CSS 像素）：由原生检测得到，注入页面供各站点脚本铺满时使用。 */
    private var screenCssW = 0
    private var screenCssH = 0

    /** 左上角的检测结果显示标签。 */
    private lateinit var screenTag: TextView

    /**
     * 检测屏幕可用的 CSS 像素尺寸。
     * 不能用 window.innerWidth / 100vw 代替：WebView 在 useWideViewPort 下布局视口
     * 可能不等于屏幕宽（桌面页尤其明显），页面里算出的「铺满」会超出或被裁。
     * 这里用窗口真实像素 ÷ density 得到与 CSS 像素一致的数值。
     */
    @Suppress("DEPRECATION")
    private fun detectScreenSize() {
        val dm = resources.displayMetrics
        val density = if (dm.density > 0f) dm.density else 1f
        var pw = dm.widthPixels
        var ph = dm.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = windowManager.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) {
                    pw = b.width()
                    ph = b.height()
                }
            } catch (e: Exception) {
            }
        } else {
            try {
                val p = android.graphics.Point()
                windowManager.defaultDisplay.getRealSize(p)
                if (p.x > 0 && p.y > 0) {
                    pw = p.x
                    ph = p.y
                }
            } catch (e: Exception) {
            }
        }
        screenCssW = Math.round(pw / density)
        screenCssH = Math.round(ph / density)
        android.util.Log.d("BrowserFS",
            "screen ${pw}x${ph}px density=$density -> ${screenCssW}x${screenCssH}css")
        updateScreenTag()
    }

    /** 刷新左上角的检测标签：成功显示长宽，失败显示失败原因。 */
    private fun updateScreenTag() {
        if (!::screenTag.isInitialized) return
        runOnUiThread {
            val ok = screenCssW > 0 && screenCssH > 0
            if (ok) {
                val max = Math.max(screenCssW, screenCssH)
                val min = Math.min(screenCssW, screenCssH)
                screenTag.text = "✔ ${max}×${min}"
                screenTag.setTextColor(0xFF117A37.toInt())
            } else {
                screenTag.text = "✘ 检测失败"
                screenTag.setTextColor(0xFFC62828.toInt())
            }
        }
    }

    /** 把真实屏幕尺寸送进页面：站点脚本据此把视频摆成正好铺满 */
    private fun injectScreenSize(wv: WebView?) {
        if (wv == null || screenCssW <= 0 || screenCssH <= 0) return
        val js = "window.__scrW=$screenCssW;window.__scrH=$screenCssH;" +
            "window.__scrScale=window.devicePixelRatio||1;"
        wv.evaluateJavascript(js, null)
    }

    /**
     * 全屏 View 的布局参数：已知宽高比时算成「按比例居中的矩形」，未知时铺满。
     * 与 WebViewTV 的 Rational 方案同思路——站点全屏键交出的 View 不带页面样式，
     * 直接 MATCH_PARENT 会把 4:3 等比例的内容拉伸变形。
     */
    private fun ratioParams(w: Int, h: Int): FrameLayout.LayoutParams {
        val aspect = videoAspect
        if (aspect <= 0f || w <= 0 || h <= 0) {
            return FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        var vw = w
        var vh = (w / aspect).toInt()
        if (vh > h) {
            vh = h
            vw = (h * aspect).toInt()
        }
        return FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
    }

    /** 视频比例后到（页面 play 才上报）时，重排已挂上的全屏 View。 */
    private fun updateCustomViewRatio() {
        val holder = customHolder ?: return
        val view = customView ?: return
        if (holder.width <= 0 || holder.height <= 0) return
        view.layoutParams = ratioParams(holder.width, holder.height)
        view.requestLayout()
    }

    private val chrome = object : WebChromeClient() {
        /**
         * 网页里 video 点「全屏」时，Chromium 内核把画面抽成独立 View 交过来。
         * 这里不做任何 DOM 注入，所以对任意站点通吃——与 X浏览器 同一套机制。
         */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            // 存档当前方向，退出全屏时还原
            try {
                orientationBeforeFullscreen = requestedOrientation
            } catch (e: Exception) {
                orientationBeforeFullscreen = 0
            }
            customView = view
            customCallback = callback
            // 黑底容器：视频比例与屏幕不一致时露出的边是黑色，不是页面残影
            val holder = FrameLayout(this@MainActivity)
            holder.setBackgroundColor(Color.BLACK)
            // 全屏时返回键先落到这个容器上：直接退回频道列表，不被网页消费
            holder.isFocusableInTouchMode = true
            holder.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    onBackPressed()
                    true
                } else false
            }
            holder.requestFocus()
            customHolder = holder
            contentRoot.addView(holder, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            // 按视频宽高比摆放（未知比例时退回铺满）
            holder.addView(view, ratioParams(holder.width, holder.height))
            // 首次 addView 时 holder 尚未测量（宽高为 0），布局完成后按真实尺寸重算
            holder.post { updateCustomViewRatio() }
            topBar.visibility = View.GONE
            pageFullscreen = true
            hideSystemBars()
            ensureAudible()
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            (view.parent as? ViewGroup)?.removeView(view)
            customHolder?.let { contentRoot.removeView(it) }
            customHolder = null
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            // 还原进入全屏前的方向；曾被全屏标记覆盖过，这里统一走退出流程
            try {
                requestedOrientation = orientationBeforeFullscreen
            } catch (e: Exception) {
            }
            pageFullscreen = false
            hideChannelPanel()
            topBar.visibility = View.VISIBLE
            goEdgeToEdge()
        }

        /**
         * 进度闸门（对照 CCTV 秒播的 startup gate）：
         * 主文档 DOM 就绪（progress≥25 且标题已出现）时先注入一次播放脚本，
         * 不再干等 onPageFinished（那要等所有广告、统计脚本、图片加载完，常滞后数秒）。
         * 只注入「自带轮询」的脚本，它们没找到 video 会继续轮询，早注入只有收益。
         */
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (newProgress < 25) return
            val key = view?.url ?: return
            if (!key.startsWith("http")) return
            if (gatedUrl == key) return
            gatedUrl = key
            // 脚本第一行就置幂等标志、内部才去找 video：body 没建就注入会白置标志，
            // 之后 onPageFinished 的二次注入反而被挡回 → 先确认 body 存在再注入。
            view.evaluateJavascript("!!(document.body&&document.head)") { r ->
                if (r != "true") { gatedUrl = null; return@evaluateJavascript }
                val js = playerScript(key)
                android.util.Log.d("BrowserFS", "gate inject url=$key")
                view.evaluateJavascript(FORCE_VIEWPORT_JS, null)
                view.evaluateJavascript(js, null)
                // 探测也一起提前：不等 onPageFinished 就能揭盖，省掉遮罩空等的秒级延迟
                if (loadingOverlay?.visibility == View.VISIBLE) {
                    loadingLabel?.text = "正在缓冲直播画面…"
                }
                startPlayProbe(view)
            }
        }
    }

    /** 进度闸门已注入过的地址：同一次加载只提前注一次，避免 25/50/75 重复执行。 */
    private var gatedUrl: String? = null

    /** 按站点选择播放器脚本：每个站点一套，互不干扰。 */
    private fun playerScript(url: String): String = when {
        url.contains("gdtv.cn") -> GDTV_JS
        url.contains("jxntv.cn") -> JXNTV_JS
        url.contains("gxtv.cn") -> GXTV_JS
        url.contains("hnntv.cn") -> HNNT_JS
        url.contains("hebrts.cn") -> HEBEI_JS
        url.contains("sztv.com.cn") -> SZTV_JS + "\n" + SZTV_BOOT_JS
        url.contains("mgtv.com") -> MGTV_FULL_JS
        url.contains("cctv.com") || url.contains("cctv.cn") || url.contains("cntv.cn") -> CCTV_JS
        url.contains("yangshipin.cn") -> YANG_JS
        url.contains("hntv.tv") || url.contains("hndt.com") -> HNTV_JS
        url.contains("iqilu.com") -> QILU_JS
        url.contains("jstv.com") -> JSTV_JS
        url.contains("kankanews.com") -> KANKAN_JS
        url.contains("fjtv.net") -> FJTV_FILL_JS
        url.contains("yntv.cn") -> YNTV_JS
        url.contains("bilibili.com") -> BILI_JS
        url.contains("cztv.com") -> AUTOPLAY_JS
        url.contains("sztv.com.cn") -> AUTOPLAY_JS
        else -> AUTOPLAY_JS
    }

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // 页面一开始解析就把真实尺寸和视口楔进去，早于站点脚本执行
            // 重置进度闸门：同一地址重载也要能再次提前注入
            gatedUrl = null
            injectScreenSize(view)
            view?.evaluateJavascript(FORCE_VIEWPORT_JS, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 任何真正的网页加载都记为「正在观看」，含页面内跳转和地址栏输入
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                rememberUrl(url)
            }
            val js = if (url != null) playerScript(url) else AUTOPLAY_JS
            val name = when {
                url == null -> "autoplay"
                url.contains("gdtv.cn") -> "gdtv"
                url.contains("jxntv.cn") -> "jxntv"
                url.contains("gxtv.cn") -> "gxtv"
                url.contains("hnntv.cn") -> "hnnt"
                url.contains("hebrts.cn") -> "hebei"
                url.contains("sztv.com.cn") -> "sztv"
                url.contains("mgtv.com") -> "mgtv"
                url.contains("cctv.com") || url.contains("cctv.cn") || url.contains("cntv.cn") -> "cctv"
                url.contains("yangshipin.cn") -> "yangshipin"
                url.contains("jstv.com") -> "jstv"
                url.contains("kankanews.com") -> "kankan"
                url.contains("fjtv.net") -> "fjtv"
                url.contains("yntv.cn") -> "yntv"
                url.contains("bilibili.com") -> "bili"
                else -> "autoplay"
            }
            // gate 已在 DOM 就绪时注入过同一地址：那套脚本自带 setInterval 轮询，
            // 再注入只会叠出多份定时器（部分站点无幂等标志），这里跳过重复注入
            val alreadyGated = url != null && gatedUrl == url
            if (alreadyGated) {
                android.util.Log.d("BrowserFS", "skip reinject[$name] url=$url")
            } else {
                android.util.Log.d("BrowserFS", "inject[$name] url=$url len=${js.length}")
            }
            // 页面骨架就绪但视频还没起播：换成「正在起播」，避免过早揭盖看到黑播放器
            runOnUiThread {
                if (loadingOverlay?.visibility == View.VISIBLE) {
                    loadingLabel?.text = "正在缓冲直播画面…"
                }
            }
            // 切后台保活：多数站点监听 visibilitychange 主动暂停，这里在隐藏时救回播放
            view?.evaluateJavascript(BACKGROUND_KEEP_JS, null)
            // 原生侧起播探测：部分站点脚本不回调 onPlaying，遮罩会一直盖住已播放的画面
            startPlayProbe(view)
            // 先把真实屏幕尺寸送进页面，脚本里的铺满逻辑优先用它
            injectScreenSize(view)
            // 再把布局视口钉到真实屏幕宽度：100vw/100vh 才真正等于屏幕
            view?.evaluateJavascript(FORCE_VIEWPORT_JS) { r ->
                android.util.Log.d("BrowserFS", "viewport force=$r scr=${screenCssW}x${screenCssH}")
            }
            // 播放器通常是后建的：首次注入后延迟再补一次，脚本自身也会持续轮询
            if (!alreadyGated) view?.evaluateJavascript(js, null)
            view?.postDelayed({
                injectScreenSize(view)
                view?.evaluateJavascript(FORCE_VIEWPORT_JS, null)
                if (!alreadyGated) view?.evaluateJavascript(js, null)
            }, 2000)
            // B站移动版播放器要真实触摸才创建 <video>：脚本模拟的 click 不算用户手势
            if (url != null && url.contains("bilibili.com")) {
                view?.postDelayed({ if (view != null) tapCenterOfWebView(view) }, 1200)
            }
            // 诊断用：上报页面视口与实际控件尺寸（排查 PC 模式布局异常）
            view?.postDelayed({
                view?.evaluateJavascript(VP_JS) { r ->
                    android.util.Log.d("BrowserFS",
                        "vp=$r view=${view.width}x${view.height} zoom=${view.settings.textZoom}")
                }
            }, 3000)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            // 非 http 协议（intent://weixin://aliplay://…）交给系统处理，失败则忽略，避免页面白屏
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                true
            } catch (e: Exception) {
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        contentRoot = FrameLayout(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        // 恢复上次选择的 UA（默认桌面版）
        useMobileUa = getSharedPreferences("channels", MODE_PRIVATE)
            .getBoolean("use_mobile_ua", false)

        // 恢复「只加载视频」（默认开）
        videoOnlyMode = getSharedPreferences("channels", MODE_PRIVATE)
            .getBoolean("video_only", true)

        // 顶部地址栏
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFFF2F3F5.toInt())
        }
        addressBar = EditText(this).apply {
            hint = "输入网址或搜索内容"
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(Color.WHITE)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openInput()
                    true
                } else false
            }
            setOnLongClickListener {
                sniffVideo()
                true
            }
        }
        // 左上角：屏幕检测结果显示（点击可重新检测）
        screenTag = TextView(this).apply {
            text = "检测中…"
            textSize = 12f
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                detectScreenSize()
                if (::webView.isInitialized) {
                    injectScreenSize(webView)
                    webView.evaluateJavascript(FORCE_VIEWPORT_JS, null)
                }
            }
        }
        topBar.addView(screenTag, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        topBar.addView(addressBar, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 频道入口
        val channelBtn = TextView(this).apply {
            text = "频道"
            textSize = 14f
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { showChannels() }
        }
        topBar.addView(channelBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // WebView
        webView = WebView(this)
        configWebView(webView)
        column.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        contentRoot.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(contentRoot)

        goEdgeToEdge()
        padTopForStatusBar(topBar)
        // 启动即检测真实屏幕尺寸，供所有站点的视频铺满使用
        detectScreenSize()

        // 内置频道（山东）只写入一次，之后用户的增删不会被覆盖
        ChannelStore(this).seedBuiltins()
        ChannelStore(this).dedupe()

        // 清理遗留的「未分类」分组（只执行一次）
        val pref = getSharedPreferences("channels", MODE_PRIVATE)
        if (!pref.getBoolean("purged_uncategorized", false)) {
            ChannelStore(this).purgeGroup("未分类")
            pref.edit().putBoolean("purged_uncategorized", true).apply()
        }

        // 回到上次观看的频道；没有记录（或该频道已不存在）时用频道列表第一个
        restoreLast()
    }

    /** 返回键：全屏 → 频道网格 → 分类列表 → 网页上一页 → 主界面（连按两次退出）。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 1) 视频全屏：退出全屏并弹回当前频道的频道列表
        if (customView != null) {
            chrome.onHideCustomView()
            showChannelsAfterExit()
            return
        }
        // 2) 页面全屏（点频道就会进这一层）：只弹回频道列表，
        //    不退出全屏、不动页面 video 样式 —— 否则画面会缩回网页内，像是网页后退了。
        if (pageFullscreen) {
            backToChannels()
            return
        }
        // 3) 分类下的频道网格：关掉弹窗并回到分类列表
        val gd = groupDialog
        if (gd != null && gd.isShowing) {
            groupDialog = null
            gd.dismiss()
            showChannelPanel()
            return
        }
        // 4) 分类列表：收起面板，再按一次退出
        if (channelPanel != null) {
            hideChannelPanel()
            confirmExit()
            return
        }
        // 5) 网页有历史则回上一页，否则进入两次退出
        if (webView.canGoBack()) webView.goBack() else confirmExit()
    }

    /** 两秒内连按两次返回才真正退出。 */
    private fun confirmExit() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackPress > 2000) {
            lastBackPress = now
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    /** 返回键退出播放：抑制页面自动全屏、撤回铺满样式，并弹出当前频道的频道列表。 */
    private fun showChannelsAfterExit() {
        userExitedFullscreenAt = android.os.SystemClock.uptimeMillis() + 60_000L
        // 页面脚本会把 video 锁成 fixed 铺满，退出后要撤掉，否则网页仍像全屏
        webView.evaluateJavascript(
            "try{var s=document.getElementById('__fillLock');if(s)s.remove();" +
                "var vs=document.querySelectorAll('video');" +
                "for(var i=0;i<vs.length;i++){vs[i].__fillWatched=false;" +
                "vs[i].style.setProperty('position','static','important');" +
                "vs[i].style.setProperty('width','100%','important');" +
                "vs[i].style.setProperty('height','auto','important');" +
                "vs[i].style.setProperty('z-index','auto','important');" +
                "}" +
                "document.documentElement.style.removeProperty('overflow');" +
                "if(document.body)document.body.style.removeProperty('overflow');" +
                "}catch(e){}", null)
        backToChannels()
    }

    /** 退出全屏后回到频道列表：优先当前频道所在分类，找不到就打开分类列表。 **/
    private fun backToChannels() {
        val store = ChannelStore(this)
        val all = store.load()
        val sep = '\u0000'
        var group = currentChannelKey.substringBefore(sep)
        if (group.isEmpty()) {
            // 当前频道没记录时，用正在看的网址反查
            val url = webView.url ?: currentUrl
            all.firstOrNull { it.url == url }?.let { group = it.group }
            if (group.isEmpty()) currentChannelKey = ""
        }
        val items = all.filter { it.group == group }
        if (group.isNotEmpty() && items.isNotEmpty()) {
            requestGroupAccess(group) { showGroupChannels(store, group, items) }
        } else {
            showChannelPanel()
        }
    }

    /** 在 WebView 画面中心派发一次真实触摸（B站移动版只认用户手势，JS 模拟的 click 不算）。 */
    private fun tapCenterOfWebView(v: WebView) {
        if (v.width <= 0 || v.height <= 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val x = v.width / 2f
        val y = v.height / 2f
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try {
            v.dispatchTouchEvent(down)
            v.dispatchTouchEvent(up)
        } catch (e: Exception) {
            android.util.Log.d("BrowserFS", "tapCenter 失败: ${e.message}")
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** 统一加载入口：先按目标站点定好 UA，再 loadUrl（UA 换了沿用上一页的会拿不到正确页面）。 */
    private fun loadPage(url: String) {
        // 原生播放频道（native://协议）：不走 WebView，直接解析取流交给内置播放器。
        if (url.startsWith("native://")) {
            openNativeStream(url)
            return
        }
        val mobile = useMobileUa || forceMobileUa(url) || preferMobileUa(url)
        val want = if (mobile) MOBILE_UA else DESKTOP_UA
        if (webView.settings.userAgentString != want) {
            webView.settings.userAgentString = want
        }
        // 桌面页普遍按 1000px+ 的固定宽度排版，开 overview 会被整体缩小并压在左上角；
        // 手机页带 viewport meta，需要 overview 才能铺满屏宽。
        // useWideViewPort 才是「整页缩小」的根因：true 时布局视口会被撑到页面宽度
        //（PC 页约 2000px），WebView 再整体缩放到屏幕宽，内容就缩在左上角。
        // 福建台是 PC 版 XHTML、页面里没有 viewport meta：即使强制了手机 UA，
        // useWideViewPort 也会把布局视口撑到页面固有宽度（约 2000px）再整体缩小，
        // 实测被压到约 0.47 倍挤在左上角。这类站点必须关掉两个开关，按屏宽 1:1 排版。
        val pcLayout = forceMobileUa(url)
        webView.settings.useWideViewPort = mobile && !pcLayout
        webView.settings.loadWithOverviewMode = mobile && !pcLayout
        // 只加载视频：blockNetworkImage 在网络层直接拦掉图片请求，
        // 比事后删 <img> 彻底（省掉下载和解码）。视频分片走 media 加载器，不受影响。
        webView.settings.blockNetworkImage = videoOnlyMode
        webView.loadUrl(url)
    }

    /**
     * 原生播放频道：native://sztv:<liveId>。
     * 直接把 liveId 交给播放页，由它在后台取流、地址一到就起播：
     * 这样播放页冷启动和网络取流是并行的，不用等取流完成才拉起界面（少一段串行等待）。
     */
    private fun openNativeStream(url: String) {
        val parts = url.removePrefix("native://").split(":")
        if (parts.size < 2 || parts[0] != "sztv") {
            Toast.makeText(this, "未知的原生频道：$url", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_LIVE_ID, parts[1])
        )
    }

    /** 启动恢复：回到上次观看的频道页，并直接按播放态铺满。 */
    private fun restoreLast() {
        val url = lastWatchedUrl()
        showLoading("正在恢复上次的频道…")
        loadPage(url)
        currentUrl = url
        ChannelStore(this).load().firstOrNull { it.url == url }?.let {
            currentChannelKey = channelKey(it.group, it.name)
        }
        enterPageFullscreen()
    }

    /** 记下当前观看的地址，供下次启动回到这里。只记真正的网页。 */
    private fun rememberUrl(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        currentUrl = url
        // 若地址恰好等于某个频道，同步把当前频道指向它（重启恢复时会用到）
        if (currentChannelKey.isEmpty()) {
            ChannelStore(this).load().firstOrNull { it.url == url }?.let {
                currentChannelKey = channelKey(it.group, it.name)
            }
        }
        getSharedPreferences("channels", MODE_PRIVATE)
            .edit().putString("last_url", url).apply()
    }

    /** 上次观看的频道地址：有记录就回到这里，没有记录则用频道列表第一个。 */
    private fun lastWatchedUrl(): String {
        val saved = getSharedPreferences("channels", MODE_PRIVATE)
            .getString("last_url", "").orEmpty()
        if (saved.isNotEmpty()) {
            // 启动恢复时补上「当前频道」，让列表一打开就标对
            ChannelStore(this).load().firstOrNull { it.url == saved }?.let {
                currentChannelKey = channelKey(it.group, it.name)
            }
            return saved
        }
        return ChannelStore(this).load().firstOrNull()?.url ?: DEMO_URL
    }

    /** 网页里的 video 开始播放时被页面脚本调用：进入页面全屏播放模式。 */
    inner class PageBridge {
        @JavascriptInterface
        fun onPlaying() {
            // 用户按返回主动退出全屏后，页面 tick 仍会每 700ms 上报播放，
            // 若无条件进全屏就会被立刻拉回去，表现为「返回键没反应」。
            if (android.os.SystemClock.uptimeMillis() < userExitedFullscreenAt) return
            // 页面已真正起播：揭掉加载动画
            runOnUiThread {
                hideLoading()
                enterPageFullscreen()
            }
        }

        /** 页面脚本查询：用户是否已主动退出全屏（退出后不再自动铺满）。 */
        @JavascriptInterface
        fun isUserExited(): String =
            if (android.os.SystemClock.uptimeMillis() < userExitedFullscreenAt) "1" else "0"

        /**
         * 页面 video 的真实像素尺寸：原生侧据此按比例摆放全屏画面。
         * 只靠 object-fit 由网页自己算时，站点全屏键交出的独立 View 拿不到页面样式，
         * 会被 MATCH_PARENT 拉伸；有了宽高比就能算成「按比例居中的矩形」。
         */
        @JavascriptInterface
        fun onVideoSize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            videoAspect = width.toFloat() / height.toFloat()
            runOnUiThread { updateCustomViewRatio() }
        }

        @JavascriptInterface
        fun onDebug(msg: String) {
            android.util.Log.d("BrowserFS", msg)
        }

        /**
         * 在页面坐标 (x, y) 处派发一次真实触摸。
         * Fullscreen API 只认用户手势：脚本合成的 click 会被静默拒绝，
         * 而宿主注入的 MotionEvent 会被 WebView 视为真实手势，站点的全屏键才生效。
         */
        @JavascriptInterface
        fun tapAt(x: Float, y: Float) {
            runOnUiThread { injectTap(x, y) }
        }
    }

    private fun injectTap(x: Float, y: Float) {
        try {
            val now = android.os.SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(down)
            webView.postDelayed({ try { webView.dispatchTouchEvent(up) } catch (e: Exception) {} }, 60)
            android.util.Log.d("BrowserFS", "injectTap $x,$y")
        } catch (e: Exception) {
            android.util.Log.d("BrowserFS", "injectTap fail ${e.message}")
        }
    }

    /** 页面全屏：隐藏地址栏与系统栏，WebView 铺满屏幕。 */
    private fun enterPageFullscreen() {
        if (pageFullscreen) return
        pageFullscreen = true
        topBar.visibility = View.GONE
        hideSystemBars()
        ensureAudible()
        attachChannelOverlay()
    }

    /** 保障有声播放：媒体流未被静音，音量为 0 时抬到可听水平。 */
    private fun ensureAudible() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_UNMUTE, 0)
            }
            if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    Math.max(1, max / 3), 0)
            }
        } catch (e: Exception) {
            // 个别机型无权限改音量，忽略
        }
    }

    /** 切换 UA 并重载当前页（UA 只能整页重新加载才生效）。 */
    private fun toggleUa() {
        useMobileUa = !useMobileUa
        getSharedPreferences("channels", MODE_PRIVATE).edit()
            .putBoolean("use_mobile_ua", useMobileUa).apply()
        val target = webView.url ?: currentUrl
        if (!target.isNullOrEmpty()) {
            // 走统一入口：对桌面 UA 会被 WAF 拦的站点仍发手机 UA，避免页面退化成无样式
            loadPage(target)
        } else {
            webView.reload()
        }
        Toast.makeText(this, if (useMobileUa) "已切换手机 UA" else "已切换桌面 UA",
            Toast.LENGTH_SHORT).show()
    }

    /** 切换「只加载视频」：改的是 WebSettings，必须重载当前页才生效。 */
    private fun toggleVideoOnly() {
        videoOnlyMode = !videoOnlyMode
        getSharedPreferences("channels", MODE_PRIVATE).edit()
            .putBoolean("video_only", videoOnlyMode).apply()
        webView.settings.blockNetworkImage = videoOnlyMode
        val target = webView.url ?: currentUrl
        if (!target.isNullOrEmpty()) {
            loadPage(target)
        } else {
            webView.reload()
        }
        Toast.makeText(this, if (videoOnlyMode) "已开启只加载视频（不加载图片）" else "已恢复加载图片",
            Toast.LENGTH_SHORT).show()
    }

    /** 退出页面全屏：恢复地址栏与系统栏。 */
    private fun exitPageFullscreen() {
        if (!pageFullscreen) return
        pageFullscreen = false
        hideChannelPanel()
        topBar.visibility = View.VISIBLE
        goEdgeToEdge()
    }

    /** WebView 播放能力配置：JS、自动播放、混合内容、三方 Cookie、全屏视频。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configWebView(wv: WebView) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            // 注意：不能固定开 overview —— 桌面页按 1000px+ 固定宽度排版会整页缩小贴顶；
            // 也不能只关 overview —— 配合 useWideViewPort 会把布局视口撑到页面宽度。
            // 统一用 setInitialScale 按屏幕宽度算缩放比，让页面宽度正好等于屏幕宽度。
            loadWithOverviewMode = false
            textZoom = 100
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = if (useMobileUa || forceMobileUa(wv.url) || preferMobileUa(wv.url)) MOBILE_UA else DESKTOP_UA
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
        }
        wv.setInitialScale(0)
        wv.setBackgroundColor(Color.BLACK)
        wv.webViewClient = client
        wv.webChromeClient = chrome
        wv.addJavascriptInterface(PageBridge(), "androidHost")
        // 真实触摸后取消静音：部分机型只认真实手势才放行音频
        wv.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { touchDownX = event.x; touchDownY = event.y }
                MotionEvent.ACTION_UP -> {
                    wv.evaluateJavascript(UNMUTE_JS, null)
                    // 全屏播放时：点左侧弹书签，点其它位置收起
                    if (pageFullscreen) {
                        val moved = Math.abs(event.x - touchDownX) > dp(24) ||
                            Math.abs(event.y - touchDownY) > dp(24)
                        if (!moved) {
                            if (event.x < wv.width * 0.28f) {
                                showChannelPanel()
                                return@setOnTouchListener true
                            }
                            if (channelPanel != null) {
                                hideChannelPanel()
                                return@setOnTouchListener true
                            }
                        }
                    }
                }
            }
            false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(wv, true)
            }
        }
    }

    /** 地址栏输入：带点的当网址，否则当搜索词。 */
    private fun openInput() {
        val raw = addressBar.text.toString().trim()
        if (raw.isEmpty()) return
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> "https://www.baidu.com/s?wd=" + android.net.Uri.encode(raw)
        }
        loadPage(url)
        addressBar.clearFocus()
    }

    /** 嗅探当前页的视频直链：只列出来供复制，播放一律留在 WebView 内的站点页面。 */
    private fun sniffVideo(auto: Boolean = false) {
        webView.evaluateJavascript(SNIFF_JS) { raw ->
            val urls = parseUrls(raw)
            if (urls.isEmpty()) {
                if (!auto) {
                    Toast.makeText(this, "当前页面没有发现视频直链，可先在网页里点播放再试",
                        Toast.LENGTH_SHORT).show()
                }
                return@evaluateJavascript
            }
            if (auto) return@evaluateJavascript
            val items = urls.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("发现 ${items.size} 个视频地址")
                .setItems(items) { _, which ->
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("video", items[which]))
                    Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 处理频道点击：统一进 WebView 打开站点直播页（与其它卫视一致）。 */
    private fun openChannel(c: ChannelStore.Channel) {
        currentChannelKey = channelKey(c.group, c.name)
        // 重新点频道视为新的一次观看，解除「用户已退出」的抑制
        userExitedFullscreenAt = 0L
        showLoading("正在打开 ${c.name}…")
        loadPage(c.url)
        addressBar.setText(c.url)
        rememberUrl(c.url)
        // 直播要铺满看：点开频道直接进全屏，地址栏留着会压小画面
        enterPageFullscreen()
    }

    /** 解析 JS 返回的地址列表（用换行分隔，避免依赖 org.json）。 */
    private fun parseUrls(raw: String?): List<String> {
        val text = (raw ?: return emptyList())
            .trim()
            .removeSurrounding("\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ── 分类密码锁：私密分类需验证密码才能展开 ─────────────────────────
    private val lockedGroups = setOf("OXAX")
    /** 本次运行已通过验证的分类，避免反复输入。 */
    private val unlockedGroups = HashSet<String>()

    private fun pwdKey(group: String) = "group_pwd_$group"

    private fun savedPwd(group: String): String =
        getSharedPreferences("channels", MODE_PRIVATE).getString(pwdKey(group), "").orEmpty()

    private fun storePwd(group: String, pwd: String) {
        getSharedPreferences("channels", MODE_PRIVATE).edit()
            .putString(pwdKey(group), pwd).apply()
        if (pwd.isEmpty()) unlockedGroups.remove(group) else unlockedGroups.add(group)
    }

    /** 进入分类的门禁：私密分类先验证，首次进入则引导设置密码。 */
    private fun requestGroupAccess(group: String, onPass: () -> Unit) {
        if (group !in lockedGroups || unlockedGroups.contains(group)) {
            onPass()
            return
        }
        if (savedPwd(group).isEmpty()) askSetPassword(group, onPass)
        else askPassword(group, onPass)
    }

    private fun pwdInput(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /** 首次进入私密分类：设置密码（两次输入一致且不少于 4 位）。 */
    private fun askSetPassword(group: String, onPass: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val first = pwdInput("设置密码（不少于 4 位数字）")
        val second = pwdInput("再输入一次")
        box.addView(first)
        box.addView(second)
        box.addView(TextView(this).apply {
            text = "设置后每次进入「$group」都需要输入密码"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(2), dp(6), dp(2), 0)
        })
        AlertDialog.Builder(this)
            .setTitle("设置「$group」密码")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val a = first.text.toString().trim()
                val b = second.text.toString().trim()
                when {
                    a.length < 4 -> {
                        Toast.makeText(this, "密码至少 4 位", Toast.LENGTH_SHORT).show()
                        askSetPassword(group, onPass)
                    }
                    a != b -> {
                        Toast.makeText(this, "两次输入不一致", Toast.LENGTH_SHORT).show()
                        askSetPassword(group, onPass)
                    }
                    else -> {
                        storePwd(group, a)
                        onPass()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 进入私密分类：校验密码。 */
    private fun askPassword(group: String, onPass: () -> Unit) {
        val input = pwdInput("请输入密码")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("进入「$group」")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                if (input.text.toString().trim() == savedPwd(group)) {
                    unlockedGroups.add(group)
                    onPass()
                } else {
                    Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show()
                    askPassword(group, onPass)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按私密分类：修改或清除密码。 */
    private fun showPasswordMenu(group: String) {
        val hasPwd = savedPwd(group).isNotEmpty()
        val items = if (hasPwd) arrayOf("修改密码", "清除密码") else arrayOf("设置密码")
        AlertDialog.Builder(this)
            .setTitle("「$group」密码")
            .setItems(items) { _, which ->
                if (items[which] == "清除密码") {
                    AlertDialog.Builder(this)
                        .setMessage("清除后进入「$group」将不再需要密码，确定？")
                        .setPositiveButton("清除") { _, _ ->
                            storePwd(group, "")
                            Toast.makeText(this, "已清除密码", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    askSetPassword(group, onPass = {})
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onPause() {
        super.onPause()
        // 切后台/息屏后继续播放：不暂停 WebView，HTML5 音频走的是系统音频焦点，
        // 一旦 pauseTimers() 页面计时器和播放器心跳全停，声音立刻断。
        // 这里只保留窗口不可见的降级，靠前台式的脚本继续驱动播放。
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // manifest 声明了自行处理旋转：屏幕尺寸变了要重新检测并重排视频
        detectScreenSize()
        if (::webView.isInitialized) {
            injectScreenSize(webView)
            webView.evaluateJavascript(FORCE_VIEWPORT_JS) { r ->
                android.util.Log.d("BrowserFS", "rotate viewport=$r scr=${screenCssW}x${screenCssH}")
            }
            // 重新触发一次站点适配脚本，让播放器按新尺寸重排
            webView.postDelayed({
                if (::webView.isInitialized) {
                    val u = webView.url
                    if (u != null) webView.evaluateJavascript(playerScript(u), null)
                }
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        wakeLock?.let { if (it.isHeld) it.release() }
        // 旋转/分屏后屏幕尺寸会变，重新检测并同步给页面
        if (::webView.isInitialized) {
            detectScreenSize()
            injectScreenSize(webView)
        }
    }

    override fun onStop() {
        super.onStop()
        // 后台继续播放：持唤醒锁防止 CPU 休眠导致直播流卡死
        try {
            if (wakeLock == null) {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "com.llq.hello:playback"
                ).apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire()
        } catch (e: Exception) {
            android.util.Log.d("BrowserFS", "wakelock 获取失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        webView.loadUrl("about:blank")
        webView.destroy()
        super.onDestroy()
    }

    /** 全屏播放时的频道浮层挂载点。 */
    private fun attachChannelOverlay() {
        hideChannelPanel()
    }

    /** 弹出频道面板（全屏状态下叠在画面之上，按分类分组）。 */
    private fun showChannelPanel() {
        hideChannelPanel()
        val store = ChannelStore(this)
        val grouped = store.grouped()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 半透明黑底：视频透出来，文字仍可辨
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(14), dp(18), dp(14), dp(14))
        }

        panel.addView(TextView(this).apply {
            text = "频道"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })

        // UA 切换：桌面 / 手机，个别站点布局或风控不兼容时点这里
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "网页版本"
                textSize = 13f
                setTextColor(0xFFBBBBBB.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // 屏幕检测结果夹在「网页版本」和「电脑版」中间，点击可重新检测
            addView(TextView(this@MainActivity).apply {
                fun refresh() {
                    val ok = screenCssW > 0 && screenCssH > 0
                    text = if (ok) {
                        "✔ " + Math.max(screenCssW, screenCssH) + "×" +
                            Math.min(screenCssW, screenCssH)
                    } else "✘ 检测失败"
                    setTextColor(if (ok) 0xFF4CD98A.toInt() else 0xFFFF6B6B.toInt())
                }
                textSize = 12f
                setPadding(dp(6), dp(5), dp(6), dp(5))
                refresh()
                setOnClickListener {
                    detectScreenSize()
                    refresh()
                    if (::webView.isInitialized) {
                        injectScreenSize(webView)
                        webView.evaluateJavascript(FORCE_VIEWPORT_JS, null)
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                text = if (useMobileUa) "手机版" else "电脑版"
                textSize = 13f
                setTextColor(0xFF4CD98A.toInt())
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener {
                    toggleUa()
                    (it as TextView).text = if (useMobileUa) "手机版" else "电脑版"
                }
            })
        })

        // 第一层只列分类：点分类再弹出该分类的频道网格
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(container) }
        var currentRow: View? = null

        if (grouped.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有频道\n点下方「添加频道」新建"
                textSize = 13f
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(dp(2), dp(8), dp(2), dp(8))
            })
        }
        // 分类横向排列：每行四个，向下排列
        for (lineGroups in grouped.toList().chunked(4)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((group, items) in lineGroups) {
                val hasCurrent = items.any { currentChannelKey == channelKey(it.group, it.name) }
                val cell = TextView(this).apply {
                    text = (if (hasCurrent) "● " else "") + group +
                        (if (group in lockedGroups) " 🔒" else "") + "\n(${items.size})"
                    textSize = 12f
                    setTextColor(if (hasCurrent) 0xFF4CD98A.toInt() else Color.WHITE)
                    if (hasCurrent) setTypeface(typeface, Typeface.BOLD)
                    setSingleLine(false)
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                    setPadding(dp(1), dp(10), dp(1), dp(10))
                    setOnClickListener {
                        hideChannelPanel()
                        requestGroupAccess(group) { showGroupChannels(store, group, items) }
                    }
                    setOnLongClickListener {
                        if (group in lockedGroups) {
                            hideChannelPanel()
                            showPasswordMenu(group)
                        }
                        true
                    }
                }
                if (hasCurrent) currentRow = cell
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // 末行不足四个时补空位，避免单元格被拉宽
            for (pad in lineGroups.size until 4) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
            }
            container.addView(line)
        }

        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel.addView(TextView(this).apply {
            text = "＋ 添加频道"
            textSize = 14f
            setTextColor(0xFF7FD6A0.toInt())
            setPadding(dp(2), dp(12), dp(2), dp(4))
            setOnClickListener { addChannelManually(store) { showChannelPanel() } }
        })

        val width = Math.min(dp(420), (resources.displayMetrics.widthPixels * 0.92f).toInt())
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.START
        contentRoot.addView(panel, lp)
        channelPanel = panel
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(160).start()

        // 打开面板后把当前频道滚到可视区中间，避免它在长列表里找不到
        val targetRow = currentRow
        if (targetRow != null) {
            panel.post {
                val offset = targetRow.top + targetRow.height / 2 - scroll.height / 2
                scroll.smoothScrollTo(0, Math.max(0, offset))
            }
        }
    }

    /** 收起频道面板。 */
    private fun hideChannelPanel() {
        val panel = channelPanel ?: return
        channelPanel = null
        contentRoot.removeView(panel)
    }

    /** 记住当前打开的分类名，返回键回到分类列表时要用。 */
    private var currentGroup: String? = null

    /** 弹出某个分类的全部频道：6 列网格向下排列，超出可滚动。 */
    private fun showGroupChannels(
        store: ChannelStore,
        group: String,
        items: List<ChannelStore.Channel>
    ) {
        currentGroup = group
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null

        if (items.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "这个分类还没有频道"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(dp(2), dp(24), dp(2), dp(24))
            })
        }

        items.chunked(6).forEach { lineItems ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in lineItems) {
                line.addView(channelCell(store, c, dialog = { dialog }) {
                    dialog?.dismiss()
                    showGroupChannelsRefreshed(store, group)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // 补齐空位，避免最后一行格子被拉宽
            repeat(6 - lineItems.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
            }
            grid.addView(line)
        }

        val scroll = ScrollView(this).apply {
            setPadding(dp(6), dp(4), dp(6), dp(4))
            isFillViewport = true
            addView(grid)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("$group（${items.size}）")
            .setView(scroll)
            .setPositiveButton("返回分类") { _, _ -> showChannelPanel() }
            .setNegativeButton("关闭", null)
            .create()
        dialog = dlg
        // 返回键会关掉弹窗（走 onCancel），这里补上「回到分类列表」
        dlg.setOnCancelListener {
            groupDialog = null
            showChannelPanel()
        }
        dlg.setOnDismissListener { groupDialog = null }
        groupDialog = dlg
        dlg.show()
        // 系统默认弹窗偏窄，这里撑到屏宽的 96%、屏高的 90%，并显式居中
        val dm = resources.displayMetrics
        dlg.window?.apply {
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.96f).toInt(), (dm.heightPixels * 0.90f).toInt())
        }
    }

    /** 分类内容变动后重开该分类弹窗（分类被清空则回到分类列表）。 */
    private fun showGroupChannelsRefreshed(store: ChannelStore, group: String) {
        val items = store.grouped()[group]
        if (items.isNullOrEmpty()) showChannelPanel()
        else requestGroupAccess(group) { showGroupChannels(store, group, items) }
    }

    /** 网格里的单个频道按钮：点击播放，长按可编辑 / 删除（内置频道只读）。 */
    private fun channelCell(
        store: ChannelStore,
        c: ChannelStore.Channel,
        dialog: () -> AlertDialog?,
        onChanged: () -> Unit
    ): TextView {
        val isCurrent = currentChannelKey == channelKey(c.group, c.name)
        return TextView(this).apply {
            text = c.name
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(if (isCurrent) 0xFF4CD98A.toInt() else Color.WHITE)
            if (isCurrent) setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(if (isCurrent) 0xFF1E4A33.toInt() else 0xFF2A2A2A.toInt())
            setPadding(dp(2), dp(9), dp(2), dp(9))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            layoutParams = lp
            setOnClickListener {
                openChannel(c)
                dialog()?.dismiss()
            }
            setOnLongClickListener {
                if (store.isBuiltin(c.group, c.name)) {
                    Toast.makeText(this@MainActivity, "内置频道不可修改", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(c.name)
                        .setItems(arrayOf("编辑", "删除")) { _, which ->
                            dialog()?.dismiss()
                            if (which == 0) editChannel(store, c) { onChanged() }
                            else confirmDeleteChannel(store, c) { onChanged() }
                        }
                        .show()
                }
                true
            }
        }
    }

    /** 渲染一行频道；当前播放的频道高亮显示。 */
    private fun channelRow(
        store: ChannelStore,
        c: ChannelStore.Channel,
        mode: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val isCurrent = currentChannelKey == channelKey(c.group, c.name)
        row.addView(TextView(this).apply {
            text = if (isCurrent) "● ${c.name}" else c.name
            textSize = 14f
            setTextColor(if (isCurrent) 0xFF4CD98A.toInt() else Color.WHITE)
            if (isCurrent) setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
            setEllipsize(android.text.TextUtils.TruncateAt.END)
            setPadding(dp(2), dp(11), dp(2), dp(11))
            setOnClickListener {
                openChannel(c)
                hideChannelPanel()
            }
            setOnLongClickListener {
                if (!store.isBuiltin(c.group, c.name)) {
                    store.remove(c.group, c.name)
                    showChannelPanel()
                }
                true
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!store.isBuiltin(c.group, c.name)) {
            row.addView(TextView(this).apply {
                text = "✎"
                textSize = 16f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(4), dp(6))
                setOnClickListener { editChannel(store, c) { showChannelPanel() } }
            })
            row.addView(TextView(this).apply {
                text = "×"
                textSize = 18f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { confirmDeleteChannel(store, c) { showChannelPanel() } }
            })
        }
        return row
    }

    /** 编辑频道：分类、频道名、网址都可改，保存后回调刷新列表。 */
    private fun editChannel(store: ChannelStore, c: ChannelStore.Channel, onDone: () -> Unit) {
        if (store.isBuiltin(c.group, c.name)) {
            Toast.makeText(this, "内置频道不可编辑", Toast.LENGTH_SHORT).show()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val groupInput = EditText(this).apply {
            hint = "分类，如：央视、卫视、电影"
            textSize = 15f
            setSingleLine(true)
            setText(c.group)
        }
        val nameInput = EditText(this).apply {
            hint = "频道名"
            textSize = 15f
            setSingleLine(true)
            setText(c.name)
        }
        val urlInput = EditText(this).apply {
            hint = "网址"
            textSize = 15f
            setSingleLine(true)
            setText(c.url)
        }
        box.addView(groupInput)
        box.addView(nameInput)
        box.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("编辑频道")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                var url = urlInput.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "网址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val group = groupInput.text.toString().trim().ifEmpty { c.group }
                val name = nameInput.text.toString().trim().ifEmpty { c.name }
                store.update(c.group, c.name, group, name, url)
                Toast.makeText(this, "已保存：$group / $name", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除频道前的确认框；确认后删除并回调刷新列表。 */
    private fun confirmDeleteChannel(
        store: ChannelStore,
        c: ChannelStore.Channel,
        onDone: () -> Unit
    ) {
        if (store.isBuiltin(c.group, c.name)) {
            Toast.makeText(this, "内置频道不可删除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除频道")
            .setMessage("确定删除「${c.name}」？")
            .setPositiveButton("删除") { _, _ ->
                store.remove(c.group, c.name)
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 频道列表（顶部按钮入口）：点击打开、长按删除。 */
    private fun showChannels() {
        val store = ChannelStore(this)
        val grouped = store.grouped()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val scroll = ScrollView(this).apply { addView(container) }

        // 置顶：屏幕检测结果显示（长×宽），点击可重新检测
        container.addView(TextView(this).apply {
            val ok = screenCssW > 0 && screenCssH > 0
            val max = Math.max(screenCssW, screenCssH)
            val min = Math.min(screenCssW, screenCssH)
            text = if (ok) "✔ 屏幕检测成功：$max×$min" else "✘ 屏幕检测失败"
            textSize = 14f
            setTextColor(if (ok) 0xFF117A37.toInt() else 0xFFC62828.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener {
                detectScreenSize()
                val ok2 = screenCssW > 0 && screenCssH > 0
                text = if (ok2) {
                    "✔ 屏幕检测成功：" + Math.max(screenCssW, screenCssH) + "×" +
                        Math.min(screenCssW, screenCssH)
                } else "✘ 屏幕检测失败"
                if (::webView.isInitialized) {
                    injectScreenSize(webView)
                    webView.evaluateJavascript(FORCE_VIEWPORT_JS, null)
                }
            }
        })
        container.addView(View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // 央视频道内置页入口（置顶）
        container.addView(TextView(this).apply {
            text = "📺 央视频道（含 33 个卫视）"
            textSize = 15f
            setTextColor(0xFF2E8B57.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, YangshipinActivity::class.java))
            }
        })
        container.addView(View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // 网页版本切换（置顶）
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "网页版本"
                textSize = 14f
                setTextColor(0xFF666666.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (useMobileUa) "手机版" else "电脑版"
                textSize = 14f
                setTextColor(0xFF2E8B57.toInt())
                setPadding(dp(10), dp(4), dp(2), dp(4))
                setOnClickListener {
                    toggleUa()
                    (it as TextView).text = if (useMobileUa) "手机版" else "电脑版"
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = if (videoOnlyMode) "只加载视频" else "加载图片"
                textSize = 14f
                setTextColor(0xFF2E8B57.toInt())
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    toggleVideoOnly()
                    (it as TextView).text = if (videoOnlyMode) "只加载视频" else "加载图片"
                }
            })
        })
        container.addView(View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        if (grouped.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有频道，点「添加频道」新建"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(dp(12), dp(20), dp(12), dp(20))
            })
        }

        var dialog: AlertDialog? = null
        // 只列分类，点分类再看该分类的频道网格
        for ((group, items) in grouped) {
            val hasCurrent = items.any { currentChannelKey == channelKey(it.group, it.name) }
            container.addView(TextView(this).apply {
                text = (if (hasCurrent) "● " else "") + group +
                    (if (group in lockedGroups) " 🔒" else "")
                textSize = 15f
                setTextColor(if (hasCurrent) 0xFF2E8B57.toInt() else 0xFF1A1A1A.toInt())
                if (hasCurrent) setTypeface(typeface, Typeface.BOLD)
                setSingleLine(true)
                setEllipsize(android.text.TextUtils.TruncateAt.END)
                setPadding(dp(18), dp(13), dp(18), dp(13))
                setOnClickListener {
                    dialog?.dismiss()
                    requestGroupAccess(group) { showGroupChannels(store, group, items) }
                }
                setOnLongClickListener {
                    if (group in lockedGroups) {
                        dialog?.dismiss()
                        showPasswordMenu(group)
                    }
                    true
                }
            })
            container.addView(View(this).apply {
                setBackgroundColor(0xFFEDEDED.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("频道")
            .setView(scroll)
            .setPositiveButton("添加频道") { _, _ -> addChannelManually(store) { showChannels() } }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 手动添加频道：分类 + 频道名 + 网址。 */
    private fun addChannelManually(store: ChannelStore, onDone: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val known = store.groups()
        val groupInput = EditText(this).apply {
            hint = "分类，如：央视、卫视、电影"
            textSize = 15f
            setSingleLine(true)
        }
        val nameInput = EditText(this).apply {
            hint = "频道名，如：CCTV-1 综合"
            textSize = 15f
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = "网址，如 https://example.com/live.m3u8"
            textSize = 15f
            setSingleLine(true)
        }
        box.addView(groupInput)
        box.addView(nameInput)
        box.addView(urlInput)

        if (known.isNotEmpty()) {
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            for (g in known.take(3)) {
                chips.addView(TextView(this).apply {
                    text = g
                    textSize = 12f
                    setTextColor(0xFF2E8B57.toInt())
                    setPadding(0, dp(4), dp(12), dp(4))
                    setOnClickListener { groupInput.setText(g) }
                })
            }
            box.addView(chips)
        }

        AlertDialog.Builder(this)
            .setTitle("添加频道")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                var url = urlInput.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "网址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val group = groupInput.text.toString().trim().ifEmpty { "未分类" }
                val name = nameInput.text.toString().trim().ifEmpty {
                    android.net.Uri.parse(url).host ?: url
                }
                store.add(group, name, url)
                Toast.makeText(this, "已添加：$group / $name", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    // ---------- 界面与系统栏 ----------

    /** 显示加载动画：从点频道到真正起播之间盖住白屏。 */
    private fun showLoading(text: String) {
        val root = loadingOverlay ?: FrameLayout(this).also { ov ->
            ov.setBackgroundColor(0xFF000000.toInt())
            ov.isClickable = true
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val pb = android.widget.ProgressBar(this).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
            }
            box.addView(pb, LinearLayout.LayoutParams(dp(56), dp(56)))
            val label = TextView(this).apply {
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
            }
            loadingLabel = label
            box.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            ov.addView(box, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
            contentRoot.addView(ov, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            loadingOverlay = ov
        }
        root.visibility = View.VISIBLE
        root.bringToFront()
        loadingLabel?.text = text
        // 兜底：站点一直不起播时，超时自动揭开，避免用户被黑屏永久挡住
        loadingTimeout?.let { loadingOverlay?.removeCallbacks(it) }
        val timeout = Runnable {
            loadingLabel?.text = "加载较慢，正在继续尝试…"
            loadingOverlay?.postDelayed({ hideLoading() }, 4000)
        }
        loadingTimeout = timeout
        root.postDelayed(timeout, 20_000)
    }

    /** 收起加载动画（起播或超时）。 */
    private fun hideLoading() {
        loadingTimeout?.let { loadingOverlay?.removeCallbacks(it) }
        loadingTimeout = null
        loadingOverlay?.visibility = View.GONE
    }

    /**
     * 原生侧起播探测：部分站点的注入脚本不会回调 onPlaying（海南 hnntv 等），
     * 遮罩就会一直盖在已经播放的画面上。这里直接轮询 videoWidth：
     * 拿到真实尺寸即视为起播；探测期间若遮罩已被 onPlaying 收起则自动停止。
     */
    private fun startPlayProbe(view: WebView?) {
        playProbeToken++
        val token = playProbeToken
        var tries = 0
        val probe = object : Runnable {
            override fun run() {
                val v = view ?: return
                if (token != playProbeToken) return
                if (loadingOverlay?.visibility != View.VISIBLE) return
                v.evaluateJavascript(PROBE_JS) { r ->
                    if (token != playProbeToken) return@evaluateJavascript
                    // 返回的是纯数字字符串（可能带引号）：能解析出 >0 即已出画
                    val w = r?.trim()?.trim('"')?.toIntOrNull() ?: 0
                    if (w > 0) {
                        android.util.Log.d("BrowserFS", "probe 出画 w=$w")
                        hideLoading()
                    }
                }
                if (++tries < 60) v.postDelayed(this, 500)
            }
        }
        view?.postDelayed(probe, 800)
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v + 0.5f).toInt()

    /** 全面屏：内容铺到系统栏下，两条系统栏透明，可进刘海区。必须在 setContentView 之后调用。 */
    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        val decor: View? = window.decorView
        if (decor == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    /** 视频全屏时隐藏系统栏。 */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Suppress("DEPRECATION")
    private fun padTopForStatusBar(view: View) {
        val base = view.paddingTop
        view.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                insets.systemWindowInsetTop
            }
            v.setPadding(v.paddingLeft, base + top, v.paddingRight, v.paddingBottom)
            insets
        }
    }

}

/** 在页面内收集可播放地址：video/source 的当前源，以及指向视频文件的链接。 */
private const val SNIFF_JS = """
(function(){
  var urls = [];
  function push(u){ if (u && urls.indexOf(u) < 0) urls.push(u); }
  var nodes = document.querySelectorAll('video,audio');
  for (var i = 0; i < nodes.length; i++){
    var n = nodes[i];
    push(n.currentSrc || n.src);
    var srcs = n.querySelectorAll('source');
    for (var j = 0; j < srcs.length; j++) push(srcs[j].src);
  }
  var srcs2 = document.querySelectorAll('source');
  for (var k = 0; k < srcs2.length; k++) push(srcs2[k].src);
  var links = document.querySelectorAll('a[href]');
  for (var m = 0; m < links.length; m++){
    var h = links[m].href || '';
    if (/\.(mp4|m3u8|mpd|webm|flv|mkv)(\?|#|$)/i.test(h)) push(h);
  }
  return urls.join('\n');
})();
"""

/** 诊断用：上报页面布局视口与缩放比，定位 PC 模式整页缩小的原因。 */
private const val VP_JS =
    "(function(){var d=document.documentElement,b=document.body;" +
    "var m=document.querySelector('meta[name=viewport]');" +
    "return JSON.stringify({iw:window.innerWidth,ih:window.innerHeight," +
    "sw:screen.width,dpr:window.devicePixelRatio," +
    "dw:d?d.clientWidth:0,dh:d?d.clientHeight:0," +
    "bw:b?b.clientWidth:0,bh:b?b.clientHeight:0," +
    "vw:window.visualViewport?window.visualViewport.width:0," +
    "vs:window.visualViewport?window.visualViewport.scale:0," +
    "meta:m?m.content:''});})()"

/** 统一视口校正：把布局视口钉到真实屏幕宽度，让 100vw/100vh 真正等于屏幕。 */
private const val FORCE_VIEWPORT_JS = """
(function(){
  try {
    var w = window.__scrW || 0, h = window.__scrH || 0;
    if (!w || !h) return 'no-size';
    var head = document.head || document.documentElement;
    if (!head) return 'no-head';
    var m = document.querySelector('meta[name="viewport"]');
    var created = false;
    if (!m){
      m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      if (head.firstChild) head.insertBefore(m, head.firstChild); else head.appendChild(m);
      created = true;
    }
    var cur = (m.getAttribute('content') || '').toLowerCase();
    // 站点自己钉死了别的固定宽度（桌面专用排版）就别动，免得整页错版
    if (!created && cur.indexOf('width=') >= 0 &&
        cur.indexOf('width=device-width') < 0 && cur.indexOf('width=' + w) < 0) return 'keep';
    m.setAttribute('content', 'width=' + w +
      ', initial-scale=1, maximum-scale=1, minimum-scale=1, user-scalable=no, viewport-fit=cover');
    try { document.documentElement.style.setProperty('overflow-x', 'hidden', 'important'); } catch(e){}
    return 'ok';
  } catch(e){ return 'err'; }
})();
"""

/** 起播探测：直接返回主视频已解码的宽度（数字），> 0 即视为已有画面。 */
private const val PROBE_JS =
    "(function(){var v=document.querySelectorAll('video'),b=0;" +
    "for(var i=0;i<v.length;i++){var w=v[i].videoWidth||0;if(w>b)b=w;}" +
    "return String(b);})()"

/** 注入脚本：切后台后把被站点暂停的视频救回来（后台继续播放）。 */
private const val BACKGROUND_KEEP_JS =
    "(function(){if(window.__bgKeep)return 'ok';window.__bgKeep=true;" +
    "function alive(){var v=document.querySelectorAll('video');" +
    "for(var i=0;i<v.length;i++){var e=v[i];if(e.paused&&!e.ended){" +
    "try{e.muted=false;}catch(x){}var p=e.play();if(p&&p.catch)p.catch(function(){});}}}" +
    "try{var orig=document.addEventListener;" +
    "document.addEventListener=function(t,f,o){return orig.call(document,t,f,o);};}catch(e){}" +
    "document.addEventListener('visibilitychange',function(){setTimeout(alive,0);setTimeout(alive,120);setTimeout(alive,600);});" +
    "setInterval(alive,3000);return 'ok';})()"

/** 注入脚本：模拟人手点击页面播放按钮、进入网页全屏，并通知原生层隐藏地址栏。 */
private const val AUTOPLAY_JS = """
(function(){
  if (window.__playerHooked) return 'ok';
  window.__playerHooked = true;

  var CLICKED = {};
  var FULL = false;

  function notify(){
    try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
  }

  // 选出画面最大的可见 video
  function mainVideo(){
    var best = null, bestArea = 0;
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++){
      var v = list[i];
      var r = v.getBoundingClientRect();
      if (r.width < 80 || r.height < 45) continue;
      var area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = v; }
    }
    return best;
  }

  // 模拟一次真实手指点击（含指针/触摸/鼠标三套事件）
  function humanClick(el){
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;
    var win = el.ownerDocument && el.ownerDocument.defaultView ? el.ownerDocument.defaultView : window;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:win};
    try {
      el.dispatchEvent(new (win.PointerEvent || win.MouseEvent)('pointerdown', opt));
      el.dispatchEvent(new win.MouseEvent('mousedown', opt));
      el.dispatchEvent(new (win.PointerEvent || win.MouseEvent)('pointerup', opt));
      el.dispatchEvent(new win.MouseEvent('mouseup', opt));
      el.dispatchEvent(new win.MouseEvent('click', opt));
      return true;
    } catch(e){ return false; }
  }

  // 在播放器容器里寻找“看起来像播放键”的元素
  function findPlayButton(scope){
    var sel = '[class*="play" i],[class*="Play"],[id*="play" i],' +
              '[aria-label*="播放"],[title*="播放"],[class*="btn"],button,.vjs-big-play-button';
    var nodes = scope.querySelectorAll(sel);
    for (var i = 0; i < nodes.length; i++){
      var el = nodes[i];
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 20) continue;
      if (r.width * r.height > 400 * 400) continue;
      return el;
    }
    return null;
  }

  // 找站点播放器自带的全屏键（各家 class 名不同，多种选择器兜底）
  function findFullBtn(scope){
    var root = scope || document;
    var sels = ['._tdp_fullbtn','.vjs-fullscreen-control','.prism-fullscreen-btn',
                '[class*="fullscreen" i]','[class*="full-screen" i]','[class*="quanping"]',
                '[aria-label*="全屏"]','[title*="全屏"]','[aria-label*="fullscreen" i]'];
    for (var s = 0; s < sels.length; s++){
      var nodes;
      try { nodes = root.querySelectorAll(sels[s]); } catch(e){ continue; }
      for (var i = 0; i < nodes.length; i++){
        var el = nodes[i], r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        if (r.width > 200 || r.height > 200) continue;
        var cs = null;
        try { cs = getComputedStyle(el); } catch(e){}
        if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) continue;
        return el;
      }
    }
    return null;
  }

  /** 点一次站点自带的全屏键，让播放器按自己的逻辑处理画面。只点一次。 */
  function pressFull(){
    if (window.__fullClicked) return;
    var btn = findFullBtn();
    if (!btn){
      // 播放器可能在同源 iframe 里
      var fs = document.querySelectorAll('iframe');
      for (var i = 0; i < fs.length && !btn; i++){
        try {
          var d = fs[i].contentDocument;
          if (d) btn = findFullBtn(d);
        } catch(e){}
      }
    }
    if (!btn) return;
    window.__fullClicked = true;
    humanClick(btn);
    try { if (window.androidHost) window.androidHost.onDebug('full btn clicked'); } catch(e){}
  }

  function enterWebFullscreen(v){
    if (FULL) return;
    var box = v.parentElement || v;
    var targets = [box, v];
    for (var i = 0; i < targets.length; i++){
      var t = targets[i];
      try {
        if (t.requestFullscreen) { t.requestFullscreen(); FULL = true; return; }
        if (t.webkitRequestFullscreen) { t.webkitRequestFullscreen(); FULL = true; return; }
        if (t.webkitEnterFullscreen) { t.webkitEnterFullscreen(); FULL = true; return; }
      } catch(e){}
    }
  }

  /** CSS 隐藏浏览器注入的原生媒体控件层。 */
  function hideNativeControls(){
    if (document.getElementById('__hideNativeCtl')) return;
    var st = document.createElement('style');
    st.id = '__hideNativeCtl';
    st.textContent = 'video::-webkit-media-controls{display:none !important;opacity:0 !important;}' +
      'video::-webkit-media-controls-enclosure{display:none !important;}' +
      'video::-webkit-media-controls-panel{display:none !important;}' +
      'video::-webkit-media-controls-start-playback-button{display:none !important;}' +
      'video::-webkit-media-controls-overlay-play-button{display:none !important;}';
    (document.head || document.documentElement).appendChild(st);
  }

  // 往页面里注入一条锁定规则：站点（video.js）会周期性重设 video 的行内样式，
  // 单靠 setProperty 会被它覆盖回来，表现为画面忽然偏移、尺寸跳变。
  var LOCK_ID = '__fillLock';
  function lockStyle(v){
    var st = document.getElementById(LOCK_ID);
    if (!st){
      st = document.createElement('style');
      st.id = LOCK_ID;
      (document.head || document.documentElement).appendChild(st);
    }
    var sel = v.id ? ('#' + v.id.replace(/([^\w-])/g, '\\$1')) : 'video';
    var css = sel + '{position:fixed!important;top:0!important;left:0!important;' +
      'right:auto!important;bottom:auto!important;width:100vw!important;height:100vh!important;' +
      'max-width:none!important;max-height:none!important;margin:0!important;transform:none!important;' +
      'object-fit:contain!important;object-position:center center!important;' +
      'z-index:2147483647!important;background:#000!important}';
    if (st.textContent !== css) st.textContent = css;
  }

  // 站点若用行内 !important 覆盖，CSS 规则压不住：监听 style/class 变化，
  // 一旦发现偏离（不再是 fixed 且铺满），立即重新锁一次。带节流，避免与站点互相刷。
  var REAPPLY_AT = 0;
  function watchStyle(v){
    if (v.__fillWatched) return;
    v.__fillWatched = true;
    try {
      new MutationObserver(function(){
        var now = Date.now();
        if (now - REAPPLY_AT < 300) return;
        var st2 = v.style;
        if (st2.position !== 'fixed' || st2.width !== '100vw' || st2.height !== '100vh'){
          REAPPLY_AT = now;
          forceFill(v);
        }
      }).observe(v, {attributes:true, attributeFilter:['style','class']});
    } catch(e){}
  }

  function forceFill(v){
    // 用户已按返回退出全屏：不再铺满，否则网页每 700ms 会把画面重新顶成全屏观感
    try { if (window.androidHost && window.androidHost.isUserExited() === '1') return; } catch(e){}
    var p = v.parentElement, guard = 0;
    while (p && p !== document.documentElement && guard++ < 40){
      try {
        var cs = getComputedStyle(p);
        if (cs.overflow !== 'visible') p.style.setProperty('overflow','visible','important');
        if (cs.transform && cs.transform !== 'none') p.style.setProperty('transform','none','important');
        // will-change / perspective / contain / backdrop-filter 即使没有 transform，
        // 也会让祖先成为 fixed 的包含块，把 left 的基准从视口挪到它自己身上
        if (cs.willChange && cs.willChange !== 'auto') p.style.setProperty('will-change','auto','important');
        if (cs.perspective && cs.perspective !== 'none') p.style.setProperty('perspective','none','important');
        if (cs.contain && cs.contain !== 'none') p.style.setProperty('contain','none','important');
        if (cs.backdropFilter && cs.backdropFilter !== 'none') p.style.setProperty('backdrop-filter','none','important');
        if (cs.scale && cs.scale !== 'none' && cs.scale !== '1') p.style.setProperty('scale','none','important');
        if (cs.filter && cs.filter !== 'none') p.style.setProperty('filter','none','important');
        if (cs.clipPath && cs.clipPath !== 'none') p.style.setProperty('clip-path','none','important');
        // 站点（如云南云视网 body zoom:92%）的页面级缩放会连 fixed 尺寸一起缩小
        if (cs.zoom && cs.zoom !== 'normal' && cs.zoom !== '1') p.style.setProperty('zoom','1','important');
      } catch(e){}
      p = p.parentElement;
    }
    try {
      var bcs = document.body ? getComputedStyle(document.body) : null;
      if (bcs && bcs.zoom && bcs.zoom !== 'normal' && bcs.zoom !== '1') document.body.style.setProperty('zoom','1','important');
    } catch(e){}
    var s = v.style;
    // 先注入锁定规则并开启监听，再设行内样式：行内样式用于立即生效，
    // 一旦被站点覆盖，监听会把它拉回来，CSS 规则则兜住站点用行内覆盖的情况。
    try { lockStyle(v); } catch(e){}
    try { watchStyle(v); } catch(e){}
    // 与其它频道一致的做法：容器铺满视口，由 object-fit 让浏览器自己居中。
    // 不用 window.innerWidth 算像素宽度：云南页有 body{zoom:92%}，
    // 清掉 zoom 后 innerWidth 与实际渲染宽度不一致，算出的宽度会比视口宽，
    // 画面于是被推出右侧、左侧露出黑边。100vw/100vh 由浏览器按视觉视口计算，没有这个问题。
    // 江苏卫视（live.jstv.com）：普通 video 页面，直接固定定位铺满屏幕
    s.setProperty('position','fixed','important');
    s.setProperty('top','0','important');
    s.setProperty('left','0','important');
    s.setProperty('right','auto','important');
    s.setProperty('bottom','auto','important');
    s.setProperty('width','100vw','important');
    s.setProperty('height','100vh','important');
    s.setProperty('max-width','none','important');
    s.setProperty('max-height','none','important');
    s.setProperty('margin','0','important');
    s.setProperty('transform','none','important');
    s.setProperty('object-position','center center','important');
    s.setProperty('z-index','2147483647','important');
    s.setProperty('background','#000','important');
    s.setProperty('object-fit','contain','important');
    // 注意：这里不做事后测量补偿。每轮 tick 都按实测结果改写 left/top 会形成
    // 「改一次→基准跟着变→再改一次」的循环，画面会缓慢横向漂移，正是抖动的来源。
    try {
      var n = window.__fillDbg = (window.__fillDbg || 0) + 1;
      if (n <= 3 && window.androidHost){
        var r = v.getBoundingClientRect();
        window.androidHost.onDebug('fill#' + n + ' vp=' + window.innerWidth + 'x' + window.innerHeight + ' rect=' + Math.round(r.left) + ',' + Math.round(r.top) + ',' + Math.round(r.width) + ',' + Math.round(r.height) +
          ' iv=' + v.videoWidth + 'x' + v.videoHeight + ' fs=' + (document.fullscreenElement ? 'yes' : 'no'));
      }
      if (n % 20 === 0 && window.androidHost){
        var r2 = v.getBoundingClientRect();
        window.androidHost.onDebug('fill-tick rect=' + Math.round(r2.left) + ',' + Math.round(r2.top) + ',' + Math.round(r2.width) + ',' + Math.round(r2.height));
      }
    } catch(e){}
    // 关闭浏览器原生控件，避免中间那层暂停按钮遮住画面（站点自带底部控制条）
    v.removeAttribute('controls');
    v.controls = false;
    hideNativeControls();
    try {
      document.documentElement.style.setProperty('overflow','hidden','important');
      if (document.body) document.body.style.setProperty('overflow','hidden','important');
    } catch(e){}
  }

  function hook(v){
    if (v.__hooked) return;
    v.__hooked = true;
    v.setAttribute('playsinline','');
    v.setAttribute('webkit-playsinline','');
    v.addEventListener('playing', function(){
      forceFill(v);
      ensureSound(v);
      notify();
      try { if (window.androidHost) window.androidHost.onDebug('playing->fill muted=' + v.muted); } catch(e){}
    });
    v.addEventListener('webkitbeginfullscreen', notify);
    // 直播流不允许暂停续播，避免误触
    v.addEventListener('pause', function(){
      if (FULL) { try { v.play(); } catch(e){} }
    });
  }

  // 把视频真实像素尺寸报给原生：全屏时按这个比例摆放，避免被拉伸变形。
  // 只在尺寸变化时上报，避免每 700ms 一次跨进程调用。
  var LAST_SIZE = '';
  function reportSize(v){
    try {
      var w = v.videoWidth | 0, h = v.videoHeight | 0;
      if (w <= 0 || h <= 0) return;
      var k = w + 'x' + h;
      if (k === LAST_SIZE) return;
      LAST_SIZE = k;
      if (window.androidHost && window.androidHost.onVideoSize) window.androidHost.onVideoSize(w, h);
    } catch(e){}
  }

  function tryPlay(v){
    // 直接带有声播放；WebView 已允许无手势自动播放
    v.muted = false;
    try { v.volume = 1.0; } catch(e){}
    var p = null;
    try { p = v.play(); } catch(e){}
    if (p && p.catch) {
      // 起播失败时也不静音，交给下一轮 tick 重试
      p.catch(function(){});
    }
  }

  /** 持续保障声音：站点或浏览器可能把 video 静音，发现即取消。 */
  function ensureSound(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
  }

  function tick(){
    var v = mainVideo();
    if (!v) {
      if (!CLICKED.page){
        CLICKED.page = true;
        var w = window.innerWidth, h = window.innerHeight;
        humanClick(document.elementFromPoint(w / 2, h / 3));
      }
      return;
    }
    hook(v);
    reportSize(v);

    if (!v.paused && v.readyState >= 2){
      // 优先用站点自带的全屏键；没有全屏键的站点再退回自己铺满
      pressFull();
      forceFill(v);
      ensureSound(v);
      notify();
      return;
    }

    // 未播放：先点播放按钮，再点视频画面本身，各一次
    var container = v.parentElement ? v.parentElement.parentElement : null;
    var btn = container ? findPlayButton(container) : null;
    if (btn && !CLICKED.btn){ CLICKED.btn = true; humanClick(btn); }
    if (!CLICKED.video){ CLICKED.video = true; humanClick(v); }
    tryPlay(v);
  }

  tick();
  try {
    new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  setInterval(tick, 700);
  try { if (window.androidHost) window.androidHost.onDebug('script ready, videos=' + document.querySelectorAll('video').length); } catch(e){}
  return 'ok';
})();
"""

/**
 * 广东广播电视台（gdtv.cn 荔枝网）：18 个频道共用同一套直播页结构，一套脚本覆盖全部。
 * 方案与央视频完全一致：不碰播放器容器，直接把 video 元素本身固定到视口；
 * 逐层清掉祖先链上的 transform / overflow:hidden / clip-path（它们会改写 fixed 的参照系，
 * 导致视频随页面滑动位移），再隐藏播放器之外的页面块。
 */
private const val GDTV_JS = """
(function(){
  if (window.__gdtvFit) return 'ok';
  window.__gdtvFit = true;

  var lastW = 0, lastH = 0, notified = false, waited = 0;

  function host(msg){
    try { if (window.androidHost && window.androidHost.onDebug) window.androidHost.onDebug('gdtv ' + msg); } catch(e){}
  }

  // 主文档 + 同源 iframe 里画面最大的 video（荔枝网各频道都走 video.js）
  function bigVideo(){
    var docs = [document];
    try {
      var fs = document.querySelectorAll('iframe');
      for (var i = 0; i < fs.length; i++){
        var d = null;
        try { d = fs[i].contentDocument; } catch(e){}
        if (d) docs.push(d);
      }
    } catch(e){}
    var best = null, area = 0;
    for (var k = 0; k < docs.length; k++){
      var list = null;
      try { list = docs[k].querySelectorAll('video'); } catch(e){ continue; }
      for (var j = 0; j < list.length; j++){
        var v = list[j], r = v.getBoundingClientRect();
        if (r.width < 80 || r.height < 45) continue;
        var a = r.width * r.height;
        if (a > area){ area = a; best = v; }
      }
    }
    return best;
  }

  function fit(v){
    if (!v) return false;
    try {
      var s = v.style;
      s.setProperty('position', 'fixed', 'important');
      s.setProperty('left', '0', 'important');
      s.setProperty('top', '0', 'important');
      s.setProperty('right', 'auto', 'important');
      s.setProperty('bottom', 'auto', 'important');
      s.setProperty('width', '100vw', 'important');
      s.setProperty('height', '100vh', 'important');
      s.setProperty('max-width', 'none', 'important');
      s.setProperty('max-height', 'none', 'important');
      s.setProperty('min-width', '0', 'important');
      s.setProperty('min-height', '0', 'important');
      s.setProperty('object-fit', 'contain', 'important');
      s.setProperty('z-index', '2147483000', 'important');
      s.setProperty('background', '#000', 'important');
      s.setProperty('transform', 'none', 'important');
      // video.js 与站点布局会给祖先写 transform / overflow:hidden / clip-path，
      // 它们既会改变 fixed 的参照系、又会把画面裁掉，逐层清掉 fixed 才稳定。
      var el = v.parentElement, guard = 0;
      while (el && el !== document.body && el !== document.documentElement && guard < 12){
        try {
          var cs = getComputedStyle(el);
          if (cs.overflow === 'hidden' || cs.overflowX === 'hidden' || cs.overflowY === 'hidden'){
            el.style.setProperty('overflow', 'visible', 'important');
          }
          if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform', 'none', 'important');
          if (cs.clipPath && cs.clipPath !== 'none') el.style.setProperty('clip-path', 'none', 'important');
        } catch(e){}
        el = el.parentElement; guard++;
      }
      // 播放器上方的遮罩/海报层会拦触摸，压掉指针事件
      try {
        var masks = document.querySelectorAll('[class*="cover" i],[class*="mask" i],[class*="poster" i]');
        for (var m = 0; m < masks.length && m < 6; m++){
          var rm = masks[m].getBoundingClientRect();
          if (rm.width > 200 && rm.height > 120 && rm.top < 120){
            masks[m].style.setProperty('pointer-events', 'none', 'important');
          }
        }
      } catch(e){}
      hideOthers(v);
      return true;
    } catch(e){ return false; }
  }

  // 只留播放器：把 video 祖先链之外的页面块全部隐藏（频道栏、推荐位会压在画面上）
  function hideOthers(v){
    try {
      var doc = v.ownerDocument || document;
      var body = doc.body;
      if (!body) return;
      var chain = [], el = v;
      while (el){ chain.push(el); el = el.parentElement; }
      var kids = body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i];
        if (chain.indexOf(k) >= 0) continue;
        var tn = k.tagName;
        if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
        try { k.style.setProperty('display', 'none', 'important'); } catch(e){}
      }
      try {
        body.style.setProperty('overflow', 'hidden', 'important');
        body.style.setProperty('margin', '0', 'important');
        body.style.setProperty('padding', '0', 'important');
      } catch(e){}
    } catch(e){}
  }

  // 部分频道页先显示海报遮罩，需要点一下播放键才起播
  function clickPlay(){
    var sels = ['.vjs-big-play-button', '.play-btn', '.playBtn',
                '[class*="play" i][class*="btn" i]', '[aria-label*="播放"]'];
    for (var i = 0; i < sels.length; i++){
      var ns = null;
      try { ns = document.querySelectorAll(sels[i]); } catch(e){ continue; }
      for (var j = 0; j < ns.length; j++){
        var r = ns[j].getBoundingClientRect();
        if (r.width < 10 || r.width > 200 || r.height < 10 || r.height > 200) continue;
        try { ns[j].click(); host('clickPlay ' + sels[i]); return true; } catch(e){}
      }
    }
    return false;
  }

  function play(v){
    if (!v) return;
    try {
      if (v.muted) v.muted = false;
      if (v.volume < 0.99) v.volume = 1.0;
    } catch(e){}
    if (v.paused){
      try {
        var p = v.play();
        if (p && p.catch) p.catch(function(){});
      } catch(e){}
    }
  }

  function tick(){
    var v = bigVideo();
    if (!v){
      if (waited++ < 30) clickPlay();
      return;
    }
    waited = 0;
    play(v);
    var w = window.innerWidth, h = window.innerHeight;
    if ((w !== lastW || h !== lastH || !v.__gdtvFitted) && fit(v)){
      v.__gdtvFitted = true;
      lastW = w; lastH = h;
      var rr = v.getBoundingClientRect();
      host('fit ' + w + 'x' + h + ' video=' + Math.round(rr.width) + 'x' + Math.round(rr.height));
    }
    if (!notified && !v.paused){
      notified = true;
      try { if (window.androidHost && window.androidHost.onPlaying) window.androidHost.onPlaying(); } catch(e){}
      host('playing->fullscreen');
    }
  }

  tick();
  setInterval(tick, 800);
  setInterval(function(){ var v = bigVideo(); if (v) play(v); }, 4000);
  try { if (window.androidHost) window.androidHost.onDebug('gdtv ready'); } catch(e){}
  return 'ok';
})();
"""

/** 深圳卫视（sztv.com.cn）：CUTV tdplayer 播放器在同源 iframe 里，需要先撑满 iframe 再控制内部 video。 */
/** 旧版：自行计算并改写尺寸，已弃用，保留仅为回滚参考。 */
private const val SZTV_JS_LEGACY = """
(function(){
  if (window.__sztvHooked) return 'ok';
  window.__sztvHooked = true;

  var CLICKED = false, CLICKED_INSIDE = false, LAST_SRC = '';
  var USER_TOUCH = 0, USER_PAUSED = false, PLAY_TRIES = 0;

  function notify(){
    try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
  }

  function playerFrame(){
    var list = document.querySelectorAll('iframe');
    var best = null, bestArea = 0;
    for (var i = 0; i < list.length; i++){
      var f = list[i];
      var r = f.getBoundingClientRect();
      if (r.width < 120 || r.height < 80) continue;
      // 优先认播放器路径；真机 UA 下播放器 URL 可能完全不含这些词，因此再退化为「面积最大的 iframe」
      if (/pindao|player|live|cutv|tvsou/i.test(f.src || '')) return f;
      var area = r.width * r.height;
      if (area > bestArea){ bestArea = area; best = f; }
    }
    return best;
  }

  /** iframe 自身铺满屏幕：tdplayer 的 _tdp_vbox 会跟着容器尺寸自适应，内部无需改动。 */
  function fillFrame(f){
    // 站点给 iframe 设了 width/height 属性，会压住 CSS 尺寸，先清掉
    f.removeAttribute('width');
    f.removeAttribute('height');
    f.removeAttribute('align');
    var s = f.style;
    // 用 fixed + 百分比撑满视口：不依赖 innerWidth/vw（真机 WebView 下二者未必等于可视宽度）
    s.setProperty('position','fixed','important');
    s.setProperty('top','0','important');
    s.setProperty('left','0','important');
    s.setProperty('right','0','important');
    s.setProperty('bottom','0','important');
    s.setProperty('margin','0','important');
    s.setProperty('width','100%','important');
    s.setProperty('height','100%','important');
    s.setProperty('min-width','100%','important');
    s.setProperty('min-height','100%','important');
    s.setProperty('max-width','none','important');
    s.setProperty('max-height','none','important');
    s.setProperty('transform','none','important');
    s.setProperty('z-index','2147483647','important');
    s.setProperty('border','0','important');
    s.setProperty('background','#000','important');
  }

  /** 点播放器自带的「全屏」按钮，让它按自己的逻辑处理画面比例与控件。只点一次。 */
  var FULL_TRIED = 0;
  function pressFull(f){
    try {
      if (FULL_TRIED >= 3) return;
      var d = f.contentDocument, w = f.contentWindow;
      if (!d) return;
      var fs = d.fullscreenElement || d.webkitFullscreenElement;
      if (fs) return;
      var btn = d.querySelector('._tdp_fullbtn');
      if (!btn){
        var cands = d.querySelectorAll('[class*="full"],[class*="screen"]');
        for (var i = 0; i < cands.length; i++){
          var r = cands[i].getBoundingClientRect();
          if (r.width > 4 && r.width < 120 && r.height > 4 && r.height < 120){ btn = cands[i]; break; }
        }
      }
      if (!btn) return;
      FULL_TRIED++;
      btn.click();
    } catch(e){}
  }

  function innerVideo(f){
    var d = null;
    try { d = f.contentDocument; } catch(e){ return null; }
    if (!d) return null;
    var list = d.querySelectorAll('video');
    var best = null, bestArea = 0;
    for (var i = 0; i < list.length; i++){
      var v = list[i];
      var r = v.getBoundingClientRect();
      if (r.width < 40 || r.height < 30) continue;
      var area = r.width * r.height;
      if (area > bestArea){ bestArea = area; best = v; }
    }
    return best;
  }

  /** 强制 video 对齐到 iframe 视口：站点可能把 video 偏移或改窄，导致一侧出现大黑边。 */
  function fillInner(f, v){
    try {
      var d = f.contentDocument, w = f.contentWindow;
      var iw = w.innerWidth, ih = w.innerHeight;
      if (d.body){
        d.body.style.setProperty('margin','0','important');
        d.body.style.setProperty('padding','0','important');
        d.body.style.setProperty('background','#000','important');
      }
      var r = v.getBoundingClientRect();
      var ok = r.width > 0 && Math.abs(r.left) < 2 && Math.abs(r.top) < 2 &&
               Math.abs(r.width - iw) < 2 && Math.abs(r.height - ih) < 2;
      if (ok) return;
      // 先把视频的容器链也拉满，避免父级宽度/偏移把 video 顶歪
      var p = v.parentElement, k = 0;
      while (p && p !== d.body && k++ < 4){
        var cs = w.getComputedStyle(p);
        if (cs.position === 'absolute' || cs.position === 'relative' ||
            /box|vbox|wrap|player/i.test(p.className || '')){
          p.style.setProperty('left','0','important');
          p.style.setProperty('top','0','important');
          p.style.setProperty('right','0','important');
          p.style.setProperty('bottom','0','important');
          p.style.setProperty('margin','0','important');
          p.style.setProperty('transform','none','important');
        }
        p = p.parentElement;
      }
      var s = v.style;
      s.setProperty('position','fixed','important');
      s.setProperty('left','0','important');
      s.setProperty('top','0','important');
      s.setProperty('right','0','important');
      s.setProperty('bottom','0','important');
      s.setProperty('margin','0','important');
      s.setProperty('width','100%','important');
      s.setProperty('height','100%','important');
      s.setProperty('object-fit','cover','important');
      s.setProperty('object-position','50% 50%','important');
      s.setProperty('transform','none','important');
      s.setProperty('z-index','0','important');
    } catch(e){}
  }

  function ticket(f, v){
    try {
      // 用户已经自己操作过播放器：完全让出控制权
      if (USER_TOUCH && Date.now() - USER_TOUCH < 3600000) return;
      // 只在开头主动播一次；用户手动暂停（USER_PAUSED）后不再抢回播放
      if (USER_PAUSED) return;
      if (PLAY_TRIES >= 3) return;
      if (v.muted) v.muted = false;
      try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
      if (!v.paused) return;
      PLAY_TRIES++;
      var pr = v.play();
      if (pr && pr.catch) pr.catch(function(){});
      if (f && !CLICKED_INSIDE){
        CLICKED_INSIDE = true;
        var d = f.contentDocument;
        var big = d.querySelector('[class*="bigplay"],[class*="big-play"],[class*="start"],.vjs-big-play-button');
        if (big && big.getBoundingClientRect().width > 0){
          big.click();
        } else {
          var r = v.getBoundingClientRect();
          var el = d.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
          if (el) el.click();
        }
      }
    } catch(e){}
  }

  var BOUND_DOC = null;
  function bindTouch(f){
    try {
      var d = f.contentDocument;
      if (!d || d === BOUND_DOC) return;
      BOUND_DOC = d;
      ['touchstart','mousedown','pointerdown'].forEach(function(t){
        d.addEventListener(t, function(){ USER_TOUCH = Date.now(); }, true);
      });
    } catch(e){}
  }

  function tick(){
    var f = playerFrame();
    if (!f){
      if (!CLICKED){
        CLICKED = true;
        var w = window.innerWidth, h = window.innerHeight;
        var el = document.elementFromPoint(w / 2, h / 2);
        if (el) el.click();
      }
      return;
    }
    // 诊断：把外壳与内部真实几何回传到 logcat
    try {
      if (window.androidHost && !window.__geoLogged){
        window.__geoLogged = 1;
        var fr0 = f.getBoundingClientRect();
        var v0 = f.contentDocument ? f.contentDocument.querySelector('video') : null;
        var vr0 = v0 ? v0.getBoundingClientRect() : null;
        var dr0 = v0 ? v0.parentElement.getBoundingClientRect() : null;
        var msg = 'GEO outer=' + innerWidth + 'x' + innerHeight +
          ' frame=' + Math.round(fr0.left) + ',' + Math.round(fr0.top) + ',' + Math.round(fr0.width) + 'x' + Math.round(fr0.height) +
          ' iframeInner=' + f.contentWindow.innerWidth + 'x' + f.contentWindow.innerHeight +
          ' iframeDocCW=' + f.contentDocument.documentElement.clientWidth +
          ' video=' + (vr0 ? Math.round(vr0.left) + ',' + Math.round(vr0.top) + ',' + Math.round(vr0.width) + 'x' + Math.round(vr0.height) : 'null') +
          ' vpar=' + (dr0 ? Math.round(dr0.width) + 'x' + Math.round(dr0.height) : 'null') +
          ' dpr=' + devicePixelRatio;
        window.androidHost.onDebug(msg);
      }
    } catch(e){}
    if (CLICKED && LAST_SRC === (f.src || '')) {
      // 已接管且播放器没换源：继续维持尺寸即可
    } else {
      CLICKED = true;
      LAST_SRC = f.src || '';
      CLICKED_INSIDE = false;
    }
    fillFrame(f);
    bindTouch(f);
    var v = innerVideo(f);
    if (!v) return;
    // 交给播放器自己的全屏：比例和控件都由它处理，我只让 iframe 撑满屏幕
    pressFull(f);
    ticket(f, v);
    if (!v.paused) notify();
  }

  tick();
  setInterval(tick, 700);
  try { if (window.androidHost) window.androidHost.onDebug('sztv ready'); } catch(e){}
  return 'ok';
})();
"""

/** 深圳卫视：只做一件事——找到播放器自带的全屏键并点击，比例与控件全交给播放器自己。 */
private const val SZTV_JS = """
(function(){
  try { if (window.androidHost) window.androidHost.onDebug('sztv full ready'); } catch(e){}

  var TRIES = 0, DONE = false;

  function frames(){
    var out = [], l = document.querySelectorAll('iframe');
    for (var i = 0; i < l.length; i++){
      var f = l[i], r = f.getBoundingClientRect();
      if (r.width >= 100 && r.height >= 60) out.push(f);
    }
    // 面积最大的那个通常是播放器
    out.sort(function(a, b){
      var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
      return rb.width * rb.height - ra.width * ra.height;
    });
    return out;
  }

  function isFull(d){
    try { return !!(d.fullscreenElement || d.webkitFullscreenElement); } catch(e){ return false; }
  }

  // 站点是「页头 + 右移的播放器」布局。移动 iframe 节点会触发重载导致断流，
  // 因此不动 DOM：逐级清掉祖先的偏移/尺寸限制，再隐藏页头等其它顶层元素。
  function solo(){
    var fs = frames();
    if (!fs.length) return;
    var f = fs[0];
    try {
      // 1) 隐藏播放器以外的顶层元素（页头、侧栏…）
      var kids = document.body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i];
        if (k === f || (k.contains && k.contains(f))) continue;
        k.style.setProperty('display','none','important');
      }
      // 2) 祖先链清偏移、放开尺寸（不改结构，不会重载）
      var p = f.parentNode;
      while (p && p.nodeType === 1){
        var ps = p.style;
        ps.setProperty('margin','0','important');
        ps.setProperty('padding','0','important');
        ps.setProperty('border','0','important');
        ps.setProperty('transform','none','important');
        ps.setProperty('max-width','none','important');
        ps.setProperty('max-height','none','important');
        ps.setProperty('left','0','important');
        ps.setProperty('top','0','important');
        if (p === document.body || p === document.documentElement){
          ps.setProperty('width','100vw','important');
          ps.setProperty('height','100vh','important');
          ps.setProperty('overflow','hidden','important');
          ps.setProperty('position','fixed','important');
        } else {
          ps.setProperty('width','100%','important');
          ps.setProperty('height','100vh','important');
          ps.setProperty('position','static','important');
          ps.setProperty('overflow','visible','important');
        }
        p = p.parentNode;
      }
      // 3) iframe 自身铺满
      f.removeAttribute('width');
      f.removeAttribute('height');
      f.removeAttribute('align');
      var s = f.style;
      s.setProperty('position','fixed','important');
      s.setProperty('top','0','important');
      s.setProperty('left','0','important');
      s.setProperty('right','0','important');
      s.setProperty('bottom','0','important');
      s.setProperty('width','100vw','important');
      s.setProperty('height','100vh','important');
      s.setProperty('margin','0','important');
      s.setProperty('padding','0','important');
      s.setProperty('border','0','important');
      s.setProperty('max-width','none','important');
      s.setProperty('max-height','none','important');
      s.setProperty('transform','none','important');
      s.setProperty('background','#000','important');
      s.setProperty('z-index','2147483647','important');
      document.documentElement.style.setProperty('overflow','hidden','important');
      if (document.body) document.body.style.setProperty('overflow','hidden','important');
      fillInner(f);
    } catch(e){}
  }

  // 内部 video 完整显示在 iframe 里：contain 不裁画面，避免宽屏下脸部被放大裁切
  function fillInner(f){
    try {
      var d = f.contentDocument;
      if (!d) return;
      var l = d.querySelectorAll('video');
      for (var i = 0; i < l.length; i++){
        var v = l[i], r = v.getBoundingClientRect();
        if (r.width < 40 || r.height < 30) continue;
        var s = v.style;
        s.setProperty('position','fixed','important');
        s.setProperty('top','0','important');
        s.setProperty('left','0','important');
        s.setProperty('width','100vw','important');
        s.setProperty('height','100vh','important');
        s.setProperty('max-width','none','important');
        s.setProperty('max-height','none','important');
        s.setProperty('margin','0','important');
        s.setProperty('object-fit','contain','important');
        s.setProperty('object-position','center center','important');
        s.setProperty('z-index','2147483647','important');
        // 首帧加速：声音先出、画面迟迟不出（加载点落在 GOP 中间，视频等关键帧），
        // 往前推 1.5 秒跨过 GOP 边界，让解码器立刻从最近关键帧出画面
        if (!v.paused && v.currentTime > 0 && (v.videoWidth === 0 || v.readyState < 3)) {
          v.__ffTicks = (v.__ffTicks || 0) + 1;
          if (v.__ffTicks === 1 || v.__ffTicks === 3) {
            try { v.currentTime = v.currentTime + 1.5; } catch(e) {}
          } else if (v.__ffTicks === 6) {
            try { v.load(); v.play(); } catch(e) {}
          }
        } else {
          v.__ffTicks = 0;
        }
        // 解码卡死兜底：播放中但 readyState 长期上不去，直接重载视频元素
        if (!v.paused && (v.videoWidth === 0 || v.readyState < 2)) {
          v.__stuck = (v.__stuck || 0) + 1;
          if (v.__stuck >= 15) { v.__stuck = 0; v.__ffTicks = 0; try { v.load(); v.play(); } catch(e) {} }
        } else {
          v.__stuck = 0;
        }
      }
      if (d.documentElement) d.documentElement.style.setProperty('overflow','hidden','important');
      if (d.body) d.body.style.setProperty('overflow','hidden','important');
    } catch(e){}
  }

  solo();
  var timer = setInterval(solo, 800);
  setTimeout(function(){ clearInterval(timer); }, 60000);
  return 'ok';
})();
"""

/** 起播与保障：主动点击 CUTV 播放器的播放键、监视起播并持续保声。 */
private const val SZTV_BOOT_JS = """
(function(){
  try { if (window.androidHost) window.androidHost.onDebug('sztv boot ready'); } catch(e){}

  var CLICKED = 0, NOTIFIED = false, LAST_M3U8 = '';

  // 模拟一次真实手指点击（与其他频道一致：down→up→click 三件套）
  function humanClick(el){
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var win = el.ownerDocument && el.ownerDocument.defaultView ? el.ownerDocument.defaultView : window;
    var opt = {bubbles:true, cancelable:true, clientX:r.left + r.width/2, clientY:r.top + r.height/2, view:win};
    try {
      el.dispatchEvent(new (win.PointerEvent || win.MouseEvent)('pointerdown', opt));
      el.dispatchEvent(new win.MouseEvent('mousedown', opt));
      el.dispatchEvent(new (win.PointerEvent || win.MouseEvent)('pointerup', opt));
      el.dispatchEvent(new win.MouseEvent('mouseup', opt));
      el.dispatchEvent(new win.MouseEvent('click', opt));
      return true;
    } catch(e){ return false; }
  }

  function videosIn(f){
    try { return f.contentDocument ? f.contentDocument.querySelectorAll('video') : []; } catch(e){ return []; }
  }

  function mainVideo(){
    // 外层直接有 video 用外层的；否则取最大 iframe 里的 video
    var own = document.querySelectorAll('video');
    if (own.length) return own[0];
    var fs = document.querySelectorAll('iframe');
    for (var i = 0; i < fs.length; i++){
      var vs = videosIn(fs[i]);
      for (var j = 0; j < vs.length; j++) return vs[j];
    }
    return null;
  }

  // 在 iframe 里找 CUTV tdplayer 的播放键（实测类名 _tdp_playbtn，多种兜底）
  function playBtn(f){
    var d = null;
    try { d = f.contentDocument; } catch(e){ return null; }
    if (!d) return null;
    var sels = ['._tdp_playbtn', '[class*="tdp_play" i]', '[class*="playbtn" i]',
                '[class*="play-btn" i]', '[class*="btnplay" i]', '[class*="playicon" i]'];
    for (var s = 0; s < sels.length; s++){
      var n;
      try { n = d.querySelectorAll(sels[s]); } catch(e){ continue; }
      for (var i = 0; i < n.length; i++){
        var el = n[i], r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        var cs = d.defaultView.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        return el;
      }
    }
    return null;
  }

  function playingNow(v){
    return v && !v.paused && v.readyState >= 2 && v.currentTime > 0;
  }

  function ensureSound(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
  }

  function notify(v){
    if (NOTIFIED) return;
    NOTIFIED = true;
    ensureSound(v);
    try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
    try { if (window.androidHost) window.androidHost.onDebug('sztv playing vw=' + v.videoWidth); } catch(e){}
  }

  function tick(){
    var f = document.querySelector('iframe');
    var v = mainVideo();
    if (!f && !v) return;

    // 已在播：保声 + 首帧加速（黑屏时轻推跨关键帧）
    if (playingNow(v)){
      ensureSound(v);
      notify(v);
      if (v.videoWidth === 0 || v.readyState < 3){
        v.__ff = (v.__ff || 0) + 1;
        if (v.__ff === 1 || v.__ff === 3){
          try { v.currentTime = v.currentTime + 1.5; } catch(e){}
        } else if (v.__ff >= 10){
          v.__ff = 0;
          try { v.load(); } catch(e){}
          try { v.play(); } catch(e){}
        }
      } else {
        v.__ff = 0;
      }
      // 上报尺寸，原生按比例摆放
      try {
        if (v.videoWidth > 0 && window.androidHost && window.androidHost.onVideoSize){
          window.androidHost.onVideoSize(v.videoWidth, v.videoHeight);
        }
      } catch(e){}
      return;
    }

    // 未起播：优先点站点播放键（每 4 轮重试一次，最多 20 次）；
    // 有 video 但点键无效时直接调 play()
    if (v){
      try { v.play(); } catch(e){}
      if (v.paused || v.readyState < 2){
        CLICKED++;
        if (CLICKED <= 20 && CLICKED % 2 === 1){
          var btn = f ? playBtn(f) : null;
          if (btn) humanClick(btn);
        }
      }
    } else if (f){
      // video 还没创建：也先点播放键，触发播放器初始化
      CLICKED++;
      if (CLICKED <= 20 && CLICKED % 2 === 1){
        var b2 = playBtn(f);
        if (b2) humanClick(b2);
      }
    }
  }

  tick();
  setInterval(tick, 700);
  try { if (window.androidHost) window.androidHost.onDebug('sztv boot loop on'); } catch(e){}
  return 'ok';
})();
"""

/**
 * 芒果TV 直播页切台：链接带 ch=（频道名）+ cat=（分类页签）。
 * 侧栏只列当前分类的频道，所以先点分类页签，再点同名频道。
 */
private const val MGTV_SWITCH_JS = """
(function(){
  var ch = '', cat = '';
  try {
    var q = decodeURIComponent(location.search || '');
    var m1 = q.match(/[?&]ch=([^&]+)/); if (m1) ch = m1[1];
    var m2 = q.match(/[?&]cat=([^&]+)/); if (m2) cat = m2[1];
  } catch(e){}
  if (!ch) return 'no-ch';
  // 同一频道不再重复切（onPageFinished 会注入两次）
  if (window.__mgtvWant === ch) return window.__mgtvDone ? 'already' : 'waiting';
  window.__mgtvWant = ch;
  window.__mgtvDone = false;

  function log(s){
    try { if (window.androidHost) window.androidHost.onDebug('mgtv-ch ' + s); } catch(e){}
  }

  function tap(el){
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
    } catch(e){
      try { el.click(); } catch(e2){}
    }
  }

  // 侧栏频道项
  function findChannel(){
    var list = document.querySelectorAll('.list .channel');
    for (var i = 0; i < list.length; i++){
      if ((list[i].textContent || '').replace(/\s+/g, '').trim() === ch) return list[i];
    }
    return null;
  }

  // 分类页签（热门 / 卫视台 / 地方台 / 特色频道），只认纯文字节点的 div.item
  function findTab(){
    if (!cat) return null;
    var all = document.querySelectorAll('.item');
    for (var i = 0; i < all.length; i++){
      var e = all[i];
      if (e.children.length) continue;
      if ((e.textContent || '').replace(/\s+/g, '').trim() === cat) return e;
    }
    return null;
  }

  var loops = 0;
  function tick(){
    if (window.__mgtvDone) return;
    loops++;
    var hit = findChannel();
    if (hit){
      // 已经是当前频道就不再点，避免打断正在播放的流
      if ((hit.className || '').indexOf('selected') >= 0){
        window.__mgtvDone = true;
        log('已是当前频道 ' + ch);
        return;
      }
      tap(hit);
      window.__mgtvDone = true;
      log('切台 ' + ch);
      return;
    }
    // 当前分类里没有这个台：先切到目标分类页签，下一轮再找频道
    var tb = findTab();
    if (tb && (tb.className || '').indexOf('selected') < 0){
      tap(tb);
      log('切分类 ' + cat);
    }
    if (loops > 90){
      window.__mgtvDone = true;
      log('未定位到频道 ' + ch + ' 分类 ' + cat);
      return;
    }
    setTimeout(tick, 500);
  }

  tick();
  return 'ok';
})();
"""

/** 芒果TV（mgtv.com）：不做任何尺寸计算，播放后直接点播放器自带的全屏键。 */
private const val MGTV_JS = """
(function(){
  if (window.__mgtvFit) return 'ok';
  window.__mgtvFit = true;

  var lastW = 0, lastH = 0, hostNotified = false, tries = 0;

  function host(msg){
    try { if (window.androidHost && window.androidHost.onDebug) window.androidHost.onDebug('mgtv ' + msg); } catch(e){}
  }

  // 取画面上最大的 video（含同源 iframe）
  function bigVideo(){
    var docs = [document];
    try {
      var fs = document.querySelectorAll('iframe');
      for (var i = 0; i < fs.length; i++){
        var d = null;
        try { d = fs[i].contentDocument; } catch(e){}
        if (d) docs.push(d);
      }
    } catch(e){}
    var best = null, area = 0;
    for (var k = 0; k < docs.length; k++){
      var list = null;
      try { list = docs[k].querySelectorAll('video'); } catch(e){ continue; }
      for (var j = 0; j < list.length; j++){
        var v = list[j], r = v.getBoundingClientRect();
        if (r.width < 60 || r.height < 34) continue;
        var a = r.width * r.height;
        if (a > area){ area = a; best = v; }
      }
    }
    return best;
  }

  // 把 video 撑满整个 WebView 视口，不改站点结构、不依赖站点全屏键
  function fit(v){
    if (!v) return false;
    // layout() 已经把播放器容器铺满：此时只需让 video 填满容器，
    // 再做 fixed 定位反而会脱离容器、并放开祖先的 overflow 导致整页重新露出来
    var pc = document.querySelector('.player-container');
    if (pc && pc.__mgtvLayoutW){
      try {
        var s0 = v.style;
        s0.setProperty('position', 'absolute', 'important');
        s0.setProperty('left', '0', 'important');
        s0.setProperty('top', '0', 'important');
        s0.setProperty('width', '100%', 'important');
        s0.setProperty('height', '100%', 'important');
        s0.setProperty('max-width', 'none', 'important');
        s0.setProperty('max-height', 'none', 'important');
        s0.setProperty('object-fit', 'contain', 'important');
        s0.setProperty('background', '#000', 'important');
        s0.setProperty('transform', 'none', 'important');
      } catch(e){}
      return true;
    }
    try {
      var s = v.style;
      s.setProperty('position', 'fixed', 'important');
      s.setProperty('left', '0', 'important');
      s.setProperty('top', '0', 'important');
      s.setProperty('right', 'auto', 'important');
      s.setProperty('bottom', 'auto', 'important');
      s.setProperty('width', '100vw', 'important');
      s.setProperty('height', '100vh', 'important');
      s.setProperty('max-width', 'none', 'important');
      s.setProperty('max-height', 'none', 'important');
      s.setProperty('min-width', '0', 'important');
      s.setProperty('min-height', '0', 'important');
      s.setProperty('object-fit', 'contain', 'important');
      s.setProperty('z-index', '2147483000', 'important');
      s.setProperty('background', '#000', 'important');
      s.setProperty('transform', 'none', 'important');
      // 祖先容器若裁剪或带位移，会挡住铺满效果：逐级放开
      var el = v.parentElement, guard = 0;
      while (el && el !== document.body && el !== document.documentElement && guard < 8){
        try {
          var cs = getComputedStyle(el);
          if (cs.overflow === 'hidden' || cs.overflowX === 'hidden' || cs.overflowY === 'hidden'){
            el.style.setProperty('overflow', 'visible', 'important');
          }
          if (cs.transform && cs.transform !== 'none'){
            el.style.setProperty('transform', 'none', 'important');
          }
          if (cs.clipPath && cs.clipPath !== 'none'){
            el.style.setProperty('clip-path', 'none', 'important');
          }
        } catch(e){}
        el = el.parentElement; guard++;
      }
      return true;
    } catch(e){ return false; }
  }

  // 播放 + 有声
  function play(v){
    if (!v) return;
    try {
      if (v.muted) v.muted = false;
      if (v.volume < 0.99) v.volume = 1.0;
    } catch(e){}
    if (v.paused){
      try {
        var p = v.play();
        if (p && p.catch) p.catch(function(){});
      } catch(e){}
    }
  }

  // 站点整页布局（左侧导航/页头/右侧浮动按钮/频道侧栏）会露在画面外：
  // 把播放器容器铺满视口，并隐藏与它同级的其它区域。幂等，轮询里每轮可调。
  function layout(){
    try {
      // 切台还没定位到频道时先不动布局，否则容器抬到最上层会挡住侧栏，点选频道会失败
      if (/[?&]ch=/.test(location.search || '') && !window.__mgtvDone) return;
      var pc = document.querySelector('.player-container');
      if (!pc){
        var vv = bigVideo();
        pc = vv ? vv.parentElement : null;
      }
      if (!pc) return;
      var w = window.innerWidth, h = window.innerHeight;
      // 切台状态变化时也要重跑（否则侧栏收起后不会重排）
      var switching = /[?&]ch=/.test(location.search || '') && !window.__mgtvDone;
      if (pc.__mgtvLayoutW === w && pc.__mgtvLayoutH === h && pc.__mgtvSwitching === switching) return;

      // 隐藏与播放器同一链路上的所有兄弟节点（导航、搜索栏、浮动按钮…）
      var node = pc;
      while (node && node !== document.body){
        var p = node.parentElement;
        if (!p) break;
        for (var i = 0; i < p.children.length; i++){
          var c = p.children[i];
          if (c === node || c.contains(pc)) continue;
          var tn = c.tagName;
          if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
          try { c.style.setProperty('display', 'none', 'important'); } catch(e){}
        }
        node = p;
      }

      pc.style.setProperty('position', 'fixed', 'important');
      pc.style.setProperty('left', '0', 'important');
      pc.style.setProperty('top', '0', 'important');
      pc.style.setProperty('right', 'auto', 'important');
      pc.style.setProperty('bottom', 'auto', 'important');
      pc.style.setProperty('width', '100vw', 'important');
      pc.style.setProperty('height', '100vh', 'important');
      pc.style.setProperty('margin', '0', 'important');
      pc.style.setProperty('padding', '0', 'important');
      pc.style.setProperty('transform', 'none', 'important');
      pc.style.setProperty('overflow', 'hidden', 'important');
      pc.style.setProperty('z-index', '2147483000', 'important');

      // 容器内的播放器本体撑满；频道侧栏在切台完成后收起（切台需要能点到它）
      var kids = pc.children;
      for (var k = 0; k < kids.length; k++){
        var kid = kids[k];
        if (String(kid.className || '').indexOf('channels') >= 0){
          if (!switching) kid.style.setProperty('display', 'none', 'important');
          continue;
        }
        kid.style.setProperty('width', '100%', 'important');
        kid.style.setProperty('height', '100%', 'important');
      }

      try {
        document.documentElement.style.setProperty('overflow', 'hidden', 'important');
        document.body.style.setProperty('overflow', 'hidden', 'important');
        document.body.style.setProperty('margin', '0', 'important');
      } catch(e){}

      pc.__mgtvLayoutW = w; pc.__mgtvLayoutH = h; pc.__mgtvSwitching = switching;
      host('layout ' + w + 'x' + h + ' switching=' + switching);
    } catch(e){}
  }

  function tick(){
    layout();
    var v = bigVideo();
    if (!v){
      if (tries++ > 20) return;
      return;
    }
    tries = 0;
    play(v);
    var w = window.innerWidth, h = window.innerHeight;
    var need = (w !== lastW || h !== lastH) || !v.__mgtvFitted;
    if (need && fit(v)){
      v.__mgtvFitted = true;
      lastW = w; lastH = h;
      var rr = v.getBoundingClientRect();
      host('fit ' + w + 'x' + h + ' video=' + Math.round(rr.width) +
           'x' + Math.round(rr.height) + ' playing=' + !v.paused);
    }
    // 第一次真正起播时通知宿主：隐藏顶栏与系统栏，WebView 铺满
    if (!hostNotified && !v.paused){
      hostNotified = true;
      try { if (window.androidHost && window.androidHost.onPlaying) window.androidHost.onPlaying(); } catch(e){}
      host('playing->fullscreen');
    }
  }

  tick();
  var timer = setInterval(tick, 800);
  setInterval(function(){
    var v = bigVideo();
    if (v) play(v);
  }, 4000);
  setTimeout(function(){ clearInterval(timer); }, 180000);
  return 'ok';
})();
"""

/**
 * 江苏荔枝网（live.jstv.com）。
 * 站点频道卡是精灵图，卡片上没有频道名文字，按名字点不到；
 * 但每张卡片的 background-position 唯一（每格 28px，依次 0/-28/-56…），
 * 所以用 bg= 参数按坐标定位卡片。切台后交给通用脚本接管播放。
 */
private const val JSTV_SWITCH_JS = """
(function(){
  if (window.__jstvSwitch) return 'ok';
  window.__jstvSwitch = true;

  var bg = '', want = '';
  try {
    var m = location.search.match(/[?&]bg=([^&]+)/);
    if (m) bg = decodeURIComponent(m[1]).replace(/\s/g, '').toLowerCase();
    var m2 = location.search.match(/[?&]ch=([^&]+)/);
    if (m2) want = decodeURIComponent(m2[1]).replace(/\s+/g, '');
  } catch(e){}
  if (!bg && !want) return 'skip';

  var done = false;
  var loops = 0;

  function norm(v){
    return (v || '').replace(/\s+/g, '').toLowerCase();
  }

  // 找出所有 background-position 与目标一致的可见元素
  function findByBg(){
    var hits = [];
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++){
      var el = all[i];
      var st = window.getComputedStyle(el);
      var bp = norm(st.backgroundPosition);
      if (!bp) continue;
      // 站点只把横坐标写成 px，纵坐标是 0px 或 0% 0%
      if (bp.indexOf(bg) !== 0) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 10 || r.height < 10) continue;
      hits.push({el: el, area: r.width * r.height});
    }
    return hits;
  }

  // 没有精灵图坐标时（接口动态下发的频道，如 4K 超高清），按卡片上的频道名找
  function findByName(){
    var all = document.querySelectorAll('*');
    var best = null;
    for (var i = 0; i < all.length; i++){
      var el = all[i];
      var t = (el.textContent || '').replace(/\s+/g, '');
      if (!t || t.length > want.length + 12) continue;
      if (t.indexOf(want) < 0) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 10) continue;
      // 取最贴近文字的节点（面积最小的那个），再往上找可点击的卡片
      if (!best || r.width * r.height < best.area) best = {el: el, area: r.width * r.height, r: r};
    }
    return best;
  }

  function tap(el){
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
    } catch(e){
      try { el.click(); } catch(e2){}
    }
  }

  // 从文字节点向上找 3 层，找到挂了点击、且尺寸像卡片的祖先
  function clickableAncestor(el){
    var cur = el;
    for (var i = 0; i < 4 && cur; i++){
      var r = cur.getBoundingClientRect();
      if (r.width >= 40 && r.height >= 30) return cur;
      cur = cur.parentElement;
    }
    return el;
  }

  function tick(){
    if (done) return;
    loops++;

    if (bg){
      var hits = findByBg();
      if (!hits.length){
        if (loops === 12) log('bg ' + bg + ' 未找到频道卡');
        return;
      }
      // 精灵图定位的可能是内层图片节点，取面积最大的那个最稳
      hits.sort(function(a, b){ return b.area - a.area; });
      var t = hits[0].el;
      // 往上找一层可点击的父级，站点通常把点击事件绑在 slide 上
      var p = t.parentElement;
      var target = (p && p.getBoundingClientRect().width >= t.getBoundingClientRect().width) ? p : t;
      tap(target);
      tap(t);
      done = true;
      log('切台 bg=' + bg + ' 命中 ' + hits.length + ' 个节点');
      return;
    }

    var hit = findByName();
    if (!hit){
      if (loops === 15) log('名 ' + want + ' 未找到频道卡');
      return;
    }
    var card = clickableAncestor(hit.el);
    tap(card);
    tap(hit.el);
    done = true;
    log('切台 ch=' + want + ' 命中');
  }

  function log(s){
    try { if (window.androidHost) window.androidHost.onDebug('jstv ' + s); } catch(e){}
  }

  tick();
  setInterval(tick, 600);
  return 'ok';
})();
"""

/** 江苏台：切台脚本 + 通用播放接管（切台后视频元素会被重建，靠通用脚本轮询重新挂上）。 */
private const val JSTV_JS = JSTV_SWITCH_JS + "\n" + AUTOPLAY_JS

/**
 * 看看新闻「看电视」：页底频道栏切换，网址不变，只能模拟点击。
 * 链接带 ?ch=频道名，脚本读出来后点击同名 li，最多试 40 秒（页面频道栏是异步渲染的）。
 */
private const val KANKAN_SWITCH_JS = """
(function(){
  var want = '';
  try {
    var q = decodeURIComponent(location.search || '');
    var m = q.match(/[?&]ch=([^&]+)/);
    if (m) want = m[1];
  } catch(e){}
  if (!want) return 'no-ch';
  // 同一频道已有循环在跑就不再开新循环（onPageFinished 会注入两次）
  if (window.__kkWant === want) {
    return window.__kkDone ? 'already' : 'waiting';
  }
  window.__kkWant = want;
  window.__kkDone = false;

  function log(s){
    try { if (window.androidHost) window.androidHost.onDebug('kankan ' + s); } catch(e){}
  }

  function tap(el){
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
    } catch(e){
      try { el.click(); } catch(e2){}
    }
  }

  var loops = 0;
  function tick(){
    if (window.__kkDone) return;
    loops++;
    var lis = document.querySelectorAll('li');
    var hit = null;
    for (var i = 0; i < lis.length; i++){
      var t = (lis[i].textContent || '').replace(/\s+/g, '');
      if (t !== want) continue;
      // 页底频道栏的 li 内部包着一层 span；顶部导航的分类 li 是纯文字。
      // 「五星体育」两边都有，靠这一点区分，避免点到导航区。
      if (!lis[i].querySelector('span')) continue;
      hit = lis[i];
      break;
    }
    if (!hit){
      if (loops === 8) log('未找到频道 ' + want + '，继续等待');
      if (loops > 80) return;
      setTimeout(tick, 500);
      return;
    }
    // 已经是当前频道就不再点（避免打断正在播放的流）
    if (hit.className && hit.className.indexOf('active') >= 0){
      window.__kkDone = true;
      log('已是当前频道 ' + want);
      return;
    }
    tap(hit);
    window.__kkDone = true;
    log('切台 ch=' + want + ' 命中');
  }

  tick();
  return 'ok';
})();
"""

/** 看看新闻：切台脚本 + 通用播放接管（切台会重建 video，靠通用脚本重新接管）。 */
private const val KANKAN_JS = KANKAN_SWITCH_JS + "\n" + AUTOPLAY_JS

/**
 * 江西网络广播电视台（www.jxntv.cn/live）：一个页面里点左侧频道条换台，地址不变。
 * 链接带 ?ch=频道名，脚本按名称点 .channel-list .item，最多等 30 秒（频道条异步渲染）。
 * 注意：参数写在 # 之前才能从 location.search 读到。
 */
private const val JXNTV_SWITCH_JS = """
(function(){
  var want = '';
  try {
    var q = decodeURIComponent(location.search || '');
    var m = q.match(/[?&]ch=([^&]+)/);
    if (m) want = m[1];
  } catch(e){}
  if (!want) return 'no-ch';
  // onPageFinished 会注入两次，同一频道已有循环就不再开新的
  if (window.__jxWant === want) {
    return window.__jxDone ? 'already' : 'waiting';
  }
  window.__jxWant = want;
  window.__jxDone = false;

  function log(s){
    try { if (window.androidHost) window.androidHost.onDebug('jxntv ' + s); } catch(e){}
  }

  function tap(el){
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
    } catch(e){
      try { el.click(); } catch(e2){}
    }
  }

  var loops = 0;
  function tick(){
    if (window.__jxDone) return;
    loops++;
    var items = document.querySelectorAll('.channel-list .item');
    var hit = null;
    for (var i = 0; i < items.length; i++){
      var t = (items[i].textContent || '').replace(/\s+/g, '');
      if (t !== want) continue;
      hit = items[i];
      break;
    }
    if (!hit){
      if (loops === 8) log('未找到频道 ' + want + '，继续等待');
      if (loops > 60) { log('放弃：频道条未出现'); return; }
      setTimeout(tick, 500);
      return;
    }
    // 已高亮说明就是当前频道，不再点，避免打断正在播放的流
    var cls = (hit.className || '') + ' ' + ((hit.parentElement || {}).className || '');
    if (hit.querySelector('.watching') || cls.indexOf('active') >= 0){
      window.__jxDone = true;
      log('已是当前频道 ' + want);
      return;
    }
    tap(hit);
    window.__jxDone = true;
    log('切台 ch=' + want + ' 命中');
  }

  tick();
  return 'ok';
})();
"""

/**
 * 江西台铺满：页面与广东台一样用阿里云 Prism 播放器（.prism-player），
 * 容器只有约 1184px 宽、video 被 transform 缩放，因此照 GDTV 的做法：
 * 把 .prism-player 整层 fixed 铺满视口，video 相对容器 100%，并清掉 transform。
 */
private const val JXNTV_FILL_JS = """
(function(){
  if (window.__jxFilled) return 'ok';
  window.__jxFilled = true;

  function player(){
    var p = document.querySelector('#J_prismPlayer') || document.querySelector('.prism-player');
    if (p) return p;
    var v = document.querySelector('video');
    return v ? v.closest('.prism-player') : null;
  }

  function mainVideo(){
    var v = document.querySelector('video');
    return (v && v.getBoundingClientRect().width > 40) ? v : null;
  }

  function fill(){
    var box = player();
    if (!box) return false;
    // 播放器外层可能有多层 wrapper 限制尺寸，逐层放开
    var p = box.parentElement, guard = 0;
    while (p && p !== document.documentElement && guard++ < 8){
      try {
        p.style.setProperty('height','auto','important');
        p.style.setProperty('max-width','none','important');
        p.style.setProperty('max-height','none','important');
        p.style.setProperty('overflow','visible','important');
      } catch(e){}
      p = p.parentElement;
    }
    box.style.cssText += ';position:fixed!important;top:0!important;left:0!important;' +
      'width:100vw!important;height:100vh!important;margin:0!important;' +
      'max-width:none!important;max-height:none!important;' +
      'z-index:2147483647!important;overflow:visible!important;background:#000!important';
    var v = mainVideo();
    if (!v) return false;
    var s = v.style;
    s.setProperty('position','absolute','important');
    s.setProperty('top','0','important');
    s.setProperty('left','0','important');
    s.setProperty('right','auto','important');
    s.setProperty('bottom','auto','important');
    s.setProperty('width','100%','important');
    s.setProperty('height','100%','important');
    // Prism 用 transform 平移/缩放 video，必须清掉，否则画面偏出屏幕
    s.setProperty('transform','none','important');
    s.setProperty('max-width','none','important');
    s.setProperty('max-height','none','important');
    s.setProperty('margin','0','important');
    s.setProperty('z-index','2147483647','important');
    s.setProperty('object-fit','contain','important');
    s.setProperty('background','#000','important');
    return true;
  }

  function sound(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
  }

  var clickedPlay = false, notified = false;

  function tick(){
    var v = mainVideo();
    if (!v) return;
    fill();
    sound(v);
    if (v.paused) {
      try { v.play(); } catch(e){}
      if (!clickedPlay){
        clickedPlay = true;
        var btn = document.querySelector('.prism-big-play-btn,.prism-cover');
        if (btn) { try { btn.click(); } catch(e){} }
      }
      return;
    }
    if (!notified){
      notified = true;
      try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
    }
  }

  tick();
  setInterval(tick, 700);
  try {
    new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  try { if (window.androidHost) window.androidHost.onDebug('jxntv ready'); } catch(e){}
  return 'ok';
})();
"""

/**
 * 云南云视网（www.yntv.cn/live.html）：PC 版页面（body 设了 min-width:1400px、zoom:92%），
 * 播放器是 video.js（#live-video，写死 1030x573，class 带 vjs-fluid）。
 * video.js 靠 JS 按容器尺寸算宽高，只改 video 尺寸会被它下次 layout 覆盖，
 * 因此必须固定外层 .video-js 容器、再清掉 video.js 写上的行内宽高与 padding。
 */
private const val YNTV_SWITCH_JS = """
(function(){
  if (window.__ynHooked) return 'ok';
  window.__ynHooked = true;

  function host(msg){
    try { if (window.androidHost && window.androidHost.onDebug) window.androidHost.onDebug('yntv ' + msg); } catch(e){}
  }

  // 不用宽高筛：video.js 设 autoplay:false 时元素初始尺寸为 0，宽高筛会永远筛不到。
  // 铺满/静音/通知统一交给 AUTOPLAY_JS，这里只负责把播放器推到起播。
  function videoEl(){
    var p = window.player;
    if (p && p.el && typeof p.el === 'function'){
      var inner = p.el().querySelector('video');
      if (inner) return inner;
    }
    return document.querySelector('#live-video video') || document.querySelector('.video-js video') ||
           document.querySelector('video');
  }

  // 站点导航、版权浮层、自绘控制条会压在满屏画面上：凡是非视频链路上、
  // 且有一半以上面积落在画面里的元素一律收掉（视频祖先不能动，否则画面一起消失）
  var CLEAN_ROUND = 0;
  function clean(v){
    if (++CLEAN_ROUND % 10 !== 1) return;
    var vr = v.getBoundingClientRect();
    if (vr.width < 2 || vr.height < 2) return;
    var all = document.querySelectorAll('body *');
    for (var i = 0; i < all.length; i++){
      var el = all[i];
      if (el === v || el.contains(v) || v.contains(el)) continue;
      if (el.__ynHidden) continue;
      try {
        var r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        var ox = Math.min(r.right, vr.right) - Math.max(r.left, vr.left);
        var oy = Math.min(r.bottom, vr.bottom) - Math.max(r.top, vr.top);
        if (ox <= 0 || oy <= 0) continue;
        // 只要压在画面上就收掉：静态定位的导航残块、悬浮角标、版权条都算，不看它自身多大
        if (ox * oy >= 2400){
          el.__ynHidden = true;
          el.style.setProperty('display', 'none', 'important');
        }
      } catch(e){}
    }
  }

  function tick(){
    var v = videoEl();
    if (!v) return;
    clean(v);
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
    if (!v.paused) return;
    // 页面是 autoplay:false + 异步 src，前几十次取流都会失败，必须持续重试
    try { window.player.play(); } catch(e){}
    try { v.play(); } catch(e){}
    var btn = document.querySelector('.vjs-big-play-button');
    if (btn && btn.offsetParent !== null){ try { btn.click(); } catch(e){} }
  }

  tick();
  setInterval(tick, 700);
  try {
    new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  return 'ok';
})();
"""

/** 云南台：切台/起播脚本 + 通用自动播放铺满（与江苏、看看新闻同一套模式）。 */
private const val YNTV_JS = YNTV_SWITCH_JS + "\n" + AUTOPLAY_JS

/** 江西台：切台脚本 + Prism 专用铺满（换台会重建 video，靠 FILL 脚本重新接管）。 */
private const val JXNTV_JS = JXNTV_SWITCH_JS + "\n" + JXNTV_FILL_JS

/**
 * 福建网络广播电视台（live.fjtv.net）：PC 版 XHTML + 阿里 Aliplayer。
 * 播放器容器 #m2o_player 被写死 843x505，且页面外层还有 1000px+ 的版心，
 * 只改 video 尺寸没用，必须逐层放开再固定铺满。
 */
private const val FJTV_FILL_JS = """
(function(){
  if (window.__fjFilled) return 'ok';
  window.__fjFilled = true;

  function mainVideo(){
    var v = document.querySelector('#m2o_player video') || document.querySelector('video');
    return (v && v.getBoundingClientRect().width > 40) ? v : null;
  }

  function fill(){
    var v = mainVideo();
    if (!v) return false;
    // 阿里播放器把尺寸写在外层容器上，先把整条祖先链的限宽限高放开
    var box = v.closest('#m2o_player') || v.parentElement;
    var p = box, guard = 0;
    while (p && p !== document.documentElement && guard++ < 10){
      try {
        p.style.setProperty('width','auto','important');
        p.style.setProperty('height','auto','important');
        p.style.setProperty('max-width','none','important');
        p.style.setProperty('max-height','none','important');
        p.style.setProperty('overflow','visible','important');
      } catch(e){}
      p = p.parentElement;
    }
    if (box){
      box.style.cssText += ';position:fixed!important;top:0!important;left:0!important;' +
        'width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;' +
        'max-width:none!important;max-height:none!important;' +
        'z-index:2147483647!important;overflow:hidden!important;background:#000!important';
    }
    var s = v.style;
    s.setProperty('position','absolute','important');
    s.setProperty('top','0','important');
    s.setProperty('left','0','important');
    s.setProperty('right','auto','important');
    s.setProperty('bottom','auto','important');
    s.setProperty('width','100%','important');
    s.setProperty('height','100%','important');
    // 阿里播放器用 transform 做等比缩放，必须清掉，否则画面偏出屏幕
    s.setProperty('transform','none','important');
    s.setProperty('max-width','none','important');
    s.setProperty('max-height','none','important');
    s.setProperty('margin','0','important');
    s.setProperty('z-index','2147483647','important');
    s.setProperty('object-fit','contain','important');
    s.setProperty('background','#000','important');
    try {
      document.documentElement.style.setProperty('overflow','hidden','important');
      if (document.body) document.body.style.setProperty('overflow','hidden','important');
    } catch(e){}
    return true;
  }

  function sound(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
  }

  var clickedPlay = false, notified = false;

  function tick(){
    var v = mainVideo();
    if (!v) return;
    fill();
    sound(v);
    if (v.paused){
      try { v.play(); } catch(e){}
      if (!clickedPlay){
        clickedPlay = true;
        var btn = document.querySelector('.prism-big-play-btn,.prism-cover,.outter');
        if (btn) { try { btn.click(); } catch(e){} }
      }
      return;
    }
    if (!notified){
      notified = true;
      try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
      try { if (window.androidHost) window.androidHost.onDebug('fjtv filled'); } catch(e){}
    }
  }

  tick();
  setInterval(tick, 700);
  try {
    new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  return 'ok';
})();
"""

/**
 * 广西网络广播电视台（tv.gxtv.cn/channel/channelivePlay_*.html）：
 * 每个频道一个独立页面，播放器是站点自研 dhc（video#dhc-video，MSE blob 流）。
 * 原始尺寸 940x570 被 .player-container / .coordinator 卡住，需整条链路放开并固定铺满。
 */
private const val GXTV_JS = """
(function(){
  if (window.__gxHooked) return 'ok';
  window.__gxHooked = true;

  function mainVideo(){
    var v = document.querySelector('#dhc-video') || document.querySelector('video');
    if (!v) return null;
    var r = v.getBoundingClientRect();
    return r.width > 40 ? v : null;
  }

  function clickAt(x, y){
    var el = document.elementFromPoint(x, y);
    if (!el) return;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
    } catch(e){}
  }

  /**
   * 用一份常驻 <style> 接管布局，而不是反复改内联样式：
   * 站点用 left:50% + translateX(-50%) 居中，任何一次清 transform 都会把它推偏，
   * 因此这里把 left/transform 一起用 !important 覆盖成满屏定宽。
   */
  function styleOnce(){
    // 站点没有 viewport meta，WebView 会按 980px 布局视口渲染；
    // 补一个就能让 innerWidth 等于屏幕宽，100vw 的铺满才真正等于屏幕。
    if (!document.querySelector('meta[name="viewport"]')){
      var m = document.createElement('meta');
      m.name = 'viewport';
      m.content = 'width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';
      (document.head || document.documentElement).appendChild(m);
    }
    if (document.getElementById('jz-fill-style')) return;
    var st = document.createElement('style');
    st.id = 'jz-fill-style';
    st.textContent = [
      'html,body{overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important;}',
      '.Live_show,.CVideo,#DivVideo,.player,.player-container,.coordinator{',
      '  width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;',
      '  margin:0!important;padding:0!important;transform:none!important;overflow:visible!important;}',
      '#player{position:fixed!important;top:0!important;left:0!important;right:auto!important;bottom:auto!important;',
      '  width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;',
      '  transform:none!important;background:#000!important;z-index:2147483646!important;overflow:visible!important;}',
      '#dhc-video,video{position:fixed!important;top:0!important;left:0!important;right:auto!important;bottom:auto!important;',
      '  width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;',
      '  transform:none!important;max-width:none!important;max-height:none!important;',
      '  object-fit:contain!important;object-position:center center!important;background:#000!important;}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(st);
  }

  function fill(){
    var v = mainVideo();
    if (!v) return false;
    styleOnce();
    // 清掉会改变 fixed 参照系的 transform/filter，以及把画面推偏的左右内边距
    var el = v.parentElement, n = 0;
    while (el && el !== document.documentElement && n++ < 12){
      try {
        var cs = getComputedStyle(el);
        if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform','none','important');
        if (cs.filter && cs.filter !== 'none') el.style.setProperty('filter','none','important');
        if (parseFloat(cs.paddingLeft) > 0 || parseFloat(cs.paddingRight) > 0){
          el.style.setProperty('padding-left','0','important');
          el.style.setProperty('padding-right','0','important');
        }
      } catch(e){}
      el = el.parentElement;
    }
    v.removeAttribute('controls');
    v.controls = false;
    return true;
  }

  function sound(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
  }

  var clicked = false, notified = false;

  function tick(){
    var v = mainVideo();
    if (!v) return;
    fill();
    sound(v);
    if (v.paused || v.readyState < 2){
      try { v.play(); } catch(e){}
      if (!clicked){
        clicked = true;
        var b = document.querySelector('.xbtn-playback');
        if (b) { try { b.click(); } catch(e){} }
        else clickAt(window.innerWidth / 2, window.innerHeight / 3);
      }
      return;
    }
    if (!notified){
      notified = true;
      try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
    }
  }

  tick();
  setInterval(tick, 700);
  try { new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true}); } catch(e){}
  try { if (window.androidHost) window.androidHost.onDebug('gxtv ready'); } catch(e){}
  return 'ok';
})();
"""

/**
 * 海南网络广播电视台（www.hnntv.cn/live.html?playType=livePlay&channelId=*）：
 * 每个频道一个 channelId，页面按参数直接建播放器，不需要模拟换台。
 *
 * 站点是固定 1200px 的桌面布局，播放器链路
 *   .player-content > .player-box > .video-box > #myPlayer.video-js(vjs-fluid) > video#myPlayer_html5_api
 * 在手机视口下整条链路被压缩、四周大量留白。这里对齐广西台/河南台的做法：
 * 把播放器容器整块 fixed 铺满 100vw x 100vh，video 用 object-fit:contain 保比例；
 * 页面其余部分（导航、频道条、节目单、页脚）隐藏并锁定滚动，避免抢占画面。
 */
private const val HNNT_JS = """
(function(){
  if (window.__hnHooked) return 'ok';
  window.__hnHooked = true;

  function host(m){
    try { if (window.androidHost) window.androidHost.onDebug('hnnt ' + m); } catch(e){}
  }

  /** 站点没有 viewport meta，补一个，让 100vw 真正等于屏幕宽。 */
  function ensureViewport(){
    var m = document.querySelector('meta[name="viewport"]');
    if (!m){
      m = document.createElement('meta');
      m.setAttribute('name','viewport');
      (document.head || document.documentElement).appendChild(m);
    }
    var want = 'width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';
    if (m.getAttribute('content') !== want) m.setAttribute('content', want);
  }

  function mainVideo(){
    var v = document.querySelector('#myPlayer_html5_api') ||
            document.querySelector('#myPlayer video') ||
            document.querySelector('video');
    if (!v) return null;
    var r = v.getBoundingClientRect();
    return r.width > 40 ? v : null;
  }

  /** 播放器最外层容器（video.js 的 .video-js）。 */
  function playerBox(v){
    try { return v.closest('.video-js') || v.closest('#myPlayer') || v.parentElement || v; }
    catch(e) { return v.parentElement || v; }
  }

  function styleOnce(){
    if (document.getElementById('__hnStyle')) return;
    var st = document.createElement('style');
    st.id = '__hnStyle';
    st.textContent = [
      'html,body{overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important;}',
      // vjs-fluid 默认靠 padding-top:56.25% 撑比例，铺满后必须归零：
      // 否则 padding 会叠加在 100vh 之外，绝对定位的 video 被挤出可视区
      // —— 表现正是「有声音、画面全黑」。
      '#myPlayer,.video-js{position:fixed!important;left:0!important;top:0!important;',
      '  right:auto!important;bottom:auto!important;width:100vw!important;height:100vh!important;',
      '  max-width:none!important;max-height:none!important;padding:0!important;margin:0!important;',
      '  transform:none!important;overflow:hidden!important;background:#000!important;',
      '  z-index:2147483640!important;}',
      '#myPlayer_html5_api,.video-js .vjs-tech{position:absolute!important;left:0!important;top:0!important;',
      '  width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;',
      '  object-fit:contain!important;object-position:center center!important;background:#000!important;}',
      '.player-content,.player-box,.video-box{width:100%!important;max-width:none!important;',
      '  height:auto!important;padding:0!important;margin:0!important;transform:none!important;}',
      // 底部控制条（LIVE/音量/全屏）与首帧大按钮、字幕层一律隐藏，画面纯净铺满
      '.vjs-control-bar,.vjs-big-play-button,.vjs-loading-spinner,',
      '.vjs-text-track-display,.vjs-poster,.vjs-controls-disabled{display:none!important;}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(st);
  }

  /** fixed 的参照系会被祖先 transform/filter/perspective 改写，整条链清掉。 */
  function clearChain(box){
    var el = box.parentElement, guard = 0;
    while (el && el !== document.documentElement && guard++ < 14){
      try {
        var cs = getComputedStyle(el);
        if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform','none','important');
        if (cs.filter && cs.filter !== 'none') el.style.setProperty('filter','none','important');
        if (cs.perspective && cs.perspective !== 'none') el.style.setProperty('perspective','none','important');
        if (cs.overflow !== 'visible') el.style.setProperty('overflow','visible','important');
        if (parseFloat(cs.paddingLeft) > 0) el.style.setProperty('padding-left','0','important');
        if (parseFloat(cs.paddingRight) > 0) el.style.setProperty('padding-right','0','important');
      } catch(e){}
      el = el.parentElement;
    }
  }

  /** 页面其余内容让位：只保留播放器所在的那条祖先链。 */
  function hideOthers(box){
    try {
      var body = document.body;
      if (!body) return;
      var chain = [], el = box;
      while (el){ chain.push(el); el = el.parentElement; }
      var kids = body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i], tn = k.tagName;
        if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
        if (chain.indexOf(k) >= 0) continue;
        k.style.setProperty('display','none','important');
      }
      // 播放器常被包在 .player > main 这类外层里，只隐藏 body 直接子级不够：
      // 沿祖先链逐层把「不是下一级的兄弟」也隐藏，header/footer 才不会留在画面上下。
      for (var j = 0; j < chain.length - 1; j++){
        var cur = chain[j], up = chain[j + 1];
        if (!up || !up.children) continue;
        for (var q = 0; q < up.children.length; q++){
          var s = up.children[q], stn = s.tagName;
          if (stn === 'SCRIPT' || stn === 'STYLE' || stn === 'LINK') continue;
          if (s === cur) continue;
          s.style.setProperty('display','none','important');
        }
      }
    } catch(e){}
  }

  function sound(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
  }

  function playOnce(v){
    if (!v.paused) return;
    var p = v.play();
    if (p && p.catch) p.catch(function(){
      v.muted = true;
      try { v.play(); } catch(e){}
    });
  }

  var fitted = false, notified = false;

  function tick(){
    var v = mainVideo();
    if (!v) return;
    ensureViewport();
    styleOnce();
    var box = playerBox(v);
    clearChain(box);
    hideOthers(box);
    sound(v);
    if (!fitted){
      fitted = true;
      var r = box.getBoundingClientRect();
      host('fit ' + Math.round(r.width) + 'x' + Math.round(r.height));
    }
    playOnce(v);
    if (!notified && !v.paused && v.readyState >= 2){
      notified = true;
      host('playing ' + v.videoWidth + 'x' + v.videoHeight);
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', tick);
  tick();
  setInterval(tick, 700);
  window.addEventListener('resize', tick);
  try { new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true}); } catch(e){}

  host('ready');
  return 'ok';
})();
"""
/**
 * 河北网络广播电视台（www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=*）：
 * 单页 7 个频道，靠 index 参数（0~6）换台，不需要模拟点击。
 * 站点播放器是 cmcPlayer（腾讯云 TGP SDK，MSE blob 流），链路
 *   #playercontainer(1200 定宽) > #cmc_playercontainer > .cmc-player
 *     > .player-content > .cmc-video > video#cmc_player_video
 * 桌面布局下播放器只有 1000x562 且偏右，手机视口里四周大片留白。
 * 与广西台/海南台同一套做法：把 #cmc_playercontainer 整块 fixed 铺满视口，
 * video 用 object-fit:contain 保比例，页面其余内容隐藏并锁滚动。
 * 注意不能对 video.js/cmc 的容器写 padding-top 撑比例 —— 铺满后与 100vh 叠加
 * 会把画面挤出可视区（海南台踩过这个坑：有声音、全黑）。
 */
private const val HEBEI_JS = """
(function(){
  if (window.__hebHook) return 'ok';
  window.__hebHook = true;

  function host(m){
    try { if (window.androidHost) window.androidHost.onDebug('hebei ' + m); } catch(e){}
  }

  /** 站点没有移动端 viewport meta，补齐，让 100vw 真正等于屏幕宽。 */
  function ensureViewport(){
    var m = document.querySelector('meta[name="viewport"]');
    if (!m){
      m = document.createElement('meta');
      m.setAttribute('name','viewport');
      (document.head || document.documentElement).appendChild(m);
    }
    var want = 'width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';
    if (m.getAttribute('content') !== want) m.setAttribute('content', want);
  }

  function mainVideo(){
    var v = document.getElementById('cmc_player_video') ||
            document.querySelector('#cmc_playercontainer video') ||
            document.querySelector('video');
    if (!v) return null;
    return v;
  }

  /** 铺满的锚点用 cmc 的播放器外壳，video 只在内部占 100%。 */
  function playerBox(){
    return document.getElementById('cmc_playercontainer') ||
           document.querySelector('.cmc-main') ||
           document.querySelector('.cmc-player');
  }

  function styleOnce(){
    if (document.getElementById('__hebStyle')) return;
    var st = document.createElement('style');
    st.id = '__hebStyle';
    st.textContent = [
      'html,body{overflow:hidden!important;margin:0!important;padding:0!important;',
      '  background:#000!important;height:100%!important;}',
      '#cmc_playercontainer{position:fixed!important;left:0!important;top:0!important;',
      '  right:auto!important;bottom:auto!important;width:100vw!important;height:100vh!important;',
      '  max-width:none!important;max-height:none!important;padding:0!important;margin:0!important;',
      '  transform:none!important;overflow:hidden!important;background:#000!important;',
      '  z-index:2147483640!important;}',
      '#cmc_playercontainer .cmc-base.cmc-player,',
      '#cmc_playercontainer .player-content,',
      '#cmc_playercontainer .cmc-base.cmc-video{position:absolute!important;',
      '  left:0!important;top:0!important;width:100%!important;height:100%!important;',
      '  max-width:none!important;max-height:none!important;padding:0!important;margin:0!important;}',
      /** cmc 会给 video 写死 1000x562 的行内尺寸，只改父层没用，直接把 video 钉在视口上。 */
      '#cmc_player_video{position:fixed!important;left:0!important;top:0!important;',
      '  width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;',
      '  object-fit:contain!important;object-position:center center!important;background:#000!important;',
      '  z-index:2147483630!important;}',
      /** 控制条原本吸附在 562 高的播放器里，铺满后会挡画面：直接永久隐藏。 */
      '#cmc_playercontainer .cmc-control,',
      '#cmc_playercontainer .cmc-control *,',
      '#cmc_playercontainer .control-bottom{display:none!important;',
      '  visibility:hidden!important;opacity:0!important;pointer-events:none!important;}',
      '#playercontainer{width:100%!important;max-width:none!important;padding:0!important;margin:0!important;}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(st);
  }

  /** fixed 的参照系会被祖先 transform/filter/perspective 改写，整条链清掉。 */
  function clearChain(box){
    var el = box.parentElement, guard = 0;
    while (el && el !== document.documentElement && guard++ < 14){
      try {
        var cs = getComputedStyle(el);
        if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform','none','important');
        if (cs.filter && cs.filter !== 'none') el.style.setProperty('filter','none','important');
        if (cs.perspective && cs.perspective !== 'none') el.style.setProperty('perspective','none','important');
        // contain:paint / content 会把 fixed 的参照系从视口改成该元素
        if (cs.contain && cs.contain !== 'none') el.style.setProperty('contain','none','important');
        if (parseFloat(cs.paddingLeft) > 0) el.style.setProperty('padding-left','0','important');
        if (parseFloat(cs.paddingRight) > 0) el.style.setProperty('padding-right','0','important');
      } catch(e){}
      el = el.parentElement;
    }
  }

  /** 页面其余内容让位：只保留播放器所在的那条祖先链。 */
  function hideOthers(box){
    try {
      var body = document.body;
      if (!body) return;
      var chain = [], el = box;
      while (el){ chain.push(el); el = el.parentElement; }
      var kids = body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i], tn = k.tagName;
        if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
        if (chain.indexOf(k) >= 0) continue;
        k.style.setProperty('display','none','important');
      }
    } catch(e){}
  }

  function playOnce(v){
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
    if (!v.paused) return;
    var p = v.play();
    if (p && p.catch) p.catch(function(){
      // 浏览器策略拦截有声起播时，先静音起播，画面出来再恢复音量
      v.muted = true;
      try { v.play(); } catch(e){}
    });
  }

  var fitted = false, notified = false;

  function tick(){
    var v = mainVideo();
    var box = playerBox();
    if (!v || !box) return;
    ensureViewport();
    styleOnce();
    clearChain(box);
    hideOthers(box);
    playOnce(v);
    if (!fitted){
      fitted = true;
      var r = box.getBoundingClientRect();
      host('fit ' + Math.round(r.width) + 'x' + Math.round(r.height));
    }
    if (!notified && !v.paused && v.readyState >= 2){
      notified = true;
      host('playing ' + v.videoWidth + 'x' + v.videoHeight);
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', tick);
  tick();
  setInterval(tick, 700);
  window.addEventListener('resize', tick);
  try { new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true}); } catch(e){}

  host('ready');
  return 'ok';
})();
"""
/** 齐鲁网直播（v.iqilu.com/live）：腾讯云 tcplayer，容器 .player-container。 */
private const val QILU_JS = """
(function(){
  if (window.__qiluFit) return 'ok';
  window.__qiluFit = true;

  var fitted = false, notified = false, lastW = 0, lastH = 0;

  function host(m){
    try { if (window.androidHost) window.androidHost.onDebug('qilu ' + m); } catch(e){}
  }

  function bigVideo(){
    var best = null, area = 0;
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++){
      var v = list[i], r = v.getBoundingClientRect();
      if (r.width < 80 || r.height < 45) continue;
      var a = r.width * r.height;
      if (a > area){ area = a; best = v; }
    }
    return best;
  }

  // tcplayer 基于 video.js：控制条在 .video-js 内部，
  // 所以铺满的必须是 .player-container 这一层，video 只做内部 100%。
  function playerBox(v){
    try {
      return v.closest('.player-container') || v.closest('.video-js') ||
             v.parentElement || v;
    } catch(e) { return v.parentElement || v; }
  }

  function fit(v){
    if (!v) return false;
    var b = playerBox(v);
    try {
      var el = b.parentElement, guard = 0;
      while (el && el !== document.documentElement && guard++ < 12){
        try {
          var cs = getComputedStyle(el);
          if (cs.overflow !== 'visible') el.style.setProperty('overflow', 'visible', 'important');
          if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform', 'none', 'important');
          if (cs.filter && cs.filter !== 'none') el.style.setProperty('filter', 'none', 'important');
          if (cs.clipPath && cs.clipPath !== 'none') el.style.setProperty('clip-path', 'none', 'important');
          if (cs.opacity && parseFloat(cs.opacity) < 0.99) el.style.setProperty('opacity', '1', 'important');
        } catch(e){}
        el = el.parentElement;
      }
      var s = b.style;
      s.setProperty('position', 'fixed', 'important');
      s.setProperty('left', '0', 'important');
      s.setProperty('top', '0', 'important');
      s.setProperty('right', '0', 'important');
      s.setProperty('bottom', '0', 'important');
      s.setProperty('width', '100vw', 'important');
      s.setProperty('height', '100vh', 'important');
      s.setProperty('max-width', 'none', 'important');
      s.setProperty('max-height', 'none', 'important');
      s.setProperty('min-width', '0', 'important');
      s.setProperty('min-height', '0', 'important');
      s.setProperty('margin', '0', 'important');
      s.setProperty('padding', '0', 'important');
      s.setProperty('float', 'none', 'important');
      s.setProperty('transform', 'none', 'important');
      s.setProperty('background', '#000', 'important');
      s.setProperty('z-index', '2147483000', 'important');
      var vs = v.style;
      vs.setProperty('width', '100%', 'important');
      vs.setProperty('height', '100%', 'important');
      vs.setProperty('max-width', 'none', 'important');
      vs.setProperty('max-height', 'none', 'important');
      vs.setProperty('object-fit', 'contain', 'important');
      vs.setProperty('background', '#000', 'important');
      lockPage();
      hideOthers(b);
      // 页面自带一层黑色遮罩 #mask，盖在播放器上会整体变暗
      var masks = document.querySelectorAll('#mask, .mask');
      for (var mi = 0; mi < masks.length; mi++){
        try { masks[mi].style.setProperty('display', 'none', 'important'); } catch(e){}
      }
      return true;
    } catch(e){ return false; }
  }

  function lockPage(){
    try {
      document.documentElement.style.setProperty('overflow', 'hidden', 'important');
      if (document.body){
        document.body.style.setProperty('overflow', 'hidden', 'important');
        document.body.style.setProperty('margin', '0', 'important');
      }
    } catch(e){}
  }

  function hideOthers(b){
    try {
      var body = document.body;
      if (!body) return;
      var chain = [], el = b;
      while (el){ chain.push(el); el = el.parentElement; }
      var kids = body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i];
        if (chain.indexOf(k) >= 0) continue;
        var tn = k.tagName;
        if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
        try { k.style.setProperty('display', 'none', 'important'); } catch(e){}
      }
    } catch(e){}
  }

  function play(v){
    try {
      if (v.muted) v.muted = false;
      if (v.volume < 0.99) v.volume = 1.0;
    } catch(e){}
    if (v.paused){
      try {
        var p = v.play();
        if (p && p.catch) p.catch(function(){});
      } catch(e){}
    }
  }

  function tick(){
    var v = bigVideo();
    if (!v) return;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    play(v);
    var w = window.innerWidth, h = window.innerHeight;
    if (!fitted || w !== lastW || h !== lastH){
      if (fit(v)){
        fitted = true;
        lastW = w; lastH = h;
        var rr = v.getBoundingClientRect();
        host('fit ' + w + 'x' + h + ' video=' + Math.round(rr.width) +
             'x' + Math.round(rr.height) + ' playing=' + !v.paused);
      }
    }
    if (!notified && !v.paused){
      notified = true;
      try { if (window.androidHost && window.androidHost.onPlaying) window.androidHost.onPlaying(); } catch(e){}
      host('playing->fullscreen');
    }
  }

  tick();
  try { new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true}); } catch(e){}
  setInterval(tick, 800);
  return 'ok';
})();
"""

/** 大象新闻（static.hntv.tv/kds）：xgplayer 专用脚本，整个播放器容器一起铺满。 */
private const val HNTV_JS = """
(function(){
  if (window.__hntvFit) return 'ok';
  window.__hntvFit = true;

  var fitted = false, notified = false, waited = 0, lastW = 0, lastH = 0;

  function host(m){
    try { if (window.androidHost) window.androidHost.onDebug('hntv ' + m); } catch(e){}
  }

  // 主文档 + 同源 iframe 里面积最大的 video
  function bigVideo(){
    var docs = [document];
    try {
      var fs = document.querySelectorAll('iframe');
      for (var i = 0; i < fs.length; i++){
        var d = null;
        try { d = fs[i].contentDocument; } catch(e){}
        if (d) docs.push(d);
      }
    } catch(e){}
    var best = null, area = 0;
    for (var k = 0; k < docs.length; k++){
      var list = null;
      try { list = docs[k].querySelectorAll('video'); } catch(e){ continue; }
      for (var j = 0; j < list.length; j++){
        var v = list[j], r = v.getBoundingClientRect();
        if (r.width < 80 || r.height < 45) continue;
        var a = r.width * r.height;
        if (a > area){ area = a; best = v; }
      }
    }
    return best;
  }

  // xgplayer 的 video 只是容器里的一层：控制条、弹层都在容器内，
  // 只把 video 拉成全屏会让控制条留在原处，所以整块容器一起铺。
  function playerBox(v){
    try { return v.closest('.xgplayer') || v.parentElement || v; }
    catch(e) { return v.parentElement || v; }
  }

  function fit(v){
    if (!v) return false;
    var b = playerBox(v);
    try {
      // 祖先链上的 transform / clip / overflow 会让 fixed 失效
      var el = b.parentElement, guard = 0;
      while (el && el !== document.documentElement && guard++ < 12){
        try {
          var cs = getComputedStyle(el);
          if (cs.overflow !== 'visible') el.style.setProperty('overflow', 'visible', 'important');
          if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform', 'none', 'important');
          if (cs.filter && cs.filter !== 'none') el.style.setProperty('filter', 'none', 'important');
          if (cs.clipPath && cs.clipPath !== 'none') el.style.setProperty('clip-path', 'none', 'important');
          if (cs.opacity && parseFloat(cs.opacity) < 0.99) el.style.setProperty('opacity', '1', 'important');
        } catch(e){}
        el = el.parentElement;
      }
      var s = b.style;
      s.setProperty('position', 'fixed', 'important');
      s.setProperty('left', '0', 'important');
      s.setProperty('top', '0', 'important');
      s.setProperty('right', '0', 'important');
      s.setProperty('bottom', '0', 'important');
      s.setProperty('width', '100vw', 'important');
      s.setProperty('height', '100vh', 'important');
      s.setProperty('max-width', 'none', 'important');
      s.setProperty('max-height', 'none', 'important');
      s.setProperty('min-width', '0', 'important');
      s.setProperty('min-height', '0', 'important');
      s.setProperty('margin', '0', 'important');
      s.setProperty('transform', 'none', 'important');
      s.setProperty('background', '#000', 'important');
      s.setProperty('z-index', '2147483000', 'important');
      var vs = v.style;
      vs.setProperty('width', '100%', 'important');
      vs.setProperty('height', '100%', 'important');
      vs.setProperty('max-width', 'none', 'important');
      vs.setProperty('max-height', 'none', 'important');
      vs.setProperty('object-fit', 'contain', 'important');
      vs.setProperty('background', '#000', 'important');
      lockPage();
      hideOthers(v);
      return true;
    } catch(e){ return false; }
  }

  // 页面别再滚：固定后底下那条长内容不该跟着动
  function lockPage(){
    try {
      document.documentElement.style.setProperty('overflow', 'hidden', 'important');
      if (document.body){
        document.body.style.setProperty('overflow', 'hidden', 'important');
        document.body.style.setProperty('margin', '0', 'important');
      }
    } catch(e){}
  }

  // 顶部导航、底部「下载大象新闻APP」横幅等全部让位
  function hideOthers(v){
    try {
      var body = document.body;
      if (!body) return;
      var chain = [], el = v;
      while (el){ chain.push(el); el = el.parentElement; }
      var kids = body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i];
        if (chain.indexOf(k) >= 0) continue;
        var tn = k.tagName;
        if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
        try { k.style.setProperty('display', 'none', 'important'); } catch(e){}
      }
      // 固定在视口上的浮层（悬浮按钮之类）也压掉
      var fl = document.querySelectorAll('body *');
      for (var j = 0; j < fl.length && j < 400; j++){
        var e = fl[j];
        if (e === v || e.contains(v) || chain.indexOf(e) >= 0) continue;
        try {
          var cs = getComputedStyle(e);
          if (cs.position !== 'fixed') continue;
          var r = e.getBoundingClientRect();
          if (r.width > 60 && r.height > 20 && e.id !== '__hideNativeCtl'){
            e.style.setProperty('display', 'none', 'important');
          }
        } catch(e2){}
      }
    } catch(e){}
  }

  function play(v){
    try {
      if (v.muted) v.muted = false;
      if (v.volume < 0.99) v.volume = 1.0;
    } catch(e){}
    if (v.paused){
      try {
        var p = v.play();
        if (p && p.catch) p.catch(function(){});
      } catch(e){}
    }
  }

  // xgplayer 初始带 xgplayer-nostart，需一次真实手势才起播
  function humanClick(el){
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
      return true;
    } catch(e){ return false; }
  }

  function tick(){
    var v = bigVideo();
    if (!v){
      waited++;
      return;
    }
    waited = 0;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    play(v);
    if (v.paused && !v.__hntvTapped){
      v.__hntvTapped = true;
      humanClick(v);
    }
    var w = window.innerWidth, h = window.innerHeight;
    if (!fitted || w !== lastW || h !== lastH){
      if (fit(v)){
        fitted = true;
        lastW = w; lastH = h;
        var rr = v.getBoundingClientRect();
        host('fit ' + w + 'x' + h + ' video=' + Math.round(rr.width) +
             'x' + Math.round(rr.height) + ' playing=' + !v.paused);
      }
    }
    if (!notified && !v.paused){
      notified = true;
      try { if (window.androidHost && window.androidHost.onPlaying) window.androidHost.onPlaying(); } catch(e){}
      host('playing->fullscreen');
    }
  }

  tick();
  try { new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true}); } catch(e){}
  setInterval(tick, 800);
  host('script ready, videos=' + document.querySelectorAll('video').length);
  return 'ok';
})();
"""

/** 央视网（tv.cctv.com）：直播页播放器在 #player / .video 容器里，直接铺满视口。 */
private const val CCTV_JS = """
(function(){
  if (window.__cctvFit) return 'ok';
  window.__cctvFit = true;

  var lastW = 0, lastH = 0, notified = false, waited = 0;

  function host(msg){
    try { if (window.androidHost && window.androidHost.onDebug) window.androidHost.onDebug('cctv ' + msg); } catch(e){}
  }

  // 主文档 + 同源 iframe 里最大的 video
  function bigVideo(){
    var docs = [document];
    try {
      var fs = document.querySelectorAll('iframe');
      for (var i = 0; i < fs.length; i++){
        var d = null;
        try { d = fs[i].contentDocument; } catch(e){}
        if (d) docs.push(d);
      }
    } catch(e){}
    var best = null, area = 0;
    for (var k = 0; k < docs.length; k++){
      var list = null;
      try { list = docs[k].querySelectorAll('video'); } catch(e){ continue; }
      for (var j = 0; j < list.length; j++){
        var v = list[j], r = v.getBoundingClientRect();
        if (r.width < 80 || r.height < 45) continue;
        var a = r.width * r.height;
        if (a > area){ area = a; best = v; }
      }
    }
    return best;
  }

  function fit(v){
    if (!v) return false;
    try {
      var s = v.style;
      s.setProperty('position', 'fixed', 'important');
      s.setProperty('left', '0', 'important');
      s.setProperty('top', '0', 'important');
      s.setProperty('right', 'auto', 'important');
      s.setProperty('bottom', 'auto', 'important');
      s.setProperty('width', '100vw', 'important');
      s.setProperty('height', '100vh', 'important');
      s.setProperty('max-width', 'none', 'important');
      s.setProperty('max-height', 'none', 'important');
      s.setProperty('min-width', '0', 'important');
      s.setProperty('min-height', '0', 'important');
      s.setProperty('object-fit', 'contain', 'important');
      s.setProperty('z-index', '2147483000', 'important');
      s.setProperty('background', '#000', 'important');
      s.setProperty('transform', 'none', 'important');
      var el = v.parentElement, guard = 0;
      while (el && el !== document.body && el !== document.documentElement && guard < 10){
        try {
          var cs = getComputedStyle(el);
          if (cs.overflow === 'hidden' || cs.overflowX === 'hidden' || cs.overflowY === 'hidden'){
            el.style.setProperty('overflow', 'visible', 'important');
          }
          if (cs.transform && cs.transform !== 'none') el.style.setProperty('transform', 'none', 'important');
          if (cs.clipPath && cs.clipPath !== 'none') el.style.setProperty('clip-path', 'none', 'important');
        } catch(e){}
        el = el.parentElement; guard++;
      }
      // 页面常有的遮罩层（播放器上方拦截触摸的浮层）挡不住 fixed 元素，但保险起见压掉
      try {
        var masks = document.querySelectorAll('[class*="cover" i],[class*="mask" i]');
        for (var m = 0; m < masks.length && m < 6; m++){
          var rm = masks[m].getBoundingClientRect();
          if (rm.width > 200 && rm.height > 120 && rm.top < 120){
            masks[m].style.setProperty('pointer-events', 'none', 'important');
          }
        }
      } catch(e){}
      hideOthers(v);
      return true;
    } catch(e){ return false; }
  }

  function play(v){
    if (!v) return;
    try {
      if (v.muted) v.muted = false;
      if (v.volume < 0.99) v.volume = 1.0;
    } catch(e){}
    if (v.paused){
      try {
        var p = v.play();
        if (p && p.catch) p.catch(function(){});
      } catch(e){}
    }
  }

  // 只留播放器：把 video 祖先链之外的页面块全部隐藏（央视顶部导航会压在视频上）
  function hideOthers(v){
    try {
      var doc = v.ownerDocument || document;
      var body = doc.body;
      if (!body) return;
      var chain = [], el = v;
      while (el){ chain.push(el); el = el.parentElement; }
      var kids = body.children;
      for (var i = 0; i < kids.length; i++){
        var k = kids[i];
        if (chain.indexOf(k) >= 0) continue;
        var tn = k.tagName;
        if (tn === 'SCRIPT' || tn === 'STYLE' || tn === 'LINK') continue;
        try { k.style.setProperty('display', 'none', 'important'); } catch(e){}
      }
      // body 自身别被限高/裁剪
      try {
        body.style.setProperty('overflow', 'hidden', 'important');
        body.style.setProperty('margin', '0', 'important');
        body.style.setProperty('padding', '0', 'important');
      } catch(e){}
    } catch(e){}
  }

  // 央视偶有「点击播放」按钮（poster 遮罩），点击后才有 video 起播
  function clickPlay(){
    var sels = ['.video_btn', '.play-btn', '.playBtn', '.cctv-play', '[class*="play" i][class*="btn" i]'];
    for (var i = 0; i < sels.length; i++){
      var ns = null;
      try { ns = document.querySelectorAll(sels[i]); } catch(e){ continue; }
      for (var j = 0; j < ns.length; j++){
        var r = ns[j].getBoundingClientRect();
        if (r.width < 10 || r.width > 200 || r.height < 10 || r.height > 200) continue;
        try { ns[j].click(); host('clickPlay ' + sels[i]); return true; } catch(e){}
      }
    }
    return false;
  }

  function tick(){
    var v = bigVideo();
    if (!v){
      if (waited++ < 30) clickPlay();
      return;
    }
    waited = 0;
    play(v);
    var w = window.innerWidth, h = window.innerHeight;
    if ((w !== lastW || h !== lastH || !v.__cctvFitted) && fit(v)){
      v.__cctvFitted = true;
      lastW = w; lastH = h;
      var rr = v.getBoundingClientRect();
      host('fit ' + w + 'x' + h + ' video=' + Math.round(rr.width) +
           'x' + Math.round(rr.height) + ' playing=' + !v.paused);
    }
    if (!notified && !v.paused){
      notified = true;
      try { if (window.androidHost && window.androidHost.onPlaying) window.androidHost.onPlaying(); } catch(e){}
      host('playing->fullscreen');
    }
  }

  tick();
  var timer = setInterval(tick, 800);
  setInterval(function(){ var v = bigVideo(); if (v) play(v); }, 4000);
  setTimeout(function(){ clearInterval(timer); }, 240000);
  return 'ok';
})();
"""
private const val MGTV_JS_OLD = """
(function(){
  if (window.__mgtvHooked) return 'ok';
  window.__mgtvHooked = true;

  var TRIES = 0, DONE = false;

  // 主文档 + 同源 iframe 的所有文档（芒果TV 播放器常嵌在 iframe 里）
  function docs(){
    var out = [document];
    var fs = document.querySelectorAll('iframe');
    for (var i = 0; i < fs.length; i++){
      try { if (fs[i].contentDocument) out.push(fs[i].contentDocument); } catch(e){}
    }
    return out;
  }

  function bigVideo(){
    var ds = docs(), best = null, area = 0;
    for (var k = 0; k < ds.length; k++){
      var list;
      try { list = ds[k].querySelectorAll('video'); } catch(e){ continue; }
      for (var i = 0; i < list.length; i++){
        var v = list[i], r = v.getBoundingClientRect();
        if (r.width < 60 || r.height < 40) continue;
        var a = r.width * r.height;
        if (a > area) { area = a; best = v; }
      }
    }
    return best;
  }

  function isFull(){
    try {
      if (document.fullscreenElement || document.webkitFullscreenElement) return true;
      var v = bigVideo();
      if (v && (v.webkitDisplayingFullscreen || v.webkitEnterFullscreen && v.webkitDisplayingFullscreen)) return true;
    } catch(e){}
    return false;
  }

  // 找播放器自带的全屏键（只要真正的按钮节点）
  function findFull(scope){
    var root = scope || document;
    var sels = ['[class*="fullscreen" i]','[class*="fullScreen"]','[class*="full-screen" i]',
                '[class*="mgtv-full"]','[class*="quanping"]',
                '[aria-label*="全屏"]','[title*="全屏"]'];
    for (var s = 0; s < sels.length; s++){
      var nodes;
      try { nodes = root.querySelectorAll(sels[s]); } catch(e){ continue; }
      for (var i = 0; i < nodes.length; i++){
        var el = nodes[i], r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        if (r.width > 220 || r.height > 220) continue;
        if (el.children.length > 2) continue;
        var cs = null;
        try { cs = getComputedStyle(el); } catch(e){}
        if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) continue;
        return el;
      }
    }
    return null;
  }

  // 控制条平时隐藏：先发鼠标移动把它唤醒，全屏键才会出现
  function wake(){
    var v = bigVideo();
    var host = v ? (v.parentElement || v) : document.body;
    if (!host) return;
    var r = host.getBoundingClientRect();
    var opt = {bubbles:true, cancelable:true,
               clientX:r.left + r.width / 2, clientY:r.top + r.height / 2, view:window};
    try {
      host.dispatchEvent(new MouseEvent('mousemove', opt));
      if (v) v.dispatchEvent(new MouseEvent('mousemove', opt));
    } catch(e){}
  }

  function click(el){
    if (!el) return;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return;
    var win = el.ownerDocument && el.ownerDocument.defaultView ? el.ownerDocument.defaultView : window;
    var opt = {bubbles:true, cancelable:true,
               clientX:r.left + r.width / 2, clientY:r.top + r.height / 2, view:win};
    try {
      el.dispatchEvent(new (win.PointerEvent || win.MouseEvent)('pointerdown', opt));
      el.dispatchEvent(new win.MouseEvent('mousedown', opt));
      el.dispatchEvent(new (win.PointerEvent || win.MouseEvent)('pointerup', opt));
      el.dispatchEvent(new win.MouseEvent('mouseup', opt));
      el.dispatchEvent(new win.MouseEvent('click', opt));
    } catch(e){}
    try { el.click(); } catch(e){}
  }

  function locate(){
    var b = findFull();
    if (b) return b;
    // 播放器常在同源 iframe 里
    var fs = document.querySelectorAll('iframe');
    for (var i = 0; i < fs.length; i++){
      try {
        var d = fs[i].contentDocument;
        if (!d) continue;
        var bb = findFull(d);
        if (bb) return bb;
      } catch(e){}
    }
    return null;
  }

  // 诊断：找不到按钮时把页面上所有「像全屏键」的元素打到日志
  function dump(){
    try {
      var out = [];
      var ds = docs();
      var vcount = 0, icount = 0;
      for (var k = 0; k < ds.length; k++){
        try { vcount += ds[k].querySelectorAll('video').length; } catch(e){}
      }
      icount = document.querySelectorAll('iframe').length;
      out.push('docs=' + ds.length + ' videos=' + vcount + ' iframes=' + icount);
      var hits = [];
      for (var k2 = 0; k2 < ds.length && hits.length < 16; k2++){
        var all;
        try { all = ds[k2].querySelectorAll('*'); } catch(e){ continue; }
        for (var i = 0; i < all.length && hits.length < 16; i++){
          var el = all[i];
          var c = (el.className && el.className.toString) ? el.className.toString() : '';
          var t = '';
          try { t = (el.getAttribute('title') || '') + '/' + (el.getAttribute('aria-label') || ''); } catch(e){}
          var s = (c + ' ' + t).toLowerCase();
          if (s.indexOf('full') < 0 && s.indexOf('quan') < 0 && s.indexOf('全屏') < 0) continue;
          var r = el.getBoundingClientRect();
          hits.push('d' + k2 + ':' + el.tagName + '[' + c.slice(0, 36) + ']' + t +
                    Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
        }
      }
      var vb = bigVideo();
      var s2 = 'nofull';
      if (vb) {
        var vr = vb.getBoundingClientRect();
        s2 = 'vb=' + Math.round(vr.width) + 'x' + Math.round(vr.height);
      }
      out.push(s2);
      out.push('full=' + hits.join(' ;; '));
      // 补充：视频区域附近的小尺寸可点元素（全屏键的真实所在）
      try {
        var vb2 = bigVideo();
        var sm = [];
        if (vb2){
          var box = vb2.getBoundingClientRect();
          var all2 = document.querySelectorAll('div,span,i,a,button');
          for (var j = 0; j < all2.length && sm.length < 18; j++){
            var e2 = all2[j], r2 = e2.getBoundingClientRect();
            if (r2.width < 12 || r2.width > 90 || r2.height < 12 || r2.height > 90) continue;
            if (r2.top < box.top || r2.left < box.left) continue;
            if (r2.bottom > box.bottom + 40) continue;
            var c2 = '';
            try { c2 = (e2.className && e2.className.toString) ? e2.className.toString() : ''; } catch(e){}
            sm.push(e2.tagName + '[' + c2.slice(0, 28) + ']' +
                    Math.round(r2.left) + ',' + Math.round(r2.top) + ' ' +
                    Math.round(r2.width) + 'x' + Math.round(r2.height));
          }
        }
        out.push('small=' + sm.join(' ;; '));
      } catch(e){}
      if (window.androidHost) window.androidHost.onDebug('mgtv dump ' + out.join(' | '));
    } catch(e){}
  }

  // 全屏：唤醒控制条 → 找站点自带的全屏键 → 用宿主真实触摸点击（最多 3 次）
  function press(){
    if (DONE) return;
    if (isFull()){ DONE = true; return; }
    if (TRIES >= 3) return;
    var v = bigVideo();
    if (!v) return;
    if (v.paused) { try { v.play(); } catch(e){} }
    wake();
    var b = locate();
    if (!b){
      if (!window.__mgtvDumped){ window.__mgtvDumped = true; dump(); }
      return;
    }
    var r = b.getBoundingClientRect();
    // 只点真正的按钮，绝不点覆盖整块的蒙层
    if (r.width > 90 || r.height > 90) return;
    TRIES++;
    var cx = Math.round(r.left + r.width / 2), cy = Math.round(r.top + r.height / 2);
    var host = false;
    try {
      if (window.androidHost && window.androidHost.tapAt){ window.androidHost.tapAt(cx, cy); host = true; }
    } catch(e){}
    if (!host) click(b);
    try {
      if (window.androidHost) {
        window.androidHost.onDebug('mgtv full tap #' + TRIES + ' ' + cx + ',' + cy +
          ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' host=' + host + ' inFull=' + isFull());
      }
    } catch(e){}
    setTimeout(function(){
      if (isFull()) { DONE = true;
        try { if (window.androidHost) window.androidHost.onDebug('mgtv FULL OK'); } catch(e){}
      }
    }, 700);
  }

  // 用户真实触摸时补一次，仍受 3 次上限约束
  function arm(){
    try {
      if (document.__mgtvArmed) return;
      document.__mgtvArmed = true;
      document.addEventListener('touchend', function(){
        if (isFull()) { DONE = true; return; }
        if (TRIES < 3) press();
      }, true);
    } catch(e){}
  }

  function tick(){
    press();
    arm();
    var v = bigVideo();
    if (v){
      if (v.muted) v.muted = false;
      try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
      if (v.paused) { try { v.play(); } catch(e){} }
    }
  }

  tick();
  var timer = setInterval(tick, 700);
  setTimeout(function(){ clearInterval(timer); }, 120000);
  return 'ok';
})();
"""

/** 芒果TV 总脚本：先按 ch=/cat= 切台，再做播放适配（切台会重建 video，靠适配脚本轮询重新接管）。 */
private const val MGTV_FULL_JS = MGTV_SWITCH_JS + "\n" + MGTV_JS

private const val BILI_JS = """
(function(){
  if (window.__biliHooked) {
    try { if (window.__biliKick) window.__biliKick(); } catch(e){}
    return 'ok';
  }
  window.__biliHooked = true;

  var tries = 0;

  function playing(){
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++){
      if (!vs[i].paused && vs[i].readyState >= 2) return true;
    }
    return false;
  }

  function mainVideo(){
    var best = null, area = 0;
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++){
      var r = vs[i].getBoundingClientRect();
      if (r.width < 80 || r.height < 45) continue;
      var a = r.width * r.height;
      if (a > area){ area = a; best = vs[i]; }
    }
    return best;
  }

  function tap(el){
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;
    var opt = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
      return true;
    } catch(e){ return false; }
  }

  /* B站大播放键是叠在画布中央的遮罩，直接按坐标取该点上的元素再点 */
  function tapCenter(v){
    var r = v.getBoundingClientRect();
    var el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    if (el && el !== v) tap(el);
    if (v.paused) tap(v);
  }

  function kick(){
    var v = mainVideo();
    if (!v) return false;
    if (!v.paused && v.readyState >= 2) return true;
    /* 先静音起播：WebView 的自动播放策略对有声播放更严格，起播后立刻恢复音量 */
    var wasMuted = v.muted;
    try {
      if (v.paused){
        v.muted = true;
        var p = v.play();
        if (p && p.then) p.then(function(){ v.muted = wasMuted; }).catch(function(){});
      }
    } catch(e){}
    if (v.paused) tapCenter(v);
    return false;
  }

  /* 关掉「画面异常 / 播放器日志」面板和「请前往APP观看」弹窗：按可见文字找按钮 */
  var DISMISSED = false;
  function dismiss(){
    var words = ['关闭', '取消', '我知道了', '知道了'];
    var hit = null;
    var nodes = document.querySelectorAll('button,div,span,a,p');
    for (var i = 0; i < nodes.length && !hit; i++){
      var el = nodes[i];
      var t = (el.textContent || '').trim();
      if (!t || t.length > 6) continue;
      for (var w = 0; w < words.length; w++){
        if (t === words[w]) {
          var r = el.getBoundingClientRect();
          if (r.width < 8 || r.height < 8) continue;
          hit = el;
          break;
        }
      }
    }
    if (!hit) return false;
    tap(hit);
    /* 面板关掉后焦点可能仍在遮罩上：再点一次画面中心把控件收起来 */
    setTimeout(function(){
      var v = mainVideo();
      if (v) tapCenter(v);
    }, 300);
    return true;
  }

  function done(){
    try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
  }

  function loop(){
    dismiss();
    if (playing()){ done(); return; }
    kick();
    if (++tries < 40) setTimeout(loop, 500);
  }

  loop();

  /* 面板可能在播放后才弹出：长时间低频巡检，见到就关 */
  setInterval(dismiss, 1500);

  /* 播放器常被 SPA 路由重建：DOM 变化后补触发，节流避免与站点互相刷 */
  var last = 0;
  try {
    new MutationObserver(function(){
      if (playing()){ done(); return; }
      var now = Date.now();
      if (now - last < 300) return;
      last = now;
      if (tries < 40) tries++;
      kick();
    }).observe(document.body || document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  return 'ok';
})();
"""

private const val UNMUTE_JS = """
(function(){
  var list = document.querySelectorAll('video');
  for (var i = 0; i < list.length; i++){
    var v = list[i];
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
    if (v.paused) { try { v.play(); } catch(e){} }
  }
  return 'ok';
})();
"""


/**
 * 央视频（yangshipin.cn）：CCTV 全组 + 「央视频」分组的 33 个卫视都走这里。
 * 站点自带的全屏键是自定义实现，藏在鼠标悬停才出现的控制条里，且不调用 requestFullscreen ——
 * 脚本里点它没有任何反应（实测 __calls 为空）。所以这里不点按钮：
 * 直接把 video 固定到视口铺满，清掉祖先链上会改写 fixed 参照系的 transform/overflow，
 * 再盖住 poster、控制条等兄弟覆盖层，最后由 onPlaying() 让原生进页面全屏。
 */
private const val YANG_JS = """
(function(){
  if (window.__yangFit) return 'ok';
  window.__yangFit = true;

  // 选可见面积最大的 video（央视频直播页只挂一个主画面）
  function bigVideo(){
    var best = null, area = 0;
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++){
      var v = list[i];
      var r = v.getBoundingClientRect();
      if (r.width < 80 || r.height < 45) continue;
      var a = r.width * r.height;
      if (a > area){ area = a; best = v; }
    }
    return best;
  }

  // 祖先链上的 transform / overflow:hidden 会改写 fixed 的参照系，导致画面滑出屏幕
  function clearAncestors(v){
    var p = v.parentElement;
    while (p && p !== document.body){
      try {
        var cs = getComputedStyle(p);
        if (cs.transform && cs.transform !== 'none') p.style.setProperty('transform','none','important');
        if (cs.filter && cs.filter !== 'none') p.style.setProperty('filter','none','important');
        if (cs.clipPath && cs.clipPath !== 'none') p.style.setProperty('clip-path','none','important');
        if (cs.contain && cs.contain !== 'none') p.style.setProperty('contain','none','important');
        if (cs.overflow && cs.overflow !== 'visible') p.style.setProperty('overflow','visible','important');
      } catch(e){}
      p = p.parentElement;
    }
  }

  function fill(v){
    var s = v.style;
    s.setProperty('position','fixed','important');
    s.setProperty('left','0','important');
    s.setProperty('top','0','important');
    s.setProperty('right','auto','important');
    s.setProperty('bottom','auto','important');
    s.setProperty('width','100vw','important');
    s.setProperty('height','100vh','important');
    s.setProperty('max-width','none','important');
    s.setProperty('max-height','none','important');
    s.setProperty('margin','0','important');
    s.setProperty('transform','none','important');
    s.setProperty('object-fit','contain','important');
    s.setProperty('object-position','center center','important');
    s.setProperty('background','#000','important');
    s.setProperty('z-index','2147483647','important');
    try { v.setAttribute('playsinline',''); } catch(e){}
  }

  // poster / 控制条 / 状态提示都是 video 的兄弟覆盖层，铺满后会压在画面上
  function hideOverlays(v){
    var box = v.parentElement ? v.parentElement.parentElement : null;
    if (box){
      for (var i = 0; i < box.children.length; i++){
        var c = box.children[i];
        if (c === v || (c.contains && c.contains(v))) continue;
        try { c.style.setProperty('display','none','important'); } catch(e){}
      }
    }
    try { document.documentElement.style.setProperty('overflow','hidden','important'); } catch(e){}
    try { if (document.body) document.body.style.setProperty('overflow','hidden','important'); } catch(e){}
  }

  var notified = false;
  function notify(){
    if (notified) return;
    notified = true;
    try { if (window.androidHost) window.androidHost.onPlaying(); } catch(e){}
  }

  function tick(){
    var v = bigVideo();
    if (!v) return;
    // 只处理真正在播的画面，避免把封面/广告一起拉满
    if (v.paused || v.readyState < 2) return;
    clearAncestors(v);
    fill(v);
    hideOverlays(v);
    if (v.muted) v.muted = false;
    try { if (v.volume < 0.99) v.volume = 1.0; } catch(e){}
    notify();
  }

  tick();
  // 央视频是 SPA：切频道不重新加载页面，靠轮询把新 video 也铺满
  setInterval(tick, 700);
  try {
    new MutationObserver(tick).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  return 'ok';
})();
"""
