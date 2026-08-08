package com.ciphershare.android.net

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.ciphershare.android.model.AppSettings
import com.ciphershare.android.model.LocalIdentity
import com.ciphershare.android.model.SecurityLevel
import com.ciphershare.android.model.TransferDirection
import com.ciphershare.android.model.TransferFileEntry
import com.ciphershare.android.model.TransferModel
import com.ciphershare.android.model.TransferRequest
import com.ciphershare.android.model.TransferStatus
import com.ciphershare.android.util.StorageUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Accepts incoming TCP connections and receives files, mirroring the receive half of
 * CipherShare (desktop) Services/TransferService.cs:
 *   read header line -> decide accept/decline -> write ACCEPT/DECLINE -> per file: stream
 *   exact byte count into "<name>.partial", verify SHA-256 from the trailer line, rename to
 *   final name -> read the Complete marker.
 *
 * Incoming transfers can be canceled (but not paused - same restriction the desktop enforces,
 * since pausing an in-progress receive isn't supported on either side) via a TransferSession
 * registered in TransferSessionRegistry under the transfer's id.
 */
class TransferServer(private val context: Context) {

    var getSettings: () -> AppSettings = { AppSettings() }
    var getIdentity: () -> LocalIdentity? = { null }
    var isDeviceTrusted: (String) -> Boolean = { false }

    var onTransferAdded: ((TransferModel) -> Unit)? = null
    var onTransferUpdated: ((TransferModel) -> Unit)? = null
    var onIncomingRequest: ((TransferRequest) -> Unit)? = null
    var onNotify: ((title: String, message: String, isIncomingRequest: Boolean) -> Unit)? = null
    /** Fired once an incoming request's accept/decline decision is final (answered or timed out), so the UI can drop it from its pending list. */
    var onRequestResolved: ((String) -> Unit)? = null
    /**
     * Fired whenever a clipboard-sync payload finishes downloading, in addition to (not instead
     * of) this class's own best-effort immediate apply attempt. See ClipboardUtils' doc comment
     * for why that immediate attempt is unreliable from here - this callback is what lets
     * AppState hold the bytes as "pending" and guarantee they eventually get applied once the
     * app's UI actually has window focus.
     */
    var onClipboardReceived: ((payloadKind: String, bytes: ByteArray, senderName: String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile var isRunning = false
        private set

    fun start(scope: CoroutineScope, port: Int) {
        stop()
        try {
            // reuseAddress matters here: start()/stop() can legitimately run back-to-back
            // (e.g. a settings change, or the service restarting networking after a permission
            // grant) close enough together that the OS hasn't fully released the previous
            // socket's hold on this port yet. Without SO_REUSEADDR that rebind can throw
            // "Address already in use", which previously left the server permanently not
            // listening (isRunning stuck false, nothing retries) even though nothing was
            // actually wrong - this is what caused "can't connect to me on port 54321" to show
            // up on a device that otherwise looked fine. A plain ServerSocket(port) does not
            // reliably set this, so bind explicitly instead of using that constructor.
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            onNotify?.invoke("CipherShare", "Could not listen for incoming transfers on TCP port $port: ${e.message}", false)
            return
        }
        isRunning = true
        acceptJob = scope.launch(Dispatchers.IO) {
            val server = serverSocket ?: return@launch
            while (isRunning) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break // closed on stop()
                } catch (_: Exception) {
                    continue
                }
                launch(Dispatchers.IO) { handleConnection(socket) }
            }
        }
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    /** Called by the UI (in-app dialog or a notification action) when the user answers an incoming-transfer request. */
    fun respond(requestId: String, accept: Boolean) {
        pending[requestId]?.complete(accept)
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 0
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val remoteIp = s.inetAddress?.hostAddress ?: "unknown"

            val headerJson = LineProtocol.readLine(input) ?: return
            val header = TransferHeader.fromJson(headerJson) ?: return

            val settings = getSettings()
            val identity = getIdentity() ?: return
            val trusted = isDeviceTrusted(header.senderId)

            val needsConfirmation = when (settings.securityLevel) {
                SecurityLevel.NO_CONFIRMATION_REQUIRED -> false
                SecurityLevel.SKIP_CONFIRMATION_FOR_TRUSTED -> !trusted
                SecurityLevel.REQUIRE_CONFIRMATION_FOR_ALL -> true
            }

            val request = TransferRequest(
                senderId = header.senderId,
                senderName = header.senderName,
                senderIp = remoteIp,
                files = header.files.map { TransferFileEntry(it.relativePath, it.size) },
                totalSize = header.totalSize,
                payloadKind = header.payloadKind
            )

            val isClipboard = header.payloadKind != "Files"

            val accepted: Boolean = if (needsConfirmation) {
                val deferred = CompletableDeferred<Boolean>()
                pending[request.id] = deferred
                onIncomingRequest?.invoke(request)
                if (settings.notifyIncomingTransfer) {
                    val message = if (isClipboard) {
                        val kind = if (header.payloadKind == "ClipboardImage") "an image" else "text"
                        "wants to copy $kind to your clipboard"
                    } else {
                        "${header.files.size} item(s), ${com.ciphershare.android.util.Formatters.formatBytes(header.totalSize)}"
                    }
                    onNotify?.invoke("Incoming transfer from ${header.senderName}", message, true)
                }
                // Give the user up to 2 minutes to respond before auto-declining.
                val result = withTimeoutOrNull(120_000) { deferred.await() } ?: false
                result
            } else {
                true
            }
            pending.remove(request.id)
            onRequestResolved?.invoke(request.id) // let the UI drop it from pendingRequests either way

            LineProtocol.writeLine(output, if (accepted) "ACCEPT" else "DECLINE")
            if (!accepted) return

            val transferId = UUID.randomUUID().toString()
            val session = TransferSession(transferId, isSender = false).also {
                it.job = coroutineContext[Job]
                it.socket = s
            }
            TransferSessionRegistry.register(session)

            try {
                receiveTransfer(transferId, session, input, header, settings, identity, remoteIp)
            } finally {
                TransferSessionRegistry.unregister(transferId)
            }
        }
    }

    private suspend fun receiveTransfer(
        transferId: String,
        session: TransferSession,
        input: java.io.InputStream,
        header: TransferHeader,
        settings: AppSettings,
        identity: LocalIdentity,
        remoteIp: String
    ) {
        val isClipboard = header.payloadKind != "Files"

        var transfer = TransferModel(
            id = transferId,
            displayName = when {
                isClipboard && header.payloadKind == "ClipboardImage" -> "Clipboard image"
                isClipboard -> "Clipboard text"
                header.files.size == 1 -> header.files[0].relativePath
                else -> "${header.files.size} items from ${header.senderName}"
            },
            files = header.files.map { TransferFileEntry(it.relativePath, it.size) },
            totalBytes = header.totalSize,
            direction = TransferDirection.RECEIVED,
            status = TransferStatus.ACTIVE,
            senderId = header.senderId,
            senderName = header.senderName,
            receiverId = identity.deviceId,
            receiverName = identity.deviceName,
            startedAtUtcMillis = System.currentTimeMillis(),
            destinationFolder = if (isClipboard) null else StorageUtils.describeDownloadLocation(context, settings),
            payloadKind = header.payloadKind,
            remoteIpAddress = remoteIp,
            remoteTransferPort = settings.networkPort
        )
        onTransferAdded?.invoke(transfer)

        val transferredSoFar = AtomicLong(0)
        val startNanos = System.nanoTime()
        var failureMessage: String? = null
        var wasCanceled = false

        if (isClipboard) {
            try {
                val bytes = receiveClipboardBytes(input, header.files[0], settings, session, transferredSoFar, header.totalSize, startNanos) { percent, transferredBytes, speedMBps ->
                    transfer = transfer.copy(progressPercent = percent, transferredBytes = transferredBytes, speedMBps = speedMBps)
                    onTransferUpdated?.invoke(transfer)
                }
                // Best-effort immediate attempt (only actually lands if the app happens to
                // already have window focus right now - see ClipboardUtils' doc comment),
                // plus the guaranteed path: hand the bytes to AppState so they get re-applied
                // once the app's UI actually gains focus.
                applyClipboardContent(header.payloadKind, bytes)
                onClipboardReceived?.invoke(header.payloadKind, bytes, header.senderName)
            } catch (e: Exception) {
                if (session.cancelRequested) wasCanceled = true else failureMessage = e.message ?: "Transfer failed"
            }
            if (!wasCanceled) LineProtocol.readLine(input) // best-effort read of the Complete marker
        } else {
            val root = StorageUtils.resolveDownloadRoot(context, settings)
            if (root == null) {
                transfer = transfer.copy(status = TransferStatus.FAILED, errorMessage = "Could not access download location")
                onTransferUpdated?.invoke(transfer)
                return
            }
            // One subfolder per incoming batch, named after the sender, so repeated sends never collide.
            val batchFolder = StorageUtils.getOrCreateDir(root, "${header.senderName}_${System.currentTimeMillis()}")
            transfer = transfer.copy(destinationFolderUri = batchFolder.uri.toString())
            onTransferUpdated?.invoke(transfer)

            val receivedUris = mutableListOf<String>()
            for (fileEntry in header.files) {
                try {
                    val fileUri = receiveOneFile(input, batchFolder, fileEntry, settings, session, transferredSoFar, header.totalSize, startNanos) { percent, transferredBytes, speedMBps ->
                        transfer = transfer.copy(
                            progressPercent = percent,
                            transferredBytes = transferredBytes,
                            speedMBps = speedMBps
                        )
                        onTransferUpdated?.invoke(transfer)
                    }
                    receivedUris.add(fileUri.toString())
                } catch (e: Exception) {
                    if (session.cancelRequested) {
                        wasCanceled = true
                    } else {
                        failureMessage = e.message ?: "Transfer failed"
                    }
                    break
                }
            }
            transfer = transfer.copy(receivedFileUris = receivedUris)

            if (!wasCanceled) LineProtocol.readLine(input) // best-effort read of the Complete marker
        }

        transfer = when {
            wasCanceled -> transfer.copy(status = TransferStatus.CANCELED)
            failureMessage == null -> transfer.copy(
                status = TransferStatus.COMPLETED,
                progressPercent = 100.0,
                transferredBytes = header.totalSize,
                completedAtUtcMillis = System.currentTimeMillis(),
                durationSeconds = ((System.nanoTime() - startNanos) / 1_000_000_000).toInt()
            )
            else -> transfer.copy(status = TransferStatus.FAILED, errorMessage = failureMessage)
        }
        onTransferUpdated?.invoke(transfer)

        val notifyOk = settings.notifyTransferComplete && transfer.status == TransferStatus.COMPLETED
        val notifyFail = settings.notifyTransferFailed && transfer.status == TransferStatus.FAILED
        if (notifyOk) {
            // For a clipboard payload, tapping this notification is also what finishes the job:
            // Android only allows the actual clipboard write once this app has focus (see
            // AppState.applyPendingClipboardIfAny), which opening this notification provides.
            val message = if (isClipboard) {
                "Received ${transfer.displayName} from ${header.senderName} - tap to copy it"
            } else {
                "Received ${transfer.displayName} from ${header.senderName}"
            }
            onNotify?.invoke("Transfer complete", message, false)
        }
        if (notifyFail) onNotify?.invoke("Transfer failed", "${transfer.displayName} from ${header.senderName}: $failureMessage", false)
    }

    /**
     * Receives a clipboard-sync payload: the single synthetic entry in header.files, buffered
     * entirely in memory (clipboard content is always small) instead of written to disk via SAF.
     */
    private suspend fun receiveClipboardBytes(
        input: java.io.InputStream,
        fileEntry: WireFileEntry,
        settings: AppSettings,
        session: TransferSession,
        transferredSoFar: AtomicLong,
        grandTotalBytes: Long,
        startNanos: Long,
        onProgress: (percent: Double, transferred: Long, speedMBps: Double) -> Unit
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val throttle = BandwidthThrottle(settings.bandwidthLimitMBps)
        val chunkSize = (settings.chunkSizeKB.coerceAtLeast(4)) * 1024
        val buffer = ByteArray(chunkSize)
        var remaining = fileEntry.size
        val out = java.io.ByteArrayOutputStream(remaining.coerceAtMost(1_048_576L).toInt().coerceAtLeast(16))

        var lastPublish = 0L
        while (remaining > 0) {
            session.awaitIfPaused()
            coroutineContext.ensureActive()

            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) throw java.io.IOException("Connection closed early while receiving clipboard content")

            out.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            remaining -= read
            transferredSoFar.addAndGet(read.toLong())
            throttle.waitIfNeeded(read)

            val now = System.currentTimeMillis()
            if (now - lastPublish > 200) {
                lastPublish = now
                publishProgress(transferredSoFar.get(), grandTotalBytes, startNanos, onProgress)
            }
        }

        val trailerJson = LineProtocol.readLine(input)
        val trailer = FileTrailer.fromJson(trailerJson)
        if (settings.verifyIntegrity) {
            val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (trailer == null || !trailer.sha256Hex.equals(computedHash, ignoreCase = true)) {
                throw java.io.IOException("Checksum mismatch for clipboard content - it may be corrupted")
            }
        }

        publishProgress(transferredSoFar.get(), grandTotalBytes, startNanos, onProgress)
        return out.toByteArray()
    }

    /**
     * Sets the local OS clipboard from received bytes. This is a best-effort immediate attempt
     * only - see ClipboardUtils' doc comment for why a call from a background service like this
     * one is usually silently denied by Android, and why onClipboardReceived (invoked by the
     * caller right after this) is what actually guarantees delivery.
     */
    private suspend fun applyClipboardContent(payloadKind: String, bytes: ByteArray) {
        com.ciphershare.android.util.ClipboardUtils.applyClipboardContent(context, payloadKind, bytes)
    }

    private suspend fun receiveOneFile(
        input: java.io.InputStream,
        batchFolder: DocumentFile,
        fileEntry: WireFileEntry,
        settings: AppSettings,
        session: TransferSession,
        transferredSoFar: AtomicLong,
        grandTotalBytes: Long,
        startNanos: Long,
        onProgress: (percent: Double, transferred: Long, speedMBps: Double) -> Unit
    ): android.net.Uri {
        val segments = fileEntry.relativePath.split("/").filter { it.isNotBlank() }
        val fileName = segments.lastOrNull() ?: fileEntry.relativePath
        val dirSegments = segments.dropLast(1)
        val targetDir = StorageUtils.getOrCreateDirPath(batchFolder, dirSegments)

        val partialName = "$fileName.partial"
        val partialDoc = StorageUtils.createOutputFile(targetDir, partialName)
            ?: throw java.io.IOException("Could not create destination file for $fileName")

        val digest = MessageDigest.getInstance("SHA-256")
        val throttle = BandwidthThrottle(settings.bandwidthLimitMBps)
        val chunkSize = (settings.chunkSizeKB.coerceAtLeast(4)) * 1024
        val buffer = ByteArray(chunkSize)
        var remaining = fileEntry.size

        var lastPublish = 0L
        try {
            context.contentResolver.openOutputStream(partialDoc.uri)?.use { out ->
                while (remaining > 0) {
                    // Receive can be canceled but not paused (matches the desktop's own
                    // restriction), so this only ever really checks for cancellation - but
                    // calling awaitIfPaused() is harmless since pause() is a no-op here.
                    session.awaitIfPaused()
                    coroutineContext.ensureActive()

                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) throw java.io.IOException("Connection closed early while receiving $fileName")

                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    remaining -= read
                    transferredSoFar.addAndGet(read.toLong())
                    throttle.waitIfNeeded(read)

                    val now = System.currentTimeMillis()
                    if (now - lastPublish > 200) {
                        lastPublish = now
                        publishProgress(transferredSoFar.get(), grandTotalBytes, startNanos, onProgress)
                    }
                }
            } ?: throw java.io.IOException("Could not open output stream for $fileName")
        } catch (e: Exception) {
            if (!settings.keepPartialFilesOnFailure) partialDoc.delete()
            throw e
        }

        val trailerJson = LineProtocol.readLine(input)
        val trailer = FileTrailer.fromJson(trailerJson)
        if (settings.verifyIntegrity) {
            val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (trailer == null || !trailer.sha256Hex.equals(computedHash, ignoreCase = true)) {
                if (!settings.keepPartialFilesOnFailure) partialDoc.delete()
                throw java.io.IOException("Checksum mismatch for $fileName - file may be corrupted")
            }
        }

        partialDoc.renameTo(fileName)
        publishProgress(transferredSoFar.get(), grandTotalBytes, startNanos, onProgress)
        return partialDoc.uri
    }

    private fun publishProgress(
        transferred: Long,
        total: Long,
        startNanos: Long,
        onProgress: (percent: Double, transferred: Long, speedMBps: Double) -> Unit
    ) {
        val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val speedMBps = if (elapsedSeconds > 0) (transferred / 1024.0 / 1024.0) / elapsedSeconds else 0.0
        val percent = if (total > 0) (transferred.toDouble() / total.toDouble()) * 100.0 else 0.0
        onProgress(percent, transferred, speedMBps)
    }
}
