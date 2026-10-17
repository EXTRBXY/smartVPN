package com.nothing.mobilecore.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileEditViewModel(
    private val controller: VpnSessionController,
    profiles: ProfileRepositoryPort,
    private val probe: ConnectivityProbePort,
) : ViewModel() {

    val profileList: StateFlow<List<VlessProfile>> = profiles.observeProfiles().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun updateProfile(profileId: String, draft: VlessProfile, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(controller.updateProfileSuspend(profileId, draft)) }
    }

    fun deleteProfile(profileId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(controller.deleteProfileSuspend(profileId)) }
    }

    /** Время TCP-соединения с host:port из формы; не проверка VLESS/Reality. */
    suspend fun testServerEndpoint(host: String, port: Int): Result<Long> = probe.tcpConnectLatency(host, port)
}
