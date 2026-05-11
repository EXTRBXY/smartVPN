package com.nothing.mobilecore.vpn

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import com.nothing.mobilecore.quicksettings.VpnQuickTileRefresh

object VpnRunningState {
    private const val PREFS = "nothing_vpn_running_flag"
    private const val KEY = "running"

    @Volatile
    var isRunning: Boolean = false
        private set

    fun syncFromDisk(context: Context) {
        val persisted = readPersisted(context)
        val runtime = isServiceRunning(context)
        isRunning = persisted || runtime
        if (isRunning != persisted) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY, isRunning)
                .apply()
        }
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
            VpnInternalBroadcastSecurity.PERMISSION,
        )
        VpnQuickTileRefresh.requestUpdate(app)
    }

    private fun isServiceRunning(context: Context): Boolean {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == "com.nothing.mobilecore.vpn.NothingVpnService" }
    }
}
