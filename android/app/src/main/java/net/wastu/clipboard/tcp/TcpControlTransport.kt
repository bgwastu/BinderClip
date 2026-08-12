package net.wastu.clipboard.tcp

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Fixed-port transport; Session performs authentication after connection. */
class TcpControlTransport(private val onSocket: (Socket) -> Unit) : Closeable {
    companion object { const val PORT = 39421 }
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var closed = false
    private var server: ServerSocket? = null

    fun start() = executor.execute {
        runCatching { ServerSocket(PORT).also { server = it }.use { while (!closed) onSocket(it.accept()) } }
    }

    fun connect(addresses: List<String>, onConnected: (String, Socket) -> Unit) = executor.execute {
        for (address in addresses.distinct()) {
            if (closed) return@execute
            runCatching { Socket().apply { connect(InetSocketAddress(address, PORT), 1500) } }
                .onSuccess { onConnected(address, it); return@execute }
        }
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        executor.shutdownNow()
    }
}
