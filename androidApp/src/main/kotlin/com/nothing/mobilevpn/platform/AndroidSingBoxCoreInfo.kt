package com.nothing.mobilevpn.platform

import android.content.Context
import com.nothing.mobilevpn.libbox.LibboxAndroidBootstrap
import com.nothing.mobilevpn.shared.application.SingBoxCoreInfoPort
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSingBoxCoreInfo(
    @Suppress("unused") context: Context,
) : SingBoxCoreInfoPort {

    override suspend fun version(): String = withContext(Dispatchers.IO) {
        LibboxAndroidBootstrap.ensureSetup()
        Libbox.version()
    }
}
