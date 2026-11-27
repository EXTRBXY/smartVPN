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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
    private val connectMutex = Mutex()
    private val attemptsMutex = Mutex()
    private val stopGeneration = MutableStateFlow(0L)
    private val connectAttempts = mutableSetOf<Job>()
    private var stopsInProgress = 0

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
        val requestGeneration = stopGeneration.value
        scope.launch { connectForGeneration(requestGeneration) }
    }

    suspend fun connectSuspend(): Result<Unit> = connectForGeneration(stopGeneration.value)

    private suspend fun connectForGeneration(requestGeneration: Long): Result<Unit> = withContext(dispatcher) {
        val job = currentCoroutineContext().job
        attemptsMutex.withLock {
            if (stopsInProgress > 0 || stopGeneration.value != requestGeneration) {
                throw CancellationException("VPN connect superseded by disconnect")
            }
            connectAttempts.add(job)
        }
        try {
            connectMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (_state.value is VpnSessionState.Connected && systemVpn.tunnelStatus.value is TunnelStatus.Running) {
                    return@withLock Result.success(Unit)
                }

                try {
                    runConnectAttempts()
                } catch (error: CancellationException) {
                    // Явное отключение само остановит сервис после выхода из connectMutex.
                    if (stopGeneration.value == requestGeneration) {
                        withContext(NonCancellable) {
                            runCatching { systemVpn.stopVpnAndAwait() }
                            if (stopGeneration.value == requestGeneration) {
                                _state.value = VpnSessionState.Disconnected
                            }
                        }
                    }
                    throw error
                }
            }
        } finally {
            withContext(NonCancellable) {
                attemptsMutex.withLock { connectAttempts.remove(job) }
            }
        }
    }

    private suspend fun runConnectAttempts(): Result<Unit> {
        val policyForAttempts = runCatching { profileRepository.getTunnelPolicy() }.getOrElse { error ->
            if (error is CancellationException) throw error
            TunnelPolicy()
        }
        val maxAttempts = if (policyForAttempts.singleConnectAttempt) 1 else MAX_CONNECT_ATTEMPTS
        var lastFailure: Throwable? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
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
                currentCoroutineContext().ensureActive()
                ensure(systemVpn.tunnelStatus.value is TunnelStatus.Running) { VpnError.VpnNotRunningAfterConnect }
                _state.value = VpnSessionState.Connected
                // Туннель мог упасть до публикации Connected, когда сборщик пропускал Stopped.
                if (systemVpn.tunnelStatus.value !is TunnelStatus.Running) {
                    _state.value = VpnSessionState.Connecting
                    vpnFail(VpnError.VpnNotRunningAfterConnect)
                }
            }
            currentCoroutineContext().ensureActive()
            if (attemptResult.isSuccess) return Result.success(Unit)

            lastFailure = attemptResult.exceptionOrNull()
            if (lastFailure is CancellationException) throw lastFailure
            lastFailure?.let { eventLog("Connect attempt $attempt/$maxAttempts failed: ${describeFailure(it)}") }
            val stopFailure = systemVpn.stopVpnAndAwait().exceptionOrNull()
            if (stopFailure != null) {
                if (stopFailure is CancellationException) throw stopFailure
                lastFailure = stopFailure
                break
            }
            if (attempt < maxAttempts) delay(1_000L shl (attempt - 1))
        }
        val error = lastFailure?.toVpnError() ?: VpnError.Unknown
        _lastError.value = error
        _state.value = VpnSessionState.Error(error)
        _state.value = VpnSessionState.Disconnected
        return Result.failure(lastFailure ?: VpnException(error))
    }

    suspend fun disconnectSuspend(): Result<Unit> = withContext(dispatcher) {
        withContext(NonCancellable) {
            attemptsMutex.withLock {
                stopsInProgress++
                stopGeneration.value++
                connectAttempts.forEach { it.cancel() }
            }
            try {
                connectMutex.withLock {
                    _state.value = VpnSessionState.Disconnecting
                    runCatching { systemVpn.stopVpnAndAwait().getOrElse { throw it } }
                        .onFailure { _lastError.value = it.toVpnError() }
                        .also { _state.value = VpnSessionState.Disconnected }
                }
            } finally {
                attemptsMutex.withLock { stopsInProgress-- }
            }
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
