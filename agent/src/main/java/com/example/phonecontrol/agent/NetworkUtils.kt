package com.example.phonecontrol.agent

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    /**
     * Finds the best local IPv4 address without needing a location permission.
     * Wi-Fi is preferred, then hotspot / Ethernet interfaces.
     */
    fun localIpv4(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            for (networkInterface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val name = networkInterface.name ?: ""
                for (address in Collections.list(networkInterface.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host.startsWith("169.254.")) continue
                    candidates.add(name to host)
                }
            }
        } catch (_: Exception) {
            return null
        }

        return candidates
            .sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") -> 1
                    name.startsWith("eth") -> 2
                    else -> 3
                }
            }
            .firstOrNull()
            ?.second
    }
}
