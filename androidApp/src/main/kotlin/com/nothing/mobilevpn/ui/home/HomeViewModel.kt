package com.nothing.mobilecore.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.platform.TunnelStats
import com.nothing.mobilecore.platform.TunnelStatsMonitor
import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.shared.domain.vpnFail
import com.nothing.mobilecore.shared.infrastructure.VlessLinkFormatter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class HomeViewModel(
    private val controller: VpnSessionController,
    private val profiles: ProfileRepositoryPort,
    private val preferences: AppPreferences,
    private val probe: ConnectivityProbePort,
    statsMonitor: TunnelStatsMonitor,
) : ViewModel() {

    // Напрямую, без stateIn с начальным Disconnected: иначе при открытии экрана во время
    // активного туннеля на мгновение мелькало «Отключено».
    val sessionState: StateFlow<VpnSessionState> = controller.state
    val tunnelStatus: StateFlow<TunnelStatus> = controller.tunnelStatus
    val lastError: StateFlow<VpnError?> = controller.lastError
    val tunnelStats: StateFlow<TunnelStats> = statsMonitor.stats

    val profileList: StateFlow<List<VlessProfile>> = profiles.observeProfiles().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    init {
        // Активный профиль меняют и другие экраны (импорт, удаление, QR): перечитываем при
        // любом изменении списка профилей.
        profiles.observeProfiles()
            .onEach { _activeProfileId.value = profiles.getActiveProfileId() }
            .launchIn(viewModelScope)
    }

    fun connect() {
        preferences.connectionRequested = true
        controller.connect()
    }

    fun disconnect() {
        preferences.connectionRequested = false
        viewModelScope.launch { controller.disconnectSuspend() }
    }

    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            profiles.setActiveProfileId(id)
            _activeProfileId.value = id
        }
    }

    fun switchActiveProfileAndReconnect(id: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                controller.disconnectSuspend().getOrThrow()
                profiles.setActiveProfileId(id)
                _activeProfileId.value = id
                preferences.connectionRequested = true
                controller.connectSuspend().getOrThrow()
            }
            onResult(result)
        }
    }

    suspend fun getTunnelPolicy(): TunnelPolicy = profiles.getTunnelPolicy()

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

    /** Время TCP-соединения с сервером профиля; не проверка VLESS/Reality. */
    suspend fun testProfileLatency(profileId: String): Result<Long> = runCatching {
        val profile = findProfile(profileId)
        probe.tcpConnectLatency(profile.host, profile.port).getOrThrow()
    }

    suspend fun findFastestProfile(): Result<Pair<String, Long>> = runCatching {
        coroutineScope {
            profiles.listProfiles().map { profile ->
                async { testProfileLatency(profile.id).getOrNull()?.let { profile.id to it } }
            }.awaitAll().filterNotNull().minByOrNull { it.second } ?: vpnFail(VpnError.NoReachableProfile)
        }
    }

    suspend fun exportProfile(profileId: String): Result<String> = runCatching {
        VlessLinkFormatter.format(findProfile(profileId))
    }

    /** [copyName] строит имя копии из имени исходного профиля (текст — из ресурсов UI). */
    fun duplicateProfile(profileId: String, copyName: (String) -> String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                val profile = findProfile(profileId)
                profiles.upsert(profile.copy(id = UUID.randomUUID().toString(), name = copyName(profile.name), favorite = false))
            })
        }
    }

    fun setProfileFavorite(profileId: String, favorite: Boolean) {
        viewModelScope.launch {
            val profile = profiles.listProfiles().firstOrNull { it.id == profileId } ?: return@launch
            profiles.upsert(profile.copy(favorite = favorite))
        }
    }

    private suspend fun findProfile(profileId: String): VlessProfile =
        profiles.listProfiles().firstOrNull { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
}
