@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.ValidatorUtils
import com.freeturn.app.viewmodel.MainViewModel

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun WireproxyConfigScreen(
    viewModel: MainViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val wgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val wgConfigText by viewModel.wgConfigText.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val text = input.bufferedReader().use { r -> r.readText() }
                        viewModel.updateWgConfigText(text)
                        Toast.makeText(context, R.string.wireproxy_import_success, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    val errorMessage = context.getString(R.string.wireproxy_import_error)
                    val fullError = context.getString(R.string.error_with_details, errorMessage, e.message ?: "")
                    Toast.makeText(context, fullError, Toast.LENGTH_LONG).show()
                }
            }
        }
    )

    val isHttpValid = remember(wgConfig.httpBindAddress) {
        ValidatorUtils.isValidHostPort(wgConfig.httpBindAddress)
    }
    val isSocksValid = remember(wgConfig.socks5BindAddress) {
        ValidatorUtils.isValidHostPort(wgConfig.socks5BindAddress)
    }
    val isEndpointValid = remember(wgConfig.endpoint) {
        ValidatorUtils.isValidHostPort(wgConfig.endpoint)
    }
    val isTargetEndpoint = remember(wgConfig.endpoint) {
        clientConfig.localPort == wgConfig.endpoint
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wireproxy_config_title)) },
                actions = {
                    IconButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        // Копирование всего конфига в буфер
                        val clip = android.content.ClipData.newPlainText("wg_config", wgConfigText)
                        cm.setPrimaryClip(clip)
                        HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                        Toast.makeText(context, R.string.copy, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(painterResource(R.drawable.content_copy_24px), stringResource(R.string.copy))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Способы импорта ─────────────────────────────────────
            Text(stringResource(R.string.wireproxy_import_config), style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(painterResource(R.drawable.file_open_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wireproxy_import_file))
                }
                Button(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val clip = cm?.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text?.toString() ?: ""
                            if (text.isNotBlank()) {
                                viewModel.updateWgConfigText(text)
                                Toast.makeText(context, R.string.wireproxy_import_success, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(painterResource(R.drawable.content_paste_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wireproxy_import_clipboard))
                }
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_proxy_addresses), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = wgConfig.httpBindAddress,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(httpBindAddress = it)) },
                label = { Text(stringResource(R.string.wireproxy_http)) },
                isError = !isHttpValid,
                placeholder = { Text(stringResource(R.string.wireproxy_http_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = wgConfig.socks5BindAddress,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(socks5BindAddress = it)) },
                label = { Text(stringResource(R.string.wireproxy_socks5)) },
                isError = !isSocksValid,
                placeholder = { Text(stringResource(R.string.wireproxy_socks5_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_edit_config), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.wireproxy_interface), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = wgConfig.privateKey,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(privateKey = it)) },
                label = { Text(stringResource(R.string.wireproxy_private_key)) },
                placeholder = { Text(stringResource(R.string.wireproxy_private_key_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode
            )

            OutlinedTextField(
                value = wgConfig.address,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(address = it)) },
                label = { Text(stringResource(R.string.wireproxy_address)) },
                placeholder = { Text(stringResource(R.string.wireproxy_address_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode
            )

            OutlinedTextField(
                value = wgConfig.dns,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(dns = it)) },
                label = { Text(stringResource(R.string.wireproxy_dns)) },
                placeholder = { Text(stringResource(R.string.wireproxy_dns_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = wgConfig.mtu,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(mtu = it)) },
                label = { Text(stringResource(R.string.wireproxy_mtu)) },
                placeholder = { Text(stringResource(R.string.wireproxy_mtu_placeholder)) },
                supportingText = {
                    if (wgConfig.mtu != "1280") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(stringResource(R.string.wireproxy_mtu_recommendation))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            Text(stringResource(R.string.wireproxy_peer), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = wgConfig.publicKey,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(publicKey = it)) },
                label = { Text(stringResource(R.string.wireproxy_public_key)) },
                placeholder = { Text(stringResource(R.string.wireproxy_public_key_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode
            )

            OutlinedTextField(
                value = wgConfig.endpoint,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(endpoint = it)) },
                label = { Text(stringResource(R.string.wireproxy_endpoint)) },
                isError = !isTargetEndpoint || !isEndpointValid,
                supportingText = {
                    if (!isTargetEndpoint) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.wireproxy_endpoint_mismatch),
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.wireproxy_endpoint_fix),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    viewModel.updateWgConfig(wgConfig.copy(endpoint = clientConfig.localPort))
                                }
                            )
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.wireproxy_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = wgConfig.allowedIps,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(allowedIps = it)) },
                label = { Text(stringResource(R.string.wireproxy_allowed_ips)) },
                placeholder = { Text(stringResource(R.string.wireproxy_allowed_ips_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = wgConfig.persistentKeepalive,
                onValueChange = { viewModel.updateWgConfig(wgConfig.copy(persistentKeepalive = it)) },
                label = { Text(stringResource(R.string.wireproxy_persistent_keepalive)) },
                placeholder = { Text(stringResource(R.string.wireproxy_persistent_keepalive_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_raw_config), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = wgConfigText,
                onValueChange = { viewModel.updateWgConfigText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp),
                placeholder = { Text(stringResource(R.string.wireproxy_config_placeholder)) },
                textStyle = MaterialTheme.typography.bodySmall
            )

            if (showFinishButton && onFinish != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.finish_setup))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
