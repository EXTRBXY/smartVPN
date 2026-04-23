package com.nothing.mobilevpn

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.nothing.mobilevpn.shared.domain.RoutingMode
import com.nothing.mobilevpn.shared.domain.VpnSessionState
import com.nothing.mobilevpn.ui.AppViewModel
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
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val graph = (application as NothingVpnApp).graph
        setContent {
            MaterialTheme {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeRoute(
    vm: AppViewModel,
    onImport: () -> Unit,
    onApps: () -> Unit,
    onPrepareVpnAndConnect: (connect: () -> Unit) -> Unit,
) {
    val state by vm.sessionState.collectAsState()
    val profiles by vm.profileList.collectAsState()
    val err by vm.lastError.collectAsState()
    var version by remember { mutableStateOf<String?>(null) }
    var policyMode by remember { mutableStateOf(RoutingMode.FULL_TUN) }

    LaunchedEffect(Unit) {
        version = runCatching { vm.singBoxVersion() }.getOrNull()
        policyMode = vm.getTunnelPolicy().routingMode
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Nothing Mobile VPN") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Состояние: ${stateLabel(state)}", style = MaterialTheme.typography.titleMedium)
            err?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
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

            if (policyMode == RoutingMode.PER_APP_ALLOWED || policyMode == RoutingMode.PER_APP_EXCLUDED) {
                Button(onClick = onApps, modifier = Modifier.fillMaxWidth()) {
                    Text("Выбрать приложения…")
                }
            }

            Text("Профили (${profiles.size})", style = MaterialTheme.typography.labelLarge)
            profiles.forEach { p ->
                Card(onClick = { vm.setActiveProfile(p.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${p.name} — ${p.host}:${p.port}", modifier = Modifier.padding(12.dp))
                }
            }

            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Импорт vless://")
            }

            Button(
                onClick = {
                    onPrepareVpnAndConnect { vm.connect() }
                },
                enabled = state !is VpnSessionState.Connecting && state !is VpnSessionState.Connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Подключить") }

            Button(
                onClick = { vm.disconnect() },
                enabled = state is VpnSessionState.Connected || state is VpnSessionState.Connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Отключить") }
        }
    }
}

@Composable
private fun stateLabel(s: VpnSessionState): String = when (s) {
    VpnSessionState.Disconnected -> "Отключено"
    VpnSessionState.Connecting -> "Подключение…"
    VpnSessionState.Connected -> "Подключено"
    VpnSessionState.Disconnecting -> "Отключение…"
    is VpnSessionState.Error -> "Ошибка"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRoute(vm: AppViewModel, onBack: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Импорт ссылки") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("Назад") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("vless://…") },
                modifier = Modifier.fillMaxWidth(),
            )
            msg?.let { Text(it) }
            Button(
                onClick = {
                    vm.importLink(link) { r ->
                        msg = r.fold(
                            onSuccess = { "Импортирован: ${it.name}" },
                            onFailure = { it.message ?: "ошибка" },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != myPkg }
            .map { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                app.packageName to label
            }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
            .distinctBy { it.first }
            .toList()
    }

    var splitMode by remember { mutableStateOf(RoutingMode.PER_APP_ALLOWED) }
    var search by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var listReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val policy = vm.getTunnelPolicy()
        splitMode = when (policy.routingMode) {
            RoutingMode.PER_APP_EXCLUDED -> RoutingMode.PER_APP_EXCLUDED
            RoutingMode.PER_APP_ALLOWED -> RoutingMode.PER_APP_ALLOWED
            RoutingMode.FULL_TUN -> RoutingMode.PER_APP_ALLOWED
        }
        val marked = policy.allowedPackageNames.toSet()
        apps.forEach { (pkg, _) -> selected[pkg] = pkg in marked }
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
                navigationIcon = { Button(onClick = onBack) { Text("Назад") } },
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
                    onValueChange = { search = it },
                    label = { Text("Поиск по имени или пакету") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Button(
                onClick = {
                    val list = selected.filterValues { it }.keys.toList()
                    vm.saveSplitTunnel(splitMode, list)
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("Сохранить") }

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
