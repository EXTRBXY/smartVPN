package com.nothing.mobilecore.ui.update

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.update.AppUpdateController
import com.nothing.mobilecore.platform.update.UpdateBusy
import com.nothing.mobilecore.platform.update.UpdateInstallResult
import com.nothing.mobilecore.platform.update.UpdateInstaller
import com.nothing.mobilecore.platform.update.UpdateSignal

@Composable
internal fun UpdateHost(updates: AppUpdateController) {
    val context = LocalContext.current
    val offer by updates.offer.collectAsState()
    val busy by updates.busy.collectAsState()
    LaunchedEffect(updates) {
        updates.signals.collect { signal -> show(context, signal) }
    }
    val release = offer ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_available, release.semver)) },
        text = if (release.notes.isBlank()) {
            null
        } else {
            { Text(release.notes.take(400)) }
        },
        confirmButton = {
            TextButton(
                enabled = busy == UpdateBusy.IDLE,
                onClick = updates::download,
            ) {
                Text(
                    stringResource(
                        if (busy == UpdateBusy.DOWNLOADING) R.string.update_downloading else R.string.update_download,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = updates::dismiss, enabled = busy != UpdateBusy.DOWNLOADING) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}

private fun show(context: Context, signal: UpdateSignal) {
    when (signal) {
        UpdateSignal.CheckFailed -> toast(context, R.string.update_check_failed)
        UpdateSignal.UpToDate -> toast(context, R.string.update_up_to_date)
        UpdateSignal.DownloadFailed -> toast(context, R.string.update_download_failed)
        is UpdateSignal.Install -> {
            val result = UpdateInstaller.start(context, signal.file)
            if (result == UpdateInstallResult.NEED_PERMISSION) toast(context, R.string.update_install_permission)
            if (result == UpdateInstallResult.FAILED) toast(context, R.string.update_download_failed)
        }
    }
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
