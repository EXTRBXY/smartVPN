package com.nothing.mobilecore

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.view.WindowManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.nothing.mobilecore.BuildConfig
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.ui.AppViewModel
import com.nothing.mobilecore.vpn.VpnInternalBroadcastSecurity
import com.nothing.mobilecore.vpn.VpnRunningState
import com.nothing.mobilecore.vpn.VpnRunningStateContract
import com.nothing.mobilecore.ui.UserErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val appIconCacheMaxBytes =
    (Runtime.getRuntime().maxMemory() / 24).toInt().coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)

private val appIconBitmapCache: LruCache<String, Bitmap> =
    object : LruCache<String, Bitmap>(appIconCacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

private fun appIconCacheKey(packageName: String, sizePx: Int): String = "$packageName#$sizePx"

class MainActivity : ComponentActivity() {

    private val vpnRunningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VpnRunningStateContract.ACTION) return
            val running = intent.getBooleanExtra(VpnRunningStateContract.EXTRA_RUNNING, false)
            (application as NothingVpnApp).graph.sessionController.applyExternalTunnelRunning(running)
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
                            onApps = { nav.navigate("apps") },
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
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(VpnRunningStateContract.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                vpnRunningReceiver,
                filter,
                VpnInternalBroadcastSecurity.PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                vpnRunningReceiver,
                filter,
                VpnInternalBroadcastSecurity.PERMISSION,
                null,
            )
        }
        VpnRunningState.syncFromDisk(this)
        (application as NothingVpnApp).graph.sessionController.applyExternalTunnelRunning(
            VpnRunningState.isRunning,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(vpnRunningReceiver) }
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeRoute(
    vm: AppViewModel,
    onImport: () -> Unit,
    onApps: () -> Unit,
    onPrepareVpnAndConnect: (connect: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val state by vm.sessionState.collectAsState()
    val profiles by vm.profileList.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val err by vm.lastError.collectAsState()
    var version by remember { mutableStateOf<String?>(null) }
    var policyMode by rememberSaveable { mutableStateOf(RoutingMode.FULL_TUN) }

    LaunchedEffect(Unit) {
        version = runCatching { vm.singBoxVersion() }.getOrNull()
        policyMode = vm.getTunnelPolicy().routingMode
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Nothing Mobile") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Состояние: ${stateLabel(state)}", style = MaterialTheme.typography.titleMedium)
            err?.let {
                Text(
                    context.getString(R.string.ui_error_prefix, UserErrorText.from(context, it)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            version?.let { Text("sing-box: $it", style = MaterialTheme.typography.bodySmall) }

            if (state is VpnSessionState.Connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
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
            profiles.forEach { p ->
                val isActive = p.id == activeProfileId
                Card(
                    onClick = { vm.setActiveProfile(p.id) },
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
                    Text("${p.name} — ${p.host}:${p.port}", modifier = Modifier.padding(12.dp))
                }
            }

            Button(
                onClick = {
                    onPrepareVpnAndConnect { vm.connect() }
                },
                enabled = state !is VpnSessionState.Connecting &&
                    state !is VpnSessionState.Recovering &&
                    state !is VpnSessionState.Connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Подключить") }

            Button(
                onClick = { vm.disconnect() },
                enabled = state is VpnSessionState.Connected ||
                    state is VpnSessionState.Connecting ||
                    state is VpnSessionState.Recovering,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Отключить") }

            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Импорт vless://")
            }

            if (policyMode == RoutingMode.PER_APP_ALLOWED || policyMode == RoutingMode.PER_APP_EXCLUDED) {
                Button(onClick = onApps, modifier = Modifier.fillMaxWidth()) {
                    Text("Выбрать приложения…")
                }
            }
        }
    }
}

@Composable
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
    val apps = remember(pm, myPkg) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
        resolved
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == myPkg) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                pkg to label
            }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
            .distinctBy { it.first }
            .toList()
    }

    var splitMode by rememberSaveable { mutableStateOf(RoutingMode.PER_APP_ALLOWED) }
    var search by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var listReady by remember { mutableStateOf(false) }
    var initialMode by remember { mutableStateOf(RoutingMode.PER_APP_ALLOWED) }
    var initialSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf(false) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    val splitTunnelSaving by vm.splitTunnelSaving.collectAsState()

    LaunchedEffect(Unit) {
        val policy = vm.getTunnelPolicy()
        splitMode = when (policy.routingMode) {
            RoutingMode.PER_APP_EXCLUDED -> RoutingMode.PER_APP_EXCLUDED
            RoutingMode.PER_APP_ALLOWED -> RoutingMode.PER_APP_ALLOWED
            RoutingMode.FULL_TUN -> RoutingMode.PER_APP_ALLOWED
        }
        val marked = policy.allowedPackageNames.toSet()
        apps.forEach { (pkg, _) -> selected[pkg] = pkg in marked }
        initialMode = splitMode
        initialSelected = marked
        listReady = true
    }

    val filtered = remember(apps, search) {
        val q = search.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) apps
        else {
            apps.filter { (pkg, label) ->
                pkg.lowercase(Locale.getDefault()).contains(q) ||
                    label.lowercase(Locale.getDefault()).contains(q)
            }
        }
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
                .filter { selected[it.first] == true }
                .sortedWith(compareBy(collator) { it.second })
        }
    }
    val otherApps by remember(filtered, collator) {
        derivedStateOf {
            filtered
                .filter { selected[it.first] != true }
                .sortedWith(compareBy(collator) { it.second })
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

            if (!listReady) {
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
                            key = { "c:${it.first}" },
                            contentType = { "app_row" },
                        ) { (pkg, label) ->
                            AppPickerRow(
                                pm = pm,
                                packageName = pkg,
                                label = label,
                                checked = true,
                                onCheckedChange = { selected[pkg] = it },
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
                            key = { "o:${it.first}" },
                            contentType = { "app_row" },
                        ) { (pkg, label) ->
                            AppPickerRow(
                                pm = pm,
                                packageName = pkg,
                                label = label,
                                checked = false,
                                onCheckedChange = { selected[pkg] = it },
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
