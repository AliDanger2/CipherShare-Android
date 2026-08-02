package com.ciphershare.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.ciphershare.android.data.AppState

/**
 * Handles taps on the Accept/Decline buttons CipherShareService attaches to an
 * incoming-transfer notification, so the person can respond without opening the app. Calls
 * the exact same AppState.respondToIncomingRequest the in-app dialog calls, so both paths
 * stay in sync no matter which one answers first.
 */
class TransferResponseReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT = "com.ciphershare.android.action.ACCEPT_TRANSFER"
        const val ACTION_DECLINE = "com.ciphershare.android.action.DECLINE_TRANSFER"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val accept = intent.action == ACTION_ACCEPT

        AppState.getInstance(context.applicationContext).respondToIncomingRequest(requestId, accept)

        if (notificationId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notificationId)
            } catch (_: Exception) {
            }
        }
    }
}
