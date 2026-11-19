package com.nothing.mobilecore.platform.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nothing.mobilecore.BuildConfig
import java.io.File

internal enum class UpdateInstallResult {
    STARTED,
    NEED_PERMISSION,
    FAILED,
}

internal object UpdateInstaller {
    fun start(context: Context, file: File): UpdateInstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (context.startActivitySafe(settings)) UpdateInstallResult.NEED_PERMISSION else UpdateInstallResult.FAILED
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("", uri) }
        return if (context.startActivitySafe(install)) UpdateInstallResult.STARTED else UpdateInstallResult.FAILED
    }

    private fun Context.startActivitySafe(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess
}
