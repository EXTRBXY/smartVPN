package com.nothing.mobilecore.platform

import com.nothing.mobilecore.platform.storage.VaultStore
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Профили, активный профиль и политика туннеля — в зашифрованном [VaultStore] (вместе с
 * секретами). Чтения не пишут; изменения сначала атомарно записываются, ошибка записи
 * пробрасывается вызывающему. Все методы ждут загрузки хранилища при старте процесса.
 */
internal class ProfileRepositoryAndroid(
    private val store: VaultStore,
) : ProfileRepositoryPort {

    override fun observeProfiles(): Flow<List<VlessProfile>> = flow {
        store.awaitReady()
        emitAll(store.state.map { it.profiles }.distinctUntilChanged())
    }

    override suspend fun listProfiles(): List<VlessProfile> {
        store.awaitReady()
        return store.state.value.profiles
    }

    override suspend fun upsert(profile: VlessProfile) {
        store.update { vault ->
            val index = vault.profiles.indexOfFirst { it.id == profile.id }
            val updated = if (index >= 0) {
                vault.profiles.toMutableList().also { it[index] = profile }
            } else {
                vault.profiles + profile
            }
            vault.copy(profiles = updated)
        }
    }

    override suspend fun delete(profileId: String) {
        store.update { vault ->
            vault.copy(
                profiles = vault.profiles.filterNot { it.id == profileId },
                activeProfileId = vault.activeProfileId.takeUnless { it == profileId },
            )
        }
    }

    override suspend fun getActiveProfileId(): String? {
        store.awaitReady()
        return store.state.value.activeProfileId
    }

    override suspend fun setActiveProfileId(id: String?) {
        store.update { it.copy(activeProfileId = id) }
    }

    override suspend fun getTunnelPolicy(): TunnelPolicy {
        store.awaitReady()
        return store.state.value.policy
    }

    override suspend fun setTunnelPolicy(policy: TunnelPolicy) {
        store.update { it.copy(policy = policy) }
    }
}
