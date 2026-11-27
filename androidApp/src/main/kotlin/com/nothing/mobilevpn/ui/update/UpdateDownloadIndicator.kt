package com.nothing.mobilecore.ui.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.update.UpdateDownloadProgress

@Composable
internal fun UpdateDownloadIndicator(progress: UpdateDownloadProgress?) {
    val context = LocalContext.current
    val fraction = progress?.fraction
    val percent = progress?.percent
    val modifier = Modifier.fillMaxWidth()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = modifier)
        }

        val label = when {
            progress == null -> stringResource(R.string.update_download_connecting)
            percent != null && progress.totalBytes != null -> stringResource(
                R.string.update_download_progress,
                percent,
                Formatter.formatShortFileSize(context, progress.downloadedBytes),
                Formatter.formatShortFileSize(context, progress.totalBytes),
            )
            else -> stringResource(
                R.string.update_download_received,
                Formatter.formatShortFileSize(context, progress.downloadedBytes),
            )
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
