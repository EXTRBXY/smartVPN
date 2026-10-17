package com.nothing.mobilecore.platform.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Чтение хранилища до перехода на [VaultFile]: два EncryptedSharedPreferences
 * (`nothing_vpn_profiles` — список без секретов, активный профиль, политика;
 * `nothing_vpn_secure` — секреты по профилям) и совсем старый незашифрованный DataStore.
 * Нужен только для миграции; вместе с ним в проекте остаётся `security-crypto`.
 */
internal class AndroidLegacySource(context: Context) : LegacySource {
    private val app = context.applicationContext
    private val sharedPrefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
    private val profilesXml = File(sharedPrefsDir, "$PROFILES_PREFS.xml")
    private val secureXml = File(sharedPrefsDir, "$SECURE_PREFS.xml")
    private val plainDataStore = File(app.filesDir, "datastore/nothing_mobile_vpn.preferences_pb")

    override fun read(): StoredVault? {
        // Проверяем файлы до открытия EncryptedSharedPreferences: открытие создаёт пустой файл.
        if (!profilesXml.exists() && !secureXml.exists() && !plainDataStore.exists()) return null
        val plain = if (plainDataStore.exists()) readPlainDataStore() else null
        val profiles = if (profilesXml.exists()) encryptedPrefs(PROFILES_PREFS) else null
        val secure = if (secureXml.exists()) encryptedPrefs(SECURE_PREFS) else null
        return LegacyDecoder.decode(
            profilesJson = profiles?.getString(KEY_PROFILES, null) ?: plain?.profiles,
            activeProfileId = profiles?.getString(KEY_ACTIVE_ID, null) ?: plain?.activeId,
            policyJson = profiles?.getString(KEY_POLICY, null) ?: plain?.policy,
            secretFor = { id -> secure?.getString(LegacyDecoder.secretKey(id), null) },
        )
    }

    override fun delete() {
        app.deleteSharedPreferences(PROFILES_PREFS)
        app.deleteSharedPreferences(SECURE_PREFS)
        val dir = plainDataStore.parentFile ?: return
        listOf("", "-shm", "-wal", "-journal").forEach { suffix -> File(dir, plainDataStore.name + suffix).delete() }
    }

    private fun encryptedPrefs(name: String) = EncryptedSharedPreferences.create(
        app,
        name,
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private data class PlainSnapshot(val profiles: String?, val activeId: String?, val policy: String?)

    private fun readPlainDataStore(): PlainSnapshot = runBlocking {
        val snap = PreferenceDataStoreFactory.create(produceFile = { plainDataStore }).data.first()
        PlainSnapshot(
            profiles = snap[stringPreferencesKey(KEY_PROFILES)],
            activeId = snap[stringPreferencesKey(KEY_ACTIVE_ID)],
            policy = snap[stringPreferencesKey(KEY_POLICY)],
        )
    }

    private companion object {
        const val PROFILES_PREFS = "nothing_vpn_profiles"
        const val SECURE_PREFS = "nothing_vpn_secure"
        const val KEY_PROFILES = "profiles_json"
        const val KEY_ACTIVE_ID = "active_profile_id"
        const val KEY_POLICY = "tunnel_policy_json"
    }
}

/** Чистая сборка [StoredVault] из старых строк — без Android, покрыта тестами. */
internal object LegacyDecoder {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun secretKey(profileId: String) = "profile_secret:$profileId"

    /** null — в старом хранилище нет ни профилей, ни активного профиля, ни политики. */
    fun decode(
        profilesJson: String?,
        activeProfileId: String?,
        policyJson: String?,
        secretFor: (String) -> String?,
    ): StoredVault? {
        if (profilesJson == null && activeProfileId == null && policyJson == null) return null
        val stored = profilesJson?.let { json.decodeFromString(ListSerializer(VlessProfile.serializer()), it) }.orEmpty()
        val profiles = stored.map { restoreSecrets(it, secretFor(it.id)) }
        val policy = policyJson?.let { json.decodeFromString(TunnelPolicy.serializer(), it) } ?: TunnelPolicy()
        return StoredVault(profiles = profiles, activeProfileId = activeProfileId, policy = policy)
    }

    /** Секреты: из отдельного хранилища, а у совсем старых записей — прямо в профиле. */
    private fun restoreSecrets(profile: VlessProfile, secretRaw: String?): VlessProfile {
        val inline = ProfileSecrets(
            profileId = profile.id,
            uuid = profile.uuid.takeIf { it.isNotBlank() },
            realityPublicKey = profile.realityPublicKey,
            realityShortId = profile.realityShortId,
        )
        val stored = secretRaw?.let { runCatching { json.decodeFromString(ProfileSecrets.serializer(), it) }.getOrNull() }
        val resolved = ProfileSecretIntegrity.resolve(profile.id, secretRaw != null, stored, inline).secrets
        return profile.copy(
            uuid = resolved?.uuid.orEmpty(),
            realityPublicKey = resolved?.realityPublicKey,
            realityShortId = resolved?.realityShortId,
        )
    }
}

@Serializable
internal data class ProfileSecrets(
    val profileId: String? = null,
    val uuid: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
)

/** Какие секреты профиля считать действительными при чтении старого хранилища. */
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
