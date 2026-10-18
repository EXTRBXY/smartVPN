package com.nothing.mobilecore.ui.profiles

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.ui.UserErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportRoute(vm: ImportViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var link by rememberSaveable { mutableStateOf("") }
    var msg by rememberSaveable { mutableStateOf<String?>(null) }
    var msgIsError by rememberSaveable { mutableStateOf(false) }
    val importInProgress by vm.importInProgress.collectAsState()
    val normalizedLink = link.trim()
    val inputError = when {
        normalizedLink.isEmpty() -> resources.getString(R.string.import_hint_empty)
        !normalizedLink.startsWith("vless://", ignoreCase = true) -> resources.getString(R.string.user_error_only_vless)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    msg = null
                },
                label = { Text("vless://…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = inputError != null && normalizedLink.isNotEmpty(),
            )
            if (inputError != null && normalizedLink.isNotEmpty()) {
                Text(inputError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            msg?.let {
                Text(
                    text = it,
                    color = if (msgIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (importInProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Button(
                onClick = {
                    vm.importLink(normalizedLink) { r ->
                        msg = r.fold(
                            onSuccess = { resources.getString(R.string.import_success_prefix, it.name) },
                            onFailure = { UserErrorText.from(context, it) },
                        )
                        msgIsError = r.isFailure
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !importInProgress && inputError == null,
            ) { Text(stringResource(R.string.import_action)) }
        }
    }
}
