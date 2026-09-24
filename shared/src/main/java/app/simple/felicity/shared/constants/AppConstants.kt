package app.simple.felicity.shared.constants

object AppConstants {
    /**
     * MusicBrainz requires every API client to identify itself with a User-Agent
     * that includes the application name, version, and a contact URL or email.
     * Requests without a proper User-Agent are rate-limited very aggressively.
     *
     * See: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
     */
    const val MUSIC_BRAINZ_USER_AGENT = "Felicity Music Player/1.0 (hamzarizwan243@gmail.com)"

    /**
     * Radio Browser explicitly asks every client for a descriptive User-Agent so its
     * maintainer can reach out to developers if something in the API needs to change.
     * No API key involved — this is purely a courtesy identifier.
     *
     * See: https://docs.radio-browser.info/
     */
    const val RADIO_BROWSER_USER_AGENT = "FelicityMusicPlayer/1.0"

    /**
     * Audius asks every client to identify itself via an `app_name` query parameter
     * (not a real API key — the API itself is fully open). Used both for search and for
     * building a track's stream URL.
     *
     * See: https://docs.audius.org/developers/guides/rest-api
     */
    const val AUDIUS_APP_NAME = "FelicityMusicPlayer"
}