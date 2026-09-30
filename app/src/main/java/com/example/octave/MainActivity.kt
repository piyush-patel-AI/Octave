package com.example.octave

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val TAG_WEBVIEW = "OctaveWebView"
    private val TAG_STARTUP = "OctaveStartup"
    private val TAG_AUTH = "OctaveAuth"

    private lateinit var webView: WebView
    private var mediaService: OctaveMediaService? = null
    private var isBound = false
    private var loadStartTime: Long = 0

    private var mediaButtonCount = 0
    private val mediaButtonHandler = Handler(Looper.getMainLooper())
    private val mediaButtonRunnable = Runnable {
        val count = mediaButtonCount
        mediaButtonCount = 0
        dispatchMediaAction(count)
    }

    private val TARGET_URL = "https://mobile-client-v3.vercel.app/"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as OctaveMediaService.OctaveBinder
            mediaService = binder.getService()
            isBound = true
            setupMediaCallback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startMediaService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG_STARTUP, "onCreate: Initializing Octave")

        // Edge-to-Edge window setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)
        val mainContainer = findViewById<ViewGroup>(R.id.main_container)
        
        setupWebView(mainContainer)

        // Handle Window Insets for Edge-to-Edge on the container
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        checkNotificationPermission()

        // Handle Back Navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    webView.evaluateJavascript("window.dispatchEvent(new PopStateEvent('popstate'))", null)
                }
            }
        })
    }

    private fun setupWebView(container: ViewGroup) {
        Log.d(TAG_STARTUP, "setupWebView: Creating WebView instance")
        webView = WebView(this).apply {
            id = R.id.webView
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(webView)

        configureWebViewSettings(webView)
        setupWebViewClients(webView)
        
        // Add JavascriptInterface
        webView.addJavascriptInterface(AndroidMediaBridge { mediaService }, "AndroidMediaBridge")

        // Load initial target URL
        val extraHeaders = HashMap<String, String>()
        extraHeaders["ngrok-skip-browser-warning"] = "69420"
        
        loadStartTime = System.currentTimeMillis()
        Log.d(TAG_STARTUP, "loadUrl: $TARGET_URL")
        webView.loadUrl(TARGET_URL, extraHeaders)
    }

    private fun configureWebViewSettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "OctaveApp/1.0 (Android Native WebView)"
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Cookie Management
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        Log.d(TAG_AUTH, "CookieManager configured: AcceptCookie=true, AcceptThirdParty=true")
    }

    private fun setupWebViewClients(wv: WebView) {
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.let { url ->
                    if (url.host?.contains("ngrok") == true) {
                        val extraHeaders = HashMap<String, String>()
                        extraHeaders["ngrok-skip-browser-warning"] = "69420"
                        view?.loadUrl(url.toString(), extraHeaders)
                        return true
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val duration = System.currentTimeMillis() - loadStartTime
                Log.d(TAG_STARTUP, "onPageStarted: $url (After ${duration}ms)")
                injectMediaSessionPolyfill()
                checkAuthCookies(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val duration = System.currentTimeMillis() - loadStartTime
                Log.d(TAG_STARTUP, "onPageFinished: $url (Total load time: ${duration}ms)")
                injectMediaSessionPolyfill()
                CookieManager.getInstance().flush()
                checkAuthCookies(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG_WEBVIEW, "Main Frame Error: ${error?.description} (${error?.errorCode}) at ${request.url}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG_WEBVIEW, "HTTP Error: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} at ${request.url}")
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash() == true
                } else false
                
                Log.e(TAG_WEBVIEW, "Renderer process gone. didCrash = $didCrash")
                
                val mainContainer = findViewById<ViewGroup>(R.id.main_container)
                mainContainer.removeView(webView)
                webView.destroy()
                
                setupWebView(mainContainer)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message() ?: ""
                val source = consoleMessage?.sourceId() ?: ""
                val line = consoleMessage?.lineNumber() ?: 0
                val level = consoleMessage?.messageLevel() ?: ConsoleMessage.MessageLevel.LOG
                
                Log.d(TAG_WEBVIEW, "[$level] $msg ($source:$line)")
                return true
            }
        }
    }

    private fun checkAuthCookies(url: String?) {
        if (url == null) return
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies != null) {
            val parts = cookies.split("; ")
            Log.d(TAG_AUTH, "Safe Cookie Check for $url: Found ${parts.size} cookies")
            // Optionally check for specific session cookie names if known, e.g. "session", "next-auth.session-token"
        } else {
            Log.d(TAG_AUTH, "Safe Cookie Check for $url: No cookies found")
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startMediaService()
            }
        } else {
            startMediaService()
        }
    }

    private fun startMediaService() {
        val serviceIntent = Intent(this, OctaveMediaService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun injectMediaSessionPolyfill() {
        val js = """
            (function() {
                if (window._octavePolyfilled) return;
                window._octavePolyfilled = true;

                const actionHandlers = {};
                
                function updateAndroidMetadata() {
                    try {
                        if (window.navigator.mediaSession && window.navigator.mediaSession.metadata) {
                            const meta = window.navigator.mediaSession.metadata;
                            const artUrl = (meta.artwork && meta.artwork.length > 0) ? meta.artwork[0].src : '';
                            const audio = document.querySelector('audio, video');
                            const dur = audio ? (audio.duration || 0) : 0;
                            
                            AndroidMediaBridge.updateMetadata(
                                meta.title || '',
                                meta.artist || '',
                                meta.album || '',
                                artUrl,
                                dur
                            );
                        }
                    } catch(e) { console.error("Octave Meta Error", e); }
                }

                if ('mediaSession' in navigator) {
                    // Hook into metadata
                    const proto = MediaSession.prototype;
                    const originalMetadataDescriptor = Object.getOwnPropertyDescriptor(proto, 'metadata');
                    
                    Object.defineProperty(navigator.mediaSession, 'metadata', {
                        get: function() { return this._metadata; },
                        set: function(val) {
                            this._metadata = val;
                            updateAndroidMetadata();
                        },
                        configurable: true
                    });

                    // Hook into action handlers
                    const originalSetActionHandler = navigator.mediaSession.setActionHandler;
                    navigator.mediaSession.setActionHandler = function(action, handler) {
                        actionHandlers[action] = handler;
                        if (originalSetActionHandler) originalSetActionHandler.call(this, action, handler);
                    };

                    // Hook into position state
                    const originalSetPositionState = navigator.mediaSession.setPositionState;
                    navigator.mediaSession.setPositionState = function(state) {
                        if (state) {
                            AndroidMediaBridge.updatePlaybackState(
                                navigator.mediaSession.playbackState === 'playing',
                                state.position || 0,
                                state.duration || 0
                            );
                        }
                        if (originalSetPositionState) originalSetPositionState.call(this, state);
                    };
                    
                    // Hook into playbackState
                    Object.defineProperty(navigator.mediaSession, 'playbackState', {
                        get: function() { return this._playbackState || 'none'; },
                        set: function(val) {
                            this._playbackState = val;
                            const audio = document.querySelector('audio, video');
                            const pos = audio ? (audio.currentTime || 0) : 0;
                            const dur = audio ? (audio.duration || 0) : 0;
                            AndroidMediaBridge.updatePlaybackState(val === 'playing', pos, dur);
                        },
                        configurable: true
                    });
                }

                window.localTuneBridge = {
                    play: () => {
                        if (actionHandlers['play']) actionHandlers['play']();
                        else {
                            const v = document.querySelector('video, audio');
                            if (v) v.play();
                        }
                    },
                    pause: () => {
                        if (actionHandlers['pause']) actionHandlers['pause']();
                        else {
                            const v = document.querySelector('video, audio');
                            if (v) v.pause();
                        }
                    },
                    next: () => {
                        if (actionHandlers['nexttrack']) actionHandlers['nexttrack']();
                        else {
                            const btn = document.querySelector('[aria-label="Next"], .next-button');
                            if (btn) btn.click();
                        }
                    },
                    prev: () => {
                        if (actionHandlers['previoustrack']) actionHandlers['previoustrack']();
                        else {
                            const btn = document.querySelector('[aria-label="Previous"], .previous-button');
                            if (btn) btn.click();
                        }
                    },
                    seek: (seconds) => {
                        if (actionHandlers['seekto']) actionHandlers['seekto']({seekTime: seconds});
                        else if (actionHandlers['seekaround']) actionHandlers['seekaround']({seekTime: seconds});
                        else {
                            const v = document.querySelector('video, audio');
                            if (v) v.currentTime = seconds;
                        }
                    },
                    seekRelative: (offset) => {
                        const v = document.querySelector('video, audio');
                        if (v) v.currentTime += offset;
                    }
                };
                
                window.octaveBridge = window.localTuneBridge;
                
                AndroidMediaBridge.logDebug("MediaSession Polyfill Injected and Active (Legacy Bridge Restored)");
                
                // Dispatch event for web-app bridge detection
                window.dispatchEvent(new CustomEvent('bridge.ready'));

                // Periodic sync for scrubbing and robustness
                let lastState = null;
                setInterval(() => {
                    try {
                        const audio = document.querySelector('audio, video');
                        if (audio) {
                            const isPlaying = !audio.paused;
                            // Only update native if something changed or if playing (to keep scrub bar moving)
                            const currentState = isPlaying + ":" + Math.floor(audio.currentTime) + ":" + Math.floor(audio.duration);
                            if (currentState !== lastState || isPlaying) {
                                AndroidMediaBridge.updatePlaybackState(isPlaying, audio.currentTime, audio.duration || 0);
                                lastState = currentState;
                            }
                        }
                    } catch(e) {}
                }, 1000);
                
                AndroidMediaBridge.logDebug("MediaSession Polyfill Re-Injected and Active");
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupMediaCallback() {
        mediaService?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }

                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    val keyCode = event.keyCode
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                        mediaButtonCount++
                        mediaButtonHandler.removeCallbacks(mediaButtonRunnable)
                        if (mediaButtonCount >= 3) {
                            mediaButtonRunnable.run()
                        } else {
                            mediaButtonHandler.postDelayed(mediaButtonRunnable, 350)
                        }
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }

            override fun onPlay() {
                android.util.Log.d("OctaveBridge", "Native Play Command Received")
                webView.post { webView.evaluateJavascript("window.localTuneBridge.play()", null) }
            }

            override fun onPause() {
                android.util.Log.d("OctaveBridge", "Native Pause Command Received")
                webView.post { webView.evaluateJavascript("window.localTuneBridge.pause()", null) }
            }

            override fun onSkipToNext() {
                android.util.Log.d("OctaveBridge", "Native Next Command Received")
                webView.post { webView.evaluateJavascript("window.localTuneBridge.next()", null) }
            }

            override fun onSkipToPrevious() {
                android.util.Log.d("OctaveBridge", "Native Previous Command Received")
                webView.post { webView.evaluateJavascript("window.localTuneBridge.prev()", null) }
            }

            override fun onSeekTo(posMs: Long) {
                android.util.Log.d("OctaveBridge", "Native Seek Command Received: $posMs")
                val seconds = posMs / 1000.0
                webView.post { webView.evaluateJavascript("window.localTuneBridge.seek($seconds)", null) }
            }

            override fun onFastForward() {
                android.util.Log.d("OctaveBridge", "Native FastForward Command Received")
                webView.post { webView.evaluateJavascript("window.localTuneBridge.seekRelative(10)", null) }
            }

            override fun onRewind() {
                android.util.Log.d("OctaveBridge", "Native Rewind Command Received")
                webView.post { webView.evaluateJavascript("window.localTuneBridge.seekRelative(-10)", null) }
            }
        })
    }

    private fun dispatchMediaAction(count: Int) {
        val controls = mediaService?.getMediaSession()?.controller?.transportControls ?: return
        when (count) {
            1 -> {
                val state = mediaService?.getMediaSession()?.controller?.playbackState?.state
                if (state == PlaybackStateCompat.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            2 -> controls.skipToNext()
            3 -> controls.skipToPrevious()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        CookieManager.getInstance().flush()
        mediaButtonHandler.removeCallbacksAndMessages(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}