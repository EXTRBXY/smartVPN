package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.ensure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class AndroidConnectivityProbe : ConnectivityProbePort {

    override suspend fun tcpConnectLatency(host: String, port: Int): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            ensure(host.isNotBlank()) { VpnError.ProfileHostRequired }
            ensure(port in 1..65535) { VpnError.ProfilePortInvalid }
            val started = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host.trim(), port), TCP_TIMEOUT_MS) }
            (System.nanoTime() - started) / 1_000_000
        }
    }

    override suspend fun externalIp(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(EXTERNAL_IP_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.setRequestProperty("Accept", "text/plain")
                // Без keep-alive: соединение из пула могло быть открыто через уже закрытый туннель.
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                ensure(connection.responseCode in 200..299) { VpnError.ExternalIpFailed }
                connection.inputStream.bufferedReader().use { it.readText().trim() }
                    .also { ip -> ensure(ip.length in 3..64) { VpnError.ExternalIpFailed } }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val TCP_TIMEOUT_MS = 4_000
        const val HTTP_TIMEOUT_MS = 5_000
        const val EXTERNAL_IP_URL = "https://api.ipify.org"
    }
}
