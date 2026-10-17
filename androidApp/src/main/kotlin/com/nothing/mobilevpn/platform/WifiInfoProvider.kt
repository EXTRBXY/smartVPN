package com.nothing.mobilecore.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/** SSID текущей Wi-Fi сети; null без разрешения на геоданные или вне Wi-Fi. */
class WifiInfoProvider(context: Context) {
    private val app = context.applicationContext

    fun currentSsid(): String? {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        @Suppress("DEPRECATION")
        val raw = app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        return raw?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
    }
}
