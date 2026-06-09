package com.musicapp.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var lockedBaseUrl = ""

    companion object {
        private const val PREF_NAME = "app_config"
        private const val KEY_BASE_URL = "locked_base_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()

        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        lockedBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""

        if (lockedBaseUrl.isEmpty()) {
            showUrlSetupDialog()
        } else {
            loadWebView(lockedBaseUrl)
        }
    }

    private fun hideSystemUI() {
        try {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } catch (e: Exception) { /* ignore */ }
    }

    private fun showUrlSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val input = EditText(this).apply {
            hint = "https://example.com"
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
                    getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_BASE_URL, url).apply()
                    lockedBaseUrl = url
                    loadWebView(url)
                } else {
                    Toast.makeText(this, "https:// 로 시작하는 URL을 입력하세요", Toast.LENGTH_LONG).show()
                    showUrlSetupDialog()
                }
            }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView(url: String) {
        val wv = WebView(this)
        webView = wv
        wv.setBackgroundColor(Color.BLACK)
        setContentView(wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // UserAgent에서 wv 태그 제거 (일부 사이트 제한 우회)
            userAgentString = userAgentString?.replace(Regex("\\bwv\\b"), "")?.trim()
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = Uri.parse(request.url.toString()).host ?: return true
                val baseHost = Uri.parse(lockedBaseUrl).host ?: return true
                return !(host == baseHost || host.endsWith(".$baseHost"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 스크롤바 숨김, 텍스트 선택 방지
                view.evaluateJavascript(
                    "(function(){var s=document.createElement('style');" +
                    "s.textContent='::-webkit-scrollbar{display:none!important}" +
                    "body{-webkit-tap-highlight-color:transparent}';" +
                    "document.head&&document.head.appendChild(s);})()", null)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                setContentView(view)
                hideSystemUI()
            }

            override fun onHideCustomView() {
                customView = null
                setContentView(wv)
                hideSystemUI()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        wv.loadUrl(url)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
    }
}
