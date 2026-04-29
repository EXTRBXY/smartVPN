package com.nothing.mobilevpn.platform

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Однократный перенос из незашифрованного DataStore (старые сборки) в EncryptedSharedPreferences.
 */
internal object ProfileStorageMigration {

    private val profileStorageReady = CompletableDeferred<Unit>()

    suspend fun awaitProfileStorageReady() {
        profileStorageReady.await()
    }

    fun startProfileStorageMigration(app: Application, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                migratePlainDataStoreIfNeeded(
                    app,
                    VpnEncryption.profilePreferences(app),
                )
            } catch (e: Exception) {
                SafeAndroidLog.e("ProfileStorageMigration", "migrate failed", e)
            } finally {
                profileStorageReady.complete(Unit)
            }
        }
    }

    private val legacyProfilesKey = stringPreferencesKey("profiles_json")
    private val legacyActiveKey = stringPreferencesKey("active_profile_id")
    private val legacyPolicyKey = stringPreferencesKey("tunnel_policy_json")

    suspend fun migratePlainDataStoreIfNeeded(context: Context, encrypted: SharedPreferences) {
        val legacyFile = File(context.filesDir, "datastore/nothing_mobile_vpn.preferences_pb")
        if (!legacyFile.exists()) return

        if (encrypted.getString(KEY_PROFILES, null) != null) {
            deleteLegacyFiles(legacyFile)
            return
        }

        val store = PreferenceDataStoreFactory.create(produceFile = { legacyFile })
        val snap = store.data.first()
        val profiles = snap[legacyProfilesKey]
        val active = snap[legacyActiveKey]
        val policy = snap[legacyPolicyKey]

        encrypted.edit().apply {
            if (profiles != null) putString(KEY_PROFILES, profiles)
            if (active != null) putString(KEY_ACTIVE_ID, active)
            if (policy != null) putString(KEY_POLICY, policy)
        }.commit()

        deleteLegacyFiles(legacyFile)
    }

    private fun deleteLegacyFiles(legacyFile: File) {
        val parent = legacyFile.parentFile ?: return
        legacyFile.delete()
        File(parent, "${legacyFile.name}-shm").delete()
        File(parent, "${legacyFile.name}-wal").delete()
    }

    const val KEY_PROFILES = "profiles_json"
    const val KEY_ACTIVE_ID = "active_profile_id"
    const val KEY_POLICY = "tunnel_policy_json"
}
