package com.nothing.mobilevpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nothing.mobilevpn.platform.NothingVpnGraph
import com.nothing.mobilevpn.shared.domain.RoutingMode
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.shared.domain.VlessProfile
import com.nothing.mobilevpn.shared.domain.VpnSessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

    val lastError = controller.lastError.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun importLink(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        viewModelScope.launch {
            val r = controller.importVlessLink(link)
            r.onSuccess { profiles.upsert(it) }
            onResult(r)
        }
    }

    fun setActiveProfile(id: String) {
        viewModelScope.launch { profiles.setActiveProfileId(id) }
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

    fun saveSplitTunnel(mode: RoutingMode, packages: List<String>) {
        require(mode == RoutingMode.PER_APP_ALLOWED || mode == RoutingMode.PER_APP_EXCLUDED)
        viewModelScope.launch {
            val cur = profiles.getTunnelPolicy()
            profiles.setTunnelPolicy(
                cur.copy(
                    routingMode = mode,
                    allowedPackageNames = packages.distinct().sorted(),
                ),
            )
        }
    }

    suspend fun getTunnelPolicy(): TunnelPolicy = profiles.getTunnelPolicy()

    fun connect() {
        viewModelScope.launch { graph.sessionController.connectSuspend() }
    }

    fun disconnect() {
        viewModelScope.launch { graph.sessionController.disconnectSuspend() }
    }

    suspend fun singBoxVersion(): String =
        graph.runtime.version()

    class Factory(
        private val graph: NothingVpnGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}
