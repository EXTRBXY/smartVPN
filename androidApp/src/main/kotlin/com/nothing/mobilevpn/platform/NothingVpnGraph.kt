package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.SingBoxConfigCompilerPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.infrastructure.SingBoxConfigCompiler
import com.nothing.mobilecore.vpn.TunnelStatusRepository

class NothingVpnGraph(context: Context) {
    private val appContext = context.applicationContext

    val secureStore = SecureStoreAndroid(appContext)
    val appPreferences = AppPreferences(appContext)
    val profileRepository = ProfileRepositoryAndroid(appContext, secureStore)
    val configCompiler: SingBoxConfigCompilerPort = SingBoxConfigCompiler()
    val singBoxCoreInfo = AndroidSingBoxCoreInfo(appContext)
    val tunnelStatus = TunnelStatusRepository(appContext)
    val systemVpn = AndroidSystemVpn(appContext, tunnelStatus)
    val sessionConfigWriter = AndroidSessionConfigWriter(appContext)
    val tunnelStatsMonitor = TunnelStatsMonitor(tunnelStatus.status)
    val connectivityProbe: ConnectivityProbePort = AndroidConnectivityProbe()
    val wifiInfo = WifiInfoProvider(appContext)
    val eventLog = EventLogStore(appContext)

    val sessionController = VpnSessionController(
        profileRepository = profileRepository,
        configCompiler = configCompiler,
        singBoxCoreInfo = singBoxCoreInfo,
        systemVpn = systemVpn,
        configWriter = sessionConfigWriter,
    )
}
