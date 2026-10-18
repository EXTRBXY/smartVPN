package com.nothing.mobilecore.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Список установленных приложений для выбора в маршрутизации и DNS. Загружается в фоне и
 * кэшируется на время жизни процесса: экран сразу показывает прошлый список, а [refresh]
 * подтягивает изменения (установку/удаление приложений).
 */
class InstalledAppsRepository(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val mutableApps = MutableStateFlow<List<InstalledApp>?>(null)

    /** null — ещё ни разу не загружали. Собственный пакет приложения в список не входит. */
    val apps: StateFlow<List<InstalledApp>?> = mutableApps.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        mutableApps.value = withContext(Dispatchers.IO) { query() }
    }

    private fun query(): List<InstalledApp> {
        val pm = app.packageManager
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        val collator = Collator.getInstance(Locale.getDefault())
        return installed
            .asSequence()
            .filter { it.packageName != app.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                    isSystem = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }
}
