package com.ciphershare.android.net

import android.content.Context
import com.ciphershare.android.model.AppSettings
import com.ciphershare.android.model.LocalIdentity
import com.ciphershare.android.model.TransferDirection
import com.ciphershare.android.model.TransferFileEntry
import com.ciphershare.android.model.TransferModel
import com.ciphershare.android.model.TransferStatus
import com.ciphershare.android.util.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Opens a TCP connection to a target device and streams files to it, mirroring the send half
 * of CipherShare (desktop) Services/TransferService.cs: write header line -> read
 * ACCEPT/DECLINE -> per file: stream exact bytes, then a JSON trailer line with its SHA-256
 * -> write the Complete marker.
 */
class TransferClient(private val context: Context) {

    /**
     * Sends [files] to [targetIp]:[targetPort]. Calls [onUpdate] with a fresh TransferModel
     * snapshot as progress changes; the very first call establishes the transfer's identity
     * (id/displayName/etc.), later calls only change progress-related fields.
     *
     * [session] is checked once per chunk for pause/cancel, and holds the live Socket so a
     * cancel can force-close it immediately even if a read/write is currently blocked.
     */
    suspend fun sendFiles(
        transferId: String,
        targetDeviceId: String,
        targetDeviceName: String,
        targetIp: String,
        targetPort: Int,
        files: List<StorageUtils.SendableFile>,
        identity: LocalIdentity,
        settings: AppSettings,
        session: TransferSession,
        onUpdate: (TransferModel) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalSize = files.sumOf { it.size }
        val displayName = if (files.size == 1) files[0].relativePath else "${files.size} items to $targetDeviceName"

        var transfer = TransferModel(
            id = transferId,
            displayName = displayName,
            files = files.map { TransferFileEntry(it.relativePath, it.size) },
            totalBytes = totalSize,
            direction = TransferDirection.SENT,
            status = TransferStatus.ACTIVE,
            senderId = identity.deviceId,
            senderName = identity.deviceName,
            receiverId = targetDeviceId,
            receiverName = targetDeviceName,
            startedAtUtcMillis = System.currentTimeMillis(),
            sourceUris = files.map { it.uri.toString() },
            remoteIpAddress = targetIp,
            remoteTransferPort = targetPort
        )
        onUpdate(transfer)

        val startNanos = System.nanoTime()
        val socket = Socket()
        session.socket = socket
        try {
            socket.use { s ->
                s.connect(InetSocketAddress(targetIp, targetPort), 10_000)
                val input = s.getInputStream()
                val output = s.getOutputStream()

                val header = TransferHeader(
                    senderId = identity.deviceId,
                    senderName = identity.deviceName,
                    files = files.map { WireFileEntry(it.relativePath, it.size) },
                    totalSize = totalSize
                )
                LineProtocol.writeLine(output, header.toJson())

                val response = LineProtocol.readLine(input)
                if (response != "ACCEPT") {
                    transfer = transfer.copy(status = TransferStatus.FAILED, errorMessage = "Declined by recipient")
                    onUpdate(transfer)
                    return@withContext
                }

                val throttle = BandwidthThrottle(settings.bandwidthLimitMBps)
                val chunkSize = (settings.chunkSizeKB.coerceAtLeast(4)) * 1024
                val buffer = ByteArray(chunkSize)
                var transferredSoFar = 0L
                var lastPublish = 0L

                for (file in files) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val inputStream = context.contentResolver.openInputStream(file.uri)
                        ?: throw java.io.IOException("Could not read ${file.relativePath}")

                    inputStream.use { fileIn ->
                        var remaining = file.size
                        while (remaining > 0) {
                            // Pause gate first (suspends here while paused), then a hard check
                            // in case cancel() fired while we were paused or blocked in I/O.
                            session.awaitIfPaused()
                            coroutineContext.ensureActive()

                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = fileIn.read(buffer, 0, toRead)
                            if (read == -1) throw java.io.IOException("File ended early: ${file.relativePath}")

                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            remaining -= read
                            transferredSoFar += read
                            throttle.waitIfNeeded(read)

                            val now = System.currentTimeMillis()
                            if (now - lastPublish > 200) {
                                lastPublish = now
                                transfer = withProgress(transfer, transferredSoFar, totalSize, startNanos)
                                onUpdate(transfer)
                            }
                        }
                    }
                    output.flush()

                    val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
                    LineProtocol.writeLine(output, FileTrailer(file.relativePath, hashHex).toJson())
                }

                LineProtocol.writeLine(output, COMPLETE_MARKER)

                transfer = transfer.copy(
                    status = TransferStatus.COMPLETED,
                    progressPercent = 100.0,
                    transferredBytes = totalSize,
                    completedAtUtcMillis = System.currentTimeMillis(),
                    durationSeconds = ((System.nanoTime() - startNanos) / 1_000_000_000).toInt()
                )
                onUpdate(transfer)
            }
        } catch (e: Exception) {
            transfer = if (session.cancelRequested) {
                transfer.copy(status = TransferStatus.CANCELED, errorMessage = null)
            } else {
                transfer.copy(status = TransferStatus.FAILED, errorMessage = e.message ?: "Transfer failed")
            }
            onUpdate(transfer)
        }
    }

    private fun withProgress(transfer: TransferModel, transferred: Long, total: Long, startNanos: Long): TransferModel {
        val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val speedMBps = if (elapsedSeconds > 0) (transferred / 1024.0 / 1024.0) / elapsedSeconds else 0.0
        val percent = if (total > 0) (transferred.toDouble() / total.toDouble()) * 100.0 else 0.0
        return transfer.copy(progressPercent = percent, transferredBytes = transferred, speedMBps = speedMBps)
    }

    /**
     * Sends [bytes] (clipboard content already read and encoded by the caller) as a
     * clipboard-sync payload instead of a real file - the receiving end applies them straight
     * to its OS clipboard rather than writing anything to disk. Mirrors sendFiles' wire
     * handshake exactly, just with a ByteArrayInputStream standing in for a file on disk.
     */
    suspend fun sendClipboard(
        transferId: String,
        targetDeviceId: String,
        targetDeviceName: String,
        targetIp: String,
        targetPort: Int,
        payloadKind: String,
        fileName: String,
        bytes: ByteArray,
        identity: LocalIdentity,
        settings: AppSettings,
        session: TransferSession,
        onUpdate: (TransferModel) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalSize = bytes.size.toLong()
        val displayName = if (payloadKind == "ClipboardImage") "Clipboard image" else "Clipboard text"

        var transfer = TransferModel(
            id = transferId,
            displayName = displayName,
            files = listOf(TransferFileEntry(fileName, totalSize)),
            totalBytes = totalSize,
            direction = TransferDirection.SENT,
            status = TransferStatus.ACTIVE,
            senderId = identity.deviceId,
            senderName = identity.deviceName,
            receiverId = targetDeviceId,
            receiverName = targetDeviceName,
            startedAtUtcMillis = System.currentTimeMillis(),
            payloadKind = payloadKind,
            remoteIpAddress = targetIp,
            remoteTransferPort = targetPort
        )
        onUpdate(transfer)

        val startNanos = System.nanoTime()
        val socket = Socket()
        session.socket = socket
        try {
            socket.use { s ->
                s.connect(InetSocketAddress(targetIp, targetPort), 10_000)
                val input = s.getInputStream()
                val output = s.getOutputStream()

                val header = TransferHeader(
                    senderId = identity.deviceId,
                    senderName = identity.deviceName,
                    files = listOf(WireFileEntry(fileName, totalSize)),
                    totalSize = totalSize,
                    payloadKind = payloadKind
                )
                LineProtocol.writeLine(output, header.toJson())

                val response = LineProtocol.readLine(input)
                if (response != "ACCEPT") {
                    transfer = transfer.copy(status = TransferStatus.FAILED, errorMessage = "Declined by recipient")
                    onUpdate(transfer)
                    return@withContext
                }

                val throttle = BandwidthThrottle(settings.bandwidthLimitMBps)
                val chunkSize = (settings.chunkSizeKB.coerceAtLeast(4)) * 1024
                val buffer = ByteArray(chunkSize)
                var transferredSoFar = 0L
                var lastPublish = 0L
                val digest = MessageDigest.getInstance("SHA-256")

                java.io.ByteArrayInputStream(bytes).use { byteIn ->
                    var remaining = totalSize
                    while (remaining > 0) {
                        session.awaitIfPaused()
                        coroutineContext.ensureActive()

                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = byteIn.read(buffer, 0, toRead)
                        if (read == -1) throw java.io.IOException("Clipboard content ended early")

                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        remaining -= read
                        transferredSoFar += read
                        throttle.waitIfNeeded(read)

                        val now = System.currentTimeMillis()
                        if (now - lastPublish > 200) {
                            lastPublish = now
                            transfer = withProgress(transfer, transferredSoFar, totalSize, startNanos)
                            onUpdate(transfer)
                        }
                    }
                }
                output.flush()

                val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
                LineProtocol.writeLine(output, FileTrailer(fileName, hashHex).toJson())
                LineProtocol.writeLine(output, COMPLETE_MARKER)

                transfer = transfer.copy(
                    status = TransferStatus.COMPLETED,
                    progressPercent = 100.0,
                    transferredBytes = totalSize,
                    completedAtUtcMillis = System.currentTimeMillis(),
                    durationSeconds = ((System.nanoTime() - startNanos) / 1_000_000_000).toInt()
                )
                onUpdate(transfer)
            }
        } catch (e: Exception) {
            transfer = if (session.cancelRequested) {
                transfer.copy(status = TransferStatus.CANCELED, errorMessage = null)
            } else {
                transfer.copy(status = TransferStatus.FAILED, errorMessage = e.message ?: "Transfer failed")
            }
            onUpdate(transfer)
        }
    }
}

