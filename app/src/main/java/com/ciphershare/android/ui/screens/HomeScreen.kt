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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ciphershare.android.model.DeviceModel
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.model.TransferDirection
import com.ciphershare.android.ui.LocalAppState
import com.ciphershare.android.ui.components.CipherCard
import com.ciphershare.android.ui.components.StatusDot
import com.ciphershare.android.ui.components.colorForDeviceStatus
import com.ciphershare.android.ui.theme.CipherShareColors
import com.ciphershare.android.util.Formatters

@Composable
fun HomeScreen(onSendFilesTo: (DeviceModel) -> Unit, onGoToDevices: () -> Unit) {
    val appState = LocalAppState.current
    val identity by appState.identity.collectAsState()
    val localIp by appState.localIp.collectAsState()
    val devices by appState.devices.collectAsState()
    val history by appState.history.collectAsState()

    val onlineDevices = devices.filter { it.status == DeviceStatus.ONLINE }
    var showDevicePicker by remember { mutableStateOf(false) }

    if (showDevicePicker) {
        DevicePickerDialog(
            devices = onlineDevices,
            onPick = { device -> showDevicePicker = false; onSendFilesTo(device) },
            onDismiss = { showDevicePicker = false }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            CipherCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("This device", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(identity?.deviceName ?: "Unknown", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(CipherShareColors.Success, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            "Discoverable on your network - ${localIp ?: "finding IP..."}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Left as Material: desktop's equivalent stat cards (ACTIVE/COMPLETED/DISCOVERED/
                // TRUSTED on HomeView) show plain numbers with no icon at all, so there's nothing
                // to map "Devices online" or "Completed" onto.
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Wifi,
                    label = "Devices online",
                    value = onlineDevices.size.toString(),
                    onClick = onGoToDevices
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.CheckCircle,
                    label = "Completed",
                    value = history.count { it.status.name == "COMPLETED" }.toString()
                )
            }
        }

        item {
            Button(
                onClick = { if (onlineDevices.size == 1) onSendFilesTo(onlineDevices[0]) else showDevicePicker = true },
                enabled = onlineDevices.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = CipherShareColors.Accent, contentColor = CipherShareColors.Background),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                // Left as Material: desktop has no "Send Files" CTA icon anywhere - it's a text-only
                // button, so there's no CipherShare Send glyph to reuse.
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Send Files", style = MaterialTheme.typography.labelLarge, color = CipherShareColors.Background)
            }
            if (onlineDevices.isEmpty()) {
                Text(
                    "No devices found yet. Make sure the desktop app is open on the same Wi-Fi network.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        }

        if (history.isEmpty()) {
            item {
                Text("Nothing yet - sent and received files will show up here.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(history.take(5)) { transfer ->
                CipherCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(transfer.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (transfer.direction == TransferDirection.SENT) "Sent to ${transfer.receiverName}" else "Received from ${transfer.senderName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(Formatters.formatBytes(transfer.totalBytes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: (() -> Unit)? = null) {
    CipherCard(modifier = modifier) {
        Column {
            Icon(icon, contentDescription = null, tint = CipherShareColors.Accent)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DevicePickerDialog(devices: List<DeviceModel>, onPick: (DeviceModel) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CipherShareColors.Surface,
        title = { Text("Send to which device?", color = CipherShareColors.TextPrimary) },
        text = {
            Column {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(colorForDeviceStatus(device.status), modifier = Modifier.padding(end = 10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.ipAddress, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onPick(device) }) { Text("Select") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
