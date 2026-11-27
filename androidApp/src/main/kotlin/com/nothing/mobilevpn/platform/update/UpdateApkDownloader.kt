package com.nothing.mobilecore.platform.update

import android.content.Context
import com.nothing.mobilecore.shared.domain.AppRelease
import com.nothing.mobilecore.shared.domain.AppUpdatePolicy
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateDownloadProgress(val downloadedBytes: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.let { downloadedBytes.coerceIn(0, it).toFloat() / it }

    val percent: Int?
        get() = totalBytes?.let { (downloadedBytes.coerceIn(0, it) * 100 / it).toInt() }
}

class UpdateApkDownloader(private val context: Context) {
    private val directory: File get() = File(context.cacheDir, "updates")

    fun cached(release: AppRelease): File? = findCachedUpdateApk(directory, release)

    fun download(release: AppRelease, onProgress: (UpdateDownloadProgress) -> Unit): File {
        if (AppUpdatePolicy.downloadUrlProblem(release.downloadUrl, requireFileName = true) != null) {
            throw IOException("update_url")
        }
        val directory = directory.apply { mkdirs() }
        val destination = updateApkFile(directory, release)
        val partial = File(directory, "${destination.name}.part")
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
            val totalBytes = length.takeIf { it > 0 }
            var received = 0L
            onProgress(UpdateDownloadProgress(received, totalBytes))
            var lastReportAt = System.nanoTime()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > MAX_BYTES) throw IOException("update_size")
                        output.write(buffer, 0, read)
                        val now = System.nanoTime()
                        if (received == read.toLong() || now - lastReportAt >= PROGRESS_INTERVAL_NS) {
                            onProgress(UpdateDownloadProgress(received, totalBytes))
                            lastReportAt = now
                        }
                    }
                }
            }
            if (totalBytes != null && received != totalBytes) throw IOException("update_incomplete")
            onProgress(UpdateDownloadProgress(received, totalBytes))
            if (!isZip(partial)) throw IOException("update_file")
            if (!partial.renameTo(destination)) throw IOException("update_save")
        } catch (error: IOException) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        directory.listFiles { file ->
            file != destination &&
                (file.name == AppUpdatePolicy.ASSET_NAME ||
                    (file.name.startsWith("NothingMobileVpn-") && file.extension in setOf("apk", "part")))
        }?.forEach { it.delete() }
        return destination
    }

    private fun isZip(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { stream ->
            stream.read() == 0x50 && stream.read() == 0x4B
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_NS = 200_000_000L
    }
}

private const val MAX_BYTES = 200L * 1024 * 1024

internal fun findCachedUpdateApk(directory: File, release: AppRelease): File? =
    updateApkFile(directory, release).takeIf { it.isFile && it.length() in 4..MAX_BYTES }

@OptIn(ExperimentalStdlibApi::class)
internal fun updateApkFile(directory: File, release: AppRelease): File {
    val key = MessageDigest.getInstance("SHA-256")
        .digest("${release.tag}\n${release.downloadUrl}".encodeToByteArray())
        .toHexString()
    return File(directory, "NothingMobileVpn-$key.apk")
}
