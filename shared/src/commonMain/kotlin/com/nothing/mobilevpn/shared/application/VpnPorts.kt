package com.nothing.mobilevpn.shared.application

import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.shared.domain.VlessProfile
import kotlinx.coroutines.flow.Flow

interface SingBoxRuntimePort {
    suspend fun start(configPath: String)
    suspend fun stop()
    suspend fun version(): String
}

interface SecureStorePort {
    suspend fun save(key: String, value: String)
    suspend fun read(key: String): String?
    suspend fun remove(key: String)
}

interface SingBoxConfigCompilerPort {
    fun compile(profile: VlessProfile, policy: TunnelPolicy): String
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
    suspend fun setTunnelPolicy(policy: TunnelPolicy)
}

/**
 * Platform VPN (Android VpnService / iOS NE). Implementations apply [TunnelPolicy] and system lifecycle.
 */
interface SystemVpnPort {
    suspend fun startVpn(policy: TunnelPolicy, singBoxConfigPath: String): Result<Unit>
    suspend fun stopVpn()
    fun vpnPermissionNeeded(): Boolean
}
