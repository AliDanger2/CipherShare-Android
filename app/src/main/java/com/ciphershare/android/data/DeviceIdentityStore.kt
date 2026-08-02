package com.ciphershare.android.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.ciphershare.android.model.LocalIdentity
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.identityStore by preferencesDataStore(name = "ciphershare_identity")

/**
 * Mirrors CipherShare (desktop) Services/LocalDeviceIdentity.cs: a random device id is
 * generated once and persisted forever, so this installation keeps a stable identity across
 * app restarts (used to recognize "this is a device I've seen/trusted before" and to filter
 * out this device's own discovery broadcasts).
 */
class DeviceIdentityStore(private val context: Context) {

    private val idKey = stringPreferencesKey("device_id")

    suspend fun getOrCreateDeviceId(): String {
        val prefs = context.identityStore.data.first()
        prefs[idKey]?.let { return it }

        val newId = UUID.randomUUID().toString()
        context.identityStore.edit { it[idKey] = newId }
        return newId
    }

    suspend fun loadIdentity(deviceName: String): LocalIdentity =
        LocalIdentity(deviceId = getOrCreateDeviceId(), deviceName = deviceName)
}
