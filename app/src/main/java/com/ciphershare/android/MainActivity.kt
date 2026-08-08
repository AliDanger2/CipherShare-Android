package com.ciphershare.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ciphershare.android.data.AppState
import com.ciphershare.android.service.CipherShareService
import com.ciphershare.android.ui.AppScaffold
import com.ciphershare.android.ui.LocalAppState
import com.ciphershare.android.ui.theme.CipherShareTheme

class MainActivity : ComponentActivity() {

    /** Tracked so onResume only restarts networking on an actual grant, not on every resume. */
    private var lastKnownNetworkPermissionState = false

    /**
     * All runtime permissions this app ever needs, requested together in ONE system call.
     *
     * Previously these were requested via three separate ActivityResultLauncher.launch() calls
     * fired within moments of each other at startup (two here in onCreate, plus a third,
     * redundant POST_NOTIFICATIONS request from a Compose LaunchedEffect in AppScaffold).
     * Android only shows one permission dialog at a time; issuing a second/third request
     * before the first one's result has come back gets silently dropped on most OEM builds
     * (Samsung/OneUI included) instead of queued. That's why only the notifications prompt was
     * ever showing up - the Nearby Wi-Fi devices (and, on older phones, location) requests were
     * simply never getting a dialog. RequestMultiplePermissions() bundles everything into a
     * single requestPermissions() call, so the system shows each dialog in its own turn
     * reliably instead of racing.
     */
    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // If Nearby Wi-Fi Devices (or, on pre-13 phones, location) just got granted,
            // discovery/transfer already started without it during initialize() - give
            // networking a fresh start now that it can actually work. Notifications are a
            // nicety either way, so no special handling needed for that result.
            val networkPermission = results[Manifest.permission.NEARBY_WIFI_DEVICES]
                ?: results[Manifest.permission.ACCESS_FINE_LOCATION]
                ?: results[Manifest.permission.ACCESS_COARSE_LOCATION]
            if (networkPermission == true) {
                AppState.getInstance(applicationContext).restartNetworking()
                // The permission dialog dismissing also triggers onResume() below, which would
                // otherwise see this same grant as "new" a second time (it only learns about
                // grants through its own before/after check) and fire a redundant second
                // restart right behind this one. Mark it as already handled. restartNetworking()
                // is safe to call more than once either way (it's mutex-serialized), but this
                // avoids two needless back-to-back socket rebinds on every first launch.
                lastKnownNetworkPermissionState = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()
        lastKnownNetworkPermissionState = hasNetworkDiscoveryPermission()
        startCipherShareService()

        val appState = AppState.getInstance(applicationContext)
        appState.initialize()

        setContent {
            CipherShareTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppState provides appState) {
                    AppScaffold()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where the user denied a permission at first launch, then granted it
        // later from Settings > Apps > CipherShare > Permissions instead of through our own
        // dialog. Android doesn't call back into permissionsLauncher for that path, and since
        // the app's process (and its foreground service) keeps running the whole time,
        // networking never gets a chance to restart on its own - so check on every resume and
        // give it a fresh start if something we need just became available.
        val nowGranted = hasNetworkDiscoveryPermission()
        if (nowGranted && !lastKnownNetworkPermissionState) {
            AppState.getInstance(applicationContext).restartNetworking()
        }
        lastKnownNetworkPermissionState = nowGranted
    }

    /**
     * A clipboard-sync transfer that arrives while this app has no window focus can't actually
     * write to the system clipboard yet - Android silently denies it (see
     * AppState.applyPendingClipboardIfAny's doc comment) - so TransferServer holds the bytes as
     * "pending" instead. onWindowFocusChanged(true) is the officially-recommended, precise
     * signal that focus is genuinely established (onResume can fire slightly before that), so
     * this is where the deferred apply actually happens - whether the user opened the app
     * directly or got here by tapping the "Transfer complete" notification.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            AppState.getInstance(applicationContext).applyPendingClipboardIfAny()
        }
    }

    /**
     * Everything this app can use, gated to what's actually relevant on the running OS
     * version so we never prompt for a permission that would be a no-op (or, on 13+, one the
     * manifest doesn't even declare past its maxSdkVersion).
     */
    private fun requestNeededPermissions() {
        val toRequest = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // NEARBY_WIFI_DEVICES doesn't exist before API 33; these are the pre-13
                // equivalent some OEM Wi-Fi stacks expect before they'll deliver broadcast/
                // multicast traffic to the app.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (toRequest.isNotEmpty()) {
            permissionsLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun hasNetworkDiscoveryPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCipherShareService() {
        val intent = Intent(this, CipherShareService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
