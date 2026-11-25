/*
 *     Copyright (C) 2026 alivemaster
 *
 *     MyGramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     MyGramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.mygomusic

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.BaseActivity
import kotlin.coroutines.cancellation.CancellationException

/** QR the sign-in dialog draws. The text is what the plugin asked to show. */
internal fun qrBitmap(text: String): Bitmap? {
    val matrix = qrMatrix(text) ?: return null
    val size = matrix.width
    val pixels = IntArray(size * size)
    var i = 0
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[i++] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

/**
 * Pages the plugin asks to open. Sign-in uses a WebView whose cookies are
 * dropped when the page closes: the jar is process-wide, and the contract
 * says that page is forgotten afterwards.
 */
internal object PluginWeb {
    private const val TAG = "Mygomusic"
    private var session: Session? = null

    private class Session(
        val request: WebSignInRequest,
        val deferred: CompletableDeferred<String>,
    ) {
        var started = false
        var activity: PluginWebActivity? = null
    }

    fun open(context: Context, url: String) {
        val page = webPage(url) ?: return
        val intent = Intent(context, PluginWebActivity::class.java).putExtra(PluginWebActivity.EXTRA_URL, page)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openExternal(context, page)
        }
    }

    fun openExternal(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_found, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun signIn(context: Context, request: WebSignInRequest): String {
        if (session != null) throw IllegalStateException("can't show a sign-in page here")
        val deferred = CompletableDeferred<String>()
        val current = Session(request, deferred)
        session = current
        coroutineContext.job.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            Handler(Looper.getMainLooper()).post {
                current.activity?.abandon()
                if (session === current) session = null
            }
        }
        try {
            withContext(Dispatchers.Main) {
                val intent = Intent(context, PluginWebActivity::class.java)
                    .putExtra(PluginWebActivity.EXTRA_SIGN_IN, true)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            return deferred.await()
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (session === current) session = null
        }
    }

    fun take(activity: PluginWebActivity): WebSignInRequest? {
        val current = session ?: return null
        current.activity = activity
        return current.request
    }

    /** False the first time this sign-in loads, so a recreated page does not wipe cookies again. */
    fun markStarted(): Boolean {
        val current = session ?: return true
        if (current.started) return true
        current.started = true
        return false
    }

    fun detach(activity: PluginWebActivity) {
        if (session?.activity === activity) session?.activity = null
    }

    fun complete(cookie: String) {
        val current = session ?: return
        session = null
        current.deferred.complete(cookie)
    }

    fun fail(message: String) {
        val current = session ?: return
        session = null
        if (!current.deferred.isCompleted) {
            current.deferred.completeExceptionally(IllegalStateException(message))
        }
    }
}

class PluginWebActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var signIn: WebSignInRequest? = null
    private var deadline = 0L
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.mygomusic_plugin_web)
        val appBar = findViewById<AppBarLayout>(R.id.appbarlayout)
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val frame = findViewById<FrameLayout>(R.id.mygomusic_web)
        val progress = findViewById<LinearProgressIndicator>(R.id.mygomusic_web_progress)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mygomusic_web_root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(bars.left, bars.top, bars.right, 0)
            frame.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        val signingIn = intent.getBooleanExtra(EXTRA_SIGN_IN, false)
        val request = if (signingIn) PluginWeb.take(this) else null
        if (signingIn && request == null) {
            finish()
            return
        }
        signIn = request
        val url = request?.url ?: webPage(intent.getStringExtra(EXTRA_URL).orEmpty())
        if (url == null) {
            finish()
            return
        }
        toolbar.title = if (request != null) {
            getString(R.string.mygomusic_sign_in)
        } else {
            Uri.parse(url).host ?: url
        }
        toolbar.setNavigationOnClickListener {
            if (request != null) giveUp() else finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val page = web
                if (page != null && page.canGoBack()) page.goBack()
                else if (signIn != null) giveUp()
                else finish()
            }
        })
        val view = try {
            WebView(this)
        } catch (e: Throwable) {
            Log.w(TAG, "webview", e)
            if (request != null) giveUp(getString(R.string.mygomusic_sign_in_no_page))
            else {
                PluginWeb.openExternal(this, url)
                finish()
            }
            return
        }
        web = view
        frame.addView(view, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (request != null) settings.cacheMode = WebSettings.LOAD_NO_CACHE
        val agent = request?.userAgent.orEmpty()
        if (agent.isNotBlank()) settings.userAgentString = agent
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, resource: WebResourceRequest): Boolean {
                return leavePage(resource.url)
            }

            @Deprecated("Use the WebResourceRequest overload")
            override fun shouldOverrideUrlLoading(view: WebView, next: String): Boolean {
                return leavePage(Uri.parse(next))
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.isIndeterminate = false
                progress.setProgressCompat(newProgress, true)
                progress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
            }
        }
        if (request != null) {
            deadline = SystemClock.elapsedRealtime() + SIGN_IN_TIMEOUT_MILLIS
            val cookies = cookieManager()
            if (cookies == null) {
                giveUp(getString(R.string.mygomusic_sign_in_no_page))
                return
            }
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(view, true)
            if (PluginWeb.markStarted()) {
                view.loadUrl(url)
                poll()
            } else {
                forget {
                    if (!settled) {
                        view.loadUrl(url)
                        poll()
                    }
                }
            }
        } else {
            view.loadUrl(url)
        }
    }

    fun abandon() {
        if (settled || isDestroyed) return
        settled = true
        handler.removeCallbacksAndMessages(null)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!settled && signIn != null && isFinishing && !isChangingConfigurations) {
            settled = true
            PluginWeb.fail(getString(R.string.mygomusic_sign_in_closed))
        }
        PluginWeb.detach(this)
        web?.apply {
            stopLoading()
            destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun poll() {
        if (settled) return
        val request = signIn ?: return
        if (SystemClock.elapsedRealtime() >= deadline) {
            giveUp(getString(R.string.mygomusic_sign_in_timeout))
            return
        }
        val header = try {
            cookieHeader(CookieManager.getInstance().getCookie(request.cookiesOf), request.until)
        } catch (e: Throwable) {
            Log.w(TAG, "cookies", e)
            null
        }
        if (header != null) {
            succeed(header)
            return
        }
        handler.postDelayed({ poll() }, 400)
    }

    private fun succeed(header: String) {
        if (settled) return
        settled = true
        handler.removeCallbacksAndMessages(null)
        forget {
            PluginWeb.complete(header)
            if (!isFinishing) finish()
        }
    }

    private fun giveUp(message: String = getString(R.string.mygomusic_sign_in_closed)) {
        if (settled) return
        settled = true
        handler.removeCallbacksAndMessages(null)
        forget {
            PluginWeb.fail(message)
            if (!isFinishing) finish()
        }
    }

    private fun leavePage(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false
        if (scheme == "about" || scheme == "data" || scheme == "javascript" || scheme == "blob") return true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
        return true
    }

    /** The WebView cookie jar is shared by the process, so a sign-in page must not keep it. */
    private fun forget(then: () -> Unit) {
        val cookies = cookieManager()
        if (cookies == null) {
            then()
            return
        }
        try {
            cookies.removeAllCookies {
                runOnUiThread {
                    runCatching { WebStorage.getInstance().deleteAllData() }
                    web?.clearCache(true)
                    then()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cookies", e)
            then()
        }
    }

    private fun cookieManager(): CookieManager? {
        return try {
            CookieManager.getInstance()
        } catch (e: Throwable) {
            Log.w(TAG, "cookies", e)
            null
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SIGN_IN = "signIn"
        private const val TAG = "Mygomusic"
    }
}
