package com.ciphershare.android.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ciphershare.android.R
import com.ciphershare.android.model.SecurityLevel
import com.ciphershare.android.ui.LocalAppState
import com.ciphershare.android.ui.components.CipherCard
import com.ciphershare.android.ui.theme.CipherShareColors
import com.ciphershare.android.util.StorageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onPickDownloadFolder: () -> Unit) {
    val appState = LocalAppState.current
    val context = LocalContext.current
    val settings by appState.settings.collectAsState()

    var deviceNameField by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var portField by remember(settings.networkPort) { mutableStateOf(settings.networkPort.toString()) }
    var intervalField by remember(settings.broadcastIntervalSeconds) { mutableStateOf(settings.broadcastIntervalSeconds.toString()) }
    var maxTransfersField by remember(settings.maxSimultaneousTransfers) { mutableStateOf(settings.maxSimultaneousTransfers.toString()) }
    var bandwidthField by remember(settings.bandwidthLimitMBps) { mutableStateOf(if (settings.bandwidthLimitMBps == 0.0) "" else settings.bandwidthLimitMBps.toString()) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsGroup("General") {
                OutlinedTextField(
                    value = deviceNameField,
                    onValueChange = { deviceNameField = it },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { if (deviceNameField.isNotBlank()) appState.updateSettings { it.copy(deviceName = deviceNameField) } }) {
                    Text("Save name")
                }

                Spacer(Modifier.height(16.dp))
                Text("Download location", style = MaterialTheme.typography.bodyMedium)
                Text(StorageUtils.describeDownloadLocation(context, settings), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickDownloadFolder) { Text("Choose folder") }
                    if (settings.downloadTreeUri != null) {
                        OutlinedButton(onClick = { appState.updateSettings { it.copy(downloadTreeUri = null) } }) { Text("Reset to default") }
                    }
                }
            }
        }

        item {
            SettingsGroup("Network") {
                SwitchRow(
                    label = "Auto-discovery",
                    description = "Broadcast this device and look for others on the LAN",
                    checked = settings.autoDiscovery,
                    onCheckedChange = { appState.updateSettings { s -> s.copy(autoDiscovery = it) } }
                )
                Divider(color = CipherShareColors.Border, modifier = Modifier.padding(vertical = 12.dp))

                NumberFieldRow(
                    label = "Network port",
                    value = portField,
                    onValueChange = { portField = it },
                    onCommit = { portField.toIntOrNull()?.let { p -> appState.updateSettings { it.copy(networkPort = p.coerceIn(1024, 65535)) } } }
                )
                Text("Must match the desktop app's port (default 54321) to discover each other.", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))
                NumberFieldRow(
                    label = "Broadcast interval (seconds)",
                    value = intervalField,
                    onValueChange = { intervalField = it },
                    onCommit = { intervalField.toIntOrNull()?.let { v -> appState.updateSettings { it.copy(broadcastIntervalSeconds = v.coerceIn(3, 120)) } } }
                )

                Spacer(Modifier.height(12.dp))
                NumberFieldRow(
                    label = "Max simultaneous transfers",
                    value = maxTransfersField,
                    onValueChange = { maxTransfersField = it },
                    onCommit = { maxTransfersField.toIntOrNull()?.let { v -> appState.updateSettings { it.copy(maxSimultaneousTransfers = v.coerceIn(1, 20)) } } }
                )

                Spacer(Modifier.height(12.dp))
                NumberFieldRow(
                    label = "Bandwidth limit (MB/s, 0 = unlimited)",
                    value = bandwidthField,
                    onValueChange = { bandwidthField = it },
                    onCommit = { appState.updateSettings { it.copy(bandwidthLimitMBps = bandwidthField.toDoubleOrNull() ?: 0.0) } }
                )
            }
        }

        item {
            SettingsGroup("Security") {
                Text("Security level", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                SecurityLevelDropdown(
                    selected = settings.securityLevel,
                    onSelected = { level -> appState.updateSettings { it.copy(securityLevel = level) } }
                )
                Spacer(Modifier.height(16.dp))
                SwitchRow(
                    label = "Verify file integrity",
                    description = "Check each received file's SHA-256 hash against the sender's",
                    checked = settings.verifyIntegrity,
                    onCheckedChange = { appState.updateSettings { s -> s.copy(verifyIntegrity = it) } }
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Keep partial files on failure",
                    description = "Don't delete a file's .partial data if a transfer fails midway",
                    checked = settings.keepPartialFilesOnFailure,
                    onCheckedChange = { appState.updateSettings { s -> s.copy(keepPartialFilesOnFailure = it) } }
                )
            }
        }

        item {
            SettingsGroup("Notifications") {
                SwitchRow("New device found", null, settings.notifyDeviceDiscovered) { appState.updateSettings { s -> s.copy(notifyDeviceDiscovered = it) } }
                Spacer(Modifier.height(10.dp))
                SwitchRow("Incoming transfer", null, settings.notifyIncomingTransfer) { appState.updateSettings { s -> s.copy(notifyIncomingTransfer = it) } }
                Spacer(Modifier.height(10.dp))
                SwitchRow("Transfer complete", null, settings.notifyTransferComplete) { appState.updateSettings { s -> s.copy(notifyTransferComplete = it) } }
                Spacer(Modifier.height(10.dp))
                SwitchRow("Transfer failed", null, settings.notifyTransferFailed) { appState.updateSettings { s -> s.copy(notifyTransferFailed = it) } }
            }
        }

        item {
            SettingsGroup("About") {
                Text("CipherShare for Android", style = MaterialTheme.typography.bodyLarge)
                Text("A companion client for the CipherShare desktop app - same LAN discovery and transfer protocol.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        CipherCard(modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun SwitchRow(label: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = CipherShareColors.Background, checkedTrackColor = CipherShareColors.Accent)
        )
    }
}

@Composable
private fun NumberFieldRow(label: String, value: String, onValueChange: (String) -> Unit, onCommit: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = onCommit) { Text("Save") }
            }
        )
    }
}

@Composable
private fun SecurityLevelDropdown(selected: SecurityLevel, onSelected: (SecurityLevel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = SecurityLevel.entries

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(labelFor(selected))
                Icon(painterResource(R.drawable.ic_cs_chevron_down), contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.9f)) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(labelFor(option)) }, onClick = { onSelected(option); expanded = false })
            }
        }
    }
}

private fun labelFor(level: SecurityLevel): String = when (level) {
    SecurityLevel.REQUIRE_CONFIRMATION_FOR_ALL -> "Confirm every transfer"
    SecurityLevel.SKIP_CONFIRMATION_FOR_TRUSTED -> "Skip confirmation for trusted devices"
    SecurityLevel.NO_CONFIRMATION_REQUIRED -> "Never confirm (not recommended)"
}
