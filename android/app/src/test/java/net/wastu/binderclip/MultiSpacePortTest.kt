package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the engine supports multiple Android spaces (users / work profile /
 * app clones) on one device out of the box, without UI changes.
 *
 * Isolation facts:
 *  - SharedPreferences and Android Keystore are per-UID, so each space already
 *    has isolated DeviceStore state and an isolated (non-exportable) group-key
 *    keystore alias (DeviceStore appends ".u<userId>").
 *  - The only cross-space conflict was the fixed host port; DirectServer now
 *    derives a distinct port per userId so two spaces can host simultaneously.
 */
class MultiSpacePortTest {
    @Test
    fun `user 0 keeps canonical port`() {
        assertEquals(39_421, DirectServer.portForUserId(39_421, 0))
    }

    @Test
    fun `secondary users get distinct ports`() {
        val user10 = DirectServer.portForUserId(39_421, 10)
        val user11 = DirectServer.portForUserId(39_421, 11)
        assertTrue("user 10 port differs", user10 != 39_421)
        assertTrue("user 11 port differs", user11 != 39_421)
        assertTrue("distinct spaces get distinct ports", user10 != user11)
        assertTrue("ports stay under 65535", user11 < 65_535)
    }

    @Test
    fun `ports are valid for ServerSocket`() {
        val port = DirectServer.portForUserId(39_421, 100)
        assertTrue(port in 1..65_535)
    }
}
