package net.wastu.binderclip

enum class ConnectionPhase {
    NotPaired,
    Connecting,
    Connected,
    Reconnecting,
}

object ConnectionStatus {
    fun phase(paired: Boolean, connected: Boolean, connecting: Boolean): ConnectionPhase = when {
        !paired -> ConnectionPhase.NotPaired
        connected -> ConnectionPhase.Connected
        connecting -> ConnectionPhase.Connecting
        else -> ConnectionPhase.Reconnecting
    }

    fun label(phase: ConnectionPhase, peerName: String?): String = when (phase) {
        ConnectionPhase.NotPaired -> "Not paired"
        ConnectionPhase.Connecting -> "Connecting…"
        ConnectionPhase.Connected -> peerName?.takeIf { it.isNotBlank() } ?: "Connected"
        ConnectionPhase.Reconnecting -> "Reconnecting…"
    }
}

/** Single owner for whether a connect race may start and how long to wait after failure. */
class ReconnectPolicy(
    private val maxDelaySeconds: Long = 16L,
) {
    var delaySeconds: Long = 1L
        private set

    fun shouldStartConnect(force: Boolean, connected: Boolean, connecting: Boolean): Boolean {
        if (force) return true
        return !connected && !connecting
    }

    fun resetBackoff() {
        delaySeconds = 1L
    }

    fun shouldAnnounceUnreachable(): Boolean = delaySeconds >= 8L

    fun nextBackoffSeconds(): Long {
        val current = delaySeconds
        delaySeconds = (delaySeconds * 2).coerceAtMost(maxDelaySeconds)
        return current
    }
}
