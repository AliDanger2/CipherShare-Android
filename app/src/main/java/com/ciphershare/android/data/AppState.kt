package com.ciphershare.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.ciphershare.android.model.AppNotification
import com.ciphershare.android.model.AppNotificationType
import com.ciphershare.android.model.AppSettings
import com.ciphershare.android.model.DeviceModel
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.model.DeviceType
import com.ciphershare.android.model.LocalIdentity
import com.ciphershare.android.model.TransferModel
import com.ciphershare.android.model.TransferRequest
import com.ciphershare.android.model.TransferStatus
import com.ciphershare.android.net.DiscoveryService
import com.ciphershare.android.net.NetworkUtils
import com.ciphershare.android.net.TransferClient
import com.ciphershare.android.net.TransferServer
import com.ciphershare.android.net.TransferSession
import com.ciphershare.android.net.TransferSessionRegistry
import com.ciphershare.android.util.StorageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Central, application-scoped state holder - the Android equivalent of CipherShare
 * (desktop) AppState.cs. Owns the long-lived networking services and exposes everything the
 * UI needs as StateFlows. A single instance lives for as long as the process does, created by
 * CipherShareApplication and used by both the foreground Service and every Compose screen.
 */
private const val TAG = "AppState"

class AppState private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val settingsRepository = SettingsRepository(context)
    private val identityStore = DeviceIdentityStore(context)
    private val deviceStore = DeviceStore(context)
    private val historyStore = HistoryStore(context)

    val discoveryService = DiscoveryService(context)
    val transferServer = TransferServer(context)
    private val transferClient = TransferClient(context)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _identity = MutableStateFlow<LocalIdentity?>(null)
    val identity: StateFlow<LocalIdentity?> = _identity.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceModel>>(emptyList())
    val devices: StateFlow<List<DeviceModel>> = _devices.asStateFlow()

    private val _activeTransfers = MutableStateFlow<List<TransferModel>>(emptyList())
    val activeTransfers: StateFlow<List<TransferModel>> = _activeTransfers.asStateFlow()

    private val _history = MutableStateFlow<List<TransferModel>>(emptyList())
    val history: StateFlow<List<TransferModel>> = _history.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<TransferRequest>>(emptyList())
    val pendingRequests: StateFlow<List<TransferRequest>> = _pendingRequests.asStateFlow()

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    /** Fired whenever something worth surfacing as a system notification happens; the Service subscribes to this. */
    private val _notificationEvents = MutableStateFlow<AppNotification?>(null)
    val notificationEvents: StateFlow<AppNotification?> = _notificationEvents.asStateFlow()

    private var initialized = false
    private var staleSweepJob: Job? = null

    /** Safe to call multiple times - only the first call does anything. */
    fun initialize() {
        if (initialized) return
        initialized = true

        scope.launch {
            val loadedSettings = settingsRepository.current()
            _settings.value = loadedSettings
            _identity.value = identityStore.loadIdentity(loadedSettings.deviceName)
            _devices.value = deviceStore.load()
            _history.value = historyStore.load()
            _localIp.value = NetworkUtils.getLocalIPv4()

            wireCallbacks()
            startNetworking()
            startStaleSweep()
            registerConnectivityCallback()

            // Keep persisted settings in sync with in-memory state for other collectors.
            launch {
                settingsRepository.settingsFlow.collect { _settings.value = it }
            }
        }
    }

    private fun wireCallbacks() {
        discoveryService.onAnnounceReceived = { packet, remoteIp ->
            upsertDevice(packet.deviceId, packet.deviceName, remoteIp, packet.transferPort, packet.osType, packet.deviceType)
        }
        discoveryService.onGoodbyeReceived = { deviceId ->
            _devices.value = _devices.value.map { if (it.id == deviceId) it.copy(status = DeviceStatus.OFFLINE) else it }
        }
        // Previously unwired - a failed/broken discovery socket (e.g. missing
        // NEARBY_WIFI_DEVICES permission, port conflict) failed completely silently with no
        // way for the user to know why devices weren't being found.
        discoveryService.onStartupFailed = { message ->
            Log.w(TAG, "Discovery startup failed: $message")
            pushNotification(AppNotificationType.CONNECTION_LOST, "Discovery isn't working", message)
        }

        transferServer.getSettings = { _settings.value }
        transferServer.getIdentity = { _identity.value }
        transferServer.isDeviceTrusted = { deviceId -> _devices.value.firstOrNull { it.id == deviceId }?.isTrusted == true }
        transferServer.onTransferAdded = { addOrUpdateTransfer(it) }
        transferServer.onTransferUpdated = { addOrUpdateTransfer(it) }
        transferServer.onIncomingRequest = { request -> _pendingRequests.value = _pendingRequests.value + request }
        transferServer.onRequestResolved = { requestId -> _pendingRequests.value = _pendingRequests.value.filterNot { it.id == requestId } }
        transferServer.onNotify = { title, message, isIncomingRequest ->
            // Incoming-transfer requests get a dedicated Accept/Decline notification built
            // directly from pendingRequests by CipherShareService - posting one here too would
            // just be a duplicate. Transfer complete/failed notifications still flow through here.
            if (!isIncomingRequest) pushNotification(AppNotificationType.INCOMING_TRANSFER, title, message)
        }
    }

    private fun startNetworking() {
        val id = _identity.value ?: return
        val current = _settings.value
        if (current.autoDiscovery) {
            discoveryService.start(scope, current, id)
        }
        transferServer.start(scope, current.networkPort)
    }

    fun restartNetworking() {
        val id = _identity.value ?: return
        val current = _settings.value
        discoveryService.restart(scope, current, id)
        transferServer.stop()
        transferServer.start(scope, current.networkPort)
    }

    /**
     * Without this, everything network-related was computed exactly once, at process start:
     * localIp was set from AppState.initialize() and never touched again (refreshLocalIp()
     * existed but nothing called it), and discovery/transfer sockets were bound once and left
     * alone. Launch the app with Wi-Fi off, or toggle Wi-Fi off and back on, or switch from
     * one network to another, and none of that got picked up - matching "stuck on finding IP"
     * and discovery not recovering after a network change. This listens for the LAN-capable
     * network actually changing and refreshes the IP + gives discovery/the transfer server a
     * fresh start on the new interface.
     */
    private fun registerConnectivityCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // NetworkRequest.Builder() requires NET_CAPABILITY_INTERNET by default even
            // though nothing here adds it explicitly - it's baked into the empty builder.
            // That means onAvailable() below would only fire once Android finishes
            // validating that the Wi-Fi network actually has a working internet connection,
            // which can take several seconds after Wi-Fi turns on, doesn't happen at all on a
            // LAN with no internet uplink, and can get stuck longer on networks with slow or
            // failing captive-portal/DNS checks. None of that matters for talking to another
            // device on the same LAN, so drop the requirement - this is what actually fixes
            // "stuck on finding IP" after toggling Wi-Fi rather than just usually recovering
            // within a few seconds.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "LAN-capable network became available - refreshing IP and restarting networking")
                // NetworkCallback methods fire on a system callback thread, not ours - hop onto
                // our own scope before touching sockets, same as updateSettings() already does.
                scope.launch {
                    refreshLocalIp()
                    restartNetworking()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "LAN-capable network lost")
                scope.launch { refreshLocalIp() }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Covers switching between Wi-Fi networks (new IP, same "available" network
                // type) which onAvailable/onLost alone wouldn't catch.
                scope.launch { refreshLocalIp() }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register connectivity callback", e)
        }
    }

    private fun startStaleSweep() {
        staleSweepJob?.cancel()
        staleSweepJob = scope.launch {
            while (true) {
                delay(5_000)
                sweepStaleDevices()
            }
        }
    }

    /** Mirrors desktop AppState.cs exactly: idleAfter = max(9s, interval*2), offlineAfter = max(18s, interval*4). */
    private fun sweepStaleDevices() {
        val intervalSeconds = _settings.value.broadcastIntervalSeconds
        val idleAfterMs = maxOf(9, intervalSeconds * 2) * 1000L
        val offlineAfterMs = maxOf(18, intervalSeconds * 4) * 1000L
        val now = System.currentTimeMillis()

        _devices.value = _devices.value.map { device ->
            if (device.status == DeviceStatus.OFFLINE) return@map device
            val elapsed = now - device.lastSeenUtcMillis
            when {
                elapsed > offlineAfterMs -> device.copy(status = DeviceStatus.OFFLINE)
                elapsed > idleAfterMs -> device.copy(status = DeviceStatus.IDLE)
                else -> device
            }
        }
    }

    private fun upsertDevice(id: String, name: String, ip: String, port: Int, osType: String, deviceTypeWire: String) {
        val existing = _devices.value.firstOrNull { it.id == id }
        val isNew = existing == null

        val updated = (existing ?: DeviceModel(id = id, name = name, ipAddress = ip, transferPort = port)).copy(
            name = name,
            ipAddress = ip,
            transferPort = port,
            osType = osType,
            deviceType = DeviceType.fromWireValue(deviceTypeWire),
            status = DeviceStatus.ONLINE,
            lastSeenUtcMillis = System.currentTimeMillis()
        )

        _devices.value = if (existing == null) _devices.value + updated else _devices.value.map { if (it.id == id) updated else it }

        scope.launch { deviceStore.save(_devices.value) }

        if (isNew && _settings.value.notifyDeviceDiscovered) {
            pushNotification(AppNotificationType.DEVICE_DISCOVERED, "New device found", "$name is now on your network")
        }
    }

    fun setDeviceTrusted(deviceId: String, trusted: Boolean) {
        _devices.value = _devices.value.map { if (it.id == deviceId) it.copy(isTrusted = trusted) else it }
        scope.launch { deviceStore.save(_devices.value) }
    }

    fun forgetDevice(deviceId: String) {
        _devices.value = _devices.value.filterNot { it.id == deviceId }
        scope.launch { deviceStore.save(_devices.value) }
    }

    fun respondToIncomingRequest(requestId: String, accept: Boolean) {
        _pendingRequests.value = _pendingRequests.value.filterNot { it.id == requestId }
        transferServer.respond(requestId, accept)
    }

    fun sendFiles(target: DeviceModel, files: List<StorageUtils.SendableFile>) {
        val id = _identity.value ?: return
        val transferId = UUID.randomUUID().toString()
        val session = TransferSession(transferId, isSender = true)
        TransferSessionRegistry.register(session)

        session.job = scope.launch {
            try {
                transferClient.sendFiles(
                    transferId = transferId,
                    targetDeviceId = target.id,
                    targetDeviceName = target.name,
                    targetIp = target.ipAddress,
                    targetPort = target.transferPort,
                    files = files,
                    identity = id,
                    settings = _settings.value,
                    session = session
                ) { updated ->
                    addOrUpdateTransfer(updated)
                    if (updated.status == TransferStatus.COMPLETED && _settings.value.notifyTransferComplete) {
                        pushNotification(AppNotificationType.TRANSFER_COMPLETE, "Transfer complete", "Sent ${updated.displayName} to ${target.name}")
                    } else if (updated.status == TransferStatus.FAILED && _settings.value.notifyTransferFailed) {
                        pushNotification(AppNotificationType.TRANSFER_FAILED, "Transfer failed", "${updated.displayName}: ${updated.errorMessage}")
                    }
                }
            } finally {
                TransferSessionRegistry.unregister(transferId)
            }
        }
    }

    /** Pause/resume only apply to transfers this device is sending - matches the desktop's own restriction. */
    fun pauseTransfer(transferId: String) {
        if (!TransferSessionRegistry.isSender(transferId)) return
        TransferSessionRegistry.pause(transferId)
        setLocalTransferStatus(transferId, TransferStatus.PAUSED)
    }

    fun resumeTransfer(transferId: String) {
        if (!TransferSessionRegistry.isSender(transferId)) return
        TransferSessionRegistry.resume(transferId)
        setLocalTransferStatus(transferId, TransferStatus.ACTIVE)
    }

    /** Works for both sent and received transfers. The transfer loop itself moves it into
     *  history as CANCELED shortly after - there's no need to touch _activeTransfers here. */
    fun cancelTransfer(transferId: String) {
        TransferSessionRegistry.cancel(transferId)
    }

    private fun setLocalTransferStatus(transferId: String, status: TransferStatus) {
        _activeTransfers.value = _activeTransfers.value.map { if (it.id == transferId) it.copy(status = status) else it }
    }

    private fun addOrUpdateTransfer(transfer: TransferModel) {
        val isFinished = transfer.status in setOf(TransferStatus.COMPLETED, TransferStatus.FAILED, TransferStatus.CANCELED)
        if (isFinished) {
            _activeTransfers.value = _activeTransfers.value.filterNot { it.id == transfer.id }
            _history.value = listOf(transfer) + _history.value.filterNot { it.id == transfer.id }
            scope.launch { historyStore.save(_history.value) }
        } else {
            val exists = _activeTransfers.value.any { it.id == transfer.id }
            _activeTransfers.value = if (exists) {
                _activeTransfers.value.map { if (it.id == transfer.id) transfer else it }
            } else {
                _activeTransfers.value + transfer
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            settingsRepository.update(transform)
            val newSettings = settingsRepository.current()
            _settings.value = newSettings
            _identity.value = _identity.value?.copy(deviceName = newSettings.deviceName)
            restartNetworking()
        }
    }

    fun refreshLocalIp() {
        _localIp.value = NetworkUtils.getLocalIPv4()
    }

    private fun pushNotification(type: AppNotificationType, title: String, message: String) {
        val notification = AppNotification(type = type, title = title, message = message)
        _notifications.value = (listOf(notification) + _notifications.value).take(100)
        _notificationEvents.value = notification
    }

    fun clearHistory() {
        _history.value = emptyList()
        scope.launch { historyStore.save(emptyList()) }
    }

    companion object {
        @Volatile private var INSTANCE: AppState? = null

        fun getInstance(context: Context): AppState =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppState(context.applicationContext).also { INSTANCE = it }
            }
    }
}
