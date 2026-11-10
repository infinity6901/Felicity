package app.simple.felicity.preferences

import app.simple.felicity.manager.SharedPreferences

/**
 * Stores user preferences for the local Wi-Fi music server mode.
 *
 * Provides the TCP port used by the embedded HTTP server.
 * The default port is {@code 8080}, which is readable without root on all
 * Android API levels targeted by this project.
 *
 * @author Hamza417
 */
object ServerPreferences {

    private const val SERVER_PORT = "server_mode_port"
    private const val SERVER_ALLOW_REMOTE_DELETE = "server_mode_allow_remote_delete"

    /**
     * Returns the configured TCP port for the local HTTP server.
     * Defaults to {@code 8080}.
     */
    fun getServerPort(): Int {
        return SharedPreferences.getSharedPreferences().getInt(SERVER_PORT, 8080)
    }

    /**
     * Persists the TCP port that the local HTTP server will bind to.
     *
     * @param port A valid non-privileged port number (typically 1024–65535).
     */
    fun setServerPort(port: Int) {
        SharedPreferences.getSharedPreferences().edit().putInt(SERVER_PORT, port).apply()
    }

    /**
     * Returns whether remote file deletion via `DELETE /api/songs/{id}` is enabled.
     * Defaults to `false` to prevent accidental or unauthorized LAN file deletion.
     */
    fun isAllowRemoteDelete(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(SERVER_ALLOW_REMOTE_DELETE, false)
    }

    fun setAllowRemoteDelete(allow: Boolean) {
        SharedPreferences.getSharedPreferences().edit().putBoolean(SERVER_ALLOW_REMOTE_DELETE, allow).apply()
    }
}
