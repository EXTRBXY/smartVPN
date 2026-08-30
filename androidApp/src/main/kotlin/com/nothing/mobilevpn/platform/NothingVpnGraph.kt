package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.shared.application.SingBoxConfigCompilerPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.infrastructure.SingBoxConfigCompiler

class NothingVpnGraph(context: Context) {
    private val appContext = context.applicationContext

    val secureStore = SecureStoreAndroid(appContext)
    val appPreferences = AppPreferences(appContext)
    val profileRepository = ProfileRepositoryAndroid(appContext, secureStore)
    val configCompiler: SingBoxConfigCompilerPort = SingBoxConfigCompiler()
    val singBoxCoreInfo = AndroidSingBoxCoreInfo(appContext)
    val systemVpn = AndroidSystemVpn(appContext)
    val sessionConfigWriter = AndroidSessionConfigWriter(appContext)
    val tunnelStatsMonitor = TunnelStatsMonitor()

    val sessionController = VpnSessionController(
        profileRepository = profileRepository,
        configCompiler = configCompiler,
        singBoxCoreInfo = singBoxCoreInfo,
        systemVpn = systemVpn,
        configWriter = sessionConfigWriter,
    )
}
