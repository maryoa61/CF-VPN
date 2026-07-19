package com.example.cfvpn.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.cfvpn.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // نتیجه‌ی درخواست مجوز VpnService.prepare()
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.connectVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CfVpnApp(
                    viewModel = viewModel,
                    onRequestVpnPermission = { requestVpnPermissionThenConnect() }
                )
            }
        }
    }

    private fun requestVpnPermissionThenConnect() {
        val intent: Intent? = viewModel.vpnPermissionIntent()
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // مجوز از قبل داده شده
            viewModel.connectVpn()
        }
    }
}

@Composable
fun CfVpnApp(
    viewModel: MainViewModel,
    onRequestVpnPermission: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("خانه") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("تنظیمات") }
                )
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> HomeScreen(
                modifier = Modifier.padding(paddingValues),
                viewModel = viewModel,
                onConnectClicked = onRequestVpnPermission
            )
            1 -> SettingsScreen(
                modifier = Modifier.padding(paddingValues),
                viewModel = viewModel
            )
        }
    }
}
