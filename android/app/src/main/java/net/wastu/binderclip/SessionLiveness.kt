package net.wastu.binderclip

object SessionLiveness {
    fun ipv4Bytes(ip: String): ByteArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in 0..3) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    fun matchingPrefixBits(remote: String, local: String): Int {
        val remoteBytes = ipv4Bytes(remote) ?: return -1
        val localBytes = ipv4Bytes(local) ?: return -1
        var bits = 0
        for (index in 0 until 4) {
            val remoteOctet = remoteBytes[index].toInt() and 0xff
            val localOctet = localBytes[index].toInt() and 0xff
            if (remoteOctet == localOctet) {
                bits += 8
                continue
            }
            val xor = remoteOctet xor localOctet
            for (shift in 7 downTo 0) {
                if (xor and (1 shl shift) != 0) return bits
                bits += 1
            }
        }
        return bits
    }

    fun boundLocalAddress(remote: String, localAddresses: List<String>): String? {
        val locals = localAddresses.filter { ipv4Bytes(it) != null }
        if (locals.isEmpty()) return null
        if (remote.startsWith("100.")) {
            locals.firstOrNull { it.startsWith("100.") }?.let { return it }
        }
        return locals.maxByOrNull { matchingPrefixBits(remote, it) }
    }

    fun shouldEvict(boundLocal: String?, currentLocals: List<String>): Boolean {
        val bound = boundLocal ?: return false
        if (ipv4Bytes(bound) == null) return false
        return bound !in currentLocals
    }

    fun isAlive(
        boundLocal: String?,
        currentLocals: List<String>,
        lastHeardMs: Long?,
        nowMs: Long,
        budgetMs: Long = SyncProtocol.HEARTBEAT_BUDGET_MS,
    ): Boolean {
        if (lastHeardMs == null) return false
        if (shouldEvict(boundLocal, currentLocals)) return false
        return nowMs - lastHeardMs <= budgetMs
    }
}
