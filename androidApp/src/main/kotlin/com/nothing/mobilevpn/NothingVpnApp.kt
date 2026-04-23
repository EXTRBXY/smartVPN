package com.nothing.mobilevpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.nothing.mobilevpn.libbox.LibboxAndroidBootstrap
import com.nothing.mobilevpn.platform.NothingVpnGraph

class NothingVpnApp : Application() {

    val graph by lazy { NothingVpnGraph(this) }

    override fun onCreate() {
        super.onCreate()
        LibboxAndroidBootstrap.attachBase(this)
        LibboxAndroidBootstrap.ensureSetup()
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
