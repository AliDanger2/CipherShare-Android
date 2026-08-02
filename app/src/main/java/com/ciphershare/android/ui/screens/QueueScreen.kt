package com.ciphershare.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ciphershare.android.util.Formatters

@Composable
fun QueueScreen() {
    val appState = LocalAppState.current
    val transfers by appState.activeTransfers.collectAsState()

    if (transfers.isEmpty()) {
        EmptyQueueState()
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(transfers, key = { it.id }) { transfer ->
            TransferRow(
                transfer = transfer,
                onPause = { appState.pauseTransfer(transfer.id) },
                onResume = { appState.resumeTransfer(transfer.id) },
                onCancel = { appState.cancelTransfer(transfer.id) }
            )
        }
    }
}

@Composable
private fun TransferRow(transfer: TransferModel, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    // Pause/resume only make sense for transfers this device is sending - matches the
    // desktop app's own restriction (pausing an in-progress receive isn't supported).
    val canPauseResume = transfer.direction == TransferDirection.SENT &&
        (transfer.status == TransferStatus.ACTIVE || transfer.status == TransferStatus.PAUSED)
    val canCancel = transfer.status == TransferStatus.ACTIVE || transfer.status == TransferStatus.PAUSED || transfer.status == TransferStatus.PENDING

    CipherCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Matches TransferRowControl on desktop: UploadIcon for sent, DownloadIcon for received.
                Icon(
                    painterResource(if (transfer.direction == TransferDirection.SENT) R.drawable.ic_cs_upload else R.drawable.ic_cs_download),
                    contentDescription = null,
                    tint = CipherShareColors.Accent,
                    modifier = Modifier.height(18.dp)
                )
                Spacer(Modifier.height(0.dp))
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(transfer.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (transfer.direction == TransferDirection.SENT) "To ${transfer.receiverName}" else "From ${transfer.senderName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Pill(transfer.status.name.lowercase().replaceFirstChar { it.uppercase() }, colorForTransferStatus(transfer.status))
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { (Formatters.clampPercent(transfer.progressPercent) / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = CipherShareColors.Accent,
                trackColor = CipherShareColors.SurfaceHover
            )

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${Formatters.formatBytes(transfer.transferredBytes)} / ${Formatters.formatBytes(transfer.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(Formatters.formatSpeed(transfer.speedMBps), style = MaterialTheme.typography.bodySmall)
            }

            if (canPauseResume || canCancel) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (canPauseResume) {
                        if (transfer.status == TransferStatus.PAUSED) {
                            TextButton(onClick = onResume) {
                                Icon(painterResource(R.drawable.ic_cs_play), contentDescription = null, tint = CipherShareColors.Accent, modifier = Modifier.height(16.dp))
                                Spacer(Modifier.height(0.dp))
                                Text(" Resume", color = CipherShareColors.Accent)
                            }
                        } else {
                            TextButton(onClick = onPause) {
                                Icon(painterResource(R.drawable.ic_cs_pause), contentDescription = null, tint = CipherShareColors.TextSecondary, modifier = Modifier.height(16.dp))
                                Spacer(Modifier.height(0.dp))
                                Text(" Pause", color = CipherShareColors.TextSecondary)
                            }
                        }
                    }
                    if (canCancel) {
                        TextButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_cs_close), contentDescription = null, tint = CipherShareColors.Danger, modifier = Modifier.height(16.dp))
                            Spacer(Modifier.height(0.dp))
                            Text(" Cancel", color = CipherShareColors.Danger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(painterResource(R.drawable.ic_cs_queue), contentDescription = null, tint = CipherShareColors.TextMuted, modifier = Modifier.height(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("No active transfers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Files you send or receive will show up here with live progress.", style = MaterialTheme.typography.bodyMedium)
    }
}
