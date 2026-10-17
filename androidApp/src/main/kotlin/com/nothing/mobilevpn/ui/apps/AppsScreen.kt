package com.nothing.mobilecore.ui.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.ui.UserErrorText
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsRoute(vm: SplitTunnelViewModel, onBack: () -> Unit) {
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
                            saveMessage = UserErrorText.from(context, result.exceptionOrNull())
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
