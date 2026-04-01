package com.castla.mirror.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class SplitWebPresentation(
    outerContext: Context,
    display: Display,
    private val initialUrl: String,
    private val onClose: () -> Unit
) : Presentation(outerContext, display) {

    private lateinit var webView: WebView
    private var webViewDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 아주 중요한 부분: 왼쪽 창 영역을 완벽히 투명하게 만들어 뒤의 지도가 터치까지 되도록 함
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        // Calculate initial width to leave the left side at a typical 9:16 phone ratio
        val metrics = context.resources.displayMetrics
        val phoneWidth = (metrics.heightPixels * 9f / 16f).toInt()
        val webWidth = (metrics.widthPixels - phoneWidth).coerceAtLeast((metrics.widthPixels * 0.3).toInt())

        val params = window?.attributes
        params?.gravity = Gravity.END or Gravity.FILL_VERTICAL
        params?.width = webWidth
        params?.height = WindowManager.LayoutParams.MATCH_PARENT
        params?.horizontalMargin = 0f
        params?.verticalMargin = 0f
        window?.attributes = params

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Draggable divider handle
        val divider = object : FrameLayout(context) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(48, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF1E1E1E.toInt())
            
            val dots = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                for (i in 0..2) {
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(8, 8).apply { setMargins(0, 8, 0, 8) }
                        setBackgroundColor(0xFF888888.toInt())
                    })
                }
            }
            addView(dots)
        }

        // Web View Container (Header + WebView)
        val webContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(Color.BLACK)
        }

        val header = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
            setBackgroundColor(0xFF111111.toInt())
            
            val closeBtn = TextView(context).apply {
                text = "✕ Close"
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
                setOnClickListener { onClose() }
            }
            addView(closeBtn)
        }

        webView = WebView(context).apply {
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

        webContainer.addView(header)
        webContainer.addView(webView)

        root.addView(divider)
        root.addView(webContainer)

        // Divider Drag Logic
        var initialX = 0f
        var initialWidth = 0
        divider.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialWidth = window?.attributes?.width ?: webWidth
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    // If moving left (negative dx), width increases
                    val newWidth = (initialWidth - dx).toInt().coerceIn(
                        (metrics.widthPixels * 0.3).toInt(),
                        (metrics.widthPixels * 0.8).toInt()
                    )
                    val p = window?.attributes
                    p?.width = newWidth
                    window?.attributes = p
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        setContentView(root)
        webView.loadUrl(initialUrl)
    }

    fun loadUrl(url: String) {
        if (!webViewDestroyed) {
            webView.loadUrl(url)
        }
    }
    override fun onStart() {
        super.onStart()
        if (::webView.isInitialized && !webViewDestroyed) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onStop() {
        if (::webView.isInitialized && !webViewDestroyed) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onStop()
    }

    override fun onDetachedFromWindow() {
        if (::webView.isInitialized && !webViewDestroyed) {
            webViewDestroyed = true
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDetachedFromWindow()
    }

}
