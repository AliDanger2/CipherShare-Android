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

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile var isRunning = false
        private set

    fun start(scope: CoroutineScope, port: Int) {
        stop()
        try {
            serverSocket = ServerSocket(port)
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
                totalSize = header.totalSize
            )

            val accepted: Boolean = if (needsConfirmation) {
                val deferred = CompletableDeferred<Boolean>()
                pending[request.id] = deferred
                onIncomingRequest?.invoke(request)
                if (settings.notifyIncomingTransfer) {
                    onNotify?.invoke(
                        "Incoming transfer from ${header.senderName}",
                        "${header.files.size} item(s), ${com.ciphershare.android.util.Formatters.formatBytes(header.totalSize)}",
                        true
                    )
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
        var transfer = TransferModel(
            id = transferId,
            displayName = if (header.files.size == 1) header.files[0].relativePath else "${header.files.size} items from ${header.senderName}",
            files = header.files.map { TransferFileEntry(it.relativePath, it.size) },
            totalBytes = header.totalSize,
            direction = TransferDirection.RECEIVED,
            status = TransferStatus.ACTIVE,
            senderId = header.senderId,
            senderName = header.senderName,
            receiverId = identity.deviceId,
            receiverName = identity.deviceName,
            startedAtUtcMillis = System.currentTimeMillis(),
            destinationFolder = StorageUtils.describeDownloadLocation(context, settings),
            remoteIpAddress = remoteIp,
            remoteTransferPort = settings.networkPort
        )
        onTransferAdded?.invoke(transfer)

        val root = StorageUtils.resolveDownloadRoot(context, settings)
        if (root == null) {
            transfer = transfer.copy(status = TransferStatus.FAILED, errorMessage = "Could not access download location")
            onTransferUpdated?.invoke(transfer)
            return
        }
        // One subfolder per incoming batch, named after the sender, so repeated sends never collide.
        val batchFolder = StorageUtils.getOrCreateDir(root, "${header.senderName}_${System.currentTimeMillis()}")

        val transferredSoFar = AtomicLong(0)
        val startNanos = System.nanoTime()
        var failureMessage: String? = null
        var wasCanceled = false

        for (fileEntry in header.files) {
            try {
                receiveOneFile(input, batchFolder, fileEntry, settings, session, transferredSoFar, header.totalSize, startNanos) { percent, transferredBytes, speedMBps ->
                    transfer = transfer.copy(
                        progressPercent = percent,
                        transferredBytes = transferredBytes,
                        speedMBps = speedMBps
                    )
                    onTransferUpdated?.invoke(transfer)
                }
            } catch (e: Exception) {
                if (session.cancelRequested) {
                    wasCanceled = true
                } else {
                    failureMessage = e.message ?: "Transfer failed"
                }
                break
            }
        }

        if (!wasCanceled) LineProtocol.readLine(input) // best-effort read of the Complete marker

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
        if (notifyOk) onNotify?.invoke("Transfer complete", "Received ${transfer.displayName} from ${header.senderName}", false)
        if (notifyFail) onNotify?.invoke("Transfer failed", "${transfer.displayName} from ${header.senderName}: $failureMessage", false)
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
    ) {
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
