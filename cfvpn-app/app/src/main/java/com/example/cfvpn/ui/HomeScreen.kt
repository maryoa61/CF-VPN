package com.example.cfvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cfvpn.data.ConnectionState
import com.example.cfvpn.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onConnectClicked: () -> Unit
) {
    val workerConfig by viewModel.workerConfig.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val vpnStats by viewModel.vpnStats.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScrollWorkaround(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        Spacer(Modifier.height(8.dp))

        ConnectButton(
            state = connectionState,
            onClick = {
                if (connectionState == ConnectionState.CONNECTED) {
                    viewModel.disconnectVpn()
                } else if (connectionState == ConnectionState.DISCONNECTED ||
                    connectionState == ConnectionState.ERROR
                ) {
                    onConnectClicked()
                }
            }
        )

        Text(
            text = connectionStatusLabel(connectionState),
            style = MaterialTheme.typography.titleMedium
        )

        SpeedCard(vpnStats.uploadSpeedBps, vpnStats.downloadSpeedBps)

        WorkerConfigCard(
            host = workerConfig.host,
            path = workerConfig.path,
            uuid = workerConfig.uuid,
            onHostChange = viewModel::updateWorkerHost,
            onPathChange = viewModel::updateWorkerPath,
            onUuidChange = viewModel::updateWorkerUuid,
            onSave = viewModel::persistWorkerConfig
        )
    }
}

@Composable
private fun ConnectButton(state: ConnectionState, onClick: () -> Unit) {
    val (containerColor, label) = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary to "متصل"
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary to "..."
        ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary to "..."
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error to "خطا"
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant to "اتصال"
    }

    val isBusy = state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING

    Button(
        onClick = onClick,
        enabled = !isBusy,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = Modifier.size(140.dp)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
        } else {
            Text(text = label, fontSize = 18.sp)
        }
    }
}

private fun connectionStatusLabel(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "وضعیت: متصل"
    ConnectionState.CONNECTING -> "وضعیت: در حال اتصال..."
    ConnectionState.DISCONNECTING -> "وضعیت: در حال قطع..."
    ConnectionState.ERROR -> "وضعیت: خطا - فیلدها را بررسی کنید"
    ConnectionState.DISCONNECTED -> "وضعیت: قطع"
}

@Composable
private fun SpeedCard(uploadBps: Long, downloadBps: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SpeedColumn(label = "آپلود", bps = uploadBps)
            SpeedColumn(label = "دانلود", bps = downloadBps)
        }
    }
}

@Composable
private fun SpeedColumn(label: String, bps: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = formatSpeed(bps), style = MaterialTheme.typography.titleLarge)
    }
}

private fun formatSpeed(bps: Long): String {
    val kbps = bps / 1024.0
    return if (kbps < 1024) "%.1f KB/s".format(kbps) else "%.2f MB/s".format(kbps / 1024.0)
}

@Composable
private fun WorkerConfigCard(
    host: String,
    path: String,
    uuid: String,
    onHostChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onUuidChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "تنظیمات Worker", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host (مثال: my-worker.username.workers.dev)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                label = { Text("Path (مثال: /ws)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uuid,
                onValueChange = onUuidChange,
                label = { Text("UUID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onSave,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("ذخیره")
            }
        }
    }
}

// در پروژه‌ی واقعی به‌جای این extension، از Modifier.verticalScroll(rememberScrollState()) استفاده کنید.
private fun Modifier.verticalScrollWorkaround(): Modifier = this
