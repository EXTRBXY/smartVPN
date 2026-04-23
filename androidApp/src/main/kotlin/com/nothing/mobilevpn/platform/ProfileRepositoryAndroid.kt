package com.nothing.mobilevpn.platform

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nothing.mobilevpn.shared.application.ProfileRepositoryPort
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.shared.domain.VlessProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.profileDataStore by preferencesDataStore(name = "nothing_mobile_vpn")

private object PrefsKeys {
    val profiles = stringPreferencesKey("profiles_json")
    val activeId = stringPreferencesKey("active_profile_id")
    val policy = stringPreferencesKey("tunnel_policy_json")
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val listSerializer = ListSerializer(VlessProfile.serializer())

class ProfileRepositoryAndroid(
    context: Context,
) : ProfileRepositoryPort {
    private val appContext = context.applicationContext
    private val store = appContext.profileDataStore

    override fun observeProfiles(): Flow<List<VlessProfile>> = store.data.map { pref ->
        val raw = pref[PrefsKeys.profiles] ?: "[]"
        json.decodeFromString(listSerializer, raw)
    }

    override suspend fun listProfiles(): List<VlessProfile> = observeProfiles().first()

    override suspend fun upsert(profile: VlessProfile) {
        store.edit { pref ->
            val current = json.decodeFromString(listSerializer, pref[PrefsKeys.profiles] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            pref[PrefsKeys.profiles] = json.encodeToString(listSerializer, current)
        }
    }

    override suspend fun delete(profileId: String) {
        store.edit { pref ->
            val current = json.decodeFromString(listSerializer, pref[PrefsKeys.profiles] ?: "[]")
                .filterNot { it.id == profileId }
            pref[PrefsKeys.profiles] = json.encodeToString(listSerializer, current)
            if (pref[PrefsKeys.activeId] == profileId) {
                pref.remove(PrefsKeys.activeId)
            }
        }
    }

    override suspend fun getActiveProfileId(): String? = store.data.first()[PrefsKeys.activeId]

    override suspend fun setActiveProfileId(id: String?) {
        store.edit { pref ->
            if (id == null) pref.remove(PrefsKeys.activeId) else pref[PrefsKeys.activeId] = id
        }
    }

    override suspend fun getTunnelPolicy(): TunnelPolicy {
        val raw = store.data.first()[PrefsKeys.policy] ?: return TunnelPolicy()
        return json.decodeFromString(TunnelPolicy.serializer(), raw)
    }

    override suspend fun setTunnelPolicy(policy: TunnelPolicy) {
        store.edit { pref ->
            pref[PrefsKeys.policy] = json.encodeToString(TunnelPolicy.serializer(), policy)
        }
    }
}
