package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnException
import com.nothing.mobilecore.shared.domain.ensure
import com.nothing.mobilecore.shared.domain.toVpnError
import com.nothing.mobilecore.shared.domain.vpnFail
import com.nothing.mobilecore.shared.domain.validate
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnSessionState
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val uuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private var connectJob: Job? = null
    private val connectMutex = Mutex()

    private val _state = MutableStateFlow<VpnSessionState>(VpnSessionState.Disconnected)
    val state: StateFlow<VpnSessionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<VpnError?>(null)
    val lastError: StateFlow<VpnError?> = _lastError.asStateFlow()

    /** Фактическое состояние туннеля (истина платформы); [state] выравнивается по нему. */
    val tunnelStatus: StateFlow<TunnelStatus> get() = systemVpn.tunnelStatus

    init {
        scope.launch {
            systemVpn.tunnelStatus.collect { onTunnelStatus(it) }
        }
    }

    fun importVlessLink(link: String): Result<VlessProfile> = runCatching {
        VlessLinkParser.parse(link.trim())
    }.onFailure { e ->
        _lastError.value = e.toVpnError()
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            connectSuspend()
        }
    }

    suspend fun connectSuspend(): Result<Unit> = withContext(Dispatchers.Default) {
        connectMutex.withLock {
            if (_state.value is VpnSessionState.Connected && systemVpn.tunnelStatus.value is TunnelStatus.Running) {
                return@withLock Result.success(Unit)
            }
            val policyForAttempts = runCatching { profileRepository.getTunnelPolicy() }.getOrElse { TunnelPolicy() }
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
                        ?: vpnFail(VpnError.NoActiveProfile)
                    val profile = profileRepository.listProfiles().find { it.id == profileId }
                        ?: vpnFail(VpnError.ProfileNotFound)
                    ensure(profile.uuid.isNotBlank()) { VpnError.ProfileSecretUnavailable }
                    ensure(
                        profile.security != "reality" ||
                            (!profile.realityPublicKey.isNullOrBlank() && !profile.realityShortId.isNullOrBlank()),
                    ) { VpnError.ProfileSecretUnavailable }
                    val policy = profileRepository.getTunnelPolicy()
                    policy.validate().getOrElse { throw it }
                    ensure(policy.routingMode != RoutingMode.PER_APP_ALLOWED || policy.allowedPackageNames.isNotEmpty()) {
                        VpnError.PerAppRequiresPackages
                    }
                    val json = configCompiler.compile(profile, policy).getOrElse { throw it }
                    val path = configWriter.writeSessionConfig(json)
                    systemVpn.startVpn(policy, path).getOrElse { throw it }
                    systemVpn.smokeCheck(policy).getOrElse { throw it }
                    ensure(systemVpn.tunnelStatus.value is TunnelStatus.Running) { VpnError.VpnNotRunningAfterConnect }
                    _state.value = VpnSessionState.Connected
                    // Туннель мог упасть между проверкой и записью: сборщик статуса тогда видел
                    // Connecting и пропустил событие, поэтому перепроверяем после записи.
                    if (systemVpn.tunnelStatus.value !is TunnelStatus.Running) {
                        _state.value = VpnSessionState.Connecting
                        vpnFail(VpnError.VpnNotRunningAfterConnect)
                    }
                }
                if (attemptResult.isSuccess) {
                    return@withLock Result.success(Unit)
                }
                lastFailure = attemptResult.exceptionOrNull()
                runCatching { systemVpn.stopVpn() }
                if (attempt < maxAttempts) {
                    delay(1_000L shl (attempt - 1))
                }
            }
            val error = lastFailure?.toVpnError() ?: VpnError.Unknown
            _lastError.value = error
            _state.value = VpnSessionState.Error(error)
            _state.value = VpnSessionState.Disconnected
            Result.failure(lastFailure ?: VpnException(error))
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
            _lastError.value = e.toVpnError()
            _state.value = VpnSessionState.Disconnected
        }
    }

    suspend fun singBoxCoreVersion(): Result<String> = withContext(Dispatchers.Default) {
        runCatching { singBoxCoreInfo.version() }
    }

    suspend fun updateProfileSuspend(profileId: String, draft: VlessProfile): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            ensure(draft.id == profileId) { VpnError.ProfileIdImmutable }
            val existing = profileRepository.listProfiles().find { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
            val normalized = normalizeProfileForSave(draft.copy(id = existing.id))
            validateProfileForSave(normalized).getOrElse { throw it }
            profileRepository.upsert(normalized)
        }.onFailure { e ->
            _lastError.value = e.toVpnError()
        }
    }

    suspend fun deleteProfileSuspend(profileId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val existing = profileRepository.listProfiles().find { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
            val activeId = profileRepository.getActiveProfileId()
            val isDeletingActive = activeId == existing.id
            val stateNow = _state.value
            val vpnIsActive = stateNow is VpnSessionState.Connected ||
                stateNow is VpnSessionState.Connecting ||
                stateNow is VpnSessionState.Recovering ||
                stateNow is VpnSessionState.Disconnecting
            ensure(!(isDeletingActive && vpnIsActive)) { VpnError.ProfileDeleteRequiresDisconnect }
            profileRepository.delete(existing.id)
            if (isDeletingActive) {
                profileRepository.setActiveProfileId(null)
            }
        }.onFailure { e ->
            _lastError.value = e.toVpnError()
        }
    }

    /**
     * Выравнивает [state] по фактическому состоянию туннеля, когда тот меняется вне
     * [connectSuspend]/[disconnectSuspend]: отключение из уведомления, отзыв разрешения VPN,
     * падение сервиса. Во время подключения/отключения события игнорируются — ими управляет
     * сам контроллер (сервис, например, кратко публикует Stopped при перезапуске ядра).
     */
    private fun onTunnelStatus(status: TunnelStatus) {
        when (status) {
            is TunnelStatus.Running -> {
                val current = _state.value
                if (current is VpnSessionState.Disconnected || current is VpnSessionState.Error) {
                    _lastError.value = null
                    _state.value = VpnSessionState.Connected
                }
            }
            is TunnelStatus.Stopped -> {
                if (_state.value is VpnSessionState.Connected) {
                    status.error?.let { _lastError.value = it }
                    _state.value = VpnSessionState.Disconnected
                }
            }
            is TunnelStatus.Starting, TunnelStatus.Stopping -> Unit
        }
    }

    private fun normalizeProfileForSave(profile: VlessProfile): VlessProfile {
        val security = profile.security.trim().lowercase()
        val normalizedRealityPublicKey = profile.realityPublicKey?.trim().takeUnless { it.isNullOrEmpty() }
        val normalizedRealityShortId = profile.realityShortId?.trim().takeUnless { it.isNullOrEmpty() }
        val dropReality = security != "reality"
        return profile.copy(
            name = profile.name.trim(),
            uuid = profile.uuid.trim(),
            host = profile.host.trim(),
            security = security,
            realityPublicKey = if (dropReality) null else normalizedRealityPublicKey,
            realityShortId = if (dropReality) null else normalizedRealityShortId,
        )
    }

    private fun validateProfileForSave(profile: VlessProfile): Result<Unit> = runCatching {
        ensure(profile.name.isNotBlank()) { VpnError.ProfileNameRequired }
        ensure(profile.host.isNotBlank()) { VpnError.ProfileHostRequired }
        ensure(profile.port in 1..65535) { VpnError.ProfilePortInvalid }
        ensure(uuidRegex.matches(profile.uuid)) { VpnError.ProfileUuidInvalid }
        ensure(
            profile.security != "reality" ||
                (!profile.realityPublicKey.isNullOrBlank() && !profile.realityShortId.isNullOrBlank()),
        ) { VpnError.ProfileRealityIncomplete }
    }
}

/** Platform provides writable path for the current session sing-box JSON. */
fun interface SessionConfigWriter {
    fun writeSessionConfig(json: String): String
}
