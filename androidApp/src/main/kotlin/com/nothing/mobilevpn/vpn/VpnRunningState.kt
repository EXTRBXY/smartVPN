package com.nothing.mobilecore.vpn

import android.content.Context
import android.content.Intent
import com.nothing.mobilecore.quicksettings.VpnQuickTileRefresh

object VpnRunningState {
    private const val PREFS = "nothing_vpn_running_flag"
    private const val KEY = "running"
    private const val KEY_STARTED_AT = "started_at"

    @Volatile
    var isRunning: Boolean = false
        private set

    fun syncFromDisk(context: Context) {
        // Service existence is not connection state: during startup/teardown it remains alive
        // while the tunnel is not usable. The service publishes the authoritative result.
        isRunning = readPersisted(context)
    }

    fun readPersisted(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun publish(context: Context, running: Boolean) {
        val wasRunning = isRunning
        isRunning = running
        val app = context.applicationContext
        val editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, running)
        if (running && !wasRunning) editor.putLong(KEY_STARTED_AT, System.currentTimeMillis())
        editor.commit()
        app.sendBroadcast(
            Intent(VpnRunningStateContract.ACTION).apply {
                setPackage(app.packageName)
                putExtra(VpnRunningStateContract.EXTRA_RUNNING, running)
            },
            VpnInternalBroadcastSecurity.PERMISSION,
        )
        VpnQuickTileRefresh.requestUpdate(app)
    }

    fun startedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_STARTED_AT, 0L)
}
