package com.ciphershare.android.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * These classes/functions mirror CipherShare (desktop) Services/NetworkProtocol.cs exactly -
 * same JSON field names (PascalCase, matching System.Text.Json's default output), same
 * shapes. The desktop app deserializes with PropertyNameCaseInsensitive = true, so exact
 * casing on the way IN to the desktop doesn't strictly matter, but matching it exactly keeps
 * both sides symmetric and makes wire captures easy to compare side by side.
 *
 * Plain org.json is used instead of a serialization library so this file has zero extra
 * Gradle dependencies to go wrong.
 */

/** Sent as a UDP broadcast so every CipherShare instance on the LAN can find each other. */
data class DiscoveryPacket(
    val messageType: String, // "Announce" or "Goodbye"
    val deviceId: String,
    val deviceName: String,
    val osType: String,
    val deviceType: String, // "desktop" | "laptop" | "mobile" | "unknown"
    val transferPort: Int
) {
    fun toJson(): String = JSONObject().apply {
        put("MessageType", messageType)
        put("DeviceId", deviceId)
        put("DeviceName", deviceName)
        put("OsType", osType)
        put("DeviceType", deviceType)
        put("TransferPort", transferPort)
    }.toString()

    companion object {
        fun fromJson(json: String): DiscoveryPacket? = try {
            val o = JSONObject(json)
            DiscoveryPacket(
                messageType = o.optString("MessageType", ""),
                deviceId = o.optString("DeviceId", ""),
                deviceName = o.optString("DeviceName", "Unknown device"),
                osType = o.optString("OsType", "other"),
                deviceType = o.optString("DeviceType", "unknown"),
                transferPort = o.optInt("TransferPort", 0)
            ).takeIf { it.deviceId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

data class WireFileEntry(val relativePath: String, val size: Long) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("RelativePath", relativePath)
        put("Size", size)
    }

    companion object {
        fun fromJson(o: JSONObject) = WireFileEntry(
            relativePath = o.optString("RelativePath", ""),
            size = o.optLong("Size", 0L)
        )
    }
}

/** First message written by the sender once a TCP connection to the receiver is open. */
data class TransferHeader(
    val senderId: String,
    val senderName: String,
    val files: List<WireFileEntry>,
    val totalSize: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("SenderId", senderId)
        put("SenderName", senderName)
        put("Files", JSONArray(files.map { it.toJson() }))
        put("TotalSize", totalSize)
    }.toString()

    companion object {
        fun fromJson(json: String): TransferHeader? = try {
            val o = JSONObject(json)
            val filesArray = o.optJSONArray("Files") ?: JSONArray()
            val files = (0 until filesArray.length()).map { i -> WireFileEntry.fromJson(filesArray.getJSONObject(i)) }
            TransferHeader(
                senderId = o.optString("SenderId", ""),
                senderName = o.optString("SenderName", "Unknown device"),
                files = files,
                totalSize = o.optLong("TotalSize", 0L)
            ).takeIf { it.files.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

/** Written by the sender immediately after streaming one file's bytes, for integrity checking. */
data class FileTrailer(val relativePath: String, val sha256Hex: String) {
    fun toJson(): String = JSONObject().apply {
        put("RelativePath", relativePath)
        put("Sha256Hex", sha256Hex)
    }.toString()

    companion object {
        fun fromJson(json: String?): FileTrailer? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                FileTrailer(o.optString("RelativePath", ""), o.optString("Sha256Hex", ""))
            } catch (_: Exception) {
                null
            }
        }
    }
}

const val COMPLETE_MARKER = "{\"MessageType\":\"Complete\"}"
