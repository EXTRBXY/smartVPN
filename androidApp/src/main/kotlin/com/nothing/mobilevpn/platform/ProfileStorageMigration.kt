package com.nothing.mobilecore.platform

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
    private const val LEGACY_MIGRATION_RETRY_MARKER = "legacy_migration_retry_needed"

    suspend fun migratePlainDataStoreIfNeeded(context: Context, encrypted: SharedPreferences) {
        val legacyFile = File(context.filesDir, "datastore/nothing_mobile_vpn.preferences_pb")
        val retryNeeded = encrypted.getBoolean(LEGACY_MIGRATION_RETRY_MARKER, false)
        if (!legacyFile.exists() && !retryNeeded) return

        if (encrypted.getString(KEY_PROFILES, null) != null) {
            deleteLegacyFiles(legacyFile)
            encrypted.edit().remove(LEGACY_MIGRATION_RETRY_MARKER).apply()
            return
        }

        val store = PreferenceDataStoreFactory.create(produceFile = { legacyFile })
        val snap = store.data.first()
        val profiles = snap[legacyProfilesKey]
        val active = snap[legacyActiveKey]
        val policy = snap[legacyPolicyKey]

        val committed = encrypted.edit().apply {
            if (profiles != null) putString(KEY_PROFILES, profiles)
            if (active != null) putString(KEY_ACTIVE_ID, active)
            if (policy != null) putString(KEY_POLICY, policy)
            putBoolean(LEGACY_MIGRATION_RETRY_MARKER, true)
        }.commit()
        if (!committed) error("legacy_migration_commit_failed")

        deleteLegacyFiles(legacyFile)
        encrypted.edit().remove(LEGACY_MIGRATION_RETRY_MARKER).apply()
    }

    private fun deleteLegacyFiles(legacyFile: File) {
        val parent = legacyFile.parentFile ?: return
        val candidates = listOf(
            legacyFile,
            File(parent, "${legacyFile.name}-shm"),
            File(parent, "${legacyFile.name}-wal"),
            File(parent, "${legacyFile.name}-journal"),
        )
        repeat(3) { attempt ->
            candidates.forEach { file ->
                if (file.exists()) {
                    runCatching { file.delete() }
                }
            }
            if (candidates.none { it.exists() }) {
                return
            }
            if (attempt < 2) {
                runCatching { Thread.sleep(100) }
            }
        }
    }

    const val KEY_PROFILES = "profiles_json"
    const val KEY_ACTIVE_ID = "active_profile_id"
    const val KEY_POLICY = "tunnel_policy_json"
}
