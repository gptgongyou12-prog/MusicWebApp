package com.musicapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var lockedBaseUrl = ""
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var pendingIconBitmap: Bitmap? = null

    companion object {
        const val PREF_NAME = "app_config"
        const val KEY_BASE_URL = "locked_base_url"
        const val ICON_FILE = "user_icon.png"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            musicService = (binder as MusicService.LocalBinder).getService()
            serviceBound = true
            loadSavedIcon()?.let { musicService?.setCustomIcon(it) }
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

    // 이미지 피커
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ -> decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            val square = cropToSquare(bmp)
            pendingIconBitmap = square
            iconPreviewView?.setImageBitmap(square)
            iconPreviewView?.visibility = View.VISIBLE
            iconHintView?.text = "✓ 이미지 선택됨"
        } catch (e: Exception) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private var iconPreviewView: ImageView? = null
    private var iconHintView: TextView? = null

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
            setPadding(64, 40, 64, 16)
        }

        // URL 입력
        TextView(this).apply {
            text = "웹 주소"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
        }.also { layout.addView(it) }

        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            setText("https://")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(urlInput)

        // 구분선
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                it.topMargin = 24; it.bottomMargin = 16
            }
            setBackgroundColor(0xFF333333.toInt())
        }.also { layout.addView(it) }

        // 앱 아이콘 업로드
        TextView(this).apply {
            text = "앱 알림 아이콘 (선택)"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
        }.also { layout.addView(it) }

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 8 }
        }

        iconPreviewView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(80, 80).also { it.rightMargin = 16 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF222222.toInt())
            visibility = View.GONE
        }

        iconHintView = TextView(this).apply {
            text = "사진을 선택하면\n알림창에 표시됩니다"
            setTextColor(0xFF888888.toInt())
            textSize = 12f
        }

        val pickBtn = Button(this).apply {
            text = "사진 선택"
            setOnClickListener { pickImage.launch("image/*") }
        }

        iconRow.addView(iconPreviewView)
        val iconTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        iconTextCol.addView(iconHintView)
        iconTextCol.addView(pickBtn)
        iconRow.addView(iconTextCol)
        layout.addView(iconRow)

        AlertDialog.Builder(this)
            .setTitle("초기 설정")
            .setMessage("설정 후 재설치 전까지 주소 변경 불가")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("시작") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "https:// 로 시작하는 URL을 입력하세요", Toast.LENGTH_LONG).show()
                    showSetupDialog(prefs)
                    return@setPositiveButton
                }
                // 아이콘 저장
                pendingIconBitmap?.let { saveIcon(it) }
                prefs.edit().putString(KEY_BASE_URL, url).apply()
                lockedBaseUrl = url
                startMusicService()
                loadWebView(url)
            }
            .show()
    }

    private fun saveIcon(bmp: Bitmap) {
        try {
            val file = File(filesDir, ICON_FILE)
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun loadSavedIcon(): Bitmap? {
        return try {
            val file = File(filesDir, ICON_FILE)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (e: Exception) { null }
    }

    private fun cropToSquare(bmp: Bitmap): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2
        return Bitmap.createBitmap(bmp, x, y, size, size)
    }

    private fun startMusicService() {
        try {
            val intent = Intent(this, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { /* 서비스 없어도 앱은 동작 */ }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView(url: String) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
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

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // JS → Android 미디어 정보 브릿지
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
                view.evaluateJavascript("""
                    (function(){
                        var s=document.createElement('style');
                        s.textContent='::-webkit-scrollbar{display:none!important}body{-webkit-tap-highlight-color:transparent}';
                        document.head&&document.head.appendChild(s);
                        function sendUpdate(p){
                            try{var m=navigator.mediaSession&&navigator.mediaSession.metadata;
                            AndroidBridge.onMediaUpdate(m?m.title:'',m?m.artist:'',p);}catch(e){}
                        }
                        function hook(el){if(el._h)return;el._h=true;
                            el.addEventListener('play',function(){sendUpdate(true);});
                            el.addEventListener('pause',function(){sendUpdate(false);});}
                        document.querySelectorAll('audio,video').forEach(hook);
                        new MutationObserver(function(){document.querySelectorAll('audio,video').forEach(hook);})
                            .observe(document.body,{childList:true,subtree:true});
                    })();
                """.trimIndent(), null)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadData("""<html><body style="background:#111;color:#aaa;font-family:sans-serif;
                        display:flex;align-items:center;justify-content:center;height:100vh;margin:0;flex-direction:column;">
                        <p style="font-size:18px">페이지를 불러올 수 없습니다</p>
                        <button onclick="location.reload()" style="margin-top:20px;padding:12px 28px;
                        background:#7c6aff;color:#fff;border:none;border-radius:10px;font-size:15px">다시 시도</button>
                        </body></html>""", "text/html", "UTF-8")
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

    override fun onResume() { super.onResume(); webView?.onResume(); hideSystemUI() }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (serviceBound) unbindService(serviceConnection)
        webView?.destroy()
        super.onDestroy()
    }
}
