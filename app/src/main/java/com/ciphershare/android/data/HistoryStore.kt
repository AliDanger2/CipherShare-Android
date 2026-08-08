package com.ciphershare.android.data

import android.content.Context
import com.ciphershare.android.model.TransferDirection
import com.ciphershare.android.model.TransferFileEntry
import com.ciphershare.android.model.TransferModel
import com.ciphershare.android.model.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists finished transfers (completed/failed/canceled) to disk so History survives app restarts. */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "transfer_history.json")
    private val maxEntries = 200

    suspend fun load(): List<TransferModel> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun save(entries: List<TransferModel>) = withContext(Dispatchers.IO) {
        val trimmed = entries.sortedByDescending { it.completedAtUtcMillis ?: it.startedAtUtcMillis ?: 0L }.take(maxEntries)
        val array = JSONArray()
        trimmed.forEach { array.put(toJson(it)) }
        try {
            file.writeText(array.toString())
        } catch (_: Exception) {
        }
    }

    private fun toJson(t: TransferModel): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("displayName", t.displayName)
        put("files", JSONArray(t.files.map { JSONObject().apply { put("relativePath", it.relativePath); put("size", it.size) } }))
        put("totalBytes", t.totalBytes)
        put("direction", t.direction.name)
        put("status", t.status.name)
        put("senderId", t.senderId)
        put("senderName", t.senderName)
        put("receiverId", t.receiverId)
        put("receiverName", t.receiverName)
        put("startedAtUtcMillis", t.startedAtUtcMillis ?: 0L)
        put("completedAtUtcMillis", t.completedAtUtcMillis ?: 0L)
        put("durationSeconds", t.durationSeconds)
        put("errorMessage", t.errorMessage ?: "")
        put("destinationFolder", t.destinationFolder ?: "")
        put("destinationFolderUri", t.destinationFolderUri ?: "")
        put("sourceUris", JSONArray(t.sourceUris))
        put("receivedFileUris", JSONArray(t.receivedFileUris))
        put("payloadKind", t.payloadKind)
        put("remoteIpAddress", t.remoteIpAddress)
        put("remoteTransferPort", t.remoteTransferPort)
    }

    private fun fromJson(o: JSONObject): TransferModel {
        val filesArray = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArray.length()).map { i ->
            val f = filesArray.getJSONObject(i)
            TransferFileEntry(f.optString("relativePath"), f.optLong("size"))
        }
        return TransferModel(
            id = o.optString("id"),
            displayName = o.optString("displayName"),
            files = files,
            totalBytes = o.optLong("totalBytes"),
            direction = runCatching { TransferDirection.valueOf(o.optString("direction")) }.getOrDefault(TransferDirection.RECEIVED),
            status = runCatching { TransferStatus.valueOf(o.optString("status")) }.getOrDefault(TransferStatus.COMPLETED),
            progressPercent = if (o.optString("status") == "COMPLETED") 100.0 else 0.0,
            transferredBytes = o.optLong("totalBytes"),
            senderId = o.optString("senderId"),
            senderName = o.optString("senderName"),
            receiverId = o.optString("receiverId"),
            receiverName = o.optString("receiverName"),
            startedAtUtcMillis = o.optLong("startedAtUtcMillis").takeIf { it > 0 },
            completedAtUtcMillis = o.optLong("completedAtUtcMillis").takeIf { it > 0 },
            durationSeconds = o.optInt("durationSeconds"),
            errorMessage = o.optString("errorMessage").takeIf { it.isNotBlank() },
            destinationFolder = o.optString("destinationFolder").takeIf { it.isNotBlank() },
            destinationFolderUri = o.optString("destinationFolderUri").takeIf { it.isNotBlank() },
            sourceUris = o.optJSONArray("sourceUris")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            receivedFileUris = o.optJSONArray("receivedFileUris")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            payloadKind = o.optString("payloadKind", "Files").ifBlank { "Files" },
            remoteIpAddress = o.optString("remoteIpAddress"),
            remoteTransferPort = o.optInt("remoteTransferPort")
        )
    }
}
