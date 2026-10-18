package com.nothing.mobilecore.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.ui.UserErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutingRoute(vm: RoutingViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val pm = context.packageManager
    val state by vm.state.collectAsState()
    val apps by vm.apps.collectAsState()
    val session by vm.session.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    var ruleSetName by rememberSaveable { mutableStateOf("") }
    var showRuleSets by rememberSaveable { mutableStateOf(false) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var messageIsError by rememberSaveable { mutableStateOf(false) }
    var offerReconnect by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft

    val requestBack = { if (state.hasChanges) showUnsavedDialog = true else onBack() }
    BackHandler(onBack = requestBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (offerReconnect && session is VpnSessionState.Connected && !state.hasChanges) {
                        OutlinedButton(
                            onClick = {
                                offerReconnect = false
                                message = null
                                vm.reconnect()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_reconnect)) }
                    }
                    Button(
                        onClick = {
                            vm.save { result ->
                                messageIsError = result.isFailure
                                if (result.isSuccess) {
                                    val connected = session is VpnSessionState.Connected
                                    offerReconnect = connected
                                    message = resources.getString(if (connected) R.string.routing_saved_reconnect else R.string.save_success)
                                } else {
                                    message = UserErrorText.from(context, result.exceptionOrNull())
                                }
                            }
                        },
                        enabled = state.hasChanges && !state.saving,
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
            item(key = "mode_title") {
                Text(stringResource(R.string.routing_question), style = MaterialTheme.typography.titleSmall)
            }
            item(key = "modes") {
                Column(Modifier.selectableGroup()) {
                    RoutingMode.entries.forEach { mode ->
                        ModeOption(
                            mode = mode,
                            selected = draft.mode == mode,
                            count = when (mode) {
                                RoutingMode.FULL_TUN -> null
                                RoutingMode.PER_APP_ALLOWED -> draft.included.size
                                RoutingMode.PER_APP_EXCLUDED -> draft.excluded.size
                            },
                            onSelect = {
                                vm.setMode(mode)
                                message = null
                            },
                        )
                    }
                }
            }
            if (draft.mode == RoutingMode.FULL_TUN) {
                if (draft.included.isNotEmpty() || draft.excluded.isNotEmpty()) {
                    item(key = "kept_hint") {
                        Text(
                            stringResource(R.string.routing_selection_kept),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyColumn
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            if (draft.mode == RoutingMode.PER_APP_ALLOWED && draft.included.isEmpty()) {
                item(key = "empty_warning") {
                    Text(
                        stringResource(R.string.routing_allowed_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            val installed = apps?.mapTo(hashSetOf()) { it.packageName }
            val missing = installed?.let { set -> draft.current.count { it !in set } } ?: 0
            if (missing > 0) {
                item(key = "missing") {
                    Text(
                        stringResource(R.string.apps_missing_from_rule, missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "rule_sets_toggle") {
                TextButton(onClick = { showRuleSets = !showRuleSets }) {
                    Text(
                        if (state.ruleSets.isEmpty()) {
                            stringResource(R.string.routing_rule_sets_empty)
                        } else {
                            stringResource(R.string.routing_rule_sets, state.ruleSets.size)
                        },
                    )
                }
            }
            if (showRuleSets) {
                item(key = "rule_set_new") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ruleSetName,
                            onValueChange = { ruleSetName = it },
                            label = { Text(stringResource(R.string.apps_rule_set_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                vm.saveRuleSet(ruleSetName)
                                ruleSetName = ""
                            },
                            enabled = ruleSetName.isNotBlank(),
                        ) { Text(stringResource(R.string.action_save)) }
                    }
                }
                state.ruleSets.forEach { ruleSet ->
                    item(key = "rule_set:${ruleSet.name}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.applyRuleSet(ruleSet) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.routing_rule_set_item, ruleSet.name, ruleSet.packages.size))
                            }
                            TextButton(onClick = { vm.deleteRuleSet(ruleSet.name) }) {
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
            appSelectionItems(
                pm = pm,
                apps = apps,
                selected = draft.current,
                search = search,
                onSearchChange = { search = it },
                filter = filter,
                onFilterChange = { filter = it },
                onToggle = { pkg, checked -> vm.toggle(pkg, checked) },
                onSelectVisible = { vm.selectAll(it) },
                onClearAll = { vm.clearCurrent() },
            )
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.apps_unsaved_title)) },
            text = { Text(stringResource(R.string.apps_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text(stringResource(R.string.action_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text(stringResource(R.string.action_stay)) }
            },
        )
    }
}

@Composable
private fun ModeOption(mode: RoutingMode, selected: Boolean, count: Int?, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            val title = stringResource(
                when (mode) {
                    RoutingMode.FULL_TUN -> R.string.routing_mode_full
                    RoutingMode.PER_APP_ALLOWED -> R.string.routing_mode_allowed
                    RoutingMode.PER_APP_EXCLUDED -> R.string.routing_mode_excluded
                },
            )
            Text(if (count != null && count > 0) stringResource(R.string.routing_mode_with_count, title, count) else title)
            Text(
                stringResource(
                    when (mode) {
                        RoutingMode.FULL_TUN -> R.string.routing_mode_full_hint
                        RoutingMode.PER_APP_ALLOWED -> R.string.apps_mode_allowed_hint
                        RoutingMode.PER_APP_EXCLUDED -> R.string.apps_mode_excluded_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
