package com.nothing.mobilecore.ui.diagnostics

import androidx.compose.ui.res.stringResource
import com.nothing.mobilecore.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.BuildConfig
import com.nothing.mobilecore.ui.home.currentNetworkLabel
import com.nothing.mobilecore.ui.home.stateLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsRoute(vm: DiagnosticsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.sessionState.collectAsState()
    val stats by vm.tunnelStats.collectAsState()
    var events by remember { mutableStateOf(vm.events()) }
    var externalIp by rememberSaveable { mutableStateOf<String?>(null) }
    var ipLoading by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val report = remember(state, stats, events, externalIp) {
        buildString {
            appendLine("Nothing Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine(context.getString(R.string.diag_report_device, Build.MANUFACTURER, Build.MODEL))
            appendLine("VPN: ${stateLabel(context, state)}")
            appendLine(context.getString(R.string.diag_report_network, currentNetworkLabel(context)))
            appendLine(context.getString(R.string.diag_report_vpn_permission, context.getString(if (VpnService.prepare(context) == null) R.string.diag_permission_granted else R.string.diag_permission_missing)))
            appendLine(context.getString(R.string.diag_report_traffic, stats.downloadTotal, stats.uploadTotal, stats.activeConnections))
            appendLine(context.getString(R.string.diag_external_ip, externalIp ?: context.getString(R.string.diag_external_ip_unchecked)))
            appendLine(context.getString(R.string.diag_report_events))
            events.forEach { appendLine(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.diag_state, stateLabel(context, state)))
            Text(stringResource(R.string.diag_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            Text("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            Text(stringResource(R.string.diag_external_ip, externalIp ?: stringResource(R.string.diag_external_ip_unchecked)))
            Button(
                onClick = {
                    ipLoading = true
                    scope.launch {
                        externalIp = vm.externalIp().getOrElse { context.getString(R.string.diag_external_ip_failed) }
                        ipLoading = false
                    }
                },
                enabled = !ipLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ipLoading) stringResource(R.string.status_checking) else stringResource(R.string.diag_check_external_ip)) }
            Text(stringResource(R.string.diag_report_privacy), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Nothing Mobile diagnostics", report))
                        Toast.makeText(context, context.getString(R.string.diag_report_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_copy)) }
                Button(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, report)
                                },
                                context.getString(R.string.diag_share_report),
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_share)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.diag_recent_events), style = MaterialTheme.typography.labelLarge)
            if (events.isEmpty()) Text(stringResource(R.string.diag_no_events), style = MaterialTheme.typography.bodySmall)
            events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (events.isNotEmpty()) {
                TextButton(onClick = {
                    vm.clearEvents()
                    events = emptyList()
                }) { Text(stringResource(R.string.diag_clear_log)) }
            }
        }
    }

}
