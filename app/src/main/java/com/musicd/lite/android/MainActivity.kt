package com.musicd.lite.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.app.Activity

/**
 * The window: a WebView showing the bundled MusicD front-end, served by the
 * local HTTP server the service is running.
 *
 * There is no native UI here on purpose. The interface is the whole point of
 * this port — it is MusicD-Remote's own HTML, CSS and JavaScript, unmodified,
 * so it looks and behaves exactly like the browser version and a newer upstream
 * UI can be dropped in as a file copy. What changed is everything underneath:
 * the Node server it used to talk to is now Kotlin in this same process.
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "MainActivity"
        const val NOTIFICATION_PERMISSION = 1

        /** Matches the front-end's page background, so nothing flashes. */
        const val BACKGROUND = 0xFF0E1012.toInt()

        /** How long to wait for the local server before saying something. */
        const val SERVER_WAIT_MS = 10_000L
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var message: TextView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)   // the front-end's own background
        }

        message = TextView(this).apply {
            setPadding(64, 64, 64, 64)
            setTextColor(0xFFBFC7CE.toInt())
            textSize = 15f
            text = "Starting…"
        }
        root.addView(message)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(BACKGROUND)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The page is served over plain HTTP from 127.0.0.1 and pulls
                // its webfont over HTTPS, so mixed content has to be allowed or
                // the font request is blocked.
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = false
                useWideViewPort = false
                // The UI does its own scaling and blocks zoom in its viewport
                // meta; honouring a pinch here would fight it.
                builtInZoomControls = false
                displayZoomControls = false
            }
            webViewClient = LocalClient()
        }
        root.addView(web)
        setContentView(root)

        askForNotificationPermission()
        startForegroundService(Intent(this, RemoteService::class.java))
        waitForServer(System.currentTimeMillis())
    }

    /**
     * The service owns the server, and it may not have bound its port yet when
     * the window opens. Poll briefly rather than racing it.
     */
    private fun waitForServer(startedAt: Long) {
        val url = RemoteService.instance?.app?.rootUrl
        if (url != null) {
            Log.i(TAG, "loading $url")
            message.visibility = View.GONE
            web.visibility = View.VISIBLE
            web.loadUrl(url)
            return
        }
        if (System.currentTimeMillis() - startedAt > SERVER_WAIT_MS) {
            message.text = "The remote could not start its local server.\n\n" +
                "Close the app completely and open it again. If it keeps happening, " +
                "restart the phone — something else may be holding the port."
            return
        }
        main.postDelayed({ waitForServer(startedAt) }, 100)
    }

    /**
     * Android 13+ needs permission before the service's status notification is
     * shown. It is not needed for the service to RUN, so a refusal costs the
     * notification and nothing else — and the pairing instructions are on
     * screen in the app anyway.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
    }

    override fun onBackPressed() {
        // The front-end is a single-page app with its own history, so Back is
        // in-app navigation until there is nothing left to go back to.
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        // The service keeps running deliberately: closing the window must not
        // disconnect the extension from Roon. See RemoteService.
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private inner class LocalClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url ?: return false
            val local = RemoteService.instance?.app?.rootUrl
            // Anything on our own server stays in the WebView; a link out — an
            // artist's Wikipedia page, say — belongs to the browser.
            if (local != null && url.toString().startsWith(local)) return false
            return openExternally(url)
        }

        private fun openExternally(url: Uri): Boolean {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.w(TAG, "nothing could open $url", e)
                true
            }
        }
    }
}
