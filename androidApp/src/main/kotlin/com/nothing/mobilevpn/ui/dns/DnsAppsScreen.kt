package com.nothing.mobilecore.ui.dns

import androidx.compose.ui.res.stringResource
import com.nothing.mobilecore.R
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.ui.apps.AppPickerRow
import com.nothing.mobilecore.ui.apps.InstalledAppEntry
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DnsAppsRoute(vm: DnsViewModel, onBack: () -> Unit) {
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
                title = { Text(stringResource(R.string.dns_apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dns_apps_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.apps_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.apps_selected_count, selected.count { it.value }))
            Button(
                onClick = {
                    vm.saveSystemDnsApps(selected.filterValues { it }.keys.toList()) { result ->
                        status = if (result.isSuccess) context.getString(R.string.status_saved) else context.getString(R.string.status_save_failed)
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
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
