package com.nothing.mobilecore.ui.update

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.update.AppUpdateController
import com.nothing.mobilecore.platform.update.UpdateBusy
import com.nothing.mobilecore.platform.update.UpdateInstaller
import com.nothing.mobilecore.platform.update.UpdateSignal
import java.io.File

@Composable
internal fun UpdateHost(updates: AppUpdateController) {
    val context = LocalContext.current
    val prompt by updates.prompt.collectAsState()
    val busy by updates.busy.collectAsState()
    val downloadProgress by updates.downloadProgress.collectAsState()
    val installReady by updates.installReady.collectAsState()
    var pendingInstallPath by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        pendingInstallPath?.let { path ->
            pendingInstallPath = null
            if (UpdateInstaller.canInstall(context)) startInstall(context, File(path))
        }
    }
    LaunchedEffect(updates, context) {
        updates.signals.collect { signal ->
            when (signal) {
                UpdateSignal.CheckFailed -> toast(context, R.string.update_check_failed)
                UpdateSignal.UpToDate -> toast(context, R.string.update_up_to_date)
                UpdateSignal.DownloadFailed -> toast(context, R.string.update_download_failed)
                is UpdateSignal.Install -> {
                    if (UpdateInstaller.canInstall(context)) {
                        startInstall(context, signal.file)
                    } else if (pendingInstallPath == null) {
                        pendingInstallPath = signal.file.path
                        val launched = runCatching {
                            context.startActivity(UpdateInstaller.permissionIntent(context))
                        }
                        if (launched.isSuccess) {
                            toast(context, R.string.update_install_permission)
                        } else {
                            pendingInstallPath = null
                            toast(context, R.string.update_install_failed)
                        }
                    }
                }
            }
        }
    }
    val release = prompt ?: return
    AlertDialog(
        onDismissRequest = {
            if (busy != UpdateBusy.DOWNLOADING) updates.dismiss(release.tag)
        },
        title = { Text(stringResource(R.string.update_available, release.semver)) },
        text = if (busy == UpdateBusy.DOWNLOADING) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UpdateDownloadIndicator(downloadProgress)
                    if (release.notes.isNotBlank()) Text(release.notes.take(400))
                }
            }
        } else if (release.notes.isNotBlank()) {
            { Text(release.notes.take(400)) }
        } else {
            null
        },
        confirmButton = {
            if (busy != UpdateBusy.DOWNLOADING) {
                TextButton(enabled = busy == UpdateBusy.IDLE, onClick = updates::prepareInstall) {
                    Text(stringResource(if (installReady) R.string.update_install else R.string.update_download))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { updates.dismiss(release.tag) }, enabled = busy != UpdateBusy.DOWNLOADING) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}

private fun startInstall(context: Context, file: File) {
    if (!UpdateInstaller.start(context, file)) toast(context, R.string.update_install_failed)
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
