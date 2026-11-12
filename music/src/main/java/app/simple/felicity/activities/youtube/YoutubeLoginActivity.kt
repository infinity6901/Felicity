package app.simple.felicity.activities.youtube

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import app.simple.felicity.R
import app.simple.felicity.repository.youtube.YoutubeLoginWebViewClient
import app.simple.felicity.repository.youtube.YoutubeSessionStore

/**
 * "Sign in with YouTube": hosts a WebView pointed at Google's login flow and
 * finishes itself as soon as a logged-in session cookie shows up for
 * music.youtube.com, saving it via [YoutubeSessionStore].
 *
 * This is a minimal, functional screen using plain views rather than
 * Felicity's themed decoration components — restyle to match the app's
 * design system (BaseActivity, themed toolbar, etc.) before shipping.
 */
class YoutubeLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_login)

        webView = findViewById(R.id.youtube_login_webview)
        progressBar = findViewById(R.id.youtube_login_progress)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = YoutubeLoginWebViewClient(this) { session ->
            YoutubeSessionStore.save(applicationContext, session)
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }

        webView.loadUrl(YoutubeLoginWebViewClient.LOGIN_URL)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Fully clears any existing YouTube WebView cookies before a fresh login. */
        fun resetForNewLogin() {
            YoutubeLoginWebViewClient.clearCookies()
        }
    }
}
