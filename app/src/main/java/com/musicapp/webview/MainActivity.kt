package com.musicapp.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var lockedBaseUrl = ""
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var pendingIconBitmap: Bitmap? = null
    private var iconPreviewView: ImageView? = null
    private var iconHintView: TextView? = null

    companion object {
        const val PREF_NAME = "app_config"
        const val KEY_BASE_URL = "locked_base_url"
        const val ICON_FILE = "user_icon.png"
    }

    // 알림 버튼 브로드캐스트 수신
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "MUSIC_PLAYPAUSE" -> webView?.evaluateJavascript("""
                    (function(){
                        var a=document.querySelector('audio')||document.querySelector('video');
                        if(a){if(a.paused)a.play();else a.pause();}
                    })();""", null)
                "MUSIC_NEXT" -> webView?.evaluateJavascript("""
                    (function(){
                        var btns=Array.from(document.querySelectorAll('button,div[role=button]'));
                        var next=btns.find(function(b){var t=(b.getAttribute('aria-label')||b.title||b.innerText||'').toLowerCase();
                            return t.includes('next')||t.includes('다음')||t.includes('skip');});
                        if(next)next.click();
                    })();""", null)
                "MUSIC_PREV" -> webView?.evaluateJavascript("""
                    (function(){
                        var btns=Array.from(document.querySelectorAll('button,div[role=button]'));
                        var prev=btns.find(function(b){var t=(b.getAttribute('aria-label')||b.title||b.innerText||'').toLowerCase();
                            return t.includes('prev')||t.includes('previous')||t.includes('이전');});
                        if(prev)prev.click();
                    })();""", null)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            musicService = (binder as MusicService.LocalBinder).getService()
            serviceBound = true
            loadSavedIcon()?.let { musicService?.setCustomIcon(it) }
            musicService?.setPlaybackController(object : MusicService.PlaybackController {
                override fun onPlay() {
                    webView?.evaluateJavascript(
                        "var a=document.querySelector('audio')||document.querySelector('video');if(a)a.play();", null)
                }
                override fun onPause() {
                    webView?.evaluateJavascript(
                        "var a=document.querySelector('audio')||document.querySelector('video');if(a)a.pause();", null)
                }
                override fun onSkipNext() {
                    webView?.evaluateJavascript("""
                        var btns=Array.from(document.querySelectorAll('button,div[role=button]'));
                        var b=btns.find(function(x){var t=(x.getAttribute('aria-label')||x.title||'').toLowerCase();
                            return t.includes('next')||t.includes('다음');});if(b)b.click();""", null)
                }
                override fun onSkipPrevious() {
                    webView?.evaluateJavascript("""
                        var btns=Array.from(document.querySelectorAll('button,div[role=button]'));
                        var b=btns.find(function(x){var t=(x.getAttribute('aria-label')||x.title||'').toLowerCase();
                            return t.includes('prev')||t.includes('이전');});if(b)b.click();""", null)
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

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
            val square = cropSquare(bmp)
            pendingIconBitmap = square
            iconPreviewView?.setImageBitmap(square)
            iconPreviewView?.visibility = View.VISIBLE
            iconHintView?.text = "✓ 선택됨"
        } catch (e: Exception) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        // 알림 버튼 수신 등록
        val filter = IntentFilter().apply {
            addAction("MUSIC_PLAYPAUSE")
            addAction("MUSIC_NEXT")
            addAction("MUSIC_PREV")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }

        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        lockedBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""

        if (lockedBaseUrl.isEmpty()) showSetupDialog(prefs)
        else { startMusicService(); loadWebView(lockedBaseUrl) }
    }

    private fun showSetupDialog(prefs: SharedPreferences) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
        }

        layout.addView(TextView(this).apply { text = "웹 주소"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f })
        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            setText("https://")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        layout.addView(urlInput)

        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 24; it.bottomMargin = 16 }
            setBackgroundColor(0xFF333333.toInt())
        })

        layout.addView(TextView(this).apply { text = "알림 아이콘 (선택)"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = 8 }
        }
        iconPreviewView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(80, 80).also { it.rightMargin = 16 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF222222.toInt())
            visibility = View.GONE
        }
        iconHintView = TextView(this).apply {
            text = "선택한 사진이 알림창에 표시됩니다"
            setTextColor(0xFF888888.toInt())
            textSize = 12f
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(iconHintView)
        col.addView(Button(this).apply { text = "사진 선택"; setOnClickListener { pickImage.launch("image/*") } })
        row.addView(iconPreviewView)
        row.addView(col)
        layout.addView(row)

        AlertDialog.Builder(this)
            .setTitle("초기 설정")
            .setMessage("재설치 전까지 주소 변경 불가")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("시작") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "https:// 로 시작하는 URL을 입력하세요", Toast.LENGTH_LONG).show()
                    showSetupDialog(prefs); return@setPositiveButton
                }
                pendingIconBitmap?.let { saveIcon(it) }
                prefs.edit().putString(KEY_BASE_URL, url).apply()
                lockedBaseUrl = url
                startMusicService()
                loadWebView(url)
            }.show()
    }

    private fun startMusicService() {
        try {
            val intent = Intent(this, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView(url: String) {
        CookieManager.getInstance().setAcceptCookie(true)

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

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaUpdate(title: String, artist: String, artworkUrl: String, isPlaying: Boolean) {
                runOnUiThread {
                    if (serviceBound) musicService?.updateMetadata(title, artist, artworkUrl, isPlaying)
                }
            }
            @JavascriptInterface
            fun onMediaUpdateWithArt(title: String, artist: String, artBase64: String, isPlaying: Boolean) {
                runOnUiThread {
                    if (serviceBound) musicService?.updateMetadataWithBase64Art(title, artist, artBase64, isPlaying)
                }
            }
        }, "AndroidBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onPageFinished(view: WebView, pageUrl: String) {
                view.evaluateJavascript("""
                (function(){
                    if(window._musicBridgeInit)return;
                    window._musicBridgeInit=true;

                    // 스타일
                    var s=document.createElement('style');
                    s.textContent='::-webkit-scrollbar{display:none!important}body{-webkit-tap-highlight-color:transparent}';
                    document.head&&document.head.appendChild(s);

                    var _lastTitle='',_lastArt='',_lastPlaying=false;

                    function blobToBase64(url,cb){
                        try{
                            var xhr=new XMLHttpRequest();
                            xhr.open('GET',url,true);
                            xhr.responseType='blob';
                            xhr.onload=function(){
                                var reader=new FileReader();
                                reader.onloadend=function(){cb(reader.result||'');};
                                reader.readAsDataURL(xhr.response);
                            };
                            xhr.onerror=function(){cb('');};
                            xhr.send();
                        }catch(e){cb('');}
                    }

                    function sendMeta(playing){
                        try{
                            var ms=navigator.mediaSession;
                            var m=ms&&ms.metadata;
                            var title=m&&m.title?m.title:(document.title||'');
                            var artist=m?(m.artist||m.album||''):'';
                            var art='';
                            if(m&&m.artwork&&m.artwork.length>0){
                                art=m.artwork[m.artwork.length-1].src||m.artwork[0].src||'';
                            }
                            var changed=(title!==_lastTitle||art!==_lastArt||playing!==_lastPlaying);
                            if(!changed)return;
                            _lastTitle=title;_lastArt=art;_lastPlaying=playing;

                            if(art&&art.startsWith('blob:')){
                                blobToBase64(art,function(b64){
                                    try{AndroidBridge.onMediaUpdateWithArt(title,artist,b64,playing);}catch(e){}
                                });
                            } else {
                                try{AndroidBridge.onMediaUpdate(title,artist,art,playing);}catch(e){}
                            }
                        }catch(e){}
                    }

                    // audio/video 이벤트 훅
                    function hookEl(el){
                        if(el._mh)return;el._mh=true;
                        el.addEventListener('play',function(){sendMeta(true);});
                        el.addEventListener('pause',function(){sendMeta(false);});
                    }
                    document.querySelectorAll('audio,video').forEach(hookEl);
                    new MutationObserver(function(){
                        document.querySelectorAll('audio,video').forEach(hookEl);
                    }).observe(document.body||document.documentElement,{childList:true,subtree:true});

                    // 2초마다 폴링 (mediaSession 늦게 세팅되는 사이트 대응)
                    setInterval(function(){
                        var el=document.querySelector('audio')||document.querySelector('video');
                        sendMeta(el?!el.paused:_lastPlaying);
                    },2000);
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

    private fun saveIcon(bmp: Bitmap) {
        try { FileOutputStream(File(filesDir, ICON_FILE)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: Exception) {}
    }

    private fun loadSavedIcon(): Bitmap? {
        return try { val f = File(filesDir, ICON_FILE); if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null } catch (e: Exception) { null }
    }

    private fun cropSquare(bmp: Bitmap): Bitmap {
        val s = minOf(bmp.width, bmp.height)
        return Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView?.let { if (it.canGoBack()) { it.goBack(); return true } }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() { super.onResume(); webView?.onResume(); hideSystemUI() }
    override fun onPause() { super.onPause(); webView?.onPause(); CookieManager.getInstance().flush() }

    override fun onDestroy() {
        try { unregisterReceiver(mediaReceiver) } catch (e: Exception) {}
        if (serviceBound) unbindService(serviceConnection)
        webView?.destroy()
        super.onDestroy()
    }
}
