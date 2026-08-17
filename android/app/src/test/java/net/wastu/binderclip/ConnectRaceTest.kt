package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectRaceTest {
    @Test
    fun oneSocketPerEndpoint() {
        val combos = ConnectRace.combinations(
            listOf("100.96.0.2:39421", "192.168.1.5:39421", "100.96.0.2:39421"),
        )
        assertEquals(listOf("100.96.0.2:39421", "192.168.1.5:39421"), combos)
    }

    @Test
    fun capsTotalSockets() {
        val endpoints = (1..12).map { "10.0.0.$it:39421" }
        assertEquals(ConnectRace.MAX_SOCKETS, ConnectRace.combinations(endpoints).size)
    }
}
