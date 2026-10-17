package com.nothing.mobilecore.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.TunnelStats
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.ui.UserErrorText
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(
    vm: HomeViewModel,
    onImport: () -> Unit,
    onScanQr: () -> Unit,
    onApps: () -> Unit,
    onDns: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onEditProfile: (String) -> Unit,
    onPrepareVpnAndConnect: (connect: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val state by vm.sessionState.collectAsState()
    val profiles by vm.profileList.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val err by vm.lastError.collectAsState()
    val tunnelStatus by vm.tunnelStatus.collectAsState()
    val tunnelStats by vm.tunnelStats.collectAsState()
    val scope = rememberCoroutineScope()
    val latencies = remember { mutableStateMapOf<String, String>() }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSwitchId by rememberSaveable { mutableStateOf<String?>(null) }
    var checkingFastest by rememberSaveable { mutableStateOf(false) }
    var showErrorDetails by rememberSaveable { mutableStateOf(false) }
    var profileActionStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val displayedProfiles = remember(profiles) {
        profiles.sortedWith(
            compareByDescending<com.nothing.mobilecore.shared.domain.VlessProfile> { it.favorite }
                .thenBy { it.sortOrder }
                .thenBy { it.name.lowercase(Locale.getDefault()) },
        )
    }
    var policyMode by rememberSaveable { mutableStateOf(RoutingMode.FULL_TUN) }

    LaunchedEffect(Unit) {
        policyMode = vm.getTunnelPolicy().routingMode
    }
    LaunchedEffect(state) {
        while (state is VpnSessionState.Connected) {
            clockNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Nothing Mobile") }) },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TunnelStatsCard(
                    stats = if (state is VpnSessionState.Connected) tunnelStats else TunnelStats(),
                    durationMs = if (state is VpnSessionState.Connected) {
                        val startedAt = (tunnelStatus as? TunnelStatus.Running)?.startedAtMillis ?: clockNow
                        (clockNow - startedAt).coerceAtLeast(0)
                    } else 0L,
                    state = state,
                    network = currentNetworkLabel(context),
                    onToggle = {
                        if (state is VpnSessionState.Connected || state is VpnSessionState.Connecting || state is VpnSessionState.Recovering) {
                            vm.disconnect()
                        } else {
                            onPrepareVpnAndConnect { vm.connect() }
                        }
                    },
                )
                err?.let {
                    Text(
                        context.getString(R.string.ui_error_prefix, UserErrorText.from(context, it)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onPrepareVpnAndConnect { vm.connect() } }) { Text("Повторить") }
                        TextButton(onClick = { showErrorDetails = !showErrorDetails }) { Text("Подробности") }
                    }
                    if (showErrorDetails) {
                        Text(it.code, style = MaterialTheme.typography.bodySmall)
                    }
                }

            Text("Режим маршрутизации", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = policyMode == RoutingMode.FULL_TUN,
                    onClick = {
                        policyMode = RoutingMode.FULL_TUN
                        vm.setRoutingMode(RoutingMode.FULL_TUN)
                    },
                    label = { Text("Весь трафик") },
                )
                FilterChip(
                    selected = policyMode == RoutingMode.PER_APP_ALLOWED,
                    onClick = {
                        policyMode = RoutingMode.PER_APP_ALLOWED
                        vm.setRoutingMode(RoutingMode.PER_APP_ALLOWED)
                    },
                    label = { Text("Через VPN только выбранные") },
                )
                FilterChip(
                    selected = policyMode == RoutingMode.PER_APP_EXCLUDED,
                    onClick = {
                        policyMode = RoutingMode.PER_APP_EXCLUDED
                        vm.setRoutingMode(RoutingMode.PER_APP_EXCLUDED)
                    },
                    label = { Text("Выбранные в обход, остальное через VPN") },
                )
            }

            Text("Профили (${profiles.size})", style = MaterialTheme.typography.labelLarge)
            if (profiles.size > 1) {
                TextButton(
                    onClick = {
                        checkingFastest = true
                        scope.launch {
                            vm.findFastestProfile().onSuccess { (id, latency) ->
                                if (state is VpnSessionState.Connected && id != activeProfileId) {
                                    pendingSwitchId = id
                                } else {
                                    vm.setActiveProfile(id)
                                }
                                profileActionStatus = "Минимальная задержка: $latency мс"
                            }.onFailure { profileActionStatus = "Доступных серверов не найдено" }
                            checkingFastest = false
                        }
                    },
                    enabled = !checkingFastest,
                ) { Text(if (checkingFastest) "Проверяем серверы…" else "Выбрать самый быстрый") }
            }
            displayedProfiles.forEach { p ->
                val isActive = p.id == activeProfileId
                Card(
                    onClick = {
                        if (state is VpnSessionState.Connected && p.id != activeProfileId) {
                            pendingSwitchId = p.id
                        } else {
                            vm.setActiveProfile(p.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = if (isActive) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${p.name} — ${p.host}:${p.port}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onEditProfile(p.id) }) {
                                Text("Редактировать")
                            }
                            TextButton(onClick = {
                                latencies[p.id] = "Проверка…"
                                scope.launch {
                                    latencies[p.id] = vm.testProfileLatency(p.id)
                                        .fold(onSuccess = { "$it мс" }, onFailure = { "Недоступен" })
                                }
                            }) { Text(latencies[p.id] ?: "Задержка") }
                            TextButton(onClick = { vm.setProfileFavorite(p.id, !p.favorite) }) {
                                Text(if (p.favorite) "★" else "☆")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                vm.duplicateProfile(p.id) { result ->
                                    profileActionStatus = if (result.isSuccess) "Профиль продублирован" else "Не удалось создать копию"
                                }
                            }) { Text("Копия") }
                            TextButton(onClick = { pendingExportId = p.id }) { Text("Поделиться") }
                        }
                    }
                }
            }

            profileActionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Импорт vless://")
            }
            Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                Text("Сканировать QR-код")
            }
            Button(onClick = onDns, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_dns_button))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSettings, modifier = Modifier.weight(1f)) {
                    Text("Настройки")
                }
                Button(onClick = onDiagnostics, modifier = Modifier.weight(1f)) {
                    Text("Диагностика")
                }
            }

                if (policyMode == RoutingMode.PER_APP_ALLOWED || policyMode == RoutingMode.PER_APP_EXCLUDED) {
                    Button(onClick = onApps, modifier = Modifier.fillMaxWidth()) {
                        Text("Выбрать приложения…")
                    }
                }
            }

            if (state is VpnSessionState.Connecting || state is VpnSessionState.Recovering) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }

    pendingExportId?.let { profileId ->
        AlertDialog(
            onDismissRequest = { pendingExportId = null },
            title = { Text("Передать профиль?") },
            text = { Text("Ссылка содержит UUID и параметры доступа. Передавайте её только тому, кому доверяете.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingExportId = null
                    scope.launch {
                        vm.exportProfile(profileId).onSuccess { link ->
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, link)
                                    },
                                    "Передать VPN-профиль",
                                ),
                            )
                        }.onFailure {
                            profileActionStatus = "Не удалось экспортировать профиль"
                        }
                    }
                }) { Text("Передать") }
            },
            dismissButton = { TextButton(onClick = { pendingExportId = null }) { Text("Отмена") } },
        )
    }
    pendingSwitchId?.let { profileId ->
        AlertDialog(
            onDismissRequest = { pendingSwitchId = null },
            title = { Text("Сменить сервер?") },
            text = { Text("VPN будет отключён и подключён заново с выбранным профилем.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitchId = null
                    vm.switchActiveProfileAndReconnect(profileId) { result ->
                        if (result.isFailure) profileActionStatus = "Переподключение не удалось"
                    }
                }) { Text("Переподключиться") }
            },
            dismissButton = { TextButton(onClick = { pendingSwitchId = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun TunnelStatsCard(stats: TunnelStats, durationMs: Long, state: VpnSessionState, network: String, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state is VpnSessionState.Connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stateLabel(state), style = MaterialTheme.typography.titleLarge)
            Text("Подключено: ${formatDuration(durationMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Сеть: $network", style = MaterialTheme.typography.bodySmall)
            Text("↓ ${formatBytes(stats.downloadTotal)}   ↑ ${formatBytes(stats.uploadTotal)}")
            Text(
                "Сейчас: ↓ ${formatBytes(stats.downloadBytesPerSecond)}/с   ↑ ${formatBytes(stats.uploadBytesPerSecond)}/с",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Активных соединений: ${stats.activeConnections}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onToggle, enabled = state !is VpnSessionState.Disconnecting, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state is VpnSessionState.Connected || state is VpnSessionState.Connecting || state is VpnSessionState.Recovering) {
                        "Отключить"
                    } else {
                        "Подключить"
                    },
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1_000
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%02d:%02d".format(minutes, rest)
}

internal fun currentNetworkLabel(context: Context): String {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val capabilities = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
        .firstOrNull {
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } ?: return "нет подключения"
    return when {
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная"
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Другая"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes Б"
    val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1_024 && index < units.lastIndex) {
        value /= 1_024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

internal fun stateLabel(s: VpnSessionState): String = when (s) {
    VpnSessionState.Disconnected -> "Отключено"
    VpnSessionState.Connecting -> "Подключение…"
    is VpnSessionState.Recovering -> "Восстановление (${s.attempt}/${s.maxAttempts})…"
    VpnSessionState.Connected -> "Подключено"
    VpnSessionState.Disconnecting -> "Отключение…"
    is VpnSessionState.Error -> "Ошибка"
}
