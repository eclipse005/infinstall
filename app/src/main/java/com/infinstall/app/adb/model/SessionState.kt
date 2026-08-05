package com.infinstall.app.adb.model

/**
 * Connection lifecycle. Only [com.infinstall.app.adb.session.AdbSession] may transition this.
 *
 * Sticky: stay [Connected] while the ADB link to adbd is alive.
 * Timeouts, stream glitches, permission errors, empty listings — do **not** leave Connected.
 * Leave only: user disconnect, connect failure, or proven link death.
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
