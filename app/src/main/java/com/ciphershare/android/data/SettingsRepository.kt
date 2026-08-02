package com.ciphershare.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ciphershare.android.model.AppSettings
import com.ciphershare.android.model.SecurityLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ciphershare_settings")

/** Mirrors CipherShare (desktop) Services/SettingsService.cs - same fields, same defaults. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        val AUTO_DISCOVERY = booleanPreferencesKey("auto_discovery")
        val BROADCAST_INTERVAL = intPreferencesKey("broadcast_interval_seconds")
        val NETWORK_PORT = intPreferencesKey("network_port")
        val MAX_SIMULTANEOUS = intPreferencesKey("max_simultaneous_transfers")
        val BANDWIDTH_LIMIT = doublePreferencesKey("bandwidth_limit_mbps")
        val LAUNCH_ON_BOOT = booleanPreferencesKey("launch_on_boot")
        val NOTIFY_DISCOVERED = booleanPreferencesKey("notify_device_discovered")
        val NOTIFY_INCOMING = booleanPreferencesKey("notify_incoming_transfer")
        val NOTIFY_COMPLETE = booleanPreferencesKey("notify_transfer_complete")
        val NOTIFY_FAILED = booleanPreferencesKey("notify_transfer_failed")
        val NOTIFY_CONN_LOST = booleanPreferencesKey("notify_connection_lost")
        val SECURITY_LEVEL = stringPreferencesKey("security_level")
        val CHUNK_SIZE_KB = intPreferencesKey("chunk_size_kb")
        val KEEP_PARTIAL = booleanPreferencesKey("keep_partial_files_on_failure")
        val VERIFY_INTEGRITY = booleanPreferencesKey("verify_integrity")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            deviceName = prefs[Keys.DEVICE_NAME] ?: defaults.deviceName,
            downloadTreeUri = prefs[Keys.DOWNLOAD_TREE_URI],
            autoDiscovery = prefs[Keys.AUTO_DISCOVERY] ?: defaults.autoDiscovery,
            broadcastIntervalSeconds = prefs[Keys.BROADCAST_INTERVAL] ?: defaults.broadcastIntervalSeconds,
            networkPort = prefs[Keys.NETWORK_PORT] ?: defaults.networkPort,
            maxSimultaneousTransfers = prefs[Keys.MAX_SIMULTANEOUS] ?: defaults.maxSimultaneousTransfers,
            bandwidthLimitMBps = prefs[Keys.BANDWIDTH_LIMIT] ?: defaults.bandwidthLimitMBps,
            launchOnBoot = prefs[Keys.LAUNCH_ON_BOOT] ?: defaults.launchOnBoot,
            notifyDeviceDiscovered = prefs[Keys.NOTIFY_DISCOVERED] ?: defaults.notifyDeviceDiscovered,
            notifyIncomingTransfer = prefs[Keys.NOTIFY_INCOMING] ?: defaults.notifyIncomingTransfer,
            notifyTransferComplete = prefs[Keys.NOTIFY_COMPLETE] ?: defaults.notifyTransferComplete,
            notifyTransferFailed = prefs[Keys.NOTIFY_FAILED] ?: defaults.notifyTransferFailed,
            notifyConnectionLost = prefs[Keys.NOTIFY_CONN_LOST] ?: defaults.notifyConnectionLost,
            securityLevel = prefs[Keys.SECURITY_LEVEL]?.let { runCatching { SecurityLevel.valueOf(it) }.getOrNull() }
                ?: defaults.securityLevel,
            chunkSizeKB = prefs[Keys.CHUNK_SIZE_KB] ?: defaults.chunkSizeKB,
            keepPartialFilesOnFailure = prefs[Keys.KEEP_PARTIAL] ?: defaults.keepPartialFilesOnFailure,
            verifyIntegrity = prefs[Keys.VERIFY_INTEGRITY] ?: defaults.verifyIntegrity
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(current())
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_NAME] = updated.deviceName
            if (updated.downloadTreeUri != null) {
                prefs[Keys.DOWNLOAD_TREE_URI] = updated.downloadTreeUri
            } else {
                prefs.remove(Keys.DOWNLOAD_TREE_URI)
            }
            prefs[Keys.AUTO_DISCOVERY] = updated.autoDiscovery
            prefs[Keys.BROADCAST_INTERVAL] = updated.broadcastIntervalSeconds
            prefs[Keys.NETWORK_PORT] = updated.networkPort
            prefs[Keys.MAX_SIMULTANEOUS] = updated.maxSimultaneousTransfers
            prefs[Keys.BANDWIDTH_LIMIT] = updated.bandwidthLimitMBps
            prefs[Keys.LAUNCH_ON_BOOT] = updated.launchOnBoot
            prefs[Keys.NOTIFY_DISCOVERED] = updated.notifyDeviceDiscovered
            prefs[Keys.NOTIFY_INCOMING] = updated.notifyIncomingTransfer
            prefs[Keys.NOTIFY_COMPLETE] = updated.notifyTransferComplete
            prefs[Keys.NOTIFY_FAILED] = updated.notifyTransferFailed
            prefs[Keys.NOTIFY_CONN_LOST] = updated.notifyConnectionLost
            prefs[Keys.SECURITY_LEVEL] = updated.securityLevel.name
            prefs[Keys.CHUNK_SIZE_KB] = updated.chunkSizeKB
            prefs[Keys.KEEP_PARTIAL] = updated.keepPartialFilesOnFailure
            prefs[Keys.VERIFY_INTEGRITY] = updated.verifyIntegrity
        }
    }
}
