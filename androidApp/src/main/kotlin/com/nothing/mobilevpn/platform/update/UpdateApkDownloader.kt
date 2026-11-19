package com.nothing.mobilecore.platform.update

import android.content.Context
import com.nothing.mobilecore.shared.domain.AppRelease
import com.nothing.mobilecore.shared.domain.AppUpdatePolicy
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateApkDownloader(private val context: Context) {
    fun download(release: AppRelease): File {
        if (AppUpdatePolicy.downloadUrlProblem(release.downloadUrl, requireFileName = true) != null) {
            throw IOException("update_url")
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(directory, AppUpdatePolicy.ASSET_NAME)
        val partial = File(directory, "${AppUpdatePolicy.ASSET_NAME}.part")
        if (partial.exists()) partial.delete()

        val connection = NonVpnHttp.open(context, URL(release.downloadUrl), readTimeoutMs = 60_000)
        try {
            val code = connection.responseCode
            val finalUrl = connection.url.toString()
            if (code != HttpURLConnection.HTTP_OK ||
                AppUpdatePolicy.downloadUrlProblem(finalUrl, requireFileName = false) != null
            ) {
                throw IOException("update_http")
            }
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) throw IOException("update_size")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > MAX_BYTES) throw IOException("update_size")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: IOException) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        if (!isZip(partial)) {
            partial.delete()
            throw IOException("update_file")
        }
        if (destination.exists()) destination.delete()
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
        return destination
    }

    private fun isZip(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { stream ->
            stream.read() == 0x50 && stream.read() == 0x4B
        }
    }

    private companion object {
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
