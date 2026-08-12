package net.wastu.clipboard.tcp

import java.net.InetSocketAddress
import java.net.Socket

object TcpImageSender {
    /**
     * Connects to a TCP server and sends the given data, optionally prefixed with a nonce.
     * The OS chooses the route for the destination. Binding to one interface would
     * break mesh/VPN routes when the destination is reachable elsewhere.
     */
    fun send(
        host: String,
        port: Int,
        data: ByteArray,
        nonce: ByteArray? = null,
        shouldCancel: () -> Boolean = { false },
        connectTimeoutMs: Int = 3000,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val out = socket.getOutputStream()
            if (nonce != null) {
                out.write(nonce)
            }
            var offset = 0
            while (offset < data.size) {
                if (shouldCancel()) throw TcpTransferException("Transfer cancelled")
                val count = minOf(64 * 1024, data.size - offset)
                out.write(data, offset, count)
                offset += count
                onProgress?.invoke(offset.toLong(), data.size.toLong())
            }
            out.flush()
        } catch (e: Exception) {
            throw TcpTransferException("Failed to send: ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
