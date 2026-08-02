package com.ciphershare.android.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ciphershare.android.model.AppSettings
import java.io.File

/**
 * Wraps both possible destinations - the app's own default folder, or a folder the user
 * picked via the system folder picker (Settings > Download location) - behind the same
 * DocumentFile API, so TransferServer doesn't need to know which one it's writing into.
 * DocumentFile.fromFile() works for a plain java.io.File tree with zero extra permissions;
 * DocumentFile.fromTreeUri() works for a SAF tree the user granted persistent access to.
 */
object StorageUtils {

    fun defaultDownloadDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "CipherShare").apply { mkdirs() }
    }

    fun resolveDownloadRoot(context: Context, settings: AppSettings): DocumentFile? {
        val treeUriString = settings.downloadTreeUri
        return if (treeUriString != null) {
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
            } catch (_: Exception) {
                null
            }
        } else {
            DocumentFile.fromFile(defaultDownloadDir(context))
        }
    }

    /** A human-readable description of the current download location, for the Settings screen. */
    fun describeDownloadLocation(context: Context, settings: AppSettings): String {
        return if (settings.downloadTreeUri != null) {
            resolveDownloadRoot(context, settings)?.name?.let { "Chosen folder: $it" } ?: "Chosen folder"
        } else {
            "App storage / CipherShare (default)"
        }
    }

    fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile {
        return parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name) ?: parent
    }

    /** Walks a (possibly nested) relative path, creating directories as needed, and returns the final directory. */
    fun getOrCreateDirPath(root: DocumentFile, segments: List<String>): DocumentFile {
        var current = root
        for (segment in segments) {
            if (segment.isBlank()) continue
            current = getOrCreateDir(current, segment)
        }
        return current
    }

    fun createOutputFile(dir: DocumentFile, name: String): DocumentFile? {
        dir.findFile(name)?.let { it.delete() } // overwrite semantics, matches desktop's FileMode.Create
        return dir.createFile("application/octet-stream", name)
    }

    /** A single file to send: where its bytes come from, and the relative path to report on the wire. */
    data class SendableFile(val relativePath: String, val uri: Uri, val size: Long)

    /** Expands a list of individually-picked file URIs (ACTION_OPEN_DOCUMENT, multiple) into SendableFiles. */
    fun expandPickedFiles(context: Context, uris: List<Uri>): List<SendableFile> {
        return uris.mapNotNull { uri ->
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@mapNotNull null
            val name = doc.name ?: return@mapNotNull null
            val size = doc.length()
            SendableFile(relativePath = name, uri = uri, size = size)
        }
    }

    /** Expands a picked folder tree (ACTION_OPEN_DOCUMENT_TREE) into SendableFiles with folder-prefixed relative paths. */
    fun expandPickedTree(context: Context, treeUri: Uri): List<SendableFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val baseName = root.name ?: "folder"
        val result = mutableListOf<SendableFile>()
        walk(root, baseName, result)
        return result
    }

    private fun walk(dir: DocumentFile, relativePrefix: String, out: MutableList<SendableFile>) {
        for (child in dir.listFiles()) {
            val childName = child.name ?: continue
            val childRelative = "$relativePrefix/$childName"
            if (child.isDirectory) {
                walk(child, childRelative, out)
            } else if (child.isFile) {
                out.add(SendableFile(relativePath = childRelative, uri = child.uri, size = child.length()))
            }
        }
    }
}
