@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.viewmodel.MainViewModel

@Composable
fun WireproxyConfigScreen(
    viewModel: MainViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wireproxy_config_title)) },
                actions = {
                    IconButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        // cm.setPrimaryClip(ClipData.newPlainText("wg_config", config.toWgQuickConfig()))
                        HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
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

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_proxy_addresses), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_http)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_socks5)) },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_edit_config), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.wireproxy_interface), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_private_key)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_address)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_dns)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_mtu)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Text(stringResource(R.string.wireproxy_peer), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_public_key)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_endpoint)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_allowed_ips)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text(stringResource(R.string.wireproxy_persistent_keepalive)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}