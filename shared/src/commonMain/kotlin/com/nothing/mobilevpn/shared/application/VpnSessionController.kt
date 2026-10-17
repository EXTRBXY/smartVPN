package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnException
import com.nothing.mobilecore.shared.domain.ensure
import com.nothing.mobilecore.shared.domain.toVpnError
import com.nothing.mobilecore.shared.domain.vpnFail
import com.nothing.mobilecore.shared.domain.validate
import com.nothing.mobilecore.shared.domain.hasCompleteRealityKeys
import com.nothing.mobilecore.shared.domain.VpnSessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * Сессия VPN: подключение с повторами, отключение и выравнивание состояния UI по фактическому
 * [TunnelStatus]. Операции с профилями — в [ProfileService].
 */
class VpnSessionController(
    private val profileRepository: ProfileRepositoryPort,
    private val configCompiler: SingBoxConfigCompilerPort,
    private val singBoxCoreInfo: SingBoxCoreInfoPort,
    private val systemVpn: SystemVpnPort,
    private val configWriter: SessionConfigWriter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    /** Журнал событий для диагностики; платформа обязана редактировать секреты (см. LogRedaction). */
    private val eventLog: (String) -> Unit = {},
) {
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

    /** Сессия занята: подключено, подключается или отключается. */
    val isBusy: Boolean
        get() = when (_state.value) {
            VpnSessionState.Connected, VpnSessionState.Connecting, is VpnSessionState.Recovering,
            VpnSessionState.Disconnecting -> true
            VpnSessionState.Disconnected, is VpnSessionState.Error -> false
        }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            connectSuspend()
        }
    }

    suspend fun connectSuspend(): Result<Unit> = withContext(dispatcher) {
        connectMutex.withLock {
            if (_state.value is VpnSessionState.Connected && systemVpn.tunnelStatus.value is TunnelStatus.Running) {
                return@withLock Result.success(Unit)
            }
            val policyForAttempts = runCatching { profileRepository.getTunnelPolicy() }.getOrElse { TunnelPolicy() }
            val maxAttempts = if (policyForAttempts.singleConnectAttempt) 1 else MAX_CONNECT_ATTEMPTS
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
                    ensure(profile.hasCompleteRealityKeys()) { VpnError.ProfileSecretUnavailable }
                    val policy = profileRepository.getTunnelPolicy()
                    policy.validate().getOrThrow()
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
                lastFailure?.let { eventLog("Connect attempt $attempt/$maxAttempts failed: ${describeFailure(it)}") }
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

    suspend fun disconnectSuspend(): Result<Unit> = withContext(dispatcher) {
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

    suspend fun singBoxCoreVersion(): Result<String> = withContext(dispatcher) {
        runCatching { singBoxCoreInfo.version() }
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

    /** Код ошибки и цепочка причин: по одному коду не понять, что именно упало (напр. HTTP-проверка). */
    private fun describeFailure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { t ->
            "${t::class.simpleName}: ${t.message.orEmpty()}"
        }
        return "${error.toVpnError().code} [$chain]"
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3
    }
}

/** Platform provides writable path for the current session sing-box JSON. */
fun interface SessionConfigWriter {
    fun writeSessionConfig(json: String): String
}
