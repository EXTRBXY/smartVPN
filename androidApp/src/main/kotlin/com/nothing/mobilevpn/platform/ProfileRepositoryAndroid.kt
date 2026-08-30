package com.nothing.mobilecore.platform

import android.content.Context
import android.content.SharedPreferences
import com.nothing.mobilecore.shared.application.SecureStorePort
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val listSerializer = ListSerializer(VlessProfile.serializer())

class ProfileRepositoryAndroid(
    context: Context,
    private val secureStore: SecureStorePort,
) : ProfileRepositoryPort {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = VpnEncryption.profilePreferences(appContext)

    override fun observeProfiles(): Flow<List<VlessProfile>> = callbackFlow {
        ProfileStorageMigration.awaitProfileStorageReady()
        val scope = CoroutineScope(Dispatchers.IO)
        var emitJob: Job? = null
        fun emitList() {
            emitJob?.cancel()
            emitJob = scope.launch {
                trySend(listProfiles())
            }
        }
        emitList()
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ProfileStorageMigration.KEY_PROFILES) emitList()
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            emitJob?.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    override suspend fun listProfiles(): List<VlessProfile> {
        ProfileStorageMigration.awaitProfileStorageReady()
        val raw = prefs.getString(ProfileStorageMigration.KEY_PROFILES, null) ?: "[]"
        val stored = json.decodeFromString(listSerializer, raw)
        val restored = stored.map { restoreSecrets(it) }
        val sanitized = restored.map { stripSecrets(it) }
        if (stored != sanitized) {
            prefs.edit()
                .putString(ProfileStorageMigration.KEY_PROFILES, json.encodeToString(listSerializer, sanitized))
                .apply()
        }
        return restored
    }

    override suspend fun upsert(profile: VlessProfile) {
        ProfileStorageMigration.awaitProfileStorageReady()
        val current =
            json.decodeFromString(
                listSerializer,
                prefs.getString(ProfileStorageMigration.KEY_PROFILES, null) ?: "[]",
            ).toMutableList()
        // The caller is authoritative on upsert. Restoring first would replace an edited
        // UUID/Reality key with the previously stored secret and make the edit a no-op.
        val profileWithSecrets = profile
        saveSecrets(profileWithSecrets)
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = stripSecrets(profileWithSecrets) else current.add(stripSecrets(profileWithSecrets))
        prefs.edit()
            .putString(ProfileStorageMigration.KEY_PROFILES, json.encodeToString(listSerializer, current))
            .apply()
    }

    override suspend fun delete(profileId: String) {
        ProfileStorageMigration.awaitProfileStorageReady()
        val current =
            json.decodeFromString(
                listSerializer,
                prefs.getString(ProfileStorageMigration.KEY_PROFILES, null) ?: "[]",
            ).filterNot { it.id == profileId }
        val ed = prefs.edit().putString(ProfileStorageMigration.KEY_PROFILES, json.encodeToString(listSerializer, current))
        if (prefs.getString(ProfileStorageMigration.KEY_ACTIVE_ID, null) == profileId) {
            ed.remove(ProfileStorageMigration.KEY_ACTIVE_ID)
        }
        ed.apply()
        secureStore.remove(secretKey(profileId))
    }

    override suspend fun getActiveProfileId(): String? {
        ProfileStorageMigration.awaitProfileStorageReady()
        return prefs.getString(ProfileStorageMigration.KEY_ACTIVE_ID, null)
    }

    override suspend fun setActiveProfileId(id: String?) {
        ProfileStorageMigration.awaitProfileStorageReady()
        val ed = prefs.edit()
        if (id == null) ed.remove(ProfileStorageMigration.KEY_ACTIVE_ID) else ed.putString(ProfileStorageMigration.KEY_ACTIVE_ID, id)
        ed.apply()
    }

    override suspend fun getTunnelPolicy(): TunnelPolicy {
        ProfileStorageMigration.awaitProfileStorageReady()
        val raw = prefs.getString(ProfileStorageMigration.KEY_POLICY, null) ?: return TunnelPolicy()
        return json.decodeFromString(TunnelPolicy.serializer(), raw)
    }

    override suspend fun setTunnelPolicy(policy: TunnelPolicy) {
        ProfileStorageMigration.awaitProfileStorageReady()
        prefs.edit()
            .putString(ProfileStorageMigration.KEY_POLICY, json.encodeToString(TunnelPolicy.serializer(), policy))
            .apply()
    }

    private suspend fun restoreSecrets(profile: VlessProfile): VlessProfile {
        val inlineSecrets = ProfileSecrets(
            profileId = profile.id,
            uuid = profile.uuid.takeIf { it.isNotBlank() },
            realityPublicKey = profile.realityPublicKey,
            realityShortId = profile.realityShortId,
        )
        val secretRaw = secureStore.read(secretKey(profile.id))
        val stored = secretRaw
            ?.let { runCatching { json.decodeFromString(ProfileSecrets.serializer(), it) }.getOrNull() }
        val resolved = ProfileSecretIntegrity.resolve(
            profileId = profile.id,
            rawSecretExists = secretRaw != null,
            stored = stored,
            inline = inlineSecrets,
        )
        if (resolved.purgeSecureStore) {
            secureStore.remove(secretKey(profile.id))
        }
        if (resolved.migrateInlineToSecureStore) {
            saveSecrets(profile.copy(
                uuid = inlineSecrets.uuid ?: "",
                realityPublicKey = inlineSecrets.realityPublicKey,
                realityShortId = inlineSecrets.realityShortId,
            ))
        }
        return profile.copy(
            uuid = resolved.secrets?.uuid ?: "",
            realityPublicKey = resolved.secrets?.realityPublicKey,
            realityShortId = resolved.secrets?.realityShortId,
        )
    }

    private suspend fun saveSecrets(profile: VlessProfile) {
        if (profile.id.isBlank()) return
        val secrets = ProfileSecrets(
            profileId = profile.id,
            uuid = profile.uuid.takeIf { it.isNotBlank() },
            realityPublicKey = profile.realityPublicKey,
            realityShortId = profile.realityShortId,
        )
        if (secrets.uuid == null && secrets.realityPublicKey == null && secrets.realityShortId == null) {
            secureStore.remove(secretKey(profile.id))
            return
        }
        secureStore.save(secretKey(profile.id), json.encodeToString(ProfileSecrets.serializer(), secrets))
    }

    private fun stripSecrets(profile: VlessProfile): VlessProfile = profile.copy(
        uuid = "",
        realityPublicKey = null,
        realityShortId = null,
    )

    private fun secretKey(profileId: String): String = "profile_secret:$profileId"

}

@kotlinx.serialization.Serializable
internal data class ProfileSecrets(
    val profileId: String? = null,
    val uuid: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
)

internal object ProfileSecretIntegrity {
    data class Resolution(
        val secrets: ProfileSecrets?,
        val migrateInlineToSecureStore: Boolean,
        val purgeSecureStore: Boolean,
    )

    fun resolve(
        profileId: String,
        rawSecretExists: Boolean,
        stored: ProfileSecrets?,
        inline: ProfileSecrets,
    ): Resolution {
        if (rawSecretExists && stored == null) {
            return Resolution(secrets = null, migrateInlineToSecureStore = false, purgeSecureStore = true)
        }
        if (stored != null) {
            val idMatches = stored.profileId.isNullOrBlank() || stored.profileId == profileId
            val hasUuid = !stored.uuid.isNullOrBlank()
            val realityIsConsistent =
                (stored.realityPublicKey.isNullOrBlank() && stored.realityShortId.isNullOrBlank()) ||
                    (!stored.realityPublicKey.isNullOrBlank() && !stored.realityShortId.isNullOrBlank())
            if (idMatches && hasUuid && realityIsConsistent) {
                return Resolution(secrets = stored, migrateInlineToSecureStore = false, purgeSecureStore = false)
            }
            return Resolution(secrets = null, migrateInlineToSecureStore = false, purgeSecureStore = true)
        }

        val hasInlineSecret =
            !inline.uuid.isNullOrBlank() ||
                !inline.realityPublicKey.isNullOrBlank() ||
                !inline.realityShortId.isNullOrBlank()
        if (hasInlineSecret) {
            return Resolution(secrets = inline, migrateInlineToSecureStore = true, purgeSecureStore = false)
        }

        return Resolution(secrets = null, migrateInlineToSecureStore = false, purgeSecureStore = false)
    }
}
