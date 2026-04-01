package com.castla.mirror.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A floating right-aligned activity that displays a Web App (like YouTube) on the right side
 * of the virtual display. Because its width is constrained, the left side remains fully
 * visible and interactive (showing the underlying Map app).
 */
class SplitWebBrowserActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 투명 배경, 딤 효과 제거, 터치 모달 해제 -> 창 밖(왼쪽) 터치가 백그라운드 앱으로 전달됨
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // VD의 화면 크기를 가져와서, 스마트폰 비율(9:16)을 제외한 나머지 우측 공간 계산
        val metrics = resources.displayMetrics
        val totalWidth = metrics.widthPixels
        val phoneWidth = (metrics.heightPixels * 9f / 16f).toInt()
        val webWidth = totalWidth - phoneWidth

        // 윈도우 자체의 크기를 줄여서 오른쪽에 붙임 (이것이 핵심!)
        val params = window.attributes
        params.gravity = Gravity.END or Gravity.FILL_VERTICAL
        params.width = webWidth
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.horizontalMargin = 0f
        params.verticalMargin = 0f
        window.attributes = params

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
            setBackgroundColor(0xFF111111.toInt())
            
            val closeBtn = TextView(this@SplitWebBrowserActivity).apply {
                text = "✕ Close Split Screen"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(30, 0, 30, 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END or Gravity.CENTER_VERTICAL
                )
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { finish() }
            }
            addView(closeBtn)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.apply {
                @Suppress("SetJavaScriptEnabled")
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val reqUrl = request.url.toString()
                    return !(reqUrl.startsWith("http://") || reqUrl.startsWith("https://"))
                }
            }
            webChromeClient = WebChromeClient()
        }

        root.addView(header)
        root.addView(webView)

        setContentView(root)
        
        val url = intent.getStringExtra("url") ?: "https://m.youtube.com"
        webView.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
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
