package com.llq.hello

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar

/**
 * 央视频「电视」页内置页：用 WebView 直接接入官方页面，
 * 并注入脚本把默认折叠的「卫视频道」组展开，左侧常驻频道栏。
 */
class YangshipinActivity : Activity() {

    companion object {
        private const val HOME = "https://www.yangshipin.cn/tv/home"

        /** 桌面 UA：PC 版才有左栏频道列表。 */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 展开卫视组：这些节点的类名是 ...-imgb，官方默认 display:none，
         * 只有点「更多」才显示。这里直接置块并放大字号，让频道栏可点。
         * 用定时重试是因为频道列表是接口异步渲染的，DOM 出现时间不固定。
         */
        private const val JS_EXPAND = """(function(){
  if(window.__yspInstall) return 'already';
  window.__yspInstall = 1;
  var SEL = '.tv-main-con-r-list-left-imgb';
  function css(){
    if(document.getElementById('ysp-style')) return;
    var st = document.createElement('style');
    st.id = 'ysp-style';
    st.textContent =
      SEL + '{display:block!important;height:auto!important;visibility:visible!important}' +
      '.tv-main-con-r-list-left{width:210px!important;min-width:210px!important;overflow-y:auto!important;overflow-x:hidden!important}' +
      '.tv-main-con-r-list-left-imga,.tv-main-con-r-list-left-imgb{font-size:15px!important;line-height:30px!important}';
    (document.head||document.documentElement).appendChild(st);
  }
  css();
  var t = setInterval(css, 500);
  setTimeout(function(){ clearInterval(t); }, 60000);
  setTimeout(function(){
    console.log('YSP_DIAG weishi=' + document.querySelectorAll(SEL).length +
      ' leftNodes=' + document.querySelectorAll('.tv-main-con-r-list-left').length +
      ' leftChildren=' + (function(){
        var p = document.querySelector('.tv-main-con-r-list-left');
        return p ? p.children.length + '/' + p.scrollHeight + '/' + p.clientHeight : 'none';
      })());
  }, 6000);
  return 'installed';
})()"""

        /**
         * 全屏规则（与 CCTV 组 YANG_JS 同一套）：
         * 把在播的 video 固定到视口铺满，清掉祖先链上会改写 fixed 参照系的属性，
         * 盖住 poster/控制条等覆盖层，再回调宿主进页面全屏。
         * 返回键时用 __yspPanel 把页面左栏频道列表抬到画面之上，画面保持全屏不动。
         */
        private const val JS_FILL = """(function(){
  if(window.__yspInstalled) return 'ok';
  window.__yspInstalled = 1;
  window.__yspOn = true;
  window.__yspPanelOn = false;

  function bigVideo(){
    var best=null, area=0, list=document.querySelectorAll('video');
    for(var i=0;i<list.length;i++){
      var v=list[i], r=v.getBoundingClientRect();
      if(r.width<80||r.height<45) continue;
      var a=r.width*r.height;
      if(a>area){area=a;best=v;}
    }
    return best;
  }

  // 祖先链上的 transform / overflow:hidden 会改写 fixed 的参照系，导致画面滑出屏幕
  function clearAncestors(v){
    var p=v.parentElement;
    while(p&&p!==document.body){
      try{
        var cs=getComputedStyle(p);
        if(cs.transform&&cs.transform!=='none')p.style.setProperty('transform','none','important');
        if(cs.filter&&cs.filter!=='none')p.style.setProperty('filter','none','important');
        if(cs.clipPath&&cs.clipPath!=='none')p.style.setProperty('clip-path','none','important');
        if(cs.contain&&cs.contain!=='none')p.style.setProperty('contain','none','important');
        if(cs.overflow&&cs.overflow!=='visible')p.style.setProperty('overflow','visible','important');
      }catch(e){}
      p=p.parentElement;
    }
  }

  function fill(v){
    var s=v.style;
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
    // 频道列表浮层打开时，让位给列表
    s.setProperty('z-index', window.__yspPanelOn?'2147483000':'2147483647','important');
    try{v.setAttribute('playsinline','');}catch(e){}
  }

  function hideOverlays(v){
    if(window.__yspPanelOn) return;
    var box=v.parentElement?v.parentElement.parentElement:null;
    if(box){
      for(var i=0;i<box.children.length;i++){
        var c=box.children[i];
        if(c===v||(c.contains&&c.contains(v))) continue;
        try{c.style.setProperty('display','none','important');}catch(e){}
      }
    }
    try{document.documentElement.style.setProperty('overflow','hidden','important');}catch(e){}
    try{if(document.body)document.body.style.setProperty('overflow','hidden','important');}catch(e){}
  }

  // 频道列表浮层：抬到画面之上，画面继续全屏播放，点频道即切换
  window.__yspPanel = function(on){
    var el=document.querySelector('.tv-main-con-r-list-left');
    if(!el) return 'none';
    window.__yspPanelOn=!!on;
    var keys=['position','left','top','bottom','width','max-width','z-index',
              'background','overflow-y','overflow-x','visibility','padding'];
    if(on){
      el.style.setProperty('position','fixed','important');
      el.style.setProperty('left','0','important');
      el.style.setProperty('top','0','important');
      el.style.setProperty('bottom','0','important');
      el.style.setProperty('width','62vw','important');
      el.style.setProperty('max-width','300px','important');
      el.style.setProperty('z-index','2147483647','important');
      el.style.setProperty('background','rgba(0,0,0,0.92)','important');
      el.style.setProperty('overflow-y','auto','important');
      el.style.setProperty('overflow-x','hidden','important');
      el.style.setProperty('visibility','visible','important');
      el.style.setProperty('display','block','important');
      el.style.setProperty('padding','12px 8px','important');
    }else{
      for(var i=0;i<keys.length;i++){ try{el.style.removeProperty(keys[i]);}catch(e){} }
    }
    return 'ok';
  };

  // 退出全屏：把 video 还原成网页内布局
  window.__yspUnfill = function(){
    window.__yspOn=false;
    window.__yspPanelOn=false;
    try{ window.__yspPanel(false); }catch(e){}
    var vs=document.querySelectorAll('video');
    for(var i=0;i<vs.length;i++){
      var s=vs[i].style;
      ['position','left','top','right','bottom','width','height','max-width',
       'max-height','margin','transform','object-fit','object-position',
       'background','z-index'].forEach(function(k){ try{s.removeProperty(k);}catch(e){} });
    }
    try{document.documentElement.style.removeProperty('overflow');}catch(e){}
    try{if(document.body)document.body.style.removeProperty('overflow');}catch(e){}
    return 'ok';
  };

  // 点画面后重新进入全屏
  window.__yspResume = function(){
    window.__yspOn=true;
    try{ window.__yspTick(); }catch(e){}
    return 'ok';
  };

  var notified=false;
  function notify(){
    if(notified) return;
    try{ if(window.androidHost&&window.androidHost.isUserExited()==='1') return; }catch(e){}
    notified=true;
    try{ if(window.androidHost) window.androidHost.onPlaying(); }catch(e){}
  }

  window.__yspTick = function(){
    if(window.__yspOn===false) return;
    var v=bigVideo();
    if(!v) return;
    if(v.paused||v.readyState<2) return;
    clearAncestors(v);
    fill(v);
    hideOverlays(v);
    if(v.muted) v.muted=false;
    try{ if(v.volume<0.99) v.volume=1.0; }catch(e){}
    notify();
  };

  window.__yspTick();
  // 央视频是 SPA：切频道不重新加载页面，靠轮询把新 video 也铺满
  setInterval(window.__yspTick, 700);
  try{ new MutationObserver(window.__yspTick).observe(document.documentElement,{childList:true,subtree:true}); }catch(e){}
  return 'ok';
})()"""
    }

    private lateinit var webView: WebView
    private var loading: ProgressBar? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var customHolder: FrameLayout? = null

    /** 是否加载网页图片：与 MainActivity 的「只加载视频」共用同一个开关。 */
    private var loadImages = false

    /** 页面全屏状态：视频铺满时隐藏系统栏，返回键只弹频道列表不退出全屏。 */
    private var pageFullscreen = false

    /** 用户主动退出全屏的时间戳：此后页面 tick 不再把画面拉回全屏。 */
    private var userExitedAt = 0L

    /** 页面内频道列表浮层是否展开（由 JS 的 __yspPanel 接管渲染）。 */
    private var panelOn = false

    /** 手势按下点：用于区分「点击」与「滑动」。 */
    private var touchDownX = 0f
    private var touchDownY = 0f

    /** dp 转 px。 */
    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    /** 页面脚本回调宿主：宣告真正起播，由宿主进入页面全屏。 */
    inner class PageBridge {
        @JavascriptInterface
        fun onPlaying() {
            if (android.os.SystemClock.uptimeMillis() < userExitedAt) return
            runOnUiThread {
                loading?.visibility = View.GONE
                enterFullscreen()
            }
        }

        /** 页面脚本查询：用户是否已主动退出全屏。 */
        @JavascriptInterface
        fun isUserExited(): String =
            if (android.os.SystemClock.uptimeMillis() < userExitedAt) "1" else "0"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 读取与主界面一致的「只加载视频」偏好（video_only=true 表示屏蔽图片）
        loadImages = !getSharedPreferences("channels", MODE_PRIVATE)
            .getBoolean("video_only", true)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            // PC 页按固定宽度排版：关掉整页缩放，按屏宽 1:1 渲染
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = UA
            // 只加载视频：与 CCTV 组同一规则，在网络层拦掉图片请求，省流量、骨架更快
            blockNetworkImage = !loadImages
        }
        // 页面脚本 → 宿主：起播回调，用于进入页面全屏
        webView.addJavascriptInterface(PageBridge(), "androidHost")
        // 全屏播放时的点击：列表展开则收起，否则重新拉满画面
        webView.setOnTouchListener { _, event ->
            if (pageFullscreen && event.action == android.view.MotionEvent.ACTION_UP) {
                val moved = Math.abs(event.x - touchDownX) > dp(24) ||
                    Math.abs(event.y - touchDownY) > dp(24)
                if (!moved && panelOn) {
                    panelOn = false
                    webView.evaluateJavascript("try{window.__yspPanel(false)}catch(e){}", null)
                    return@setOnTouchListener true
                }
            }
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                touchDownX = event.x
                touchDownY = event.y
            }
            false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loading?.visibility = View.GONE
                view?.evaluateJavascript(JS_EXPAND, null)
                view?.evaluateJavascript(JS_FILL, null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("YSP", "${msg.message()} @${msg.lineNumber()}")
                return true
            }

            // 网页里点全屏时，内核把画面抽成独立 View 交过来
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                val holder = FrameLayout(this@YangshipinActivity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                }
                customHolder = holder
                root.addView(holder, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreen(true)
            }

            override fun onHideCustomView() {
                val holder = customHolder ?: return
                root.removeView(holder)
                customHolder = null
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                fullscreen(false)
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        loading = ProgressBar(this).apply { visibility = View.VISIBLE }
        root.addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setContentView(root)
        webView.loadUrl(HOME)
    }

    /** 全屏切换：隐藏/恢复系统栏。 */
    @Suppress("DEPRECATION")
    private fun fullscreen(on: Boolean) {
        val decor: View = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!on)
            window.insetsController?.apply {
                if (on) {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            decor.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    /**
     * 进入页面全屏：隐藏系统栏让 WebView 铺满。
     * 画面本身由 JS_FILL 铺满，这里只负责系统 UI 与状态标记。
     */
    private fun enterFullscreen() {
        if (pageFullscreen) return
        pageFullscreen = true
        fullscreen(true)
    }

    /**
     * 退出页面全屏回到网页内布局。
     * 记录时间戳，避免页面 tick 立刻把画面重新拉满（表现为「返回键没反应」）。
     */
    private fun exitFullscreen() {
        if (!pageFullscreen) {
            // 未在全屏也要还原样式：页面可能自己铺满了
            webView.evaluateJavascript("try{window.__yspUnfill&&window.__yspUnfill()}catch(e){}", null)
            return
        }
        pageFullscreen = false
        userExitedAt = android.os.SystemClock.uptimeMillis() + 2500
        fullscreen(false)
        webView.evaluateJavascript("try{window.__yspUnfill&&window.__yspUnfill()}catch(e){}", null)
    }

    /** 返回键：全屏时只弹频道列表（画面不缩回），列表开着则收起，最后才退出全屏 / 后退 / 关页。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        // 网页全屏播放器（video 自带全屏键）优先退出
        if (panelOn) {
            panelOn = false
            webView.evaluateJavascript("try{window.__yspPanel(false)}catch(e){}", null)
            return
        }
        // 全屏播放中：弹频道列表，画面保持全屏
        if (pageFullscreen) {
            panelOn = true
            val js = "try{window.__yspPanel(true)}catch(e){}"
            webView.evaluateJavascript(js) { r ->
                if (r == null || r.contains("none")) fallbackChannelPanel()
            }
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    /** 页面里找不到左栏列表时：退出全屏并关闭本页，回到频道主界面。 */
    private fun fallbackChannelPanel() {
        runOnUiThread {
            panelOn = false
            exitFullscreen()
            finish()
        }
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        runCatching {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
