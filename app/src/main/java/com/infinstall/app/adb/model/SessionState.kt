package com.infinstall.app.adb.model

/**
 * Connection lifecycle. Only [com.infinstall.app.adb.session.AdbSession] may transition this.
 *
 * Principle: Connected stays Connected while the remote adbd accepts our session.
 * Timeouts, permission errors, empty listings — none of these change state.
 */
sealed class SessionState {
    data object Disconnected : SessionState()

    data class Connecting(
        val host: String,
        val port: Int,
    ) : SessionState()

    data class Pairing(
        val host: String,
        val pairPort: Int,
    ) : SessionState()

    /**
     * @param sinceMs SystemClock elapsedRealtime at connect success
     * @param lastError last **operation** error (does not imply disconnect)
     */
    data class Connected(
        val host: String,
        val port: Int,
        val sinceMs: Long,
        val lastError: String? = null,
    ) : SessionState() {
        val endpoint: String get() = "$host:$port"
    }

    val isConnected: Boolean get() = this is Connected
}
