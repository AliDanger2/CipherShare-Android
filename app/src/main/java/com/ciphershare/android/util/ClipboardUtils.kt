package com.ciphershare.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies received clipboard-sync bytes to the local OS clipboard.
 *
 * IMPORTANT PLATFORM LIMITATION: since Android 10, ClipboardManager.setPrimaryClip() (and
 * getPrimaryClip()) is silently denied - no exception, just a no-op - for any caller that
 * isn't the currently focused window or the default IME. A background foreground-service
 * (like CipherShareService, which is what's running when a transfer arrives while the app
 * isn't open) never has window focus, so calling this the instant bytes finish arriving only
 * works if the user happens to already have CipherShare's own screen open and focused at that
 * exact moment. See AppState.onClipboardReceived/applyPendingClipboardIfAny for how the
 * guaranteed path works: bytes are held as "pending" and re-applied once MainActivity actually
 * gains window focus (e.g. the user taps the "Transfer complete" notification), which is the
 * same workaround real clipboard-sync apps use for this restriction - there's no way to make a
 * silent, fully-background clipboard write reliable, since Android intentionally disallows it.
 */
object ClipboardUtils {

    /**
     * Writes an image to a cache file and shares it as a content:// URI via FileProvider (since
     * ClipData has no way to carry raw image bytes inline), then sets the clipboard. Must reach
     * the actual setPrimaryClip() call on the main thread with the caller's window focused -
     * see the class doc above.
     */
    suspend fun applyClipboardContent(context: Context, payloadKind: String, bytes: ByteArray): Boolean = try {
        val clipUri = if (payloadKind == "ClipboardImage") {
            withContext(Dispatchers.IO) {
                val cacheDir = File(context.cacheDir, "clipboard").apply { mkdirs() }
                val cacheFile = File(cacheDir, "clipboard_${System.currentTimeMillis()}.png")
                cacheFile.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
            }
        } else null

        withContext(Dispatchers.Main) {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboardManager == null) {
                false
            } else if (clipUri != null) {
                // No manual grantUriPermission() call needed: a ClipData backed by a content://
                // URI from a provider with android:grantUriPermissions="true" gets its read
                // grant issued automatically by the platform to whichever app performs the paste.
                clipboardManager.setPrimaryClip(ClipData.newUri(context.contentResolver, "Clipboard image", clipUri))
                true
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Clipboard text", String(bytes, Charsets.UTF_8)))
                true
            }
        }
    } catch (_: Exception) {
        false
    }
}
