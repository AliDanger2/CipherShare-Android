package com.ciphershare.android.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared by TransferClient, TransferServer, and AppState so the UI can pause/resume/cancel a
 * running transfer by id without either networking class needing to know about the other.
 */
object TransferSessionRegistry {
    private val sessions = ConcurrentHashMap<String, TransferSession>()

    fun register(session: TransferSession) {
        sessions[session.transferId] = session
    }

    fun unregister(transferId: String) {
        sessions.remove(transferId)
    }

    fun pause(transferId: String) {
        sessions[transferId]?.pause()
    }

    fun resume(transferId: String) {
        sessions[transferId]?.resume()
    }

    fun cancel(transferId: String) {
        sessions[transferId]?.requestCancel()
    }

    fun isSender(transferId: String): Boolean = sessions[transferId]?.isSender == true
}
