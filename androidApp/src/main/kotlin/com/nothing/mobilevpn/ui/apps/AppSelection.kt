package com.nothing.mobilecore.ui.apps

import android.content.pm.PackageManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.InstalledApp
import java.util.Locale

internal enum class AppFilter { ALL, USER, SYSTEM, SELECTED }

/** Применяет поиск и фильтр; выбранные приложения идут первыми, внутри групп — по имени. */
internal fun filterApps(apps: List<InstalledApp>, selected: Set<String>, search: String, filter: AppFilter): List<InstalledApp> {
    val query = search.trim().lowercase(Locale.getDefault())
    return apps
        .filter { app ->
            val matchesSearch = query.isEmpty() ||
                app.label.lowercase(Locale.getDefault()).contains(query) ||
                app.packageName.lowercase(Locale.getDefault()).contains(query)
            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
                AppFilter.SELECTED -> app.packageName in selected
            }
            matchesSearch && matchesFilter
        }
        .sortedBy { it.packageName !in selected }
}

/**
 * Поиск, фильтры, «выбрать показанные / снять все» и список приложений с галочками —
 * общий блок для экранов «Маршрутизация» и «Приложения с системным DNS».
 */
internal fun LazyListScope.appSelectionItems(
    pm: PackageManager,
    apps: List<InstalledApp>?,
    selected: Set<String>,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
    onToggle: (packageName: String, checked: Boolean) -> Unit,
    onSelectVisible: (List<String>) -> Unit,
    onClearAll: () -> Unit,
) {
    item(key = "app_search") {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            label = { Text(stringResource(R.string.apps_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "app_filters") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    AppFilter.ALL -> R.string.apps_filter_all
                                    AppFilter.USER -> R.string.apps_filter_user
                                    AppFilter.SYSTEM -> R.string.apps_filter_system
                                    AppFilter.SELECTED -> R.string.apps_filter_selected
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
    if (apps == null) {
        item(key = "app_loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        return
    }
    val visible = filterApps(apps, selected, search, filter)
    item(key = "app_bulk") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.apps_selected_count, selected.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 14.dp),
            )
            TextButton(onClick = { onSelectVisible(visible.map { it.packageName }) }) {
                Text(stringResource(R.string.apps_select_visible))
            }
            TextButton(onClick = onClearAll, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.apps_clear_all))
            }
        }
    }
    items(items = visible, key = { "app:${it.packageName}" }, contentType = { "app_row" }) { app ->
        AppPickerRow(
            pm = pm,
            packageName = app.packageName,
            label = if (app.isSystem) stringResource(R.string.apps_system_app_label, app.label) else app.label,
            checked = app.packageName in selected,
            onCheckedChange = { onToggle(app.packageName, it) },
        )
    }
}
