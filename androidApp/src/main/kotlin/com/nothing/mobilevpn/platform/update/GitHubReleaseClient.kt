package com.nothing.mobilecore.platform.update

import android.content.Context
import com.nothing.mobilecore.shared.domain.AppRelease
import com.nothing.mobilecore.shared.domain.AppUpdatePolicy
import com.nothing.mobilecore.shared.infrastructure.GitHubReleaseParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleaseClient(private val context: Context) {
    fun latest(): AppRelease? {
        val connection = NonVpnHttp.open(context, URL(AppUpdatePolicy.latestReleaseUrl()), readTimeoutMs = 20_000)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (!connection.url.host.equals("api.github.com", ignoreCase = true)) {
                throw IOException("update_host")
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) throw IOException("update_http")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return try {
                GitHubReleaseParser.parse(body)
            } catch (_: Exception) {
                throw IOException("update_json")
            }
        } finally {
            connection.disconnect()
        }
    }
}
