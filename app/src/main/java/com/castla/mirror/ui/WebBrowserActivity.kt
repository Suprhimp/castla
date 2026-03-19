package com.castla.mirror.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.widget.FrameLayout
import android.util.Log
import android.content.pm.ActivityInfo

class WebBrowserActivity : Activity() {

    companion object {
        private const val TAG = "WebBrowserActivity"
    }

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullScreenContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "WebBrowserActivity created")

        // 풀스크린 설정 및 가로모드(가상 디스플레이 특성) 고정
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        fullScreenContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setupWebView(this)
        }

        root.addView(webView)
        root.addView(fullScreenContainer)
        setContentView(root)

        val url = intent.getStringExtra("url") ?: "https://m.youtube.com"
        Log.i(TAG, "Loading URL: $url")
        webView.loadUrl(url)
    }

    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // 터치 없이 자동재생 허용
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // 모바일 크롬 브라우저와 완벽하게 동일한 User-Agent 사용
            // 단, 태블릿/PC UI를 선호하는 테슬라 화면 비율을 위해 iPad UserAgent를 사용하는 것이 더 안정적임
            userAgentString = "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"
        }

        // 쿠키 허용
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                
                // 앱 링크 차단 (유튜브, 넷플릭스 등 앱으로 넘어가는 것을 차단)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false // 정상적인 웹 페이지는 웹뷰가 처리하도록 통과
                }
                
                Log.i(TAG, "Blocked app link redirect: $url")
                return true // 그 외 스킴(intent:// 등)은 차단
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(TAG, "WebView Error: ${error?.errorCode} - ${error?.description} (URL: ${request?.url})")
                super.onReceivedError(view, request, error)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // HTML5 동영상 전체화면(Full Screen) 진입 시 호출
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                Log.i(TAG, "Entering full screen video mode")
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                fullScreenContainer.visibility = View.VISIBLE
                fullScreenContainer.addView(view)
            }

            // 전체화면 종료 시 호출
            override fun onHideCustomView() {
                Log.i(TAG, "Exiting full screen video mode")
                if (customView == null) return
                fullScreenContainer.visibility = View.GONE
                fullScreenContainer.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
