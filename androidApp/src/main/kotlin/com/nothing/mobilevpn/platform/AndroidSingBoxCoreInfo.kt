package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.libbox.LibboxAndroidBootstrap
import com.nothing.mobilecore.shared.application.SingBoxCoreInfoPort
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
