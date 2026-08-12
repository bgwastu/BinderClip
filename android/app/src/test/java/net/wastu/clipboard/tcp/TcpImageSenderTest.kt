package net.wastu.clipboard.tcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TcpImageSenderTest {

    @Test
    fun senderConnectsAndPushesData() {
        val payload = ByteArray(2048) { (it % 256).toByte() }
        val server = ServerSocket(0)

        try {
            val received = ByteArray(payload.size)
            val serverThread = Thread {
                val client = server.accept()
                val input = client.getInputStream()
                var offset = 0
                while (offset < received.size) {
                    val n = input.read(received, offset, received.size - offset)
                    if (n == -1) break
                    offset += n
                }
                client.close()
            }
            serverThread.start()

            TcpImageSender.send("127.0.0.1", server.localPort, payload)

            serverThread.join(5000)
            assertArrayEquals(payload, received)
        } finally {
            server.close()
        }
    }

    @Test
    fun senderTransfersExactly20MiB() {
        val payload = ByteArray(20_971_520) { (it * 31).toByte() }
        val server = ServerSocket(0)
        val received = ByteArray(payload.size)
        val complete = CountDownLatch(1)
        try {
            Thread {
                server.accept().use { client ->
                    val input = client.getInputStream()
                    var offset = 0
                    while (offset < received.size) {
                        val count = input.read(received, offset, received.size - offset)
                        if (count < 0) return@Thread
                        offset += count
                    }
                    complete.countDown()
                }
            }.start()
            TcpImageSender.send("127.0.0.1", server.localPort, payload)
            assertTrue("20 MiB TCP transfer should complete", complete.await(30, TimeUnit.SECONDS))
            assertArrayEquals(payload, received)
        } finally {
            server.close()
        }
    }

    @Test
    fun senderPrependsNonceToData() {
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val payload = ByteArray(512) { (it % 256).toByte() }
        val server = ServerSocket(0)

        try {
            val received = ByteArray(nonce.size + payload.size)
            val serverThread = Thread {
                val client = server.accept()
                val input = client.getInputStream()
                var offset = 0
                while (offset < received.size) {
                    val n = input.read(received, offset, received.size - offset)
                    if (n == -1) break
                    offset += n
                }
                client.close()
            }
            serverThread.start()

            TcpImageSender.send("127.0.0.1", server.localPort, payload, nonce = nonce)

            serverThread.join(5000)
            assertArrayEquals(nonce, received.sliceArray(0 until 16))
            assertArrayEquals(payload, received.sliceArray(16 until received.size))
        } finally {
            server.close()
        }
    }

    @Test
    fun senderThrowsOnConnectionRefused() {
        // Use a port that is not listening
        try {
            TcpImageSender.send("127.0.0.1", 1, ByteArray(10), connectTimeoutMs = 500)
            fail("Expected TcpTransferException")
        } catch (e: TcpTransferException) {
            // expected
        }
    }
}
