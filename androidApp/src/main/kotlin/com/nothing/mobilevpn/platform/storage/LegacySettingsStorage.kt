package com.nothing.mobilecore.platform.storage

import android.content.Context
import com.nothing.mobilecore.platform.AppSettings
import com.nothing.mobilecore.platform.AutomationSettings
import com.nothing.mobilecore.platform.RoutingRuleSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Настройки до версии 0.3.0 лежали в открытом SharedPreferences `nothing_vpn_user_settings`.
 * Читаются один раз при загрузке хранилища; файл удаляется после успешного переноса.
 */
internal class AndroidLegacySettingsSource(context: Context) : LegacySettingsSource {
    private val app = context.applicationContext
    private val xml = File(File(app.applicationInfo.dataDir, "shared_prefs"), "$PREFS.xml")

    override fun read(): AppSettings? {
        // Проверяем файл до getSharedPreferences, чтобы не создать пустой.
        if (!xml.exists()) return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ruleSets = runCatching {
            Json.decodeFromString(ListSerializer(RoutingRuleSet.serializer()), prefs.getString("routing_rule_sets", null) ?: "[]")
        }.getOrDefault(emptyList())
        return AppSettings(
            automation = AutomationSettings(
                autoStartAfterBoot = prefs.getBoolean("auto_start", false),
                autoReconnect = prefs.getBoolean("auto_reconnect", false),
                connectOnUntrustedWifi = prefs.getBoolean("untrusted_wifi", false),
                trustedWifiNames = prefs.getStringSet("trusted_wifi", emptySet()).orEmpty().toSet(),
                periodicHealthCheck = prefs.getBoolean("health_check", false),
            ),
            routingRuleSets = ruleSets,
            connectionRequested = prefs.getBoolean("connection_requested", false),
        )
    }

    override fun delete() {
        app.deleteSharedPreferences(PREFS)
    }

    private companion object {
        const val PREFS = "nothing_vpn_user_settings"
    }
}
