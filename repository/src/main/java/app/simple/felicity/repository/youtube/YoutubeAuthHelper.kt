package app.simple.felicity.repository.youtube

import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.TimeZone

object YoutubeAuthHelper {

    fun buildContextBody(
        session: YoutubeSession?,
        client: JSONObject = YoutubeConstants.Client.WEB_REMIX,
    ): JSONObject {
        val user = JSONObject().apply {
            put("lockedSafetyMode", false)
            session?.dataSyncId?.let { put("onBehalfOfUser", it) }
        }

        val context = JSONObject().apply {
            put("client", client)
            put("user", user)
            session?.visitorData?.let { put("visitorData", it) }
        }

        return JSONObject().apply {
            put("context", context)
        }
    }

    fun Request.Builder.applyYoutubeHeaders(
        url: HttpUrl,
        session: YoutubeSession?,
        client: JSONObject = YoutubeConstants.Client.WEB_REMIX,
    ) = apply {
        headersFor(url, session, client).forEach { (name, value) -> addHeader(name, value) }
    }

    private fun headersFor(
        url: HttpUrl,
        session: YoutubeSession?,
        client: JSONObject,
    ): Map<String, String> {
        val origin = "${url.scheme}://${url.host}"
        val nowMs = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val utcOffsetMinutes = tz.getOffset(nowMs) / 60000

        val headers = mutableMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Origin" to origin,
            "Referer" to "$origin/",
            "X-Goog-Api-Format-Version" to "1",
            "X-Origin" to origin,
            "X-Goog-Event-Time" to nowMs.toString(),
            "X-Goog-Request-Time" to nowMs.toString(),
            "X-YouTube-Utc-Offset" to utcOffsetMinutes.toString(),
            "X-YouTube-Time-Zone" to tz.id,
        )

        client.optString("clientVersion").takeIf { it.isNotBlank() }?.let {
            headers["X-YouTube-Client-Version"] = it
        }
        client.optString("xClientName").takeIf { it.isNotBlank() }?.let {
            headers["X-YouTube-Client-Name"] = it
        }
        client.optString("userAgent").takeIf { it.isNotBlank() }?.let {
            headers["User-Agent"] = it
        }
        session?.visitorData?.let { headers["X-Goog-Visitor-Id"] = it }

        if (isAllowedCredentialUrl(url)) {
            session?.rawCookies?.takeIf { it.isNotEmpty() }?.let { cookies ->
                headers["Cookie"] = session.toRawCookieHeader()

                val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]
                if (sapisid != null) {
                    headers["Authorization"] = generateSapisidHash(sapisid, origin)
                }
            }
        }

        return headers
    }

    /**
     * YouTube/Google's "SAPISIDHASH" scheme: proves the caller holds the SAPISID
     * cookie for a given origin without sending the cookie value itself in the
     * Authorization header.
     */
    private val ALLOWED_CREDENTIAL_HOSTS = setOf(
        "music.youtube.com",
        "www.youtube.com",
        "youtube.com",
        "youtubei.googleapis.com",
    )

    private val ALLOWED_TRACKING_HOSTS = setOf(
        "s.youtube.com",
        "music.youtube.com",
        "www.youtube.com",
    )

    fun isAllowedCredentialUrl(url: HttpUrl): Boolean =
        url.isHttps &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.host.lowercase() in ALLOWED_CREDENTIAL_HOSTS

    fun isAllowedTrackingUrl(url: HttpUrl): Boolean =
        url.isHttps &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.host.lowercase() in ALLOWED_TRACKING_HOSTS

    private fun generateSapisidHash(sapisid: String, origin: String): String {
        val currentTime = System.currentTimeMillis() / 1000
        val hash = sha1("$currentTime $sapisid $origin")
        val token = "${currentTime}_$hash"
        return "SAPISIDHASH $token SAPISID1PHASH $token SAPISID3PHASH $token"
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
