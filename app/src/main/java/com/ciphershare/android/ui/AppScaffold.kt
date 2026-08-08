package com.ciphershare.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciphershare.android.R
import com.ciphershare.android.model.DeviceModel
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.ui.components.StatusDot
import com.ciphershare.android.ui.components.colorForDeviceStatus
import com.ciphershare.android.ui.dialogs.IncomingTransferDialog
import com.ciphershare.android.ui.screens.DevicesScreen
import com.ciphershare.android.ui.screens.HistoryScreen
import com.ciphershare.android.ui.screens.HomeScreen
import com.ciphershare.android.ui.screens.QueueScreen
import com.ciphershare.android.ui.screens.SettingsScreen
import com.ciphershare.android.ui.theme.CipherShareColors
import com.ciphershare.android.util.StorageUtils
import kotlinx.coroutines.launch

private enum class Screen(val title: String) {
    HOME("Home"),
    DEVICES("Devices"),
    QUEUE("Transfer Queue"),
    HISTORY("History"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold() {
    val appState = LocalAppState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var selectedScreen by remember { mutableStateOf(Screen.HOME) }

    val identity by appState.identity.collectAsState()
    val localIp by appState.localIp.collectAsState()
    val activeTransfers by appState.activeTransfers.collectAsState()
    val pendingRequests by appState.pendingRequests.collectAsState()

    var sendTarget by remember { mutableStateOf<DeviceModel?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = sendTarget
        if (target != null && uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // Some providers don't support persistable grants - the file can still be sent now,
                    // it just won't be reopenable from History once this permission ages out.
                }
            }
            appState.sendFiles(target, StorageUtils.expandPickedFiles(context, uris))
        }
        sendTarget = null
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val target = sendTarget
        if (target != null && treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            appState.sendFiles(target, StorageUtils.expandPickedTree(context, treeUri))
        }
        sendTarget = null
    }
    val downloadFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            appState.updateSettings { it.copy(downloadTreeUri = treeUri.toString()) }
        }
    }
    // Runtime permissions (notifications, nearby Wi-Fi devices, location) are all requested
    // together from MainActivity.onCreate, before this Composable is even first composed.
    // This used to also fire its own separate POST_NOTIFICATIONS request here via a
    // LaunchedEffect - which runs at essentially the same moment as MainActivity's requests
    // during startup. Two (or three) independent ActivityResultLauncher.launch() calls that
    // close together race each other: only one permission dialog can be shown/pending at a
    // time, so every request after the first either gets silently dropped or never shown at
    // all - which is exactly why only the notifications prompt was ever appearing. Requesting
    // permissions from a single place, once, is what actually fixes that.

    fun startSendFiles(device: DeviceModel) {
        sendTarget = device
        filePickerLauncher.launch(arrayOf("*/*"))
    }
    fun startSendFolder(device: DeviceModel) {
        sendTarget = device
        folderPickerLauncher.launch(null)
    }

    // Incoming-transfer confirmation dialog - shown on top of whatever screen is visible.
    pendingRequests.firstOrNull()?.let { request ->
        IncomingTransferDialog(
            request = request,
            onAccept = { appState.respondToIncomingRequest(request.id, true) },
            onDecline = { appState.respondToIncomingRequest(request.id, false) }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = CipherShareColors.Surface,
                drawerContentColor = CipherShareColors.TextPrimary
            ) {
                NavDrawerContent(
                    selected = selectedScreen,
                    activeTransferCount = activeTransfers.size,
                    deviceName = identity?.deviceName ?: "This device",
                    localIp = localIp,
                    onSelect = {
                        selectedScreen = it
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = CipherShareColors.Background,
            topBar = {
                TopAppBar(
                    title = { Text(selectedScreen.title, color = CipherShareColors.TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            // Left as Material: desktop has no collapsible-drawer/hamburger concept
                            // (its sidebar is always visible), so there's no CipherShare icon for this.
                            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = CipherShareColors.TextPrimary)
                        }
                    },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = CipherShareColors.Background,
                        titleContentColor = CipherShareColors.TextPrimary
                    )
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).background(CipherShareColors.Background)) {
                when (selectedScreen) {
                    Screen.HOME -> HomeScreen(
                        onSendFilesTo = ::startSendFiles,
                        onGoToDevices = { selectedScreen = Screen.DEVICES }
                    )
                    Screen.DEVICES -> DevicesScreen(
                        onSendFiles = ::startSendFiles,
                        onSendFolder = ::startSendFolder,
                        onSendClipboard = { appState.sendClipboard(it) }
                    )
                    Screen.QUEUE -> QueueScreen()
                    Screen.HISTORY -> HistoryScreen()
                    Screen.SETTINGS -> SettingsScreen(onPickDownloadFolder = { downloadFolderLauncher.launch(null) })
                }
            }
        }
    }
}

@Composable
private fun NavDrawerContent(
    selected: Screen,
    activeTransferCount: Int,
    deviceName: String,
    localIp: String?,
    onSelect: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(CipherShareColors.Surface)
            .padding(vertical = 12.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Left as Material: this is a standalone brand mark next to the app name. Desktop's
            // title bar has no icon here at all (just a colored dot + text), so there's no
            // corresponding CipherShare geometry to reuse.
            Icon(Icons.Filled.SwapVert, contentDescription = null, tint = CipherShareColors.Accent)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(8.dp))

        DrawerItem(painterResource(R.drawable.ic_cs_home), "Home", selected == Screen.HOME) { onSelect(Screen.HOME) }
        DrawerItem(painterResource(R.drawable.ic_cs_devices), "Devices", selected == Screen.DEVICES) { onSelect(Screen.DEVICES) }
        DrawerItem(painterResource(R.drawable.ic_cs_queue), "Transfer Queue", selected == Screen.QUEUE, badge = activeTransferCount.takeIf { it > 0 }) { onSelect(Screen.QUEUE) }
        DrawerItem(painterResource(R.drawable.ic_cs_history), "History", selected == Screen.HISTORY) { onSelect(Screen.HISTORY) }
        DrawerItem(painterResource(R.drawable.ic_cs_settings), "Settings", selected == Screen.SETTINGS) { onSelect(Screen.SETTINGS) }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .background(CipherShareColors.SurfaceHover, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_cs_smartphone), contentDescription = null, tint = CipherShareColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(deviceName, style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(colorForDeviceStatus(DeviceStatus.ONLINE), modifier = Modifier.padding(end = 5.dp))
                    Text(localIp ?: "Finding IP address...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(icon: Painter, label: String, isSelected: Boolean, badge: Int? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(if (isSelected) CipherShareColors.Accent.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (isSelected) CipherShareColors.Accent else CipherShareColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            color = if (isSelected) CipherShareColors.TextPrimary else CipherShareColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Row(
                modifier = Modifier
                    .background(CipherShareColors.Accent, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(badge.toString(), color = CipherShareColors.Background, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
