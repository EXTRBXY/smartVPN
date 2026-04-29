package com.nothing.mobilevpn.shared.application

import com.nothing.mobilevpn.shared.domain.RoutingMode
import com.nothing.mobilevpn.shared.domain.VlessProfile
import com.nothing.mobilevpn.shared.domain.VpnSessionState
import com.nothing.mobilevpn.shared.infrastructure.LogRedaction
import com.nothing.mobilevpn.shared.infrastructure.VlessLinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnSessionController(
    private val profileRepository: ProfileRepositoryPort,
    private val configCompiler: SingBoxConfigCompilerPort,
    private val singBoxCoreInfo: SingBoxCoreInfoPort,
    private val systemVpn: SystemVpnPort,
    private val configWriter: SessionConfigWriter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<VpnSessionState>(VpnSessionState.Disconnected)
    val state: StateFlow<VpnSessionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun importVlessLink(link: String): Result<VlessProfile> = runCatching {
        VlessLinkParser.parse(link.trim())
    }.onFailure { e ->
        _lastError.value = e.message?.let { LogRedaction.redactUserMessage(it) }
    }

    fun connect() {
        scope.launch {
            connectSuspend()
        }
    }

    suspend fun connectSuspend(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            if (_state.value is VpnSessionState.Connecting || _state.value is VpnSessionState.Connected) {
                return@runCatching
            }
            _state.value = VpnSessionState.Connecting
            _lastError.value = null
            val profileId = profileRepository.getActiveProfileId()
                ?: error("no_active_profile")
            val profile = profileRepository.listProfiles().find { it.id == profileId }
                ?: error("profile_not_found")
            val policy = profileRepository.getTunnelPolicy()
            if (policy.routingMode == RoutingMode.PER_APP_ALLOWED && policy.allowedPackageNames.isEmpty()) {
                error("per_app_requires_packages")
            }
            val json = configCompiler.compile(profile, policy)
            val path = configWriter.writeSessionConfig(json)
            systemVpn.startVpn(policy, path).getOrElse { throw it }
            _state.value = VpnSessionState.Connected
        }.onFailure { e ->
            val msg = e.message ?: "connect_failed"
            _lastError.value = LogRedaction.redactUserMessage(msg)
            _state.value = VpnSessionState.Error(msg)
            runCatching { systemVpn.stopVpn() }
            _state.value = VpnSessionState.Disconnected
        }
    }

    suspend fun disconnectSuspend(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            _state.value = VpnSessionState.Disconnecting
            systemVpn.stopVpn()
            _state.value = VpnSessionState.Disconnected
        }.onFailure { e ->
            _lastError.value = LogRedaction.redactUserMessage(e.message ?: "disconnect_failed")
            _state.value = VpnSessionState.Disconnected
        }
    }

    suspend fun singBoxCoreVersion(): Result<String> = withContext(Dispatchers.Default) {
        runCatching { singBoxCoreInfo.version() }
    }

    /**
     * Туннель поднят или остановлен вне этого контроллера (плитка Quick Settings и т.п.).
     * Не вызывает [SystemVpnPort], только выравнивает [state] с реальностью.
     */
    fun applyExternalTunnelRunning(running: Boolean) {
        if (running) {
            _lastError.value = null
            if (_state.value !is VpnSessionState.Connected) {
                _state.value = VpnSessionState.Connected
            }
        } else {
            if (_state.value is VpnSessionState.Disconnected) return
            _state.value = VpnSessionState.Disconnected
        }
    }
}

/** Platform provides writable path for the current session sing-box JSON. */
fun interface SessionConfigWriter {
    fun writeSessionConfig(json: String): String
}
