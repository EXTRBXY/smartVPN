package com.nothing.mobilecore.ui.dns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.ui.apps.AppFilter
import com.nothing.mobilecore.ui.apps.appSelectionItems

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DnsAppsRoute(vm: DnsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val apps by vm.apps.collectAsState()
    val saving by vm.dnsSaving.collectAsState()
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var initial by remember { mutableStateOf<Set<String>>(emptySet()) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshApps()
        val stored = vm.getTunnelPolicy().systemDnsPackageNames.toSet()
        initial = stored
        selected = stored
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = {
                            val current = selected ?: return@Button
                            vm.saveSystemDnsApps(current.toList()) { result ->
                                if (result.isSuccess) initial = current
                                status = resources.getString(if (result.isSuccess) R.string.status_saved else R.string.status_save_failed)
                            }
                        },
                        enabled = selected != null && selected != initial && !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "hint") {
                Text(stringResource(R.string.dns_apps_hint), style = MaterialTheme.typography.bodySmall)
            }
            val current = selected.orEmpty()
            appSelectionItems(
                pm = context.packageManager,
                apps = if (selected == null) null else apps,
                selected = current,
                search = search,
                onSearchChange = { search = it },
                filter = filter,
                onFilterChange = { filter = it },
                onToggle = { pkg, checked -> selected = if (checked) current + pkg else current - pkg },
                onSelectVisible = { selected = current + it },
                onClearAll = { selected = emptySet() },
            )
        }
    }
}
