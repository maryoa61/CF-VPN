package com.example

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.VpnConfig
import com.example.service.XrayVpnService
import com.example.ui.VpnViewModel
import com.example.ui.theme.CFVPNTheme
import com.example.vpn.VpnStatus

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            // اگر این تابع را نداری می‌توانی حذفش کنی
            // viewModel.onVpnPermissionDenied()
            viewModel.simulator.log("VPN permission denied by user")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CFVPNTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onToggleVpn = { requestVpnPermissionAndConnect() },
                        onDisconnectVpn = { stopVpnService() }
                    )
                }
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val config = viewModel.selectedConfigFlow.value ?: run {
            viewModel.simulator.log("No selected config, cannot start VPN")
            return
        }

        // شما در سرویس، VpnConfig JSON می‌خواهی.
        // اگر این تابع را داری نگه دار، اگر نداری باید اضافه شود.
        val configJson = viewModel.configToJson(config)

        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG_JSON, configJson)
        }

        startForegroundService(intent)
        viewModel.simulator.log("Requested VPN START for ${config.name}")
    }

    private fun stopVpnService() {
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
        }
        startService(intent)
        viewModel.simulator.log("Requested VPN STOP")
    }
}

@Composable
fun MainScreen(
    viewModel: VpnViewModel,
    onToggleVpn: () -> Unit,
    onDisconnectVpn: () -> Unit
) {
    var currentScreen by remember { mutableStateOf("home") }

    when (currentScreen) {
        "home" -> HomeScreen(
            viewModel = viewModel,
            onToggleVpn = onToggleVpn,
            onDisconnectVpn = onDisconnectVpn,
            onNavigateToConfigs = { currentScreen = "configs" },
            onNavigateToSettings = { currentScreen = "settings" }
        )
        "settings" -> SettingsScreen(
            viewModel = viewModel,
            onBack = { currentScreen = "home" }
        )
        "configs" -> ConfigScreen(
            viewModel = viewModel,
            onNavigateToHome = { currentScreen = "home" }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    onToggleVpn: () -> Unit,
    onDisconnectVpn: () -> Unit,
    onNavigateToConfigs: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val status by viewModel.status.collectAsState(initial = VpnStatus.DISCONNECTED)
    val selectedConfig by viewModel.selectedConfigFlow.collectAsState(initial = null)

    // سرعت‌ها String هستند، دقیقا مثل VpnConnectionManager
    val downloadSpeed by viewModel.downloadSpeed.collectAsState(initial = "0.0 KB/s")
    val uploadSpeed by viewModel.uploadSpeed.collectAsState(initial = "0.0 KB/s")

    // لاگ‌ها هم اسمش logs است
    val logLines by viewModel.logs.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CF-VPN") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
               .fillMaxSize()
               .padding(padding)
               .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = selectedConfig?.name ?: "No config selected",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onNavigateToConfigs) {
                Text("Change config")
            }

            Spacer(modifier = Modifier.height(32.dp))

            val isConnected = status == VpnStatus.CONNECTED
            val isConnecting = status == VpnStatus.CONNECTING

            FilledIconButton(
                onClick = {
                    if (isConnected) onDisconnectVpn() else onToggleVpn()
                },
                enabled = !isConnecting && selectedConfig != null,
                modifier = Modifier.size(120.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = if (isConnected) "Disconnect" else "Connect",
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (status) {
                    VpnStatus.CONNECTED -> "Connected"
                    VpnStatus.CONNECTING -> "Connecting..."
                    VpnStatus.DISCONNECTED -> "Disconnected"
                },
                style = MaterialTheme.typography.bodyLarge
            )

            if (isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("⬇ $downloadSpeed")
                    Text("⬆ $uploadSpeed")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(modifier = Modifier.padding(12.dp)) {
                    items(logLines) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit
) {
    // اگر در ViewModel این‌ها را نداری، فعلا می‌توانی این صفحه را ساده‌تر کنی
    val edgeIps by viewModel.edgeIps.collectAsState(initial = "")
    var edgeIpsInput by remember(edgeIps) { mutableStateOf(edgeIps) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = edgeIpsInput,
                onValueChange = { edgeIpsInput = it },
                label = { Text("Edge IPs (comma-separated)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.setEdgeIps(edgeIpsInput) }) {
                Text("Save Edge IPs")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: VpnViewModel,
    onNavigateToHome: () -> Unit
) {
    val allConfigs by viewModel.allConfigs.collectAsState(initial = emptyList())
    val selectedConfig by viewModel.selectedConfigFlow.collectAsState(initial = null)

    var showAddMenu by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showBugReportDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<VpnConfig?>(null) }
    var inputLink by remember { mutableStateOf("") }
    var inputSubUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateToHome) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMenu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Import from link") },
                            onClick = { showAddMenu = false; inputLink = ""; showImportDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Import subscription") },
                            onClick = { showAddMenu = false; inputSubUrl = ""; showSubscriptionDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("New manual config") },
                            onClick = { showAddMenu = false; editingConfig = null; showEditDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Report a bug") },
                            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                            onClick = { showAddMenu = false; showBugReportDialog = true }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(allConfigs) { config ->
                Row(
                    modifier = Modifier
                       .fillMaxWidth()
                       .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(config.name, fontWeight = FontWeight.Bold)
                        Text("${config.type} · ${config.address}:${config.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.testPing(config) }) { Text("Ping") }
                    RadioButton(
                        selected = config.id == selectedConfig?.id,
                        onClick = { viewModel.selectConfig(config) }
                    )
                    IconButton(onClick = { editingConfig = config; showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { viewModel.deleteConfig(config) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
                Divider()
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import from link") },
            text = {
                OutlinedTextField(
                    value = inputLink,
                    onValueChange = { inputLink = it },
                    label = { Text("vless:// vmess:// trojan:// ss:// link") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFromLink(inputLink)
                    showImportDialog = false
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSubscriptionDialog) {
        AlertDialog(
            onDismissRequest = { showSubscriptionDialog = false },
            title = { Text("Import subscription") },
            text = {
                OutlinedTextField(
                    value = inputSubUrl,
                    onValueChange = { inputSubUrl = it },
                    label = { Text("Subscription URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFromSubscription(inputSubUrl)
                    showSubscriptionDialog = false
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showSubscriptionDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBugReportDialog) {
        var bugText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBugReportDialog = false },
            title = { Text("Report a bug") },
            text = {
                OutlinedTextField(
                    value = bugText,
                    onValueChange = { bugText = it },
                    label = { Text("Describe the issue") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.submitBugReport(bugText)
                    showBugReportDialog = false
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showBugReportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showEditDialog) {
        var name by remember { mutableStateOf(editingConfig?.name ?: "") }
        var address by remember { mutableStateOf(editingConfig?.address ?: "") }
        var port by remember { mutableStateOf(editingConfig?.port?.toString() ?: "443") }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(if (editingConfig == null) "New config" else "Edit config") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") })
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val portInt = port.toIntOrNull() ?: 443
                    val current = editingConfig
                    if (current == null) {
                        viewModel.insertConfig(name = name, address = address, port = portInt)
                    } else {
                        viewModel.updateConfig(current.copy(name = name, address = address, port = portInt))
                    }
                    showEditDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }
}
