package com.nothing.mobilecore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.NothingVpnGraph
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.validate
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(
    private val graph: NothingVpnGraph,
) : ViewModel() {

    private val controller = graph.sessionController
    private val profiles = graph.profileRepository

    val sessionState: StateFlow<VpnSessionState> = controller.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VpnSessionState.Disconnected,
    )

    val profileList: StateFlow<List<VlessProfile>> = profiles.observeProfiles().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId

    val lastError = controller.lastError.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress

    private val _splitTunnelSaving = MutableStateFlow(false)
    val splitTunnelSaving: StateFlow<Boolean> = _splitTunnelSaving

    init {
        viewModelScope.launch {
            _activeProfileId.value = profiles.getActiveProfileId()
        }
    }

    fun importLink(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        if (_importInProgress.value) return
        viewModelScope.launch {
            _importInProgress.update { true }
            val result = runCatching {
                val r = controller.importVlessLink(link)
                r.onSuccess { profiles.upsert(it) }
                r
            }.getOrElse { Result.failure(it) }
            onResult(result)
            _importInProgress.update { false }
        }
    }

    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            profiles.setActiveProfileId(id)
            _activeProfileId.value = id
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            val cur = profiles.getTunnelPolicy()
            profiles.setTunnelPolicy(
                cur.copy(
                    routingMode = mode,
                    allowedPackageNames = if (mode == RoutingMode.FULL_TUN) emptyList() else cur.allowedPackageNames,
                ),
            )
        }
    }

    fun saveSplitTunnel(mode: RoutingMode, packages: List<String>, onResult: (Result<Unit>) -> Unit) {
        require(mode == RoutingMode.PER_APP_ALLOWED || mode == RoutingMode.PER_APP_EXCLUDED)
        if (_splitTunnelSaving.value) return
        viewModelScope.launch {
            _splitTunnelSaving.update { true }
            val result = runCatching {
                val cur = profiles.getTunnelPolicy()
                val updated = cur.copy(
                        routingMode = mode,
                        allowedPackageNames = packages.distinct().sorted(),
                )
                updated.validate().getOrElse { throw it }
                profiles.setTunnelPolicy(updated)
            }
            onResult(result)
            _splitTunnelSaving.update { false }
        }
    }

    suspend fun getTunnelPolicy(): TunnelPolicy = profiles.getTunnelPolicy()

    fun connect() {
        graph.sessionController.connect()
    }

    fun disconnect() {
        viewModelScope.launch { graph.sessionController.disconnectSuspend() }
    }

    suspend fun singBoxVersion(): String =
        controller.singBoxCoreVersion().getOrElse { throw it }

    class Factory(
        private val graph: NothingVpnGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}
