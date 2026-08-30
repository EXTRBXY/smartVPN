package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.shared.domain.RoutingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class RoutingRuleSet(val name: String, val mode: RoutingMode, val packages: List<String>)

data class AutomationSettings(
    val autoStartAfterBoot: Boolean,
    val autoReconnect: Boolean,
    val connectOnUntrustedWifi: Boolean,
    val trustedWifiNames: Set<String>,
    val periodicHealthCheck: Boolean,
)

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun automation(): AutomationSettings = AutomationSettings(
        autoStartAfterBoot = prefs.getBoolean(KEY_AUTO_START, false),
        autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, false),
        connectOnUntrustedWifi = prefs.getBoolean(KEY_UNTRUSTED_WIFI, false),
        trustedWifiNames = prefs.getStringSet(KEY_TRUSTED_WIFI, emptySet()).orEmpty().toSet(),
        periodicHealthCheck = prefs.getBoolean(KEY_HEALTH_CHECK, false),
    )

    fun setAutoStart(enabled: Boolean) = editBoolean(KEY_AUTO_START, enabled)
    fun setAutoReconnect(enabled: Boolean) = editBoolean(KEY_AUTO_RECONNECT, enabled)
    fun setConnectOnUntrustedWifi(enabled: Boolean) = editBoolean(KEY_UNTRUSTED_WIFI, enabled)
    fun setPeriodicHealthCheck(enabled: Boolean) = editBoolean(KEY_HEALTH_CHECK, enabled)

    fun setTrustedWifiNames(names: Set<String>) {
        prefs.edit().putStringSet(KEY_TRUSTED_WIFI, names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
    }

    fun routingRuleSets(): List<RoutingRuleSet> = runCatching {
        Json.decodeFromString(ListSerializer(RoutingRuleSet.serializer()), prefs.getString("routing_rule_sets", "[]").orEmpty())
    }.getOrDefault(emptyList())

    fun saveRoutingRuleSet(ruleSet: RoutingRuleSet) {
        val updated = routingRuleSets().filterNot { it.name == ruleSet.name } + ruleSet
        prefs.edit().putString("routing_rule_sets", Json.encodeToString(ListSerializer(RoutingRuleSet.serializer()), updated)).apply()
    }

    fun deleteRoutingRuleSet(name: String) {
        val updated = routingRuleSets().filterNot { it.name == name }
        prefs.edit().putString("routing_rule_sets", Json.encodeToString(ListSerializer(RoutingRuleSet.serializer()), updated)).apply()
    }

    var connectionRequested: Boolean
        get() = prefs.getBoolean(KEY_REQUESTED, false)
        set(value) { editBoolean(KEY_REQUESTED, value) }

    private fun editBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        private const val PREFS = "nothing_vpn_user_settings"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_UNTRUSTED_WIFI = "untrusted_wifi"
        private const val KEY_TRUSTED_WIFI = "trusted_wifi"
        private const val KEY_HEALTH_CHECK = "health_check"
        private const val KEY_REQUESTED = "connection_requested"
    }
}
