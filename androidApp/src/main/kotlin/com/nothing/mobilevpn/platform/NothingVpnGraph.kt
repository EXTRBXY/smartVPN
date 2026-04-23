package com.nothing.mobilevpn.platform

import android.content.Context
import com.nothing.mobilevpn.shared.application.SingBoxConfigCompilerPort
import com.nothing.mobilevpn.shared.application.VpnSessionController
import com.nothing.mobilevpn.shared.infrastructure.SingBoxConfigCompiler

class NothingVpnGraph(context: Context) {
    private val appContext = context.applicationContext

    val profileRepository = ProfileRepositoryAndroid(appContext)
    val secureStore = SecureStoreAndroid(appContext)
    val configCompiler: SingBoxConfigCompilerPort = SingBoxConfigCompiler()
    val runtime = SingBoxAndroidRuntime(appContext)
    val systemVpn = AndroidSystemVpn(appContext)
    val sessionConfigWriter = AndroidSessionConfigWriter(appContext)

    val sessionController = VpnSessionController(
        profileRepository = profileRepository,
        configCompiler = configCompiler,
        runtime = runtime,
        systemVpn = systemVpn,
        configWriter = sessionConfigWriter,
    )
}
