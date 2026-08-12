package net.wastu.clipboard.tcp

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {
    fun getLocalIpAddresses(): List<String> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.toList() ?: return emptyList()

        return interfaces
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filterNot { it.startsWith("169.254.") }
            .distinct()
            .toList()
    }

    fun getLocalIpAddress(): String? {
        return getLocalIpAddresses().firstOrNull()
    }
}
