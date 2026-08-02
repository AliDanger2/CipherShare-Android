package com.ciphershare.android.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Mirrors the spirit of CipherShare (desktop) Services/NetworkHelper.cs. Android conveniently
 * exposes each interface's subnet broadcast address directly via InterfaceAddress.broadcast,
 * so there's no manual subnet-mask arithmetic needed here (unlike the .NET side).
 */
object NetworkUtils {

    /**
     * A local adapter address paired with that same adapter's subnet broadcast address.
     * Both are needed together: sending from a socket bound to [localAddress] is what forces
     * the packet out that specific adapter (see DiscoveryService.broadcast) instead of
     * letting Android's routing layer pick whichever network it currently considers "default"
     * for this process - which, on a phone with mobile data active alongside Wi-Fi, can
     * silently be the cellular radio instead of Wi-Fi. An unbound broadcast socket has no way
     * to force the correct interface; a bound one does.
     */
    data class BroadcastTarget(val localAddress: InetAddress?, val broadcastAddress: InetAddress)

    /**
     * This device's primary LAN IPv4 address - the one other devices would use to reach it.
     * Wi-Fi-named interfaces (wlan/ap) are tried first so an active mobile-data interface
     * (rmnet/ccmni/etc, which is also "up" and non-virtual) can't win purely by happening to
     * enumerate before the Wi-Fi adapter - mirrors the desktop's own preference for Wi-Fi/
     * Ethernet over other adapter types.
     */
    fun getLocalIPv4(): String? {
        val candidates = usableInterfaces()
            .sortedByDescending { isLikelyWifi(it.name) }

        return candidates
            .flatMap { it.interfaceAddresses }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    /**
     * One (local address, broadcast address) pair per real, non-virtual, non-loopback, up
     * interface with an IPv4 address - mirrors NetworkHelper.GetAllBroadcastEndpoints on the
     * desktop side, including the part that actually matters: keeping each broadcast address
     * paired with the local address of the adapter it belongs to, so the caller can bind its
     * send socket to that adapter instead of sending from an unbound socket and hoping the OS
     * picks the right one. Sending out of every one of these (instead of guessing a single
     * "primary" adapter) is what makes a phone reliably discoverable regardless of which
     * adapter - Wi-Fi, a USB Ethernet dongle, a tethered hotspot - is actually on the same LAN
     * as the other devices.
     */
    fun getBroadcastTargets(): List<BroadcastTarget> {
        val targets = usableInterfaces()
            .flatMap { it.interfaceAddresses }
            .mapNotNull { ia ->
                val broadcast = ia.broadcast ?: return@mapNotNull null
                val local = ia.address as? Inet4Address ?: return@mapNotNull null
                BroadcastTarget(local, broadcast)
            }
            .distinctBy { it.localAddress?.hostAddress to it.broadcastAddress.hostAddress }

        return targets.ifEmpty {
            // Nothing usable found (e.g. no IPv4 interface reported a broadcast address) -
            // fall back to a single unbound (null local address - caller won't bind the send
            // socket) global broadcast so discovery still has a chance instead of sending
            // nothing at all. Matches the desktop's identical fallback in
            // NetworkHelper.GetAllBroadcastEndpoints.
            listOf(BroadcastTarget(null, InetAddress.getByName("255.255.255.255")))
        }
    }

    private fun usableInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { nic ->
            try {
                nic.isUp && !nic.isLoopback && !nic.isVirtual && !isLikelyVpn(nic.displayName)
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun isLikelyVpn(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return listOf("tun", "ppp", "vpn").any { n.contains(it) }
    }

    private fun isLikelyWifi(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return listOf("wlan", "ap", "eth").any { n.startsWith(it) }
    }
}
