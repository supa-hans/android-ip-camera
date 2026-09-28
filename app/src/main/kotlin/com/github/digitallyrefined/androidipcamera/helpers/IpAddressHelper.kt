package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks which of the device's IP addresses to show/copy in the UI. A phone doing camera duty
 * over Tailscale usually has more than one active interface (Wi-Fi LAN + the tailscale0
 * tunnel), and Java doesn't guarantee which one NetworkInterface.getNetworkInterfaces() lists
 * first - so the "first non-loopback IPv4" address picked automatically can flip to the wrong
 * one, which is a problem the moment something (like Frigate) is wired to a specific address.
 * The "display_ip" preference lets the user pin a specific interface (e.g. "tailscale0") so the
 * shown/copied URL stays put; "auto" (the default) keeps the old first-found behaviour.
 */
object IpAddressHelper {
    private const val PREF_KEY = "display_ip"
    private const val AUTO = "auto"

    /** All current non-loopback IPv4 addresses, as (interface name, address) pairs. */
    fun listAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                iface.inetAddresses.toList().forEach { address ->
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress
                        if (host != null) result.add(iface.name to host)
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /** The address to show/copy right now, honouring the user's "display_ip" pin if set and
     *  currently up - falling back to the first-found address otherwise (covers both "auto"
     *  and a pinned interface that's temporarily down, e.g. Tailscale disconnected). */
    fun resolve(context: Context): String {
        val chosen = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, AUTO) ?: AUTO
        val addresses = listAddresses()
        if (chosen != AUTO) {
            addresses.firstOrNull { it.first == chosen }?.let { return it.second }
        }
        return addresses.firstOrNull()?.second ?: "unknown"
    }
}
