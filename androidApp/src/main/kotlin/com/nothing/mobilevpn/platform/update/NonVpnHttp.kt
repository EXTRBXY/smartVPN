package com.nothing.mobilecore.platform.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Запрос мимо собственного VPN: иначе проверка обновления зависит от того, жив ли туннель.
 * Если сети кроме VPN нет, используется маршрут по умолчанию.
 */
internal object NonVpnHttp {
    fun open(context: Context, url: URL, readTimeoutMs: Int): HttpURLConnection {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.allNetworks.firstOrNull { candidate ->
            val capabilities = connectivity.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", "NothingMobileVpn")
        return connection
    }
}
