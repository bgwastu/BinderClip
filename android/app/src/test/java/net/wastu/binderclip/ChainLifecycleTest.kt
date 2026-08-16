package net.wastu.binderclip

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ChainLifecycleTest {
    @Test
    fun helloWithCandidateHostsRoundTrip() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val hosts = listOf("192.168.1.100", "100.64.0.5", "10.0.0.12")
        val message = JSONObject()
            .put("type", "hello")
            .put("deviceID", "mac-studio-1")
            .put("name", "Studio Mac")
            .put("platform", "macOS")
            .put("hosts", JSONArray(hosts))

        val encrypted = DirectProtocol.seal(message, key)
        val output = ByteArrayOutputStream()
        DirectProtocol.write(DataOutputStream(output), encrypted)
        val decoded = DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        val opened = DirectProtocol.open(decoded, key)

        assertEquals("hello", opened.getString("type"))
        assertEquals("mac-studio-1", opened.getString("deviceID"))
        assertEquals("Studio Mac", opened.getString("name"))
        val decodedHosts = opened.getJSONArray("hosts")
        assertEquals(3, decodedHosts.length())
        assertEquals("192.168.1.100", decodedHosts.getString(0))
        assertEquals("100.64.0.5", decodedHosts.getString(1))
        assertEquals("10.0.0.12", decodedHosts.getString(2))
    }

    @Test
    fun rosterPreservesOfflineMemberState() {
        val key = ByteArray(32) { (it + 5).toByte() }
        val membersArray = JSONArray().apply {
            put(JSONObject().put("id", "dev-1").put("name", "Host Mac").put("host", "192.168.1.10").put("port", 39421).put("platform", "macOS").put("connected", true))
            put(JSONObject().put("id", "dev-2").put("name", "Pixel 9").put("host", "192.168.1.20").put("port", 39421).put("platform", "Android").put("connected", false))
        }
        val message = JSONObject().put("type", "roster").put("members", membersArray)

        val encrypted = DirectProtocol.seal(message, key)
        val output = ByteArrayOutputStream()
        DirectProtocol.write(DataOutputStream(output), encrypted)
        val decoded = DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        val opened = DirectProtocol.open(decoded, key)

        assertEquals("roster", opened.getString("type"))
        val members = opened.getJSONArray("members")
        assertEquals(2, members.length())
        val hostMember = members.getJSONObject(0)
        assertEquals("dev-1", hostMember.getString("id"))
        assertTrue(hostMember.getBoolean("connected"))
        val disconnectedMember = members.getJSONObject(1)
        assertEquals("dev-2", disconnectedMember.getString("id"))
        assertFalse(disconnectedMember.getBoolean("connected"))
    }

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
}
