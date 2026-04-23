package com.nothing.mobilevpn.platform

import android.content.Context
import com.nothing.mobilevpn.libbox.LibboxAndroidBootstrap
import com.nothing.mobilevpn.shared.application.SingBoxRuntimePort
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SingBoxAndroidRuntime(
    @Suppress("unused") context: Context,
) : SingBoxRuntimePort {

    override suspend fun start(configPath: String) {
        LibboxAndroidBootstrap.ensureSetup()
        /* Основной цикл — [com.nothing.mobilevpn.vpn.NothingVpnService] + CommandServer. */
    }

    override suspend fun stop() {
        /* Остановка через SystemVpnPort / сервис. */
    }

    override suspend fun version(): String = withContext(Dispatchers.IO) {
        LibboxAndroidBootstrap.ensureSetup()
        Libbox.version()
    }
}
