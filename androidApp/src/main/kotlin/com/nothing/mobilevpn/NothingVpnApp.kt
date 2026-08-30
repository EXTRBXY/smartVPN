package com.nothing.mobilecore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.nothing.mobilecore.libbox.LibboxAndroidBootstrap
import com.nothing.mobilecore.platform.NothingVpnGraph
import com.nothing.mobilecore.platform.ProfileStorageMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NothingVpnApp : Application() {

    private val appStartScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val graph by lazy { NothingVpnGraph(this) }
    val automation by lazy { com.nothing.mobilecore.platform.VpnAutomationCoordinator(this, graph) }

    override fun onCreate() {
        super.onCreate()
        ProfileStorageMigration.startProfileStorageMigration(this, appStartScope)
        LibboxAndroidBootstrap.attachBase(this)
        LibboxAndroidBootstrap.ensureSetup()
        automation.start()
        graph.tunnelStatsMonitor.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_VPN,
                getString(R.string.vpn_notification_title),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_VPN = "nothing_vpn"
        const val NOTIFICATION_ID = 1001
    }
}
