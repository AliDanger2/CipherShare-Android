package com.ciphershare.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ciphershare.android.R
import com.ciphershare.android.model.DeviceModel
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.model.DeviceType
import com.ciphershare.android.ui.LocalAppState
import com.ciphershare.android.ui.components.CipherCard
import com.ciphershare.android.ui.components.Pill
import com.ciphershare.android.ui.components.StatusDot
import com.ciphershare.android.ui.components.colorForDeviceStatus
import com.ciphershare.android.ui.theme.CipherShareColors
import com.ciphershare.android.util.Formatters

@Composable
fun DevicesScreen(onSendFiles: (DeviceModel) -> Unit, onSendFolder: (DeviceModel) -> Unit, onSendClipboard: (DeviceModel) -> Unit) {
    val appState = LocalAppState.current
    val devices by appState.devices.collectAsState()

    if (devices.isEmpty()) {
        EmptyDevicesState()
        return
    }

    val sorted = devices.sortedWith(
        compareBy(
            { it.status != DeviceStatus.ONLINE },
            { it.status != DeviceStatus.IDLE },
            { it.name.lowercase() }
        )
    )

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(sorted, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                onSendFiles = { onSendFiles(device) },
                onSendFolder = { onSendFolder(device) },
                onSendClipboard = { onSendClipboard(device) },
                onToggleTrust = { appState.setDeviceTrusted(device.id, !device.isTrusted) },
                onForget = { appState.forgetDevice(device.id) }
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceModel,
    onSendFiles: () -> Unit,
    onSendFolder: () -> Unit,
    onSendClipboard: () -> Unit,
    onToggleTrust: () -> Unit,
    onForget: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    CipherCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(iconForDeviceType(device.deviceType)), contentDescription = null, tint = CipherShareColors.TextSecondary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        if (device.isTrusted) {
                            Spacer(Modifier.width(6.dp))
                            // Matches the trusted-device star badge on DeviceCardControl in the desktop app.
                            Icon(painterResource(R.drawable.ic_cs_star), contentDescription = "Trusted", tint = CipherShareColors.Warning, modifier = Modifier.width(14.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(colorForDeviceStatus(device.status), modifier = Modifier.padding(end = 6.dp))
                        Text(
                            "${statusLabel(device.status)} - ${device.ipAddress}${if (device.status != DeviceStatus.ONLINE) " - seen ${Formatters.formatRelativeTime(device.lastSeenUtcMillis)}" else ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        // Left as Material: desktop's DeviceCardControl uses explicit inline buttons
                        // instead of an overflow menu, so there's no "more/kebab" icon in the set.
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = CipherShareColors.TextSecondary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (device.isTrusted) "Remove trust" else "Trust device") },
                            // Left as Material: the desktop icon set only has one filled StarIcon, with
                            // no "unstarred/outline" counterpart, and desktop's own trust toggle is a
                            // plain text button rather than an icon.
                            leadingIcon = { Icon(if (device.isTrusted) Icons.Filled.StarBorder else Icons.Filled.Star, contentDescription = null) },
                            onClick = { menuOpen = false; onToggleTrust() }
                        )
                        DropdownMenuItem(
                            text = { Text("Forget device") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_cs_trash), contentDescription = null) },
                            onClick = { menuOpen = false; onForget() }
                        )
                    }
                }
            }

            Spacer(Modifier.padding(top = 6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSendFiles,
                    enabled = device.status != DeviceStatus.OFFLINE,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CipherShareColors.Accent)
                ) {
                    // Left as Material: desktop's "Send Files" is a text-only button with no icon,
                    // so there's no corresponding CipherShare geometry to reuse here.
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send files")
                }
                OutlinedButton(
                    onClick = onSendFolder,
                    enabled = device.status != DeviceStatus.OFFLINE,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CipherShareColors.TextSecondary)
                ) {
                    // Left as Material: desktop doesn't have a distinct "send folder" icon either -
                    // its folder picker is a text-only "Add Folder..." button inside the send dialog.
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send folder")
                }
                IconButton(
                    onClick = onSendClipboard,
                    enabled = device.status != DeviceStatus.OFFLINE
                ) {
                    // Same ClipboardIcon geometry as desktop's DeviceCardControl "send clipboard" button.
                    Icon(
                        painterResource(R.drawable.ic_cs_clipboard),
                        contentDescription = "Send clipboard content to this device",
                        tint = if (device.status != DeviceStatus.OFFLINE) CipherShareColors.TextSecondary else CipherShareColors.TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDevicesState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(painterResource(R.drawable.ic_cs_devices), contentDescription = null, tint = CipherShareColors.TextMuted, modifier = Modifier.width(48.dp))
        Spacer(Modifier.padding(top = 12.dp))
        Text("Looking for devices...", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(top = 6.dp))
        Text(
            "Open CipherShare on your desktop or another phone on the same Wi-Fi network to see it here.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// Mirrors DeviceTypeToIconConverter in the desktop app, including its fallback: unlike this
// screen's old Material mapping (which reused the desktop icon for both Desktop and Unknown),
// desktop actually ships a dedicated UnknownIcon, so we use that here too.
private fun iconForDeviceType(type: DeviceType): Int = when (type) {
    DeviceType.DESKTOP -> R.drawable.ic_cs_monitor
    DeviceType.LAPTOP -> R.drawable.ic_cs_laptop
    DeviceType.MOBILE -> R.drawable.ic_cs_smartphone
    DeviceType.UNKNOWN -> R.drawable.ic_cs_unknown
}

private fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.ONLINE -> "Online"
    DeviceStatus.IDLE -> "Idle"
    DeviceStatus.OFFLINE -> "Offline"
}
