package com.ciphershare.android.data

import android.content.Context
import com.ciphershare.android.model.DeviceModel
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.model.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the "known devices" list (name, id, trust flag, last-seen address) to a small JSON
 * file so devices - and whether they're trusted - are remembered across app restarts, even
 * before their next discovery broadcast arrives. Mirrors CipherShare (desktop)
 * Services/DeviceStore.cs.
 */
class DeviceStore(context: Context) {

    private val file = File(context.filesDir, "known_devices.json")

    suspend fun load(): List<DeviceModel> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                DeviceModel(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    ipAddress = o.optString("ipAddress"),
                    transferPort = o.optInt("transferPort", 54321),
                    osType = o.optString("osType", "other"),
                    deviceType = DeviceType.fromWireValue(o.optString("deviceType")),
                    status = DeviceStatus.OFFLINE, // always start offline; discovery will mark it online again
                    isTrusted = o.optBoolean("isTrusted", false),
                    lastSeenUtcMillis = o.optLong("lastSeenUtcMillis", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun save(devices: List<DeviceModel>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        devices.forEach { d ->
            array.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("ipAddress", d.ipAddress)
                    put("transferPort", d.transferPort)
                    put("osType", d.osType)
                    put("deviceType", d.deviceType.wireValue)
                    put("isTrusted", d.isTrusted)
                    put("lastSeenUtcMillis", d.lastSeenUtcMillis)
                }
            )
        }
        try {
            file.writeText(array.toString())
        } catch (_: Exception) {
            // Best-effort persistence - a failed write just means devices re-announce next time.
        }
    }
}
