package com.ciphershare.android.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.ciphershare.android.model.AppSettings
import com.ciphershare.android.model.LocalIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

private const val TAG = "DiscoveryService"

/**
 * Finds other CipherShare instances on the LAN using UDP broadcast - mirrors CipherShare
 * (desktop) Services/DiscoveryService.cs message-for-message. Every instance (desktop or
 * this Android build) periodically shouts "I'm here" on the configured port, and everyone
 * listens for those shouts. Not a full mDNS/Bonjour implementation on purpose - same
 * trade-off the desktop app makes: simple, dependency-free, works on a single LAN segment.
 */
class DiscoveryService(private val context: Context) {

    var onAnnounceReceived: ((DiscoveryPacket, String) -> Unit)? = null
    var onGoodbyeReceived: ((String) -> Unit)? = null
    var onStartupFailed: ((String) -> Unit)? = null

    private var listenSocket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var broadcastJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Set right before we close the socket ourselves in stop(), so listenLoop() can tell "this
    // SocketException is expected because we just closed the socket" apart from "this
    // SocketException means something (a permission denial, e.g. a missing
    // NEARBY_WIFI_DEVICES grant or Local Network Protection) is actually blocking us" - the two
    // were previously handled identically, which meant real failures never surfaced anywhere.
    @Volatile private var stoppingIntentionally = false

    @Volatile var isRunning: Boolean = false
        private set

    private var identity: LocalIdentity? = null
    private var port: Int = 54321
    private var intervalSeconds: Int = 10

    fun start(scope: CoroutineScope, settings: AppSettings, identity: LocalIdentity) {
        stop()

        this.identity = identity
        this.port = settings.networkPort
        this.intervalSeconds = settings.broadcastIntervalSeconds.coerceAtLeast(3)

        // Many OEM Wi-Fi power-saving stacks silently drop incoming broadcast/multicast
        // packets unless a multicast lock is held, even though this app only uses plain
        // broadcast, not multicast - holding the lock is cheap and avoids that whole class of
        // "discovery works on some phones but not others" bug reports.
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("ciphershare-discovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire multicast lock", e)
        }

        stoppingIntentionally = false

        try {
            // Deliberately NOT using DatagramSocket(...).apply { ... bind(InetSocketAddress(port)) }
            // here. DatagramSocket has its own "port" property (getPort(), which returns -1 for
            // an unconnected socket) - inside an apply{} block that socket becomes the implicit
            // receiver, so a bare "port" reference resolves to THAT (-1) instead of this class's
            // "port" field (54321), and bind() throws "port out of range:-1". That's exactly the
            // "Could not listen... port out of range:-1" error. Plain sequential statements have
            // no implicit receiver, so "port" unambiguously means the outer field.
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(port))
            listenSocket = socket
        } catch (e: Exception) {
            onStartupFailed?.invoke("Could not listen for other devices on UDP port $port: ${e.message}")
            return
        }

        isRunning = true

        listenJob = scope.launch(Dispatchers.IO) { listenLoop() }
        broadcastJob = scope.launch(Dispatchers.IO) {
            sendAnnounce()
            while (isActive) {
                delay(intervalSeconds * 1000L)
                sendAnnounce()
            }
        }
    }

    fun stop() {
        if (!isRunning && listenSocket == null) return

        try {
            sendGoodbye()
        } catch (e: Exception) {
            Log.w(TAG, "Goodbye send failed", e)
        }

        broadcastJob?.cancel()
        broadcastJob = null
        listenJob?.cancel()
        listenJob = null

        stoppingIntentionally = true
        try {
            listenSocket?.close()
        } catch (_: Exception) {
        }
        listenSocket = null

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null

        isRunning = false
    }

    /** Call after Settings change so a changed port/interval takes effect right away. */
    fun restart(scope: CoroutineScope, settings: AppSettings, identity: LocalIdentity) {
        if (!settings.autoDiscovery) {
            stop()
            return
        }
        start(scope, settings, identity)
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(4096)
        val socket = listenSocket ?: return

        while (isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val parsed = DiscoveryPacket.fromJson(json) ?: continue
                if (parsed.deviceId == identity?.deviceId) continue // ignore our own broadcast

                val remoteIp = packet.address.hostAddress ?: continue

                if (parsed.messageType == "Goodbye") {
                    onGoodbyeReceived?.invoke(parsed.deviceId)
                } else {
                    onAnnounceReceived?.invoke(parsed, remoteIp)
                }
            } catch (_: SocketTimeoutException) {
                // ignore, loop again
            } catch (e: SocketException) {
                if (!stoppingIntentionally) {
                    // Not us closing the socket - most likely cause on Android 13+/16+ is a
                    // missing NEARBY_WIFI_DEVICES grant or OEM Local Network Protection
                    // silently rejecting the receive with EPERM. Surface it instead of dying
                    // quietly - previously this looked identical to a normal stop().
                    Log.e(TAG, "Discovery listen socket failed unexpectedly (permission issue?)", e)
                    onStartupFailed?.invoke("Lost the ability to listen for other devices: ${e.message}. If this keeps happening, check Settings > Apps > CipherShare > Permissions > Nearby devices is allowed.")
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "Bad discovery packet ignored", e)
            }
        }
    }

    private fun sendAnnounce() = broadcast("Announce")
    private fun sendGoodbye() = broadcast("Goodbye")

    /**
     * Sends one announce/goodbye packet out of every real adapter, binding each send socket to
     * that adapter's own local address first. Binding matters: without it, this being a phone
     * with mobile data active alongside Wi-Fi means Android's routing layer - not this code -
     * decides which physical radio an unbound socket's packets actually leave through, and it
     * can silently pick cellular instead of Wi-Fi. A bound socket can't be rerouted like that;
     * this mirrors the desktop's own SendOn, which binds for exactly the same reason.
     */
    private fun broadcast(messageType: String) {
        val id = identity ?: return
        val packet = DiscoveryPacket(
            messageType = messageType,
            deviceId = id.deviceId,
            deviceName = id.deviceName,
            osType = "android",
            deviceType = "mobile",
            transferPort = port
        )
        val bytes = packet.toJson().toByteArray(Charsets.UTF_8)

        for (target in NetworkUtils.getBroadcastTargets()) {
            try {
                DatagramSocket(null).use { sender ->
                    sender.reuseAddress = true
                    sender.broadcast = true
                    if (target.localAddress != null) {
                        sender.bind(InetSocketAddress(target.localAddress, 0))
                    } else {
                        sender.bind(InetSocketAddress(0))
                    }
                    sender.send(DatagramPacket(bytes, bytes.size, target.broadcastAddress, port))
                }
            } catch (e: Exception) {
                // No network on this path right now - next tick tries again; other targets
                // (if any) still get their own attempt this round.
                Log.w(TAG, "Broadcast send failed on ${target.localAddress}", e)
            }
        }
    }
}
