package app.simple.felicity.repository.youtube

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Drop this into a WebView-hosting Activity/Fragment to implement "Sign in with YouTube":
 *
 * ```
 * webView.settings.javaScriptEnabled = true
 * CookieManager.getInstance().setAcceptCookie(true)
 * webView.webViewClient = YoutubeLoginWebViewClient(context) { session ->
 *     YoutubeSessionStore.save(context, session)
 *     // close the login screen, refresh UI to "logged in" state
 * }
 * webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/")
 * ```
 *
 * There is no official OAuth flow being used here — this mirrors the cookies of a
 * real browser session, the same way NewPipe/ytmusicapi-style tools authenticate.
 * The user is signing into their real Google account inside your WebView, so treat
 * the captured cookies as sensitive credentials (see [YoutubeSessionStore]).
 */
class YoutubeLoginWebViewClient(
    private val context: Context,
    private val onLoginDetected: (YoutubeSession) -> Unit,
) : WebViewClient() {

    private var alreadyReported = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return !isAllowedLoginUrl(request.url)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return true
        return !isAllowedLoginUrl(uri)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)

        if (alreadyReported || url.isNullOrBlank()) return
        val parsedUri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val host = parsedUri.host?.lowercase() ?: return
        if (parsedUri.scheme?.lowercase() != "https" || host != "music.youtube.com") return

        val cookieString = CookieManager.getInstance().getCookie("https://music.youtube.com")
            ?: return

        val session = YoutubeSession.fromCookieString(cookieString)
        if (session.isLoggedIn) {
            alreadyReported = true
            onLoginDetected(session)
        }
    }

    companion object {
        private val ALLOWED_LOGIN_HOSTS = setOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "music.youtube.com",
            "www.youtube.com",
            "youtube.com",
            "consent.youtube.com",
            "consent.google.com",
            "myaccount.google.com",
            "gds.google.com",
        )

        fun isAllowedLoginUrl(uri: Uri?): Boolean {
            if (uri == null) return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (!uri.userInfo.isNullOrEmpty()) return false
            val host = uri.host?.lowercase() ?: return false
            return host in ALLOWED_LOGIN_HOSTS
        }

        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/"

        fun clearCookies() {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        }
    }
}
