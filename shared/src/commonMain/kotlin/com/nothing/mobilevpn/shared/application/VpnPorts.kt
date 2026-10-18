package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Только чтение сведений о встроенном ядре sing-box (libbox): версия и т.п.
 * Запуск/остановка трафика и [CommandServer] — у [SystemVpnPort] и платформенного VPN-сервиса.
 */
fun interface SingBoxCoreInfoPort {
    suspend fun version(): String
}

interface SingBoxConfigCompilerPort {
    fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String>
}

/** Persists imported profiles and tunnel policy (no secrets in plain logs). */
interface ProfileRepositoryPort {
    fun observeProfiles(): Flow<List<VlessProfile>>
    suspend fun listProfiles(): List<VlessProfile>
    suspend fun upsert(profile: VlessProfile)
    suspend fun delete(profileId: String)
    suspend fun getActiveProfileId(): String?
    suspend fun setActiveProfileId(id: String?)
    suspend fun getTunnelPolicy(): TunnelPolicy

    /** Политика и её изменения. По умолчанию — однократное значение (для простых реализаций). */
    fun observeTunnelPolicy(): Flow<TunnelPolicy> = flow { emit(getTunnelPolicy()) }
    suspend fun setTunnelPolicy(policy: TunnelPolicy)
}

/**
 * Platform VPN (Android VpnService / iOS NE). Implementations apply [TunnelPolicy] and system lifecycle.
 */
interface SystemVpnPort {
    /** Фактическое состояние туннеля; единственный источник истины (см. [TunnelStatus]). */
    val tunnelStatus: StateFlow<TunnelStatus>
    suspend fun startVpn(policy: TunnelPolicy, singBoxConfigPath: String): Result<Unit>
    suspend fun stopVpn()
    suspend fun stopVpnAndAwait(): Result<Unit>
    suspend fun smokeCheck(policy: TunnelPolicy): Result<Unit>
    fun vpnPermissionNeeded(): Boolean
}

/**
 * Сетевые проверки для UI (задержка, внешний IP). Платформа выполняет их вне туннеля или
 * через него — как получится по текущей маршрутизации.
 *
 * Важно: [tcpConnectLatency] — только время TCP-соединения с host:port. Это не проверка
 * VLESS/Reality/TLS и не гарантия, что профиль рабочий.
 */
interface ConnectivityProbePort {
    suspend fun tcpConnectLatency(host: String, port: Int): Result<Long>
    suspend fun externalIp(): Result<String>
}
