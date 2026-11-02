package com.tamilarasu.watch2iphone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tamilarasu.watch2iphone.service.AncsService

class MainActivity : ComponentActivity() {

    private var missing by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            results.forEach { (permission, granted) ->
                BleLog.perm("result ${AppPermissions.label(permission)} granted=$granted")
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleLog.perm("onCreate")
        refresh()
        setContent {
            MaterialTheme {
                if (missing.isNotEmpty()) {
                    PermissionScreen(
                        missing = missing,
                        onRequest = {
                            BleLog.perm("requesting ${missing.map(AppPermissions::label)}")
                            permissionLauncher.launch(missing.toTypedArray())
                        },
                    )
                } else {
                    MainScreen(send = ::sendToService)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun sendToService(action: String, address: String? = null) {
        val intent = AncsService.intent(this, action, address)
        if (action == AncsService.ACTION_STOP && AncsService.controller == null) return
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refresh() {
        val now = AppPermissions.missing(this)
        if (now != missing) {
            BleLog.perm("missing ${missing.map(AppPermissions::label)} -> ${now.map(AppPermissions::label)}")
        }
        missing = now
    }
}

@Composable
private fun PermissionScreen(missing: List<String>, onRequest: () -> Unit) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                item { ListHeader { Text("Permissions") } }
                AppPermissions.required.forEach { permission ->
                    item {
                        val state = if (permission in missing) "needed" else "granted"
                        Text("${AppPermissions.label(permission)}: $state")
                    }
                }
                item { Button(onClick = onRequest, label = { Text("Grant") }) }
            }
        }
    }
}

private enum class Screen { HOME, SCAN }

@Composable
private fun MainScreen(send: (String, String?) -> Unit) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val ui by AncsStatus.ui.collectAsState()
    val scan by AncsStatus.scanResults.collectAsState()

    BackHandler(enabled = screen == Screen.SCAN) { screen = Screen.HOME }

    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                when (screen) {
                    Screen.HOME -> {
                        item { ListHeader { Text("Watch2iPhone") } }
                        item { Text(ui.phase.name) }
                        if (ui.detail.isNotBlank()) item { Text(ui.detail) }
                        ui.deviceName?.let { item { Text("Device: $it") } }
                        item {
                            Button(
                                onClick = {
                                    send(AncsService.ACTION_START_SCAN, null)
                                    screen = Screen.SCAN
                                },
                                label = { Text("Scan for iPhone") },
                            )
                        }
                        item {
                            Button(
                                onClick = { send(AncsService.ACTION_START_ADVERTISE, null) },
                                label = { Text("Advertise") },
                                secondaryLabel = { Text("iPhone connects to watch") },
                            )
                        }
                        ui.savedDevice?.let { saved ->
                            item {
                                Button(
                                    onClick = { send(AncsService.ACTION_RESUME, null) },
                                    label = { Text("Reconnect") },
                                    secondaryLabel = { Text(saved) },
                                )
                            }
                            item {
                                Button(
                                    onClick = { send(AncsService.ACTION_FORGET, null) },
                                    label = { Text("Forget saved device") },
                                )
                            }
                        }
                        item { Button(onClick = { send(AncsService.ACTION_STOP, null) }, label = { Text("Stop") }) }
                    }
                    Screen.SCAN -> {
                        item { ListHeader { Text("Pick your iPhone") } }
                        item { Text("${ui.phase.name}: ${ui.detail}") }
                        if (scan.isEmpty()) item { Text("No devices yet…") }
                        scan.forEach { found ->
                            item {
                                Button(
                                    onClick = {
                                        send(AncsService.ACTION_CONNECT, found.address)
                                        screen = Screen.HOME
                                    },
                                    label = { Text(found.name ?: "(unnamed)") },
                                    secondaryLabel = { Text(found.detail) },
                                )
                            }
                        }
                        item {
                            Button(
                                onClick = { send(AncsService.ACTION_START_SCAN, null) },
                                label = { Text("Scan again") },
                            )
                        }
                        item { Button(onClick = { screen = Screen.HOME }, label = { Text("Back") }) }
                    }
                }
            }
        }
    }
}
