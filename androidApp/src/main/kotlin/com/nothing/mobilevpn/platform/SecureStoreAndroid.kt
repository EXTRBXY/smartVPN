package com.nothing.mobilevpn.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nothing.mobilevpn.shared.application.SecureStorePort

class SecureStoreAndroid(context: Context) : SecureStorePort {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "nothing_vpn_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun read(key: String): String? = prefs.getString(key, null)

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
