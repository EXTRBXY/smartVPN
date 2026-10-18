package com.nothing.mobilecore.ui.settings

import androidx.compose.ui.res.stringResource
import com.nothing.mobilecore.R
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRoute(vm: SettingsViewModel, onBack: () -> Unit, onDns: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { vm.automationSettings() }
    var autoStart by rememberSaveable { mutableStateOf(initial.autoStartAfterBoot) }
    var autoReconnect by rememberSaveable { mutableStateOf(initial.autoReconnect) }
    var untrustedWifi by rememberSaveable { mutableStateOf(initial.connectOnUntrustedWifi) }
    var healthCheck by rememberSaveable { mutableStateOf(initial.periodicHealthCheck) }
    var trustedWifi by rememberSaveable { mutableStateOf(initial.trustedWifiNames.joinToString("\n")) }
    val currentWifi = remember { vm.currentWifiName() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_section_network), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = onDns, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_dns_button))
            }
            HorizontalDivider()
            Text(stringResource(R.string.settings_section_connection), style = MaterialTheme.typography.labelLarge)
            SettingSwitch(stringResource(R.string.settings_auto_start), autoStart) {
                autoStart = it
                vm.setAutoStart(it)
            }
            SettingSwitch(stringResource(R.string.settings_auto_reconnect), autoReconnect) {
                autoReconnect = it
                vm.setAutoReconnect(it)
            }
            SettingSwitch(stringResource(R.string.settings_untrusted_wifi), untrustedWifi) {
                untrustedWifi = it
                vm.setConnectOnUntrustedWifi(it)
                if (it && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    (context as? Activity)?.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        10,
                    )
                }
            }
            SettingSwitch(stringResource(R.string.settings_health_check), healthCheck) {
                healthCheck = it
                vm.setPeriodicHealthCheck(it)
            }

            Text(stringResource(R.string.settings_trusted_wifi), style = MaterialTheme.typography.labelLarge)
            Text(
                currentWifi?.let { context.getString(R.string.settings_current_wifi, it) }
                    ?: context.getString(R.string.settings_current_wifi_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = trustedWifi,
                onValueChange = { trustedWifi = it },
                label = { Text(stringResource(R.string.settings_trusted_wifi_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.setTrustedWifiNames(trustedWifi.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    Toast.makeText(context, context.getString(R.string.settings_trusted_saved), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_trusted_save)) }

            HorizontalDivider()
            Text("Kill Switch", style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.settings_kill_switch_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_open_vpn)) }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
