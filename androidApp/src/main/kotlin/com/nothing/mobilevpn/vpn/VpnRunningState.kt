package com.nothing.mobilevpn.vpn

import android.content.Context
import android.content.Intent
import com.nothing.mobilevpn.quicksettings.VpnQuickTileRefresh

object VpnRunningState {
    private const val PREFS = "nothing_vpn_running_flag"
    private const val KEY = "running"

    @Volatile
    var isRunning: Boolean = false
        private set

    fun syncFromDisk(context: Context) {
        isRunning = readPersisted(context)
    }

    fun readPersisted(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun publish(context: Context, running: Boolean) {
        isRunning = running
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, running)
            .commit()
        app.sendBroadcast(
            Intent(VpnRunningStateContract.ACTION).apply {
                setPackage(app.packageName)
                putExtra(VpnRunningStateContract.EXTRA_RUNNING, running)
            },
        )
        VpnQuickTileRefresh.requestUpdate(app)
    }
}
