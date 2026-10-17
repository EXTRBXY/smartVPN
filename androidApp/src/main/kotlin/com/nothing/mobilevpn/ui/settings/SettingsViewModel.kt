package com.nothing.mobilecore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.platform.AutomationSettings
import com.nothing.mobilecore.platform.WifiInfoProvider
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val profiles: ProfileRepositoryPort,
    private val preferences: AppPreferences,
    private val wifiInfo: WifiInfoProvider,
) : ViewModel() {

    fun automationSettings(): AutomationSettings = preferences.automation()
    fun currentWifiName(): String? = wifiInfo.currentSsid()

    fun setAutoStart(enabled: Boolean) = preferences.setAutoStart(enabled)

    /** «Восстанавливать соединение»: также переключает ретраи подключения (`failClosed = !enabled`). */
    fun setAutoReconnect(enabled: Boolean) {
        preferences.setAutoReconnect(enabled)
        viewModelScope.launch {
            val policy = profiles.getTunnelPolicy()
            profiles.setTunnelPolicy(policy.copy(failClosed = !enabled))
        }
    }

    fun setConnectOnUntrustedWifi(enabled: Boolean) = preferences.setConnectOnUntrustedWifi(enabled)
    fun setPeriodicHealthCheck(enabled: Boolean) = preferences.setPeriodicHealthCheck(enabled)
    fun setTrustedWifiNames(names: Set<String>) = preferences.setTrustedWifiNames(names)
}
