package com.nothing.mobilecore.ui.dns

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.ui.UserErrorText
import kotlinx.coroutines.launch

private data class DnsPreset(
    val name: String,
    val host: String,
    val path: String,
    val sni: String?,
)

private val dnsPresets = listOf(
    DnsPreset("Cloudflare", "1.1.1.1", "/dns-query", "cloudflare-dns.com"),
    DnsPreset("Google", "8.8.8.8", "/dns-query", "dns.google"),
    DnsPreset("Quad9", "9.9.9.9", "/dns-query", "dns.quad9.net"),
    DnsPreset("AdGuard", "94.140.14.14", "/dns-query", "dns.adguard-dns.com"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DnsRoute(vm: DnsViewModel, onBack: () -> Unit, onDnsApps: () -> Unit) {
    val context = LocalContext.current
    val dnsSaving by vm.dnsSaving.collectAsState()
    var dnsMode by rememberSaveable { mutableStateOf(DnsMode.SYSTEM) }
    var customHost by rememberSaveable { mutableStateOf("") }
    var customPath by rememberSaveable { mutableStateOf("/dns-query") }
    var customSni by rememberSaveable { mutableStateOf("") }
    var localDnsDomains by rememberSaveable { mutableStateOf("") }
    var selectedPresetName by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var statusError by rememberSaveable { mutableStateOf(false) }
    var dnsTesting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val presets = remember { dnsPresets }

    LaunchedEffect(Unit) {
        val policy = vm.getTunnelPolicy()
        dnsMode = policy.dnsMode
        customHost = policy.dohServer
        customPath = policy.dohPath
        customSni = policy.dohSni.orEmpty()
        localDnsDomains = policy.localDnsDomains.joinToString("\n")
        selectedPresetName = presets.firstOrNull { it.host == customHost && it.path == customPath }?.name
    }

    fun applyDnsResult(result: Result<Unit>) {
        statusError = result.isFailure
        status = if (result.isSuccess) {
            context.getString(R.string.save_success)
        } else {
            UserErrorText.from(context, result.exceptionOrNull())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                        )
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.dns_mode_title), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = dnsMode == DnsMode.SYSTEM,
                    onClick = { dnsMode = DnsMode.SYSTEM },
                    label = { Text(stringResource(R.string.dns_mode_system)) },
                )
                FilterChip(
                    selected = dnsMode == DnsMode.DOH,
                    onClick = { dnsMode = DnsMode.DOH },
                    label = { Text(stringResource(R.string.dns_mode_doh)) },
                )
            }

            if (dnsMode == DnsMode.DOH) {
                Button(onClick = onDnsApps, modifier = Modifier.fillMaxWidth()) {
                    Text("Приложения с системным DNS")
                }
                Text(stringResource(R.string.dns_presets_title), style = MaterialTheme.typography.labelLarge)
                presets.forEach { preset ->
                    Button(
                        onClick = {
                            customHost = preset.host
                            customPath = preset.path
                            customSni = preset.sni.orEmpty()
                            selectedPresetName = preset.name
                        },
                        enabled = !dnsSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(preset.name) }
                }
                selectedPresetName?.let { selected ->
                    Text(
                        text = stringResource(R.string.dns_selected_preset, selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = { Text(stringResource(R.string.dns_custom_host)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text(stringResource(R.string.dns_custom_path)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = customSni,
                    onValueChange = { customSni = it },
                    label = { Text(stringResource(R.string.dns_custom_sni)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDnsDomains,
                    onValueChange = { localDnsDomains = it },
                    label = { Text("Локальные домены, по одному в строке") },
                    supportingText = { Text("Эти домены будут разрешаться системным DNS") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (dnsSaving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            status?.let {
                Text(
                    text = it,
                    color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    if (dnsMode == DnsMode.SYSTEM) {
                        vm.saveDnsSystem(::applyDnsResult)
                    } else {
                        vm.saveDnsCustom(
                            host = customHost,
                            path = customPath,
                            sni = customSni,
                            localDomains = localDnsDomains.lineSequence().toList(),
                        ) { result -> applyDnsResult(result) }
                    }
                },
                enabled = !dnsSaving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.dns_save)) }
            if (dnsMode == DnsMode.DOH) {
                Button(
                    onClick = {
                        dnsTesting = true
                        status = null
                        scope.launch {
                            vm.testDnsEndpoint(customHost).fold(
                                onSuccess = { latency ->
                                    statusError = false
                                    status = "DNS-сервер доступен: $latency мс"
                                },
                                onFailure = {
                                    statusError = true
                                    status = "DNS-сервер недоступен"
                                },
                            )
                            dnsTesting = false
                        }
                    },
                    enabled = !dnsTesting && !dnsSaving && customHost.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (dnsTesting) "Проверка…" else "Проверить DNS-сервер") }
            }
        }
    }
}
