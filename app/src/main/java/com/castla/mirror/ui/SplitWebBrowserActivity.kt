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
 * A floating dialog-style activity that displays a Web App (like YouTube) on the right side
 * of the virtual display, leaving the left side transparent for the background map app.
 */
class SplitWebBrowserActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Important: This removes any dialog borders and allows touches to pass through
        // to the app (e.g. Map) running in the background.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        // Hide system UI (immersive mode) inside the dialog window
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.decorView.setPadding(0, 0, 0, 0)

        // Calculate initial width to leave the left side at a typical 9:16 phone ratio
        val metrics = resources.displayMetrics
        val phoneWidth = (metrics.heightPixels * 9f / 16f).toInt()
        val webWidth = (metrics.widthPixels - phoneWidth).coerceAtLeast((metrics.widthPixels * 0.3).toInt())

        // Apply width and position to the right
        val params = window.attributes
        params.gravity = Gravity.END or Gravity.FILL_VERTICAL
        params.width = webWidth
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.horizontalMargin = 0f
        params.verticalMargin = 0f
        window.attributes = params

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Draggable divider handle
        val divider = object : FrameLayout(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(48, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF1E1E1E.toInt())
            
            val dots = LinearLayout(this@SplitWebBrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                for (i in 0..2) {
                    addView(View(this@SplitWebBrowserActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(8, 8).apply { setMargins(0, 8, 0, 8) }
                        setBackgroundColor(0xFF888888.toInt())
                    })
                }
            }
            addView(dots)
        }

        // Web View Container (Header + WebView)
        val webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(Color.BLACK)
        }

        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
            setBackgroundColor(0xFF111111.toInt())
            
            val closeBtn = TextView(this@SplitWebBrowserActivity).apply {
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
                    initialWidth = window.attributes.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    // If moving left (negative dx), width increases
                    val newWidth = (initialWidth - dx).toInt().coerceIn(
                        (metrics.widthPixels * 0.3).toInt(),
                        (metrics.widthPixels * 0.8).toInt()
                    )
                    val p = window.attributes
                    p.width = newWidth
                    window.attributes = p
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
        
        val url = intent.getStringExtra("url") ?: "https://m.youtube.com"
        webView.loadUrl(url)
    }

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
