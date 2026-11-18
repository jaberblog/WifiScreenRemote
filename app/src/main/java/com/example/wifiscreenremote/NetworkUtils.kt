package com.example.wifiscreenremote

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun localIpv4Address(): String? {
        return try {
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val priority = when {
                        networkInterface.name.startsWith("wlan", ignoreCase = true) -> 0
                        networkInterface.name.startsWith("wifi", ignoreCase = true) -> 1
                        networkInterface.name.startsWith("eth", ignoreCase = true) -> 2
                        else -> 5
                    }
                    candidates += priority to address.hostAddress.orEmpty()
                }
            }
            candidates.minByOrNull { it.first }?.second
        } catch (_: Exception) {
            null
        }
    }
}
