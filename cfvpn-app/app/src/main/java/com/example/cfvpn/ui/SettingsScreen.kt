package com.example.cfvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.cfvpn.data.DeployResult
import com.example.cfvpn.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel
) {
    val credentials by viewModel.cloudflareCredentials.collectAsState()
    val deployInProgress by viewModel.deployInProgress.collectAsState()
    val deployResult by viewModel.deployResult.collectAsState()

    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var scriptName by rememberSaveable { mutableStateOf("cfvpn-proxy") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(text = "اتصال به Cloudflare", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = credentials.accountId,
            onValueChange = viewModel::updateAccountId,
            label = { Text("Account ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = credentials.apiToken,
            onValueChange = viewModel::updateApiToken,
            label = { Text("Global API Token") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { tokenVisible = !tokenVisible }) {
                    Text(if (tokenVisible) "پنهان" else "نمایش")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { viewModel.persistCloudflareCredentials() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ذخیره اطلاعات کلودفلر")
        }

        Divider()

        Text(text = "دیپلوی خودکار Worker", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = scriptName,
            onValueChange = { scriptName = it },
            label = { Text("نام Worker (script name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                // TODO: کد واقعی Worker (JS) که باید دیپلوی شود را از یک تمپلیت
                // (مثلاً فایلی در assets) بخوانید و اینجا جای placeholder ارسال کنید.
                val workerSourceCode = "export default { fetch() { return new Response('ok'); } };"
                viewModel.deployWorker(scriptName, workerSourceCode)
            },
            enabled = !deployInProgress &&
                credentials.accountId.isNotBlank() &&
                credentials.apiToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (deployInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Deploy Worker")
            }
        }

        deployResult?.let { result ->
            when (result) {
                is DeployResult.Success -> Text(
                    text = "موفق: ${result.deployedUrl}",
                    color = MaterialTheme.colorScheme.primary
                )
                is DeployResult.Failure -> Text(
                    text = "خطا: ${result.message}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
