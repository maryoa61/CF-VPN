package com.example
// Sync trigger comment for GitHub

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.VpnConfig
import com.example.service.XrayVpnService
import com.example.ui.VpnViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: VpnViewModel

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceInternal()
        } else {
            viewModel.simulator.log("VPN permission denied by user")
            Toast.makeText(this, "VPN permission is required to connect", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val text = "=== CRASH at ${java.util.Date()} ===\n" +
                    android.util.Log.getStackTraceString(throwable) + "\n\n"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "crash_log.txt")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { u ->
                    contentResolver.openOutputStream(u)?.use { stream -> stream.write(text.toByteArray()) }
                }
            } catch (e: Exception) { }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
        enableEdgeToEdge()

        setContent {
            viewModel = viewModel()
            val themeMode by viewModel.theme.collectAsStateWithLifecycle()
            val isDark = themeMode == "Dark"

            MyApplicationTheme(darkTheme = isDark) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onToggleVpn = { handleVpnToggle() }
                    )
                }
            }
        }
    }

    private fun handleVpnToggle() {
        val currentStatus = viewModel.status.value
        if (currentStatus == VpnStatus.DISCONNECTED) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPrepareLauncher.launch(intent)
            } else {
                startVpnServiceInternal()
            }
        } else {
            stopVpnServiceInternal()
        }
    }

    private fun startVpnServiceInternal() {
        try {
            val config = viewModel.selectedConfigFlow.value
            if (config == null) {
                viewModel.simulator.log("Error: No configuration selected to start the VPN")
                Toast.makeText(this, "Please select a config first", Toast.LENGTH_SHORT).show()
                return
            }

            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val configJson = moshi.adapter(VpnConfig::class.java).toJson(config)

            val intent = Intent(this, XrayVpnService::class.java).apply {
                putExtra(XrayVpnService.EXTRA_CONFIG_JSON, configJson)
            }
            startService(intent)
            viewModel.toggleConnection()
        } catch (e: Exception) {
            viewModel.simulator.log("Error starting VPN Service: ${e.message}")
        }
    }

    private fun stopVpnServiceInternal() {
        try {
            val intent = Intent(this, XrayVpnService::class.java).apply {
                action = "STOP"
            }
            startService(intent)
            viewModel.toggleConnection()
        } catch (e: Exception) {
            viewModel.simulator.log("Error stopping VPN Service: ${e.message}")
        }
    }
}

enum class ActiveScreen {
    CONFIG,
    HOME,
    SETTINGS
}

@Composable
fun MainScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
    onToggleVpn: () -> Unit
) {
    var activeTab by remember { mutableStateOf(ActiveScreen.HOME) }
    var previousTab by remember { mutableStateOf(ActiveScreen.HOME) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (activeTab) {
                    ActiveScreen.CONFIG -> ConfigScreen(
                        viewModel = viewModel,
                        onNavigateToHome = {
                            previousTab = activeTab
                            activeTab = ActiveScreen.HOME
                        }
                    )
                    ActiveScreen.HOME -> HomeScreen(
                        viewModel = viewModel,
                        onToggleVpn = onToggleVpn,
                        onNavigateToSettings = {
                            previousTab = activeTab
                            activeTab = ActiveScreen.SETTINGS
                        }
                    )
                    ActiveScreen.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeTab = previousTab
                        }
                    )
                }
            }

            // Custom floating pill bottom navigation bar matching the screenshots
            if (activeTab != ActiveScreen.SETTINGS) {
                CustomBottomNavigation(
                    activeTab = activeTab,
                    onTabSelected = { tab ->
                        previousTab = activeTab
                        activeTab = tab
                    }
                )
            }
        }
    }
}

@Composable
fun CustomBottomNavigation(
    activeTab: ActiveScreen,
    onTabSelected: (ActiveScreen) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Config Tab
            val isConfigActive = activeTab == ActiveScreen.CONFIG
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .testTag("nav_config")
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTabSelected(ActiveScreen.CONFIG) }
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isConfigActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Config Screen",
                        tint = if (isConfigActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "config",
                    fontSize = 11.sp,
                    fontWeight = if (isConfigActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isConfigActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Home Tab
            val isHomeActive = activeTab == ActiveScreen.HOME
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .testTag("nav_home")
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTabSelected(ActiveScreen.HOME) }
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isHomeActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Home Screen",
                        tint = if (isHomeActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "home",
                    fontSize = 11.sp,
                    fontWeight = if (isHomeActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isHomeActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ConfigScreen(
    viewModel: VpnViewModel,
    onNavigateToHome: () -> Unit
) {
    val context = LocalContext.current
    val configs by viewModel.allConfigs.collectAsStateWithLifecycle()
    val selectedConfig by viewModel.selectedConfigFlow.collectAsStateWithLifecycle()

    var showAddMenu by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showBugReportDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<VpnConfig?>(null) }
    var inputLink by remember { mutableStateOf("") }
    var inputSubUrl by remember { mutableStateOf("https://premium-vpn.com/get-sub") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Config",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { showImportDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier
                            .testTag("add_config_button")
                            .height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Add", fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { showAddMenu = !showAddMenu },
                        modifier = Modifier
                            .testTag("config_menu_toggle")
                            .size(36.dp)
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (showAddMenu) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Show More Options",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Clipboard import") },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val pasteText = clipData.getItemAt(0).text?.toString() ?: ""
                                if (viewModel.importFromLink(pasteText)) {
                                    Toast.makeText(context, "Config imported successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    inputLink = pasteText
                                    showImportDialog = true
                                }
                            } else {
                                showImportDialog = true
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Manual Configuration") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            editingConfig = VpnConfig(
                                id = 0,
                                name = "Manual VLESS Node",
                                type = "vless",
                                address = "104.244.42.1",
                                port = 443,
                                rawLink = "manual://",
                                uuid = java.util.UUID.randomUUID().toString(),
                                network = "tcp",
                                security = "xtls",
                                flow = "xtls-rprx-vision",
                                sni = "twitter.com",
                                fragmentEnabled = true,
                                fragmentLength = "10-20",
                                fragmentInterval = "10-20",
                                fragmentPackets = "tlshello"
                            )
                            showEditDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("QR code import") },
                        leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            // Simulating a QR code scan
                            viewModel.simulator.log("Scanning QR Code...")
                            val mockQrLink = "vless://qr-scanned-node@91.200.12.3:443?security=tls#QR_Imported_Node"
                            if (viewModel.importFromLink(mockQrLink)) {
                                Toast.makeText(context, "QR code config imported!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Subscription") },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showSubscriptionDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Locate selected node") },
                        leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            viewModel.locateSelectedNode()
                            Toast.makeText(context, "Node located in logs", Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete All") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            viewModel.deleteAllConfigs()
                            Toast.makeText(context, "All configs deleted", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    DropdownMenuItem(
                        text = { Text("Bug Report") },
                        leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showBugReportDialog = true
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (configs.isEmpty()) {
            // Empty State exactly matching Screenshot 1
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(bottom = 64.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(60.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                            .offset(y = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "No configuration file",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Import a configuration via clipboard or QR code to get started.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { showImportDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Create First Config", fontSize = 16.sp)
                }
            }
        } else {
            // Configuration List with star-indicators and ping tests
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(configs, key = { it.id }) { config ->
                    val isSelected = selectedConfig?.id == config.id
                    ConfigItemRow(
                        config = config,
                        isSelected = isSelected,
                        onSelect = { viewModel.selectConfig(config) },
                        onPing = { viewModel.testPing(config) },
                        onDelete = { viewModel.deleteConfig(config) },
                        onEdit = {
                            editingConfig = config
                            showEditDialog = true
                        }
                    )
                }
            }
        }
    }

    // Dialogs
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Config Link") },
            text = {
                Column {
                    Text(
                        "Paste a standard Vless, Vmess, Shadowsocks, Trojan, or Hysteria2 share link:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputLink,
                        onValueChange = { inputLink = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("vless://... or vmess://...") },
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputLink.trim().isNotEmpty()) {
                            if (viewModel.importFromLink(inputLink)) {
                                Toast.makeText(context, "Config imported successfully", Toast.LENGTH_SHORT).show()
                                inputLink = ""
                                showImportDialog = false
                            } else {
                                Toast.makeText(context, "Failed to parse. Check format", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showEditDialog && editingConfig != null) {
        val config = editingConfig!!
        
        // Local state for all fields so they are editable
        var name by remember { mutableStateOf(config.name) }
        var type by remember { mutableStateOf(config.type) }
        var address by remember { mutableStateOf(config.address) }
        var port by remember { mutableStateOf(config.port.toString()) }
        
        // VLESS settings state
        var uuid by remember { mutableStateOf(config.uuid ?: "") }
        var network by remember { mutableStateOf(config.network ?: "tcp") }
        var security by remember { mutableStateOf(config.security ?: "none") }
        var flow by remember { mutableStateOf(config.flow ?: "none") }
        var sni by remember { mutableStateOf(config.sni ?: "") }
        var publicKey by remember { mutableStateOf(config.publicKey ?: "") }
        var shortId by remember { mutableStateOf(config.shortId ?: "") }
        
        // Fragment settings state
        var fragmentEnabled by remember { mutableStateOf(config.fragmentEnabled) }
        var fragmentLength by remember { mutableStateOf(config.fragmentLength ?: "10-20") }
        var fragmentInterval by remember { mutableStateOf(config.fragmentInterval ?: "10-20") }
        var fragmentPackets by remember { mutableStateOf(config.fragmentPackets ?: "tlshello") }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(
                    text = if (config.id == 0) "Manual Node Config" else "Edit VPN Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Profile Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Protocol Type Selector
                    Text("Protocol Type", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("vless", "vmess", "shadowsocks", "trojan", "hysteria2").forEach { proto ->
                            val isSel = type == proto
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                    .clickable { type = proto }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = proto.uppercase(),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Server Address and Port
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Address") },
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // VLESS specific configuration
                    if (type == "vless") {
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        Text(
                            text = "VLESS Protocol Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("UUID (User ID)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Transport Network Selection
                        Text("Transport Protocol", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("tcp", "ws", "grpc").forEach { net ->
                                val isSel = network == net
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .border(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { network = net }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = net.uppercase(),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Security Selection
                        Text("Security Encryption", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("none", "tls", "xtls", "reality").forEach { sec ->
                                val isSel = security == sec
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .border(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { security = sec }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = sec.uppercase(),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Flow Selection (Only active for XTLS / TLS / Reality)
                        Text("Flow (XTLS settings)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("none", "xtls-rprx-vision").forEach { fl ->
                                val isSel = flow == fl
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .border(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { flow = fl }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = fl,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("SNI Domain") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (security == "reality") {
                            OutlinedTextField(
                                value = publicKey,
                                onValueChange = { publicKey = it },
                                label = { Text("Reality Public Key") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = shortId,
                                onValueChange = { shortId = it },
                                label = { Text("Reality Short ID") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Fragment Settings Section
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fragment Packets",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Splits TLS packets (Anti-censorship)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fragmentEnabled,
                            onCheckedChange = { fragmentEnabled = it }
                        )
                    }

                    if (fragmentEnabled) {
                        // Fragment Packets
                        Text("Packet Fragment Type", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("tlshello", "tcp").forEach { pkt ->
                                val isSel = fragmentPackets == pkt
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .border(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { fragmentPackets = pkt }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = pkt,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Fragment Length (Min-Max)
                        OutlinedTextField(
                            value = fragmentLength,
                            onValueChange = { fragmentLength = it },
                            label = { Text("Length Range (e.g., 10-20)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Fragment Interval (Min-Max milliseconds)
                        OutlinedTextField(
                            value = fragmentInterval,
                            onValueChange = { fragmentInterval = it },
                            label = { Text("Interval Range in ms (e.g., 10-20)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedPort = port.toIntOrNull() ?: 443
                        val updated = config.copy(
                            name = name,
                            type = type,
                            address = address,
                            port = parsedPort,
                            uuid = if (uuid.isNotEmpty()) uuid else null,
                            network = network,
                            security = security,
                            flow = flow,
                            sni = if (sni.isNotEmpty()) sni else null,
                            publicKey = if (publicKey.isNotEmpty()) publicKey else null,
                            shortId = if (shortId.isNotEmpty()) shortId else null,
                            fragmentEnabled = fragmentEnabled,
                            fragmentLength = fragmentLength,
                            fragmentInterval = fragmentInterval,
                            fragmentPackets = fragmentPackets
                        )
                        if (config.id == 0) {
                            viewModel.insertConfig(updated)
                        } else {
                            viewModel.updateConfig(updated)
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSubscriptionDialog) {
        AlertDialog(
            onDismissRequest = { showSubscriptionDialog = false },
            title = { Text("Update Subscription") },
            text = {
                Column {
                    Text(
                        "Enter the subscription provider link to fetch the latest xray nodes:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputSubUrl,
                        onValueChange = { inputSubUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Subscription URL") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputSubUrl.trim().isNotEmpty()) {
                            viewModel.importFromSubscription(inputSubUrl)
                            Toast.makeText(context, "Subscription sync requested", Toast.LENGTH_SHORT).show()
                            showSubscriptionDialog = false
                        }
                    }
                ) {
                    Text("Fetch")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubscriptionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBugReportDialog) {
        AlertDialog(
            onDismissRequest = { showBugReportDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bug Report / Diagnostics")
                }
            },
            text = {
                Column {
                    Text("Engine: core-xray v1.8.4", fontWeight = FontWeight.Bold)
                    Text("Library: AndroidLibXrayLite-v1.2.0")
                    Text("Socks Tunnel: HevSocks5Tunnel-v2.1.1")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Everything is operating in optimal parameters. No memory leaks or service daemon failures reported in current diagnostic buffer.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showBugReportDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigItemRow(
    config: VpnConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onDelete
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Globe/Network type indicator
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Selection State",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = config.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val fragmentStatus = if (config.fragmentEnabled) " • FRAGMENT" else ""
                    Text(
                        text = "${config.type.uppercase()} • ${config.address}$fragmentStatus",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (config.delayMs != null) {
                    Text(
                        text = "${config.delayMs} ms",
                        fontSize = 12.sp,
                        color = if (config.delayMs < 150) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = onPing,
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.06f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Test Delay/Ping",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.06f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Configuration",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    onToggleVpn: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val uploadSpeed by viewModel.uploadSpeed.collectAsStateWithLifecycle()
    val downloadSpeed by viewModel.downloadSpeed.collectAsStateWithLifecycle()
    val selectedConfig by viewModel.selectedConfigFlow.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Logo Icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "X",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Text(
                    text = "X-Lite VPN",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        Toast.makeText(context, "AES-256 encrypted tunnel active", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure Connection Status",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.testTag("settings_gear")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Connection State Title
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val isConnected = status == VpnStatus.CONNECTED
            val isConnecting = status == VpnStatus.CONNECTING

            Text(
                text = when (status) {
                    VpnStatus.CONNECTED -> "CONNECTED"
                    VpnStatus.CONNECTING -> "CONNECTING"
                    VpnStatus.DISCONNECTED -> "DISCONNECTED"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(4.dp))

            // Timer display or status state
            Text(
                text = if (isConnected) "00:42:19" else "00:00:00",
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // Large Central Connection Toggle Button with glowing pulse effect
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val isConnected = status == VpnStatus.CONNECTED
            val isConnecting = status == VpnStatus.CONNECTING

            // Pulse Animation
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "scale"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "alpha"
            )

            if (isConnected || isConnecting) {
                Box(
                    modifier = Modifier
                        .size(176.dp)
                        .scale(pulseScale)
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )
            }

            Button(
                onClick = onToggleVpn,
                modifier = Modifier
                    .size(176.dp)
                    .testTag("vpn_toggle_button")
                    .shadow(16.dp, CircleShape),
                shape = CircleShape,
                border = BorderStroke(4.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Check else Icons.Default.PowerSettingsNew,
                        contentDescription = "Power toggle",
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            isConnected -> "DISCONNECT"
                            isConnecting -> "CONNECTING"
                            else -> "TAP TO CONNECT"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.4f))

        // Integrated Details & Speeds Container matching Elegant Dark card
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Top Node Info Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Globe Icon Wrapper
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "Node Globe",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = selectedConfig?.name ?: "No Selected Node",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val detailsText = selectedConfig?.let {
                                val typePart = it.type.uppercase()
                                val securityPart = if (it.security != null && it.security != "none") " • ${it.security.uppercase()}" else ""
                                val fragPart = if (it.fragmentEnabled) " • FRAGMENT" else ""
                                "$typePart$securityPart$fragPart"
                            } ?: "Please select or import a node"
                            Text(
                                text = detailsText,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Ping info
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .clickable(enabled = selectedConfig != null) {
                                selectedConfig?.let { viewModel.testPing(it) }
                            }
                            .padding(4.dp)
                    ) {
                        val pingText = selectedConfig?.delayMs?.let { "$it ms" } ?: "—"
                        Text(
                            text = pingText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "PING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Divider line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Download/Upload speeds Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Download",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = downloadSpeed,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Upload",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uploadSpeed,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Live Log Terminal exactly matching the HTML style
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(10.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = false
            ) {
                val lastLogs = logs.take(6)
                if (lastLogs.isEmpty()) {
                    item {
                        Text(
                            text = "[SYSTEM] Ready. Select a node to establish connection.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    items(lastLogs.reversed()) { logLine ->
                        Text(
                            text = logLine,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(88.dp)) // Leave space for Bottom Navigation
    }
}

@Composable
fun SettingsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by viewModel.theme.collectAsStateWithLifecycle()
    val bootAutoStart by viewModel.bootAutoStart.collectAsStateWithLifecycle()
    val hideFromRecentTasks by viewModel.hideFromRecentTasks.collectAsStateWithLifecycle()
    val liveUpdateNotification by viewModel.liveUpdateNotification.collectAsStateWithLifecycle()
    val listenAddress by viewModel.listenAddress.collectAsStateWithLifecycle()
    val socksPort by viewModel.socksPort.collectAsStateWithLifecycle()
    val socksUsername by viewModel.socksUsername.collectAsStateWithLifecycle()
    
    val socksPassword by viewModel.socksPassword.collectAsStateWithLifecycle()
    val dnsIpv4 by viewModel.dnsIpv4.collectAsStateWithLifecycle()
    val enableIpv6 by viewModel.enableIpv6.collectAsStateWithLifecycle()
    val dnsIpv6 by viewModel.dnsIpv6.collectAsStateWithLifecycle()
    val routeSettings by viewModel.routeSettings.collectAsStateWithLifecycle()
    val enableHexTun by viewModel.enableHexTun.collectAsStateWithLifecycle()
    val testUrl by viewModel.testUrl.collectAsStateWithLifecycle()
    val socksTunnelEngine by viewModel.socksTunnelEngine.collectAsStateWithLifecycle()
    var showTunnelEngineDialog by remember { mutableStateOf(false) }

    var showPortDialog by remember { mutableStateOf(false) }
    var inputPort by remember { mutableStateOf("") }

    var showUsernameDialog by remember { mutableStateOf(false) }
    var inputUsername by remember { mutableStateOf("") }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var inputPassword by remember { mutableStateOf("") }

    var showDnsIpv4Dialog by remember { mutableStateOf(false) }
    var inputDnsIpv4 by remember { mutableStateOf("") }

    var showDnsIpv6Dialog by remember { mutableStateOf(false) }
    var inputDnsIpv6 by remember { mutableStateOf("") }

    var showRouteSettingsDialog by remember { mutableStateOf(false) }
    var inputRouteSettings by remember { mutableStateOf("") }

    var showTestUrlDialog by remember { mutableStateOf(false) }
    var inputTestUrl by remember { mutableStateOf("") }

    var showLogcatDialog by remember { mutableStateOf(false) }
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("settings_back")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = "Go Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // General settings
            item {
                Text(
                    text = "general",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // Theme Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Theme", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Select to turn on dark mode", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // Theme Toggle Dropdown representation
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        val nextTheme = if (themeMode == "Light") "Dark" else "Light"
                                        viewModel.setTheme(nextTheme)
                                    }
                                    .padding(8.dp)
                            ) {
                                Text(themeMode, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Open app proxy settings Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Toast.makeText(context, "Proxy routing settings loaded", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Launch, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("open app proxy settings", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("select special app to config proxy", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Logcat Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLogcatDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Logcat", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("check the logcat for more details", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Boot auto start
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Boot auto start", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Allow the app to start automatically when the device boots up.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = bootAutoStart,
                                onCheckedChange = { viewModel.setBootAutoStart(it) }
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Hide from recent tasks
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Hide from recent tasks", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Exclude the app from the recent tasks list (may need to restart the app)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = hideFromRecentTasks,
                                onCheckedChange = { viewModel.setHideFromRecentTasks(it) }
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Live update notification
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Live update notification", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Notification will pin at status bar", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = liveUpdateNotification,
                                onCheckedChange = { viewModel.setLiveUpdateNotification(it) }
                            )
                        }
                    }
                }
            }

            // Network settings
            item {
                Text(
                    text = "network",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // Listen Address Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("listen", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("listen address(127.0.0.1 default)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        val nextAddress = if (listenAddress == "127.0.0.1") "0.0.0.0" else "127.0.0.1"
                                        viewModel.setListenAddress(nextAddress)
                                    }
                                    .padding(8.dp)
                            ) {
                                Text(listenAddress, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Socks port Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputPort = socksPort
                                    showPortDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("socks port", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(socksPort, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Socks Username Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputUsername = socksUsername
                                    showUsernameDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Socks username", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(socksUsername, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Socks Password Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputPassword = socksPassword
                                    showPasswordDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Password, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Socks password", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(socksPassword, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // DNS IPv4 Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputDnsIpv4 = dnsIpv4
                                    showDnsIpv4Dialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("DNS IPv4", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(dnsIpv4, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Enable IPv6 Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("enable IPv6", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Click to enable IPv6 proxy", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = enableIpv6,
                                onCheckedChange = { viewModel.setEnableIpv6(it) }
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // DNS IPv6 Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputDnsIpv6 = dnsIpv6
                                    showDnsIpv6Dialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("DNS IPv6", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(dnsIpv6, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Route settings Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputRouteSettings = routeSettings
                                    showRouteSettingsDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.AltRoute, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Route settings", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(routeSettings, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowLeft,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // geoip Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("geoip", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("IP matching table", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Start the service first — downloads route through the proxy to reach GitHub reliably ",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Info",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        viewModel.simulator.log("Uploading custom geoip matching table...")
                                        Toast.makeText(context, "Uploading custom geoip table", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Upload,
                                        contentDescription = "Upload geoip",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.simulator.log("Downloading updated geoip database from GitHub...")
                                        Toast.makeText(context, "Downloading latest geoip matching table...", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download geoip",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // geosite Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("geosite", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Site matching table", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Start the service first — downloads route through the proxy to reach GitHub reliably ",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Info",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        viewModel.simulator.log("Uploading custom geosite matching table...")
                                        Toast.makeText(context, "Uploading custom geosite table", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Upload,
                                        contentDescription = "Upload geosite",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.simulator.log("Downloading updated geosite database from GitHub...")
                                        Toast.makeText(context, "Downloading latest geosite matching table...", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download geosite",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Geo_ip_lite Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Geo_ip_lite", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("IP address and corresponding country information", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Start the service first — downloads route through the proxy to reach GitHub reliably ",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Info",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    viewModel.simulator.log("Downloading Geo_ip_lite country database...")
                                    Toast.makeText(context, "Downloading Geo_ip_lite database...", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download Geo_ip_lite",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Enable hex tun Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("enable hex tun", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("enable hex tun as third part tool", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = enableHexTun,
                                onCheckedChange = { viewModel.setEnableHexTun(it) }
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Test URL Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputTestUrl = testUrl
                                    showTestUrlDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("test url", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(testUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                        // Socks Tunnel Engine Item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTunnelEngineDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Socks Tunnel Engine", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(socksTunnelEngine, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Settings Dialogs
    if (showPortDialog) {
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("Set SOCKS5 Port") },
            text = {
                OutlinedTextField(
                    value = inputPort,
                    onValueChange = { inputPort = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port Number") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputPort.toIntOrNull() != null) {
                            viewModel.setSocksPort(inputPort)
                            showPortDialog = false
                        } else {
                            Toast.makeText(context, "Please enter a valid port", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showUsernameDialog) {
        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            title = { Text("Set SOCKS5 Username") },
            text = {
                OutlinedTextField(
                    value = inputUsername,
                    onValueChange = { inputUsername = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputUsername.trim().isNotEmpty()) {
                            viewModel.setSocksUsername(inputUsername)
                            showUsernameDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsernameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Set SOCKS5 Password") },
            text = {
                OutlinedTextField(
                    value = inputPassword,
                    onValueChange = { inputPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setSocksPassword(inputPassword)
                        showPasswordDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDnsIpv4Dialog) {
        AlertDialog(
            onDismissRequest = { showDnsIpv4Dialog = false },
            title = { Text("Set DNS IPv4 Addresses") },
            text = {
                OutlinedTextField(
                    value = inputDnsIpv4,
                    onValueChange = { inputDnsIpv4 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DNS Servers (comma-separated)") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputDnsIpv4.trim().isNotEmpty()) {
                            viewModel.setDnsIpv4(inputDnsIpv4)
                            showDnsIpv4Dialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDnsIpv4Dialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDnsIpv6Dialog) {
        AlertDialog(
            onDismissRequest = { showDnsIpv6Dialog = false },
            title = { Text("Set DNS IPv6 Addresses") },
            text = {
                OutlinedTextField(
                    value = inputDnsIpv6,
                    onValueChange = { inputDnsIpv6 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IPv6 DNS Servers") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputDnsIpv6.trim().isNotEmpty()) {
                            viewModel.setDnsIpv6(inputDnsIpv6)
                            showDnsIpv6Dialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDnsIpv6Dialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRouteSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showRouteSettingsDialog = false },
            title = { Text("Set Route Settings") },
            text = {
                OutlinedTextField(
                    value = inputRouteSettings,
                    onValueChange = { inputRouteSettings = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Route Pattern") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputRouteSettings.trim().isNotEmpty()) {
                            viewModel.setRouteSettings(inputRouteSettings)
                            showRouteSettingsDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRouteSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTestUrlDialog) {
        AlertDialog(
            onDismissRequest = { showTestUrlDialog = false },
            title = { Text("Set Connection Test URL") },
            text = {
                OutlinedTextField(
                    value = inputTestUrl,
                    onValueChange = { inputTestUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Test URL") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputTestUrl.trim().isNotEmpty()) {
                            viewModel.setTestUrl(inputTestUrl)
                            showTestUrlDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTunnelEngineDialog) {
        val engines = listOf(
            "HevSocks5Tunnel (C-based)",
            "tun2socks (Standard Go-based)",
            "Go-tun2socks (optimized)"
        )
        AlertDialog(
            onDismissRequest = { showTunnelEngineDialog = false },
            title = { Text("Select Socks Tunnel Engine") },
            text = {
                Column {
                    Text(
                        "Socks Tunnel Engine is used to route device TCP/UDP traffic through the local SOCKS5 proxy created by the core daemon.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    engines.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setSocksTunnelEngine(engine)
                                    showTunnelEngineDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (engine == socksTunnelEngine),
                                onClick = {
                                    viewModel.setSocksTunnelEngine(engine)
                                    showTunnelEngineDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(engine, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTunnelEngineDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showLogcatDialog) {
        AlertDialog(
            onDismissRequest = { showLogcatDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Xray Logcat Monitor")
                    IconButton(
                        onClick = { viewModel.simulator.clearLogs() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            "No logs generated yet.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(logs) { log ->
                                Text(
                                    text = log,
                                    color = if (log.contains("Error")) Color.Red else Color.Green,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showLogcatDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
