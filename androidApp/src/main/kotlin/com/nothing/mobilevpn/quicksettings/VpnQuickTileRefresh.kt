package com.nothing.mobilevpn.quicksettings

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

object VpnQuickTileRefresh {
    /** Заставляет систему снова вызвать [VpnQuickTileService.onStartListening] и обновить плитку. */
    fun requestUpdate(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val app = context.applicationContext
        TileService.requestListeningState(
            app,
            ComponentName(app, VpnQuickTileService::class.java),
        )
    }
}
