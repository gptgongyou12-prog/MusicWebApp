package com.musicapp.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private var lockedBaseUrl: String = ""

    companion object {
        private const val PREF_NAME = "app_config"
        private const val KEY_BASE_URL = "locked_base_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        lockedBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""

        setupFullscreen()

        if (lockedBaseUrl.isEmpty()) {
            showUrlSetupDialog()
        } else {
            initWebView(lockedBaseUrl)
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun showUrlSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val input = EditText(this).apply {
            hint = "https://example.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText("https://")
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("시작 주소 설정")
            .setMessage("앱에서 열 웹 주소를 입력하세요.\n재설치 전까지 변경할 수 없습니다.")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    prefs.edit().putString(KEY_BASE_URL, url).apply()
                    lockedBaseUrl = url
                    initWebView(url)
                } else {
                    Toast.makeText(this, "올바른 URL을 입력하세요 (https://...)", Toast.LENGTH_LONG).show()
                    showUrlSetupDialog()
                }
            }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String) {
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(webView)

        acquireWakeLock()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = buildUserAgent()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val requestUrl = request.url.toString()
                return if (isAllowedUrl(requestUrl)) {
                    false
                } else {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAppStyles()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val errorHtml = """
                        <html><body style="background:#111;color:#aaa;font-family:sans-serif;
                        display:flex;align-items:center;justify-content:center;height:100vh;margin:0;flex-direction:column;">
                        <p style="font-size:18px;">연결할 수 없습니다</p>
                        <p style="font-size:13px;opacity:.6;">네트워크를 확인해 주세요</p>
                        <button onclick="location.reload()" style="margin-top:20px;padding:10px 24px;
                        background:#333;color:#fff;border:none;border-radius:8px;font-size:15px;">다시 시도</button>
                        </body></html>
                    """.trimIndent()
                    view.loadData(errorHtml, "text/html", "UTF-8")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                setContentView(view)
                setupFullscreen()
            }

            override fun onHideCustomView() {
                customViewCallback?.onCustomViewHidden()
                customView = null
                setContentView(webView)
                setupFullscreen()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.loadUrl(url)
    }

    private fun isAllowedUrl(url: String): Boolean {
        return try {
            val baseHost = Uri.parse(lockedBaseUrl).host ?: return false
            val requestHost = Uri.parse(url).host ?: return false
            requestHost == baseHost || requestHost.endsWith(".$baseHost")
        } catch (e: Exception) {
            false
        }
    }

    private fun injectAppStyles() {
        val js = """
            (function() {
                var style = document.createElement('style');
                style.textContent = '::-webkit-scrollbar{display:none!important}' +
                    'body{-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent;}' +
                    'input,textarea,select{-webkit-user-select:auto!important;user-select:auto!important;}';
                document.head && document.head.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun buildUserAgent(): String {
        val base = WebSettings.getDefaultUserAgent(this)
        return base.replace("wv", "").replace("  ", " ").trim()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MusicApp::AudioWakeLock"
        ).apply { acquire(12 * 60 * 60 * 1000L) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::webView.isInitialized) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        setupFullscreen()
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        if (::webView.isInitialized) {
            webView.destroy()
        }
    }
}
