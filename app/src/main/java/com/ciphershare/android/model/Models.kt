package com.ciphershare.android.model

import java.util.UUID

/**
 * Mirrors CipherShare (desktop) Models/Devicetype.cs. Wire value strings ("desktop",
 * "laptop", "mobile", "unknown") must match exactly - the desktop app already ships with
 * this exact vocabulary (and its own "Mobile" enum member, icon, and label) waiting for a
 * client like this one.
 */
enum class DeviceType(val wireValue: String) {
    UNKNOWN("unknown"),
    DESKTOP("desktop"),
    LAPTOP("laptop"),
    MOBILE("mobile");

    companion object {
        fun fromWireValue(value: String?): DeviceType =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Mirrors Models/DeviceStatus.cs - how recently a discovery broadcast was heard from a device. */
enum class DeviceStatus { ONLINE, IDLE, OFFLINE }

/** Mirrors Models/TransferStatus.cs */
enum class TransferStatus { PENDING, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELED }

/** Mirrors Models/TransferDirection.cs */
enum class TransferDirection { SENT, RECEIVED }

/** Mirrors Models/SecurityLevel.cs */
enum class SecurityLevel {
    /** Every incoming transfer, from any device, must be accepted manually. */
    REQUIRE_CONFIRMATION_FOR_ALL,

    /** Trusted devices skip the confirmation dialog; everyone else still needs approval. */
    SKIP_CONFIRMATION_FOR_TRUSTED,

    /** No confirmation is ever required. Use with care on untrusted networks. */
    NO_CONFIRMATION_REQUIRED
}

/** Mirrors Models/NotificationType.cs */
enum class AppNotificationType { DEVICE_DISCOVERED, INCOMING_TRANSFER, TRANSFER_COMPLETE, TRANSFER_FAILED, CONNECTION_LOST }

/** A machine found on the LAN (or previously seen and remembered). Mirrors Models/DeviceModel.cs. */
data class DeviceModel(
    val id: String,
    val name: String,
    val ipAddress: String,
    val transferPort: Int,
    val osType: String = "other",
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val status: DeviceStatus = DeviceStatus.OFFLINE,
    val isTrusted: Boolean = false,
    val lastSeenUtcMillis: Long = 0L
)

/** Mirrors Models/TransferFileEntry.cs */
data class TransferFileEntry(val relativePath: String, val size: Long)

/** One file (or folder, or batch of files) being sent or received. Mirrors Models/TransferModel.cs. */
data class TransferModel(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val files: List<TransferFileEntry>,
    val totalBytes: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val progressPercent: Double = 0.0,
    val transferredBytes: Long = 0L,
    val speedMBps: Double = 0.0,
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val receiverName: String,
    val startedAtUtcMillis: Long? = null,
    val completedAtUtcMillis: Long? = null,
    val durationSeconds: Int = 0,
    val errorMessage: String? = null,
    /** content:// URI strings of the original files, only populated for transfers we sent, so Retry can re-read them. */
    val sourceUris: List<String> = emptyList(),
    /** Human-readable destination description, only populated for received transfers. */
    val destinationFolder: String? = null,
    /** Openable URI of the folder received files were written into, only populated for received transfers. Lets History offer "open containing folder". */
    val destinationFolderUri: String? = null,
    /** Openable URI of each file actually written to disk, in the same order as `files`, only populated for received transfers. Lets History open a single received file directly. */
    val receivedFileUris: List<String> = emptyList(),
    /** "Files" (default) or "ClipboardText"/"ClipboardImage" - mirrors WireModels.TransferHeader.payloadKind. Clipboard transfers have nothing on disk/in destinationFolderUri to open. */
    val payloadKind: String = "Files",
    val remoteIpAddress: String,
    val remoteTransferPort: Int
) {
    val itemCount: Int get() = files.size
    val isClipboard: Boolean get() = payloadKind != "Files"
}

/** Pending incoming transfer awaiting Accept/Decline. Mirrors Models/TransferRequestModel.cs. */
data class TransferRequest(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val senderIp: String,
    val files: List<TransferFileEntry>,
    val totalSize: Long,
    val payloadKind: String = "Files"
)

/** Mirrors Models/NotificationModel.cs */
data class AppNotification(
    val id: String = UUID.randomUUID().toString(),
    val type: AppNotificationType,
    val title: String,
    val message: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)

/**
 * All user-configurable settings, persisted via SettingsRepository (DataStore Preferences).
 * Field names/defaults intentionally mirror Models/AppSettingsModel.cs so the app behaves
 * the same way out of the box and interoperates with the desktop app's defaults (54321).
 */
data class AppSettings(
    val deviceName: String = android.os.Build.MODEL ?: "Android Device",
    /** null = the app's own default folder; otherwise a persisted SAF tree URI string. */
    val downloadTreeUri: String? = null,
    val autoDiscovery: Boolean = true,
    val broadcastIntervalSeconds: Int = 10,
    val networkPort: Int = 54321,
    val maxSimultaneousTransfers: Int = 5,
    /** 0 = unlimited. */
    val bandwidthLimitMBps: Double = 0.0,
    val launchOnBoot: Boolean = false,
    val notifyDeviceDiscovered: Boolean = true,
    val notifyIncomingTransfer: Boolean = true,
    val notifyTransferComplete: Boolean = true,
    val notifyTransferFailed: Boolean = true,
    val notifyConnectionLost: Boolean = true,
    val securityLevel: SecurityLevel = SecurityLevel.REQUIRE_CONFIRMATION_FOR_ALL,
    val chunkSizeKB: Int = 64,
    val keepPartialFilesOnFailure: Boolean = true,
    val verifyIntegrity: Boolean = true
)

/** A stable identity for this installation, mirrors Services/LocalDeviceIdentity.cs. */
data class LocalIdentity(val deviceId: String, val deviceName: String)
