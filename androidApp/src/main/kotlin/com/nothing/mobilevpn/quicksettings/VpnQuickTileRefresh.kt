package com.nothing.mobilecore.quicksettings

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object VpnQuickTileRefresh {
    /** Заставляет систему снова вызвать [VpnQuickTileService.onStartListening] и обновить плитку. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        TileService.requestListeningState(
            app,
            ComponentName(app, VpnQuickTileService::class.java),
        )
    }
}
