package com.ciphershare.android.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Backs the two actions History offers per transfer: tap the row to open the transferred
 * file itself, tap the folder icon to open the folder it landed in.
 *
 * Received files live under a DocumentFile tree (see StorageUtils/TransferServer), whose
 * `.uri` is either a real content:// SAF document (when the user picked a custom download
 * folder in Settings) or a file:// path (the app's own default folder). Sent files are
 * opened via the original content:// URI the user picked to send them - see
 * TransferModel.sourceUris.
 */
object FileOpenUtils {

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    /** Opens a single file - a received file on disk, or a sent transfer's original source file - with whatever app the device has for its type. */
    fun openFile(context: Context, uriString: String): Boolean {
        val uri = resolveOpenableUri(context, uriString) ?: run {
            toast(context, "Couldn't locate that file.")
            return false
        }
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return tryStart(context, intent, "No app found to open this file.")
    }

    /**
     * Opens the folder a received transfer's file(s) landed in, in whatever file-manager app
     * the device has. Only reliable when the destination is a real SAF document tree (i.e. a
     * custom download folder chosen in Settings) - the app's own default private folder has no
     * browsable equivalent in other apps, so that case surfaces a helpful message instead of
     * silently failing.
     *
     * `hasCustomDownloadFolder` reflects the *current* Settings state, not the state at the
     * time this particular transfer was received - a chosen download folder only applies to
     * transfers received after it was set, so a transfer that landed in the app's private
     * folder before that stays there permanently. Passing the current setting lets the message
     * say which of those two situations the user is actually in, instead of always telling them
     * to "choose a folder in Settings" even when they already have.
     */
    fun openFolder(context: Context, uriString: String, hasCustomDownloadFolder: Boolean): Boolean {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") {
            val message = if (hasCustomDownloadFolder) {
                "This transfer was saved before you chose a download folder, so it's in CipherShare's private storage and can't be opened from other apps. Transfers received after choosing a folder can be."
            } else {
                "Choose a download folder in Settings to be able to open transfers from other apps."
            }
            toast(context, message)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return tryStart(context, intent, "No file manager found to open this folder.")
    }

    private fun resolveOpenableUri(context: Context, uriString: String): Uri? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return null
        return try {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX, File(path))
        } catch (_: Exception) {
            null
        }
    }

    private fun tryStart(context: Context, intent: Intent, notFoundMessage: String): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            toast(context, notFoundMessage)
            false
        } catch (_: Exception) {
            toast(context, "Couldn't open that.")
            false
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
