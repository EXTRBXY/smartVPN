package com.nothing.mobilevpn.platform

import android.content.Context
import android.content.SharedPreferences
import com.nothing.mobilevpn.shared.application.ProfileRepositoryPort
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.shared.domain.VlessProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val listSerializer = ListSerializer(VlessProfile.serializer())

class ProfileRepositoryAndroid(
    context: Context,
) : ProfileRepositoryPort {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = VpnEncryption.profilePreferences(appContext)

    override fun observeProfiles(): Flow<List<VlessProfile>> = callbackFlow {
        ProfileStorageMigration.awaitProfileStorageReady()
        fun emitList() {
            val raw = prefs.getString(ProfileStorageMigration.KEY_PROFILES, null) ?: "[]"
            trySend(json.decodeFromString(listSerializer, raw))
        }
        emitList()
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ProfileStorageMigration.KEY_PROFILES) emitList()
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun listProfiles(): List<VlessProfile> {
        ProfileStorageMigration.awaitProfileStorageReady()
        val raw = prefs.getString(ProfileStorageMigration.KEY_PROFILES, null) ?: "[]"
        return json.decodeFromString(listSerializer, raw)
    }

    override suspend fun upsert(profile: VlessProfile) {
        ProfileStorageMigration.awaitProfileStorageReady()
        val current =
            json.decodeFromString(
                listSerializer,
                prefs.getString(ProfileStorageMigration.KEY_PROFILES, null) ?: "[]",
            ).toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
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
}
