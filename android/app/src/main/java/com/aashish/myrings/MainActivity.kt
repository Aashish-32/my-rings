package com.aashish.myrings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aashish.myrings.service.RingService
import com.aashish.myrings.ui.AppViewModel
import com.aashish.myrings.ui.MyRingsApp
import com.aashish.myrings.ui.MyRingsTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

    private fun hasBluetooth() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onResume() {
        super.onResume()
        if (hasBluetooth()) vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // rotating the screen also stops the activity; only a real trip to the background should sync
        if (!isChangingConfigurations && hasBluetooth()) vm.onBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyRingsTheme {
                var granted by remember { mutableStateOf(hasBluetooth()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasBluetooth() }
                LaunchedEffect(granted) { if (granted) RingService.start(this@MainActivity) }
                LaunchedEffect(Unit) { if (!granted) launcher.launch(needed) }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (granted) MyRingsApp(vm) else PermissionScreen { launcher.launch(needed) }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Bluetooth access needed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("My Rings talks to your ring over Bluetooth. Nothing is sent to the internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Allow") }
    }
}
