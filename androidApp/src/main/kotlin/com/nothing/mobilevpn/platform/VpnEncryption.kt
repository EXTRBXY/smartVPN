package com.nothing.mobilecore.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal object VpnEncryption {
    fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    /** Профили и политика туннеля (секреты) — только в EncryptedSharedPreferences. */
    fun profilePreferences(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "nothing_vpn_profiles",
            masterKey(context),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
