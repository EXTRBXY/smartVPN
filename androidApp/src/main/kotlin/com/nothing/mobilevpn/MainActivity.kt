package com.nothing.mobilecore

import android.Manifest
import android.app.Activity
import android.view.WindowManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.VpnService
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nothing.mobilecore.ui.NothingVpnTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.nothing.mobilecore.BuildConfig
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.ui.AppViewModel
import com.nothing.mobilecore.ui.UserErrorText
import com.nothing.mobilecore.platform.ConnectionEventLog
import com.nothing.mobilecore.platform.TunnelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val appIconCacheMaxBytes =
    (Runtime.getRuntime().maxMemory() / 24).toInt().coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)

private val appIconBitmapCache: LruCache<String, Bitmap> =
    object : LruCache<String, Bitmap>(appIconCacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

private fun appIconCacheKey(packageName: String, sizePx: Int): String = "$packageName#$sizePx"

private data class DnsPreset(
    val name: String,
    val host: String,
    val path: String,
    val sni: String?,
)

private data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

private val dnsPresets = listOf(
    DnsPreset("Cloudflare", "1.1.1.1", "/dns-query", "cloudflare-dns.com"),
    DnsPreset("Google", "8.8.8.8", "/dns-query", "dns.google"),
    DnsPreset("Quad9", "9.9.9.9", "/dns-query", "dns.quad9.net"),
    DnsPreset("AdGuard", "94.140.14.14", "/dns-query", "dns.adguard-dns.com"),
)

class MainActivity : ComponentActivity() {

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val link = result.contents ?: return@registerForActivityResult
        val graph = (application as NothingVpnApp).graph
        lifecycleScope.launch {
            graph.sessionController.importVlessLink(link)
                .onSuccess { profile ->
                    graph.profileRepository.upsert(profile)
                    graph.profileRepository.setActiveProfileId(profile.id)
                    Toast.makeText(this@MainActivity, "Профиль импортирован", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "QR-код не содержит корректный профиль", Toast.LENGTH_LONG).show()
                }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConnect?.invoke()
        }
        pendingConnect = null
    }

    private var pendingConnect: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val graph = (application as NothingVpnApp).graph
        setContent {
            NothingVpnTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(graph))
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeRoute(
                            vm = vm,
                            onImport = { nav.navigate("import") },
                            onScanQr = {
                                qrScannerLauncher.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Наведите камеру на QR-код профиля")
                                        .setBeepEnabled(false),
                                )
                            },
                            onApps = { nav.navigate("apps") },
                            onDns = { nav.navigate("dns") },
                            onSettings = { nav.navigate("settings") },
                            onDiagnostics = { nav.navigate("diagnostics") },
                            onEditProfile = { profileId -> nav.navigate("profile/edit/$profileId") },
                            onPrepareVpnAndConnect = { connect ->
                                val intent = VpnService.prepare(this@MainActivity)
                                if (intent != null) {
                                    pendingConnect = connect
                                    vpnPermissionLauncher.launch(intent)
                                } else {
                                    connect()
                                }
                            },
                        )
                    }
                    composable("import") {
                        ImportRoute(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("apps") {
                        AppsRoute(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("dns") {
                        DnsRoute(vm = vm, onBack = { nav.popBackStack() }, onDnsApps = { nav.navigate("dns/apps") })
                    }
                    composable("dns/apps") {
                        DnsAppsRoute(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsRoute(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("diagnostics") {
                        DiagnosticsRoute(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable(
                        route = "profile/edit/{profileId}",
                        arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val profileId = backStackEntry.arguments?.getString("profileId").orEmpty()
                        ProfileEditRoute(
                            vm = vm,
                            profileId = profileId,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeRoute(
    vm: AppViewModel,
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
                        Text(it, style = MaterialTheme.typography.bodySmall)
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

private fun currentNetworkLabel(context: Context): String {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoute(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NothingVpnApp
    val initial = remember { vm.automationSettings() }
    var autoStart by rememberSaveable { mutableStateOf(initial.autoStartAfterBoot) }
    var autoReconnect by rememberSaveable { mutableStateOf(initial.autoReconnect) }
    var untrustedWifi by rememberSaveable { mutableStateOf(initial.connectOnUntrustedWifi) }
    var healthCheck by rememberSaveable { mutableStateOf(initial.periodicHealthCheck) }
    var trustedWifi by rememberSaveable { mutableStateOf(initial.trustedWifiNames.joinToString("\n")) }
    val currentWifi = remember { app.automation.currentWifiName() }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsRoute(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.sessionState.collectAsState()
    val stats by vm.tunnelStats.collectAsState()
    var events by remember { mutableStateOf(ConnectionEventLog.read(context)) }
    var externalIp by rememberSaveable { mutableStateOf<String?>(null) }
    var ipLoading by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val report = remember(state, stats, events, externalIp) {
        buildString {
            appendLine("Nothing Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("VPN: ${stateLabel(state)}")
            appendLine("Сеть: ${currentNetworkLabel(context)}")
            appendLine("Разрешение VPN: ${if (VpnService.prepare(context) == null) "предоставлено" else "не предоставлено"}")
            appendLine("Трафик: down=${stats.downloadTotal}, up=${stats.uploadTotal}, connections=${stats.activeConnections}")
            appendLine("Внешний IP: ${externalIp ?: "не проверялся"}")
            appendLine("События:")
            events.forEach { appendLine(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
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
            Text("Состояние: ${stateLabel(state)}")
            Text("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            Text("Внешний IP: ${externalIp ?: "не проверялся"}")
            Button(
                onClick = {
                    ipLoading = true
                    scope.launch {
                        externalIp = vm.externalIp().getOrElse { "не удалось определить" }
                        ipLoading = false
                    }
                },
                enabled = !ipLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ipLoading) "Проверка…" else "Проверить внешний IP") }
            Text("Секреты, UUID и содержимое профилей в отчёт не включаются.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Nothing Mobile diagnostics", report))
                        Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Копировать") }
                Button(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, report)
                                },
                                "Передать отчёт",
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Поделиться") }
            }
            HorizontalDivider()
            Text("Последние события", style = MaterialTheme.typography.labelLarge)
            if (events.isEmpty()) Text("Событий пока нет", style = MaterialTheme.typography.bodySmall)
            events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (events.isNotEmpty()) {
                TextButton(onClick = {
                    ConnectionEventLog.clear(context)
                    events = emptyList()
                }) { Text("Очистить журнал") }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsRoute(vm: AppViewModel, onBack: () -> Unit, onDnsApps: () -> Unit) {
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
            UserErrorText.from(context, result.exceptionOrNull()?.message)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditRoute(
    vm: AppViewModel,
    profileId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val profiles by vm.profileList.collectAsState()
    val profile = profiles.find { it.id == profileId }
    if (profile == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Редактирование профиля") },
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
                    .padding(16.dp),
            ) {
                Text(
                    text = UserErrorText.from(context, "profile_not_found"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }

    var name by rememberSaveable(profileId) { mutableStateOf(profile.name) }
    var host by rememberSaveable(profileId) { mutableStateOf(profile.host) }
    var portText by rememberSaveable(profileId) { mutableStateOf(profile.port.toString()) }
    var uuid by rememberSaveable(profileId) { mutableStateOf(profile.uuid) }
    var security by rememberSaveable(profileId) { mutableStateOf(profile.security) }
    var realityPublicKey by rememberSaveable(profileId) { mutableStateOf(profile.realityPublicKey.orEmpty()) }
    var realityShortId by rememberSaveable(profileId) { mutableStateOf(profile.realityShortId.orEmpty()) }
    var statusMessage by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var statusIsError by rememberSaveable(profileId) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(profileId) { mutableStateOf(false) }
    var testingServer by rememberSaveable(profileId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактирование профиля") },
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя профиля") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uuid,
                onValueChange = { uuid = it },
                label = { Text("UUID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = security,
                onValueChange = { security = it },
                label = { Text("Security (tls/reality)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = realityPublicKey,
                onValueChange = { realityPublicKey = it },
                label = { Text("Reality Public Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = realityShortId,
                onValueChange = { realityShortId = it },
                label = { Text("Reality Short ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            statusMessage?.let {
                Text(
                    text = it,
                    color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    testingServer = true
                    scope.launch {
                        vm.testServerEndpoint(host, portText.toIntOrNull() ?: -1).fold(
                            onSuccess = {
                                statusIsError = false
                                statusMessage = "Сервер доступен: $it мс. Проверка не подтверждает корректность UUID/TLS."
                            },
                            onFailure = {
                                statusIsError = true
                                statusMessage = "Сервер недоступен. Проверьте адрес и порт."
                            },
                        )
                        testingServer = false
                    }
                },
                enabled = !testingServer,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (testingServer) "Проверка…" else "Проверить сервер перед сохранением") }

            Button(
                onClick = {
                    val updated = profile.copy(
                        name = name,
                        host = host,
                        port = portText.toIntOrNull() ?: -1,
                        uuid = uuid,
                        security = security,
                        realityPublicKey = realityPublicKey,
                        realityShortId = realityShortId,
                    )
                    vm.updateProfile(profileId, updated) { result ->
                        if (result.isSuccess) {
                            statusIsError = false
                            statusMessage = context.getString(R.string.save_success)
                            onBack()
                        } else {
                            statusIsError = true
                            statusMessage = UserErrorText.from(context, result.exceptionOrNull()?.message)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Удалить профиль") }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удаление профиля") },
            text = { Text("Удалить этот профиль? Действие необратимо.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteProfile(profileId) { result ->
                        if (result.isSuccess) {
                            onBack()
                        } else {
                            statusIsError = true
                            statusMessage = UserErrorText.from(context, result.exceptionOrNull()?.message)
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsAppsRoute(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var search by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val policy = vm.getTunnelPolicy()
        apps = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0).map { info ->
                InstalledAppEntry(
                    info.packageName,
                    runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                    info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
        }
        apps.forEach { selected[it.packageName] = it.packageName in policy.systemDnsPackageNames }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Приложения с системным DNS") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Для выбранных приложений DNS-запросы будут отправляться системному DNS, даже при включённом DoH.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Поиск по имени или пакету") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Выбрано: ${selected.count { it.value }}")
            Button(
                onClick = {
                    vm.saveSystemDnsApps(selected.filterValues { it }.keys.toList()) { result ->
                        status = if (result.isSuccess) "Сохранено" else "Не удалось сохранить"
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
            status?.let { Text(it) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn {
                items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                    AppPickerRow(
                        pm = pm,
                        packageName = app.packageName,
                        label = app.label,
                        checked = selected[app.packageName] == true,
                        onCheckedChange = { selected[app.packageName] = it },
                    )
                }
            }

        }
    }
}

private fun stateLabel(s: VpnSessionState): String = when (s) {
    VpnSessionState.Disconnected -> "Отключено"
    VpnSessionState.Connecting -> "Подключение…"
    is VpnSessionState.Recovering -> "Восстановление (${s.attempt}/${s.maxAttempts})…"
    VpnSessionState.Connected -> "Подключено"
    VpnSessionState.Disconnecting -> "Отключение…"
    is VpnSessionState.Error -> "Ошибка"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRoute(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var msg by rememberSaveable { mutableStateOf<String?>(null) }
    var msgIsError by rememberSaveable { mutableStateOf(false) }
    val importInProgress by vm.importInProgress.collectAsState()
    val normalizedLink = link.trim()
    val inputError = when {
        normalizedLink.isEmpty() -> "Вставьте ссылку vless://"
        !normalizedLink.startsWith("vless://", ignoreCase = true) -> "Поддерживаются только ссылки vless://"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Импорт ссылки") },
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
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    msg = null
                },
                label = { Text("vless://…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = inputError != null && normalizedLink.isNotEmpty(),
            )
            if (inputError != null && normalizedLink.isNotEmpty()) {
                Text(inputError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            msg?.let {
                Text(
                    text = it,
                    color = if (msgIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (importInProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Button(
                onClick = {
                    vm.importLink(normalizedLink) { r ->
                        msg = r.fold(
                            onSuccess = { context.getString(R.string.import_success_prefix, it.name) },
                            onFailure = { UserErrorText.from(context, it.message) },
                        )
                        msgIsError = r.isFailure
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !importInProgress && inputError == null,
            ) { Text("Импортировать") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsRoute(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val myPkg = context.packageName
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<InstalledAppEntry>?>(null) }

    LaunchedEffect(pm, myPkg) {
        apps = withContext(Dispatchers.IO) {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            installed
                .asSequence()
                .filter { it.packageName != myPkg }
                .map { info ->
                    InstalledAppEntry(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(
                    compareBy<InstalledAppEntry> { it.label.lowercase(Locale.getDefault()) }
                        .thenBy { it.packageName },
                )
                .toList()
        }
    }

    var splitMode by rememberSaveable { mutableStateOf(RoutingMode.PER_APP_ALLOWED) }
    var search by rememberSaveable { mutableStateOf("") }
    var appFilter by rememberSaveable { mutableStateOf("all") }
    var ruleSetName by rememberSaveable { mutableStateOf("") }
    var ruleSets by remember { mutableStateOf(vm.routingRuleSets()) }
    var showRuleSets by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var listReady by remember { mutableStateOf(false) }
    var initialMode by remember { mutableStateOf(RoutingMode.PER_APP_ALLOWED) }
    var initialSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf(false) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    val splitTunnelSaving by vm.splitTunnelSaving.collectAsState()

    LaunchedEffect(apps) {
        val loadedApps = apps ?: return@LaunchedEffect
        val policy = vm.getTunnelPolicy()
        splitMode = when (policy.routingMode) {
            RoutingMode.PER_APP_EXCLUDED -> RoutingMode.PER_APP_EXCLUDED
            RoutingMode.PER_APP_ALLOWED -> RoutingMode.PER_APP_ALLOWED
            RoutingMode.FULL_TUN -> RoutingMode.PER_APP_ALLOWED
        }
        val marked = policy.allowedPackageNames.toSet()
        loadedApps.forEach { app -> selected[app.packageName] = app.packageName in marked }
        initialMode = splitMode
        initialSelected = marked
        listReady = true
    }

    val filtered = apps.orEmpty().filter { app ->
        val q = search.trim().lowercase(Locale.getDefault())
        val matchesSearch = q.isEmpty() || app.packageName.lowercase(Locale.getDefault()).contains(q) ||
            app.label.lowercase(Locale.getDefault()).contains(q)
        val matchesFilter = when (appFilter) {
            "user" -> !app.isSystem
            "system" -> app.isSystem
            "selected" -> selected[app.packageName] == true
            else -> true
        }
        matchesSearch && matchesFilter
    }

    val collator = remember { java.text.Collator.getInstance(Locale.getDefault()) }
    val selectedPackages by remember(selected) {
        derivedStateOf { selected.filterValues { it }.keys.toSet() }
    }
    val hasUnsavedChanges by remember(splitMode, initialMode, selectedPackages, initialSelected, listReady) {
        derivedStateOf {
            listReady && (splitMode != initialMode || selectedPackages != initialSelected)
        }
    }
    val saveValidationError by remember(splitMode, selectedPackages) {
        derivedStateOf {
            if (splitMode == RoutingMode.PER_APP_ALLOWED && selectedPackages.isEmpty()) {
                "Выберите хотя бы одно приложение для режима \"только выбранные\"."
            } else {
                null
            }
        }
    }

    val requestBack = {
        if (hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = true) {
        requestBack()
    }

    val chosenApps by remember(filtered, collator) {
        derivedStateOf {
            filtered
                .filter { selected[it.packageName] == true }
                .sortedWith(compareBy(collator) { it.label })
        }
    }
    val otherApps by remember(filtered, collator) {
        derivedStateOf {
            filtered
                .filter { selected[it.packageName] != true }
                .sortedWith(compareBy(collator) { it.label })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Раздельная маршрутизация") },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
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
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = splitMode == RoutingMode.PER_APP_ALLOWED,
                        onClick = { splitMode = RoutingMode.PER_APP_ALLOWED },
                        label = { Text("В VPN только выбранные") },
                    )
                    FilterChip(
                        selected = splitMode == RoutingMode.PER_APP_EXCLUDED,
                        onClick = { splitMode = RoutingMode.PER_APP_EXCLUDED },
                        label = { Text("В обход только выбранные") },
                    )
                }
                Text(
                    when (splitMode) {
                        RoutingMode.PER_APP_ALLOWED ->
                            "Отмеченные приложения идут через VPN, остальной трафик — мимо туннеля."
                        RoutingMode.PER_APP_EXCLUDED ->
                            "Отмеченные приложения идут мимо VPN, всё остальное — через туннель."
                        RoutingMode.FULL_TUN -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = {
                        search = it
                        saveMessage = null
                    },
                    label = { Text("Поиск по имени или пакету") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = appFilter == "all", onClick = { appFilter = "all" }, label = { Text("Все") })
                    FilterChip(selected = appFilter == "user", onClick = { appFilter = "user" }, label = { Text("Пользовательские") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = appFilter == "system", onClick = { appFilter = "system" }, label = { Text("Системные") })
                    FilterChip(selected = appFilter == "selected", onClick = { appFilter = "selected" }, label = { Text("Выбранные") })
                }
                Text("Выбрано: ${selectedPackages.size}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showRuleSets = !showRuleSets }) {
                    Text(if (showRuleSets) "Скрыть наборы правил" else "Наборы правил")
                }
                if (showRuleSets) {
                    OutlinedTextField(
                        value = ruleSetName,
                        onValueChange = { ruleSetName = it },
                        label = { Text("Имя набора") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            vm.saveRoutingRuleSet(ruleSetName, splitMode, selectedPackages.toList())
                            ruleSets = vm.routingRuleSets()
                            ruleSetName = ""
                        },
                        enabled = ruleSetName.isNotBlank(),
                    ) { Text("Сохранить текущий набор") }
                    ruleSets.forEach { ruleSet ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                splitMode = ruleSet.mode
                                selected.keys.toList().forEach { selected[it] = it in ruleSet.packages }
                                saveMessage = "Набор применён. Нажмите «Сохранить»."
                                saveError = false
                            }) { Text(ruleSet.name) }
                            TextButton(onClick = {
                                vm.deleteRoutingRuleSet(ruleSet.name)
                                ruleSets = vm.routingRuleSets()
                            }) { Text("Удалить") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { filtered.forEach { selected[it.packageName] = true } }) {
                        Text("Выбрать показанные")
                    }
                    TextButton(onClick = { selected.keys.toList().forEach { selected[it] = false } }) {
                        Text("Снять все")
                    }
                }
                val installedPackages = apps.orEmpty().mapTo(hashSetOf()) { it.packageName }
                val removedCount = initialSelected.count { it !in installedPackages }
                if (removedCount > 0) {
                    Text(
                        "Не установлено приложений из старого правила: $removedCount. После сохранения они будут удалены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Button(
                onClick = {
                    if (saveValidationError != null) {
                        saveError = true
                        saveMessage = saveValidationError
                        return@Button
                    }
                    vm.saveSplitTunnel(splitMode, selectedPackages.toList()) { result ->
                        if (result.isSuccess) {
                            initialMode = splitMode
                            initialSelected = selectedPackages
                            saveError = false
                            saveMessage = context.getString(R.string.save_success)
                        } else {
                            saveError = true
                            saveMessage = UserErrorText.from(context, result.exceptionOrNull()?.message)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                enabled = !splitTunnelSaving,
            ) { Text("Сохранить") }
            saveMessage?.let {
                Text(
                    text = it,
                    color = if (saveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (splitTunnelSaving) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            if (!listReady || apps == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (chosenApps.isNotEmpty()) {
                        item(key = "header_chosen") {
                            Text(
                                "Выбранные",
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(
                            items = chosenApps,
                            key = { "c:${it.packageName}" },
                            contentType = { "app_row" },
                        ) { app ->
                            AppPickerRow(
                                pm = pm,
                                packageName = app.packageName,
                                label = if (app.isSystem) "${app.label} · системное" else app.label,
                                checked = true,
                                onCheckedChange = { selected[app.packageName] = it },
                            )
                        }
                        if (otherApps.isNotEmpty()) {
                            item(key = "divider_chosen") {
                                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            }
                        }
                    }
                    if (otherApps.isNotEmpty()) {
                        item(key = "header_rest") {
                            Text(
                                if (chosenApps.isNotEmpty()) "Остальные" else "Все приложения",
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(
                            items = otherApps,
                            key = { "o:${it.packageName}" },
                            contentType = { "app_row" },
                        ) { app ->
                            AppPickerRow(
                                pm = pm,
                                packageName = app.packageName,
                                label = if (app.isSystem) "${app.label} · системное" else app.label,
                                checked = false,
                                onCheckedChange = { selected[app.packageName] = it },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Есть несохранённые изменения") },
            text = { Text("Выйти без сохранения?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onBack()
                    },
                ) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text("Остаться") }
            },
        )
    }
}

@Composable
private fun AppPickerRow(
    pm: PackageManager,
    packageName: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        AppPackageIcon(pm = pm, packageName = packageName, label = label)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppPackageIcon(
    pm: PackageManager,
    packageName: String,
    label: String,
) {
    val density = LocalDensity.current
    val sizePx = remember(density) {
        with(density) { 40.dp.roundToPx().coerceAtLeast(1) }
    }
    val cacheKey = remember(packageName, sizePx) { appIconCacheKey(packageName, sizePx) }

    var bitmap by remember(packageName, sizePx) {
        mutableStateOf(
            synchronized(appIconBitmapCache) { appIconBitmapCache.get(cacheKey) },
        )
    }

    LaunchedEffect(cacheKey) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            synchronized(appIconBitmapCache) {
                appIconBitmapCache.get(cacheKey)
            } ?: runCatching {
                pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
            }.getOrNull()?.also { decoded ->
                synchronized(appIconBitmapCache) {
                    appIconBitmapCache.put(cacheKey, decoded)
                }
            }
        }
        if (decoded != null) bitmap = decoded
    }

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = label,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = label.take(1).uppercase(Locale.getDefault()).ifEmpty { "·" },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
