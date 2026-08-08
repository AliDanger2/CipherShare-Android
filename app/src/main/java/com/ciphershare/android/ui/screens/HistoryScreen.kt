package com.ciphershare.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ciphershare.android.R
import com.ciphershare.android.model.TransferDirection
import com.ciphershare.android.model.TransferModel
import com.ciphershare.android.model.TransferStatus
import com.ciphershare.android.ui.LocalAppState
import com.ciphershare.android.ui.components.CipherCard
import com.ciphershare.android.ui.components.Pill
import com.ciphershare.android.ui.components.colorForTransferStatus
import com.ciphershare.android.ui.theme.CipherShareColors
import com.ciphershare.android.util.FileOpenUtils
import com.ciphershare.android.util.Formatters

@Composable
fun HistoryScreen() {
    val appState = LocalAppState.current
    val history by appState.history.collectAsState()
    val settings by appState.settings.collectAsState()
    val hasCustomDownloadFolder = settings.downloadTreeUri != null

    Column(modifier = Modifier.fillMaxSize()) {
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { appState.clearHistory() }) {
                    Icon(painterResource(R.drawable.ic_cs_trash), contentDescription = "Clear history", tint = CipherShareColors.TextSecondary)
                }
            }
        }

        if (history.isEmpty()) {
            EmptyHistoryState()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history, key = { it.id }) { HistoryRow(it, hasCustomDownloadFolder) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(transfer: TransferModel, hasCustomDownloadFolder: Boolean) {
    val context = LocalContext.current

    // Only a completed transfer's files are actually on disk (or, for a sent item, still
    // wherever the user originally picked them from) - failed/canceled entries have nothing
    // reliable to open.
    val singleFileUri = if (transfer.status == TransferStatus.COMPLETED) {
        if (transfer.direction == TransferDirection.RECEIVED) transfer.receivedFileUris.singleOrNull()
        else transfer.sourceUris.singleOrNull()
    } else null
    // Only received transfers know exactly which folder they landed in - a sent transfer's
    // source file(s) could be picked from anywhere, so there's no reliable folder to jump to.
    val folderUri = if (transfer.status == TransferStatus.COMPLETED && transfer.direction == TransferDirection.RECEIVED) {
        transfer.destinationFolderUri
    } else null

    // Tapping the row opens the single file when there is exactly one; for a multi-file batch
    // there's nothing singular to open, so it falls back to opening the containing folder.
    val rowClickable: Modifier = when {
        singleFileUri != null -> Modifier.clickable { FileOpenUtils.openFile(context, singleFileUri) }
        folderUri != null -> Modifier.clickable { FileOpenUtils.openFolder(context, folderUri, hasCustomDownloadFolder) }
        else -> Modifier
    }

    CipherCard(modifier = Modifier.fillMaxWidth().then(rowClickable)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Matches TransferRowControl on desktop: UploadIcon for sent, DownloadIcon for received.
            Icon(
                painterResource(if (transfer.direction == TransferDirection.SENT) R.drawable.ic_cs_upload else R.drawable.ic_cs_download),
                contentDescription = null,
                tint = CipherShareColors.TextSecondary,
                modifier = Modifier.height(18.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(transfer.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${if (transfer.direction == TransferDirection.SENT) "To ${transfer.receiverName}" else "From ${transfer.senderName}"} - ${Formatters.formatDateTime(transfer.completedAtUtcMillis)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (transfer.status == TransferStatus.FAILED && !transfer.errorMessage.isNullOrBlank()) {
                    Text(transfer.errorMessage, style = MaterialTheme.typography.bodySmall, color = CipherShareColors.Danger)
                }
            }
            if (folderUri != null) {
                // Same FolderIcon geometry as desktop's TransferRowControl "show in folder" action.
                IconButton(onClick = { FileOpenUtils.openFolder(context, folderUri, hasCustomDownloadFolder) }) {
                    Icon(painterResource(R.drawable.ic_cs_folder), contentDescription = "Open containing folder", tint = CipherShareColors.TextSecondary)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Pill(transfer.status.name.lowercase().replaceFirstChar { it.uppercase() }, colorForTransferStatus(transfer.status))
                Text(Formatters.formatBytes(transfer.totalBytes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun EmptyHistoryState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_cs_history), contentDescription = null, tint = CipherShareColors.TextMuted, modifier = Modifier.height(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No transfer history yet", style = MaterialTheme.typography.titleMedium)
        }
    }
}
