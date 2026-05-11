package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.validate
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.shared.infrastructure.LogRedaction
import com.nothing.mobilecore.shared.infrastructure.VlessLinkParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VpnSessionController(
    private val profileRepository: ProfileRepositoryPort,
    private val configCompiler: SingBoxConfigCompilerPort,
    private val singBoxCoreInfo: SingBoxCoreInfoPort,
    private val systemVpn: SystemVpnPort,
    private val configWriter: SessionConfigWriter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectJob: Job? = null
    private val connectMutex = Mutex()

    private val _state = MutableStateFlow<VpnSessionState>(VpnSessionState.Disconnected)
    val state: StateFlow<VpnSessionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private var lastPolicyFailClosed: Boolean = true

    fun importVlessLink(link: String): Result<VlessProfile> = runCatching {
        VlessLinkParser.parse(link.trim())
    }.onFailure { e ->
        _lastError.value = e.message?.let { LogRedaction.redactUserMessage(it) }
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            connectSuspend()
        }
    }

    suspend fun connectSuspend(): Result<Unit> = withContext(Dispatchers.Default) {
        connectMutex.withLock {
            if (_state.value is VpnSessionState.Connecting || _state.value is VpnSessionState.Connected) {
                return@withLock Result.success(Unit)
            }
            val policyForAttempts = runCatching { profileRepository.getTunnelPolicy() }.getOrElse { TunnelPolicy() }
            lastPolicyFailClosed = policyForAttempts.failClosed
            val maxAttempts = if (policyForAttempts.failClosed) 1 else 3
            var lastFailure: Throwable? = null
            repeat(maxAttempts) { index ->
                val attempt = index + 1
                _state.value = if (attempt == 1) {
                    VpnSessionState.Connecting
                } else {
                    VpnSessionState.Recovering(attempt = attempt, maxAttempts = maxAttempts)
                }
                _lastError.value = null
                val attemptResult = runCatching {
                    val profileId = profileRepository.getActiveProfileId()
                        ?: error("no_active_profile")
                    val profile = profileRepository.listProfiles().find { it.id == profileId }
                        ?: error("profile_not_found")
                    if (profile.uuid.isBlank()) {
                        error("profile_secret_unavailable")
                    }
                    if (profile.security == "reality" &&
                        (profile.realityPublicKey.isNullOrBlank() || profile.realityShortId.isNullOrBlank())
                    ) {
                        error("profile_secret_unavailable")
                    }
                    val policy = profileRepository.getTunnelPolicy()
                    policy.validate().getOrElse { throw it }
                    if (policy.routingMode == RoutingMode.PER_APP_ALLOWED && policy.allowedPackageNames.isEmpty()) {
                        error("per_app_requires_packages")
                    }
                    val json = configCompiler.compile(profile, policy).getOrElse { throw it }
                    val path = configWriter.writeSessionConfig(json)
                    systemVpn.startVpn(policy, path).getOrElse { throw it }
                    systemVpn.smokeCheck(policy).getOrElse { throw it }
                    _state.value = VpnSessionState.Connected
                }
                if (attemptResult.isSuccess) {
                    return@withLock Result.success(Unit)
                }
                lastFailure = attemptResult.exceptionOrNull()
                runCatching { systemVpn.stopVpn() }
                if (attempt < maxAttempts) {
                    delay(300L * attempt)
                }
            }
            val msg = lastFailure?.message ?: "connect_failed"
            _lastError.value = LogRedaction.redactUserMessage(msg)
            _state.value = VpnSessionState.Error(msg)
            _state.value = VpnSessionState.Disconnected
            Result.failure(lastFailure ?: IllegalStateException("connect_failed"))
        }
    }

    suspend fun disconnectSuspend(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            connectJob?.cancel()
            connectJob = null
            _state.value = VpnSessionState.Disconnecting
            systemVpn.stopVpnAndAwait().getOrElse { throw it }
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
            if (_state.value is VpnSessionState.Disconnected || _state.value is VpnSessionState.Disconnecting) return
            if (lastPolicyFailClosed) {
                _lastError.value = LogRedaction.redactUserMessage("vpn_not_running_after_connect")
            }
            _state.value = VpnSessionState.Disconnected
        }
    }
}

/** Platform provides writable path for the current session sing-box JSON. */
fun interface SessionConfigWriter {
    fun writeSessionConfig(json: String): String
}
