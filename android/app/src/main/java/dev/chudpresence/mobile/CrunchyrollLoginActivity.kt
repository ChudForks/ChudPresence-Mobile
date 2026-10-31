package dev.chudpresence.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class CrunchyrollLoginActivity : ComponentActivity() {
    private val app get() = application as ChudPresenceApplication
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private var completing = false
    private val codeVerifier = UUID.randomUUID().toString()
    private val anonymousId = UUID.randomUUID().toString()
    private val deviceId = UUID.randomUUID().toString()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.crunchyroll_login_title)

        val padding = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            text = getString(R.string.crunchyroll_login_warning)
            setPadding(padding, padding, padding, padding)
        }
        progress = ProgressBar(this).apply { visibility = android.view.View.GONE }
        val cancel = Button(this).apply {
            text = getString(R.string.crunchyroll_login_cancel)
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }
        retry = Button(this).apply {
            text = getString(R.string.crunchyroll_login_retry)
            visibility = android.view.View.GONE
            setOnClickListener {
                visibility = android.view.View.GONE
                webView.visibility = android.view.View.VISIBLE
                status.text = getString(R.string.crunchyroll_login_prompt)
                // Authorization codes are single use; restart sign-in after a failed exchange.
                webView.loadUrl(app.crunchyrollApi.authorizationUrl(codeVerifier, anonymousId))
            }
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(22, 23, 29))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleNavigation(request.url)

                @Deprecated("Legacy WebView callback")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleNavigation(Uri.parse(url))

                override fun onPageFinished(view: WebView, url: String) {
                    if (!completing && retry.visibility != android.view.View.VISIBLE) {
                        status.text = getString(R.string.crunchyroll_login_prompt)
                    }
                }
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padding, 0, padding, padding)
            addView(progress)
            addView(retry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        webView.loadUrl(app.crunchyrollApi.authorizationUrl(codeVerifier, anonymousId))
    }

    private fun handleNavigation(uri: Uri): Boolean {
        if (uri.scheme != "sso.crunchyroll" || uri.host != "auth") return uri.scheme != "https"
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        if (code.isNullOrBlank()) {
            status.text = getString(R.string.crunchyroll_login_incomplete,
                error ?: getString(R.string.crunchyroll_login_unknown_error))
            return true
        }
        if (completing) return true
        completing = true
        webView.visibility = android.view.View.INVISIBLE
        progress.visibility = android.view.View.VISIBLE
        status.text = getString(R.string.crunchyroll_login_finishing)
        lifecycleScope.launch {
            try {
                val session = app.crunchyrollApi.exchangeAuthorizationCode(code, codeVerifier, deviceId)
                app.settingsStore.saveCrunchyrollSession(session)
                setResult(Activity.RESULT_OK)
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (issue: Exception) {
                completing = false
                progress.visibility = android.view.View.GONE
                retry.visibility = android.view.View.VISIBLE
                status.text = getString(R.string.crunchyroll_login_failed,
                    issue.message ?: getString(R.string.crunchyroll_login_unknown_error))
            }
        }
        return true
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
