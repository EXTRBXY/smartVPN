package com.nothing.mobilecore.ui.settings

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
internal fun SettingsRoute(vm: SettingsViewModel, onBack: () -> Unit) {
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
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            SettingSwitch("Подключаться после перезагрузки", autoStart) {
                autoStart = it
                vm.setAutoStart(it)
            }
            SettingSwitch("Восстанавливать соединение", autoReconnect) {
                autoReconnect = it
                vm.setAutoReconnect(it)
            }
            SettingSwitch("Подключаться в недоверенных Wi‑Fi", untrustedWifi) {
                untrustedWifi = it
                vm.setConnectOnUntrustedWifi(it)
                if (it && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    (context as? Activity)?.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        10,
                    )
                }
            }
            SettingSwitch("Периодически проверять туннель", healthCheck) {
                healthCheck = it
                vm.setPeriodicHealthCheck(it)
            }

            Text("Доверенные Wi‑Fi", style = MaterialTheme.typography.labelLarge)
            Text(
                currentWifi?.let { "Текущая сеть: $it" }
                    ?: "Имя текущей сети недоступно — проверьте разрешение геолокации.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = trustedWifi,
                onValueChange = { trustedWifi = it },
                label = { Text("По одному имени сети в строке") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.setTrustedWifiNames(trustedWifi.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    Toast.makeText(context, "Список сохранён", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить доверенные сети") }

            HorizontalDivider()
            Text("Kill Switch", style = MaterialTheme.typography.labelLarge)
            Text(
                "Строгую блокировку трафика включает Android: выберите Nothing Mobile как постоянный VPN и включите блокировку соединений без VPN.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Открыть системные настройки VPN") }
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
