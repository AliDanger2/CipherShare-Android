package com.ciphershare.android.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ciphershare.android.model.TransferRequest
import com.ciphershare.android.ui.theme.CipherShareColors
import com.ciphershare.android.util.Formatters

@Composable
fun IncomingTransferDialog(request: TransferRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    val isClipboard = request.payloadKind != "Files"

    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = CipherShareColors.Surface,
        title = { Text(if (isClipboard) "Incoming clipboard content" else "Incoming transfer", color = CipherShareColors.TextPrimary) },
        text = {
            Column {
                if (isClipboard) {
                    val kind = if (request.payloadKind == "ClipboardImage") "an image" else "text"
                    Text(
                        "${request.senderName} (${request.senderIp}) wants to copy $kind to your clipboard, ${Formatters.formatBytes(request.totalSize)}.",
                        color = CipherShareColors.TextSecondary
                    )
                    Spacer()
                    Text(
                        "Accepting will replace whatever is currently on your clipboard.",
                        color = CipherShareColors.TextMuted
                    )
                } else {
                    Text(
                        "${request.senderName} (${request.senderIp}) wants to send you ${request.files.size} item(s), ${Formatters.formatBytes(request.totalSize)}.",
                        color = CipherShareColors.TextSecondary
                    )
                    Spacer()
                    request.files.take(5).forEach {
                        Text("• ${it.relativePath}", color = CipherShareColors.TextMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (request.files.size > 5) {
                        Text("...and ${request.files.size - 5} more", color = CipherShareColors.TextMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = CipherShareColors.Accent, contentColor = CipherShareColors.Background)
            ) { Text("Accept") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text("Decline", color = CipherShareColors.Danger) }
        }
    )
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
}
