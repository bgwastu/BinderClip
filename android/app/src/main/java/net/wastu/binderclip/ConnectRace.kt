package net.wastu.binderclip

/** One WebSocket per Mac address. Racing the same IP on Wi-Fi and default
 *  dual-authenticates; the Mac then replaces the session and kills the winner. */
object ConnectRace {
    const val MAX_SOCKETS = 8

    fun combinations(endpoints: List<String>): List<String> =
        endpoints.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_SOCKETS)
}
