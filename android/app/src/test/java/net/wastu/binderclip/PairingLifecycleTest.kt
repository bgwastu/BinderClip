package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PairingLifecycleTest {
    @Test
    fun rememberedPeerStateTransition() {
        val peer = RememberedPeer(
            name = "MacBook Pro",
            host = "192.168.1.5",
            port = 39421,
            deviceId = "mbp-123",
            platform = "macOS",
            connected = true
        )
        assertTrue(peer.connected)

        val disconnected = peer.copy(connected = false)
        assertFalse(disconnected.connected)
        assertEquals(peer.deviceId, disconnected.deviceId)
        assertEquals(peer.name, disconnected.name)
        assertEquals(peer.host, disconnected.host)
    }

    @Test
    fun parseEndpointHostPort() {
        val parsed = SyncProtocol.parseEndpoint("192.168.1.50:39421")
        assertNotNull(parsed)
        assertEquals("192.168.1.50", parsed!!.first)
        assertEquals(39421, parsed.second)
        assertEquals(SyncProtocol.DEFAULT_PORT, SyncProtocol.parseEndpoint("example.local")!!.second)
        assertEquals("2001:db8::1", SyncProtocol.parseEndpoint("[2001:db8::1]:39421")!!.first)
    }

    @Test
    fun mergeAdvertisedEndpointsPrefersIncoming() {
        val current = listOf("192.168.50.168:39421", "100.96.0.2:39421")
        val incoming = listOf("192.168.60.249:39421", "100.96.0.2:39421")
        val merged = SyncProtocol.mergeAdvertisedEndpoints(current, incoming)
        assertEquals("192.168.60.249:39421", merged[0])
        assertEquals("100.96.0.2:39421", merged[1])
        assertEquals("192.168.50.168:39421", merged[2])
        assertEquals(3, merged.size)
    }

    @Test
    fun endpointsFromJsonReadsArray() {
        val json = org.json.JSONObject("""{"type":"endpoints","endpoints":["10.0.0.5:39421","100.64.0.1:39421"]}""")
        val endpoints = SyncProtocol.endpointsFromJson(json)
        assertEquals(listOf("10.0.0.5:39421", "100.64.0.1:39421"), endpoints)
    }

    @Test
    fun peerCandidatesDeduplication() {
        val endpoints = listOf("192.168.1.50:39421", "100.64.0.1:39421", "192.168.1.50:39421")
        val unique = endpoints.distinct().filter { it.isNotBlank() }
        assertEquals(2, unique.size)
        assertEquals("192.168.1.50:39421", unique[0])
        assertEquals("100.64.0.1:39421", unique[1])
    }
}
