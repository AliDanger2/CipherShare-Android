package com.ciphershare.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ciphershare.android.CipherShareApplication
import com.ciphershare.android.MainActivity
import com.ciphershare.android.data.AppState
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.model.TransferRequest
import com.ciphershare.android.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val STATUS_NOTIFICATION_ID = 1
private const val EVENT_NOTIFICATION_ID_BASE = 1000

/**
 * Keeps the UDP discovery listener and TCP transfer server alive while the app isn't in the
 * foreground - without this, Android would eventually suspend the process's sockets and the
 * phone would stop being discoverable/reachable a few minutes after the user leaves the app.
 * Mirrors the always-on nature of the desktop app, which keeps running (and discoverable) as
 * long as it's open, including minimized.
 *
 * Incoming-transfer requests get their own notification with Accept/Decline buttons right on
 * it (handled by TransferResponseReceiver), built directly from AppState.pendingRequests so it
 * stays in sync however the request gets answered - tapping a button here, answering the
 * in-app dialog, or the request timing out unanswered.
 */
class CipherShareService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var appState: AppState

    /** requestId -> the notification id it was posted under, so we can cancel it once resolved. */
    private val postedRequestNotifications = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        appState = AppState.getInstance(applicationContext)
        appState.initialize()

        startForeground(STATUS_NOTIFICATION_ID, buildStatusNotification("Starting..."))
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun observeState() {
        serviceScope.launch {
            combine(appState.identity, appState.localIp, appState.devices) { identity, ip, devices ->
                Triple(identity?.deviceName, ip, devices.count { it.status == DeviceStatus.ONLINE })
            }.collectLatest { (name, ip, onlineCount) ->
                val label = name ?: "this device"
                val ipText = ip?.let { " ($it)" } ?: ""
                val text = "Discoverable as $label$ipText - $onlineCount device(s) nearby"
                updateStatusNotification(text)
            }
        }

        serviceScope.launch {
            appState.notificationEvents.collectLatest { notification ->
                if (notification == null) return@collectLatest
                postEventNotification(notification.title, notification.message)
            }
        }

        serviceScope.launch {
            appState.pendingRequests.collectLatest { requests ->
                val currentIds = requests.map { it.id }.toSet()

                requests.filter { it.id !in postedRequestNotifications.keys }.forEach { request ->
                    postIncomingTransferNotification(request)
                }

                val resolvedIds = postedRequestNotifications.keys.filter { it !in currentIds }
                resolvedIds.forEach { id ->
                    postedRequestNotifications.remove(id)?.let { notificationId ->
                        safeCancel(notificationId)
                    }
                }
            }
        }
    }

    private fun buildStatusNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CipherShareApplication.CHANNEL_STATUS)
            .setContentTitle("CipherShare")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateStatusNotification(text: String) {
        safeNotify(STATUS_NOTIFICATION_ID, buildStatusNotification(text))
    }

    private fun postEventNotification(title: String, message: String) {
        val openAppIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CipherShareApplication.CHANNEL_EVENTS)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        safeNotify(EVENT_NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    /** Posts an incoming-transfer notification with Accept/Decline action buttons right on it. */
    private fun postIncomingTransferNotification(request: TransferRequest) {
        if (!appState.settings.value.notifyIncomingTransfer) return

        // Stable positive int derived from the request id, distinct from STATUS_NOTIFICATION_ID
        // and the EVENT_NOTIFICATION_ID_BASE range used above.
        val notificationId = 2_000_000 + (request.id.hashCode() and 0x0FFFFFFF)
        postedRequestNotifications[request.id] = notificationId

        val acceptIntent = Intent(this, TransferResponseReceiver::class.java).apply {
            action = TransferResponseReceiver.ACTION_ACCEPT
            putExtra(TransferResponseReceiver.EXTRA_REQUEST_ID, request.id)
            putExtra(TransferResponseReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            this, notificationId * 2,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, TransferResponseReceiver::class.java).apply {
            action = TransferResponseReceiver.ACTION_DECLINE
            putExtra(TransferResponseReceiver.EXTRA_REQUEST_ID, request.id)
            putExtra(TransferResponseReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, notificationId * 2 + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = PendingIntent.getActivity(
            this, notificationId,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CipherShareApplication.CHANNEL_EVENTS)
            .setContentTitle("Incoming transfer from ${request.senderName}")
            .setContentText("${request.files.size} item(s), ${Formatters.formatBytes(request.totalSize)}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false) // only dismissed once the request is actually resolved (see observeState above)
            .setContentIntent(openAppIntent)
            .addAction(0, "Accept", acceptPendingIntent)
            .addAction(0, "Decline", declinePendingIntent)
            .build()

        safeNotify(notificationId, notification)
    }

    /** POST_NOTIFICATIONS can be denied by the user on Android 13+ - never let a missing permission crash the service. */
    private fun safeNotify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun safeCancel(id: Int) {
        try {
            NotificationManagerCompat.from(this).cancel(id)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
