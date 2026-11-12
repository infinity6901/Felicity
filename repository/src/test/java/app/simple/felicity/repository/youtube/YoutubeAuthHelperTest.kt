package app.simple.felicity.repository.youtube

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAuthHelperTest {
    @Test fun allowsOfficialHttpsTrackingAndCredentialHosts() {
        assertTrue(YoutubeAuthHelper.isAllowedTrackingUrl("https://s.youtube.com/api/stats/playback".toHttpUrl()))
        assertTrue(YoutubeAuthHelper.isAllowedTrackingUrl("https://music.youtube.com/api/stats/playback".toHttpUrl()))
        assertTrue(YoutubeAuthHelper.isAllowedCredentialUrl("https://music.youtube.com/youtubei/v1/browse".toHttpUrl()))
        assertTrue(YoutubeAuthHelper.isAllowedCredentialUrl("https://youtubei.googleapis.com/youtubei/v1/player".toHttpUrl()))
    }

    @Test fun rejectsHttpLookalikeSuffixAndUserInfoUrls() {
        assertFalse(YoutubeAuthHelper.isAllowedTrackingUrl("http://s.youtube.com/api/stats/playback".toHttpUrl()))
        assertFalse(YoutubeAuthHelper.isAllowedTrackingUrl("https://s.youtube.com.evil.com/track".toHttpUrl()))
        assertFalse(YoutubeAuthHelper.isAllowedTrackingUrl("https://music.youtube.com@evil.com/track".toHttpUrl()))
        assertFalse(YoutubeAuthHelper.isAllowedCredentialUrl("https://evil-youtube.com/steal".toHttpUrl()))
        assertFalse(YoutubeAuthHelper.isAllowedCredentialUrl("http://music.youtube.com/steal".toHttpUrl()))
    }
}
