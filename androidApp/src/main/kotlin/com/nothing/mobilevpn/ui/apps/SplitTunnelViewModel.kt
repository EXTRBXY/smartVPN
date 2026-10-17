package com.nothing.mobilecore.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.platform.RoutingRuleSet
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.validate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SplitTunnelViewModel(
    private val profiles: ProfileRepositoryPort,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _splitTunnelSaving = MutableStateFlow(false)
    val splitTunnelSaving: StateFlow<Boolean> = _splitTunnelSaving.asStateFlow()

    suspend fun getTunnelPolicy(): TunnelPolicy = profiles.getTunnelPolicy()

    fun saveSplitTunnel(mode: RoutingMode, packages: List<String>, onResult: (Result<Unit>) -> Unit) {
        require(mode == RoutingMode.PER_APP_ALLOWED || mode == RoutingMode.PER_APP_EXCLUDED)
        if (_splitTunnelSaving.value) return
        _splitTunnelSaving.value = true
        viewModelScope.launch {
            try {
                onResult(
                    runCatching {
                        val updated = profiles.getTunnelPolicy().copy(
                            routingMode = mode,
                            allowedPackageNames = packages.distinct().sorted(),
                        )
                        updated.validate().getOrThrow()
                        profiles.setTunnelPolicy(updated)
                    },
                )
            } finally {
                _splitTunnelSaving.value = false
            }
        }
    }

    fun routingRuleSets(): List<RoutingRuleSet> = preferences.routingRuleSets()

    fun saveRoutingRuleSet(name: String, mode: RoutingMode, packages: List<String>) =
        preferences.saveRoutingRuleSet(RoutingRuleSet(name.trim(), mode, packages))

    fun deleteRoutingRuleSet(name: String) = preferences.deleteRoutingRuleSet(name)
}
