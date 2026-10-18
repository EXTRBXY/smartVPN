package com.nothing.mobilecore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nothing.mobilecore.platform.NothingVpnGraph
import com.nothing.mobilecore.ui.apps.RoutingViewModel
import com.nothing.mobilecore.ui.diagnostics.DiagnosticsViewModel
import com.nothing.mobilecore.ui.dns.DnsViewModel
import com.nothing.mobilecore.ui.home.HomeViewModel
import com.nothing.mobilecore.ui.profiles.ImportViewModel
import com.nothing.mobilecore.ui.profiles.ProfileEditViewModel
import com.nothing.mobilecore.ui.settings.SettingsViewModel

/** Фабрика всех ViewModel экранов; зависимости берутся из [NothingVpnGraph]. */
class AppViewModelFactory(private val graph: NothingVpnGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java -> HomeViewModel(graph.sessionController, graph.profileRepository, graph.appPreferences, graph.connectivityProbe, graph.tunnelStatsMonitor)
        ImportViewModel::class.java -> ImportViewModel(graph.profileService)
        ProfileEditViewModel::class.java -> ProfileEditViewModel(graph.profileService, graph.profileRepository, graph.connectivityProbe)
        RoutingViewModel::class.java -> RoutingViewModel(graph.profileRepository, graph.appPreferences, graph.installedApps, graph.sessionController)
        DnsViewModel::class.java -> DnsViewModel(graph.profileRepository, graph.connectivityProbe, graph.installedApps)
        SettingsViewModel::class.java -> SettingsViewModel(graph.profileRepository, graph.appPreferences, graph.wifiInfo)
        DiagnosticsViewModel::class.java -> DiagnosticsViewModel(graph.sessionController, graph.tunnelStatsMonitor, graph.connectivityProbe, graph.eventLog)
        else -> error("Unknown ViewModel: ${modelClass.name}")
    } as T
}
