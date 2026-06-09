package com.musicapp.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var lockedBaseUrl = ""
    private var musicService: MusicService? = null
    private var serviceBound = false

    companion object {
        private const val PREF_NAME = "app_config"
        private const val KEY_BASE_URL = "locked_base_url"
        private const val KEY_ALIAS = "chosen_alias"

        val PRESETS = listOf(
            Triple("Alias_music",   "Music",    "#7c6aff"),
            Triple("Alias_melon",   "Melon",    "#00c73c"),
            Triple("Alias_genie",   "지니",      "#0064ff"),
            Triple("Alias_bugs",    "벅스",      "#ff6b35"),
            Triple("Alias_flo",     "FLO",      "#2d2d5e"),
            Triple("Alias_vibe",    "VIBE",     "#e8003d"),
            Triple("Alias_spotify", "Spotify",  "#1db954"),
            Triple("Alias_ytmusic", "YT Music", "#ff0000")
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            musicService = (binder as MusicService.LocalBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        lockedBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""

        if (lockedBaseUrl.isEmpty()) {
            showSetupDialog(prefs)
        } else {
            startMusicService()
            loadWebView(lockedBaseUrl)
        }
    }

    private fun showSetupDialog(prefs: SharedPreferences) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        // URL 입력
        val urlLabel = TextView(this).apply { text = "웹 주소"; setTextColor(Color.WHITE) }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            setText("https://")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        // 앱 테마 선택
        val themeLabel = TextView(this).apply {
            text = "\n앱 이름 / 아이콘 선택"
            setTextColor(Color.WHITE)
        }

        val presetNames = PRESETS.map { it.second }.toTypedArray()
        var selectedIndex = 0

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, presetNames)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedIndex = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        layout.addView(urlLabel)
        layout.addView(urlInput)
        layout.addView(themeLabel)
        layout.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("초기 설정")
            .setMessage("재설치 전까지 변경 불가")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("시작") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val chosen = PRESETS[selectedIndex]
                    prefs.edit()
                        .putString(KEY_BASE_URL, url)
                        .putString(KEY_ALIAS, chosen.first)
                        .apply()
                    lockedBaseUrl = url
                    applyAlias(chosen.first)
                    startMusicService()
                    loadWebView(url)
                } else {
                    Toast.makeText(this, "https:// 로 시작하는 URL을 입력하세요", Toast.LENGTH_LONG).show()
                    showSetupDialog(prefs)
                }
            }
            .show()
    }

    private fun applyAlias(chosenAlias: String) {
        val pm = packageManager
        val pkg = packageName
        PRESETS.forEach { (alias, _, _) ->
            val state = if (alias == chosenAlias)
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "$pkg.$alias"),
                    state,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun startMusicService() {
        val intent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView(url: String) {
        // 쿠키 영구 저장 설정
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(null, true)
        }

        val wv = WebView(this)
        webView = wv
        wv.setBackgroundColor(Color.BLACK)
        setContentView(wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = userAgentString?.replace(Regex("\\bwv\\b"), "")?.trim()
        }

        // JavaScript → Android 브릿지 (미디어 정보 수신)
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaUpdate(title: String, artist: String, isPlaying: Boolean) {
                runOnUiThread {
                    if (serviceBound) musicService?.updateMetadata(title, artist, isPlaying)
                }
            }
        }, "AndroidBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onPageFinished(view: WebView, pageUrl: String) {
                // 스타일 정리
                view.evaluateJavascript("""
                    (function(){
                        var s=document.createElement('style');
                        s.textContent='::-webkit-scrollbar{display:none!important}body{-webkit-tap-highlight-color:transparent}';
                        document.head&&document.head.appendChild(s);
                    })();
                """.trimIndent(), null)

                // 미디어 세션 감지 브릿지 주입
                view.evaluateJavascript("""
                    (function(){
                        function sendUpdate(playing){
                            try{
                                var t=navigator.mediaSession&&navigator.mediaSession.metadata;
                                var title=t?t.title:'';
                                var artist=t?t.artist:'';
                                AndroidBridge.onMediaUpdate(title,artist,playing);
                            }catch(e){}
                        }
                        // audio/video 이벤트 감지
                        function hookMedia(el){
                            if(el._hooked)return; el._hooked=true;
                            el.addEventListener('play',function(){sendUpdate(true);});
                            el.addEventListener('pause',function(){sendUpdate(false);});
                        }
                        document.querySelectorAll('audio,video').forEach(hookMedia);
                        var obs=new MutationObserver(function(){
                            document.querySelectorAll('audio,video').forEach(hookMedia);
                        });
                        obs.observe(document.body,{childList:true,subtree:true});
                    })();
                """.trimIndent(), null)
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadData("""
                        <html><body style="background:#111;color:#aaa;font-family:sans-serif;
                        display:flex;align-items:center;justify-content:center;height:100vh;
                        margin:0;flex-direction:column;text-align:center;">
                        <p style="font-size:18px;">페이지를 불러올 수 없습니다</p>
                        <p style="font-size:13px;opacity:.6;margin:8px 24px;">네트워크를 확인해 주세요</p>
                        <button onclick="location.reload()" style="margin-top:20px;padding:12px 28px;
                        background:#7c6aff;color:#fff;border:none;border-radius:10px;font-size:15px;">
                        다시 시도</button></body></html>
                    """.trimIndent(), "text/html", "UTF-8")
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; setContentView(view); hideSystemUI()
            }
            override fun onHideCustomView() {
                customView = null; setContentView(wv); hideSystemUI()
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.loadUrl(url)
    }

    private fun hideSystemUI() {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } catch (e: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView?.let { if (it.canGoBack()) { it.goBack(); return true } }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        CookieManager.getInstance().flush() // 쿠키 즉시 디스크에 저장
    }

    override fun onDestroy() {
        if (serviceBound) unbindService(serviceConnection)
        webView?.destroy()
        super.onDestroy()
    }
}
