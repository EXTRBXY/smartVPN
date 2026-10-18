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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.ui.UserErrorText
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(
    vm: HomeViewModel,
    onImport: () -> Unit,
    onScanQr: () -> Unit,
    onRouting: () -> Unit,
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
    val policy by vm.policy.collectAsState()
    val scope = rememberCoroutineScope()
    val latencies = remember { mutableStateMapOf<String, String>() }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSwitchId by rememberSaveable { mutableStateOf<String?>(null) }
    var checkingFastest by rememberSaveable { mutableStateOf(false) }
    var showErrorDetails by rememberSaveable { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var profileActionStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val displayedProfiles = remember(profiles) {
        profiles.sortedWith(
            compareByDescending<VlessProfile> { it.favorite }
                .thenBy { it.sortOrder }
                .thenBy { it.name.lowercase(Locale.getDefault()) },
        )
    }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    val busy = state is VpnSessionState.Connected || state is VpnSessionState.Connecting || state is VpnSessionState.Recovering

    LaunchedEffect(state) {
        while (state is VpnSessionState.Connected) {
            clockNow = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun selectProfile(id: String) {
        if (state is VpnSessionState.Connected && id != activeProfileId) pendingSwitchId = id else vm.setActiveProfile(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onDiagnostics) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.diag_title))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TunnelStatsCard(
                    state = state,
                    profileName = activeProfile?.name,
                    stats = tunnelStats,
                    durationMs = if (state is VpnSessionState.Connected) {
                        val startedAt = (tunnelStatus as? TunnelStatus.Running)?.startedAtMillis ?: clockNow
                        (clockNow - startedAt).coerceAtLeast(0)
                    } else {
                        0L
                    },
                    network = currentNetworkLabel(context),
                    onToggle = { if (busy) vm.disconnect() else onPrepareVpnAndConnect { vm.connect() } },
                )

                err?.let {
                    Text(
                        stringResource(R.string.ui_error_prefix, UserErrorText.from(context, it)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onPrepareVpnAndConnect { vm.connect() } }) { Text(stringResource(R.string.action_retry)) }
                        TextButton(onClick = { showErrorDetails = !showErrorDetails }) { Text(stringResource(R.string.action_details)) }
                    }
                    if (showErrorDetails) Text(it.code, style = MaterialTheme.typography.bodySmall)
                }

                RoutingCard(policy = policy, onClick = onRouting)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_profiles_count, profiles.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (profiles.size > 1) {
                        TextButton(
                            onClick = {
                                checkingFastest = true
                                scope.launch {
                                    vm.findFastestProfile().onSuccess { (id, latency) ->
                                        selectProfile(id)
                                        profileActionStatus = context.getString(R.string.home_fastest_result, latency)
                                    }.onFailure { profileActionStatus = context.getString(R.string.home_fastest_none) }
                                    checkingFastest = false
                                }
                            },
                            enabled = !checkingFastest,
                        ) {
                            Text(stringResource(if (checkingFastest) R.string.home_fastest_checking else R.string.home_fastest_pick))
                        }
                    }
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_profile))
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_import)) },
                                onClick = {
                                    showAddMenu = false
                                    onImport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_scan_qr)) },
                                onClick = {
                                    showAddMenu = false
                                    onScanQr()
                                },
                            )
                        }
                    }
                }

                if (profiles.isEmpty()) {
                    Text(
                        stringResource(R.string.home_no_profiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.home_import)) }
                        OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.home_scan_qr)) }
                    }
                }

                displayedProfiles.forEach { p ->
                    ProfileCard(
                        profile = p,
                        isActive = p.id == activeProfileId,
                        latency = latencies[p.id],
                        onSelect = { selectProfile(p.id) },
                        onToggleFavorite = { vm.setProfileFavorite(p.id, !p.favorite) },
                        onEdit = { onEditProfile(p.id) },
                        onLatency = {
                            latencies[p.id] = context.getString(R.string.status_checking)
                            scope.launch {
                                latencies[p.id] = vm.testProfileLatency(p.id).fold(
                                    onSuccess = { context.getString(R.string.latency_ms, it) },
                                    onFailure = { context.getString(R.string.status_unreachable) },
                                )
                            }
                        },
                        onDuplicate = {
                            vm.duplicateProfile(p.id, { context.getString(R.string.profile_copy_name, it) }) { result ->
                                profileActionStatus = context.getString(
                                    if (result.isSuccess) R.string.home_duplicate_done else R.string.home_duplicate_failed,
                                )
                            }
                        },
                        onShare = { pendingExportId = p.id },
                    )
                }

                profileActionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            if (state is VpnSessionState.Connecting || state is VpnSessionState.Recovering) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            }
        }
    }

    pendingExportId?.let { profileId ->
        AlertDialog(
            onDismissRequest = { pendingExportId = null },
            title = { Text(stringResource(R.string.home_export_title)) },
            text = { Text(stringResource(R.string.home_export_warning)) },
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
                                    context.getString(R.string.home_export_chooser),
                                ),
                            )
                        }.onFailure {
                            profileActionStatus = context.getString(R.string.home_export_failed)
                        }
                    }
                }) { Text(stringResource(R.string.action_send)) }
            },
            dismissButton = { TextButton(onClick = { pendingExportId = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pendingSwitchId?.let { profileId ->
        AlertDialog(
            onDismissRequest = { pendingSwitchId = null },
            title = { Text(stringResource(R.string.home_switch_title)) },
            text = { Text(stringResource(R.string.home_switch_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitchId = null
                    vm.switchActiveProfileAndReconnect(profileId) { result ->
                        if (result.isFailure) profileActionStatus = context.getString(R.string.home_switch_failed)
                    }
                }) { Text(stringResource(R.string.home_switch_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingSwitchId = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Режим маршрутизации одной строкой; нажатие открывает экран «Маршрутизация». */
@Composable
private fun RoutingCard(policy: TunnelPolicy?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.routing_title), style = MaterialTheme.typography.labelLarge)
                val emptyAllowed = policy?.routingMode == RoutingMode.PER_APP_ALLOWED && policy.includedPackageNames.isEmpty()
                Text(
                    when (policy?.routingMode) {
                        null -> ""
                        RoutingMode.FULL_TUN -> stringResource(R.string.routing_mode_full)
                        RoutingMode.PER_APP_ALLOWED ->
                            stringResource(R.string.routing_mode_with_count, stringResource(R.string.routing_mode_allowed), policy.includedPackageNames.size)
                        RoutingMode.PER_APP_EXCLUDED ->
                            stringResource(R.string.routing_mode_with_count, stringResource(R.string.routing_mode_excluded), policy.excludedPackageNames.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (emptyAllowed) {
                    Text(
                        stringResource(R.string.routing_allowed_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun ProfileCard(
    profile: VlessProfile,
    isActive: Boolean,
    latency: String?,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onLatency: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${profile.host}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val details = listOfNotNull(
                    stringResource(R.string.home_profile_active).takeIf { isActive },
                    latency,
                )
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = onToggleFavorite) { Text(if (profile.favorite) "★" else "☆") }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_profile_actions))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_latency)) }, onClick = {
                        showMenu = false
                        onLatency()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = {
                        showMenu = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_duplicate)) }, onClick = {
                        showMenu = false
                        onDuplicate()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                        showMenu = false
                        onShare()
                    })
                }
            }
        }
    }
}

@Composable
private fun TunnelStatsCard(
    state: VpnSessionState,
    profileName: String?,
    stats: TunnelStats,
    durationMs: Long,
    network: String,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val connected = state is VpnSessionState.Connected
    val active = connected || state is VpnSessionState.Connecting || state is VpnSessionState.Recovering
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stateLabel(context, state), style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(profileName ?: stringResource(R.string.home_no_active_profile), network).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connected) {
                Text(stringResource(R.string.home_connected_for, formatDuration(durationMs)), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.home_traffic,
                        formatBytes(context, stats.downloadTotal),
                        formatBytes(context, stats.uploadTotal),
                        formatBytes(context, stats.downloadBytesPerSecond),
                        formatBytes(context, stats.uploadBytesPerSecond),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.home_active_connections, stats.activeConnections), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onToggle,
                enabled = state !is VpnSessionState.Disconnecting,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(if (active) R.string.action_disconnect else R.string.action_connect))
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
        } ?: return context.getString(R.string.network_none)
    return when {
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> context.getString(R.string.network_cellular)
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> context.getString(R.string.network_other)
    }
}

private fun formatBytes(context: Context, bytes: Long): String {
    if (bytes < 1_024) return context.getString(R.string.bytes_b, bytes)
    val units = context.resources.getStringArray(R.array.byte_units)
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1_024 && index < units.lastIndex) {
        value /= 1_024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

internal fun stateLabel(context: Context, s: VpnSessionState): String = when (s) {
    VpnSessionState.Disconnected -> context.getString(R.string.state_disconnected)
    VpnSessionState.Connecting -> context.getString(R.string.state_connecting)
    is VpnSessionState.Recovering -> context.getString(R.string.state_recovering, s.attempt, s.maxAttempts)
    VpnSessionState.Connected -> context.getString(R.string.state_connected)
    VpnSessionState.Disconnecting -> context.getString(R.string.state_disconnecting)
    is VpnSessionState.Error -> context.getString(R.string.state_error)
}
