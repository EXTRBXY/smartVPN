package com.nothing.mobilecore.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.platform.InstalledApp
import com.nothing.mobilecore.platform.InstalledAppsRepository
import com.nothing.mobilecore.platform.RoutingRuleSet
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.shared.domain.validate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Черновик маршрутизации: режим и свои списки приложений для каждого режима. */
internal data class RoutingDraft(
    val mode: RoutingMode = RoutingMode.FULL_TUN,
    val included: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
) {
    /** Список, который редактируется в текущем режиме (для «весь трафик» — нет). */
    val current: Set<String>
        get() = when (mode) {
            RoutingMode.FULL_TUN -> emptySet()
            RoutingMode.PER_APP_ALLOWED -> included
            RoutingMode.PER_APP_EXCLUDED -> excluded
        }

    fun withCurrent(packages: Set<String>): RoutingDraft = when (mode) {
        RoutingMode.FULL_TUN -> this
        RoutingMode.PER_APP_ALLOWED -> copy(included = packages)
        RoutingMode.PER_APP_EXCLUDED -> copy(excluded = packages)
    }
}

internal data class RoutingUiState(
    val loaded: Boolean = false,
    val draft: RoutingDraft = RoutingDraft(),
    val saved: RoutingDraft = RoutingDraft(),
    val saving: Boolean = false,
    val ruleSets: List<RoutingRuleSet> = emptyList(),
) {
    val hasChanges: Boolean get() = loaded && draft != saved
}

/**
 * Экран «Маршрутизация»: режим (весь трафик / только выбранные / все, кроме выбранных) и выбор
 * приложений в одном месте. У режимов с выбором — отдельные списки, поэтому переключение режима
 * ничего не стирает.
 */
internal class RoutingViewModel(
    private val profiles: ProfileRepositoryPort,
    private val preferences: AppPreferences,
    private val installedApps: InstalledAppsRepository,
    private val controller: VpnSessionController,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RoutingUiState(ruleSets = preferences.routingRuleSets()))
    val state: StateFlow<RoutingUiState> = mutableState.asStateFlow()
    val apps: StateFlow<List<InstalledApp>?> = installedApps.apps
    val session: StateFlow<VpnSessionState> = controller.state

    init {
        viewModelScope.launch {
            val policy = profiles.getTunnelPolicy()
            val saved = RoutingDraft(policy.routingMode, policy.includedPackageNames.toSet(), policy.excludedPackageNames.toSet())
            mutableState.update { it.copy(loaded = true, draft = saved, saved = saved) }
        }
        viewModelScope.launch { installedApps.refresh() }
    }

    fun setMode(mode: RoutingMode) = updateDraft { it.copy(mode = mode) }

    fun toggle(packageName: String, checked: Boolean) = updateDraft { draft ->
        draft.withCurrent(if (checked) draft.current + packageName else draft.current - packageName)
    }

    fun selectAll(packages: List<String>) = updateDraft { it.withCurrent(it.current + packages) }

    fun clearCurrent() = updateDraft { it.withCurrent(emptySet()) }

    fun applyRuleSet(ruleSet: RoutingRuleSet) = updateDraft {
        it.copy(mode = ruleSet.mode).withCurrent(ruleSet.packages.toSet())
    }

    fun saveRuleSet(name: String) {
        val draft = mutableState.value.draft
        if (name.isBlank() || draft.mode == RoutingMode.FULL_TUN) return
        preferences.saveRoutingRuleSet(RoutingRuleSet(name.trim(), draft.mode, draft.current.sorted()))
        mutableState.update { it.copy(ruleSets = preferences.routingRuleSets()) }
    }

    fun deleteRuleSet(name: String) {
        preferences.deleteRoutingRuleSet(name)
        mutableState.update { it.copy(ruleSets = preferences.routingRuleSets()) }
    }

    fun save(onResult: (Result<Unit>) -> Unit) {
        val snapshot = mutableState.value
        if (snapshot.saving || !snapshot.loaded) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val draft = snapshot.draft
            val result = runCatching {
                val updated = profiles.getTunnelPolicy().copy(
                    routingMode = draft.mode,
                    includedPackageNames = draft.included.sorted(),
                    excludedPackageNames = draft.excluded.sorted(),
                )
                updated.validate().getOrThrow()
                profiles.setTunnelPolicy(updated)
            }
            mutableState.update { it.copy(saving = false, saved = if (result.isSuccess) draft else it.saved) }
            onResult(result)
        }
    }

    /** Новые правила применяются при подключении: переподключаем, если VPN уже работает. */
    fun reconnect() {
        viewModelScope.launch {
            controller.disconnectSuspend()
            controller.connectSuspend()
        }
    }

    private fun updateDraft(transform: (RoutingDraft) -> RoutingDraft) {
        mutableState.update { it.copy(draft = transform(it.draft)) }
    }
}
