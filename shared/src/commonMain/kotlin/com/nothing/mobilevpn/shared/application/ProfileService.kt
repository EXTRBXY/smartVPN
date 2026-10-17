package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VlessSecurity
import com.nothing.mobilecore.shared.domain.VlessUuid
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.ensure
import com.nothing.mobilecore.shared.domain.hasCompleteRealityKeys
import com.nothing.mobilecore.shared.domain.vpnFail
import com.nothing.mobilecore.shared.infrastructure.VlessLinkParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Операции с профилями: импорт ссылки, правка, удаление. Ошибки возвращаются в [Result] как
 * [com.nothing.mobilecore.shared.domain.VpnException]; общий `lastError` сессии они не трогают.
 *
 * [isTunnelBusy] — активна ли сессия (подключено/подключается/отключается): активный профиль
 * в это время удалять нельзя.
 */
class ProfileService(
    private val profiles: ProfileRepositoryPort,
    private val isTunnelBusy: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Разбор ссылки vless:// без сохранения. */
    fun parseLink(link: String): Result<VlessProfile> = runCatching { VlessLinkParser.parse(link.trim()) }

    /** Разбор и сохранение; при [activate] профиль становится активным. */
    suspend fun importLink(link: String, activate: Boolean): Result<VlessProfile> = withContext(dispatcher) {
        runCatching {
            parseLink(link).getOrThrow().also { profile ->
                profiles.upsert(profile)
                if (activate) profiles.setActiveProfileId(profile.id)
            }
        }
    }

    suspend fun update(profileId: String, draft: VlessProfile): Result<Unit> = withContext(dispatcher) {
        runCatching {
            ensure(draft.id == profileId) { VpnError.ProfileIdImmutable }
            ensure(profiles.listProfiles().any { it.id == profileId }) { VpnError.ProfileNotFound }
            val normalized = normalize(draft)
            validate(normalized)
            profiles.upsert(normalized)
        }
    }

    suspend fun delete(profileId: String): Result<Unit> = withContext(dispatcher) {
        runCatching {
            val existing = profiles.listProfiles().find { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
            val isDeletingActive = profiles.getActiveProfileId() == existing.id
            ensure(!(isDeletingActive && isTunnelBusy())) { VpnError.ProfileDeleteRequiresDisconnect }
            profiles.delete(existing.id)
            if (isDeletingActive) profiles.setActiveProfileId(null)
        }
    }

    private fun normalize(profile: VlessProfile): VlessProfile {
        val keepReality = profile.security == VlessSecurity.REALITY
        return profile.copy(
            name = profile.name.trim(),
            uuid = profile.uuid.trim(),
            host = profile.host.trim(),
            realityPublicKey = profile.realityPublicKey?.trim()?.takeIf { keepReality && it.isNotEmpty() },
            realityShortId = profile.realityShortId?.trim()?.takeIf { keepReality && it.isNotEmpty() },
        )
    }

    private fun validate(profile: VlessProfile) {
        ensure(profile.name.isNotBlank()) { VpnError.ProfileNameRequired }
        ensure(profile.host.isNotBlank()) { VpnError.ProfileHostRequired }
        ensure(profile.port in 1..65535) { VpnError.ProfilePortInvalid }
        ensure(VlessUuid.isValid(profile.uuid)) { VpnError.ProfileUuidInvalid }
        ensure(profile.hasCompleteRealityKeys()) { VpnError.ProfileRealityIncomplete }
    }
}
