package com.nothing.mobilecore.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.ui.UserErrorText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditRoute(
    vm: ProfileEditViewModel,
    profileId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val profiles by vm.profileList.collectAsState()
    val profile = profiles.find { it.id == profileId }
    if (profile == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Редактирование профиля") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp),
            ) {
                Text(
                    text = UserErrorText.from(context, VpnError.ProfileNotFound),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }

    var name by rememberSaveable(profileId) { mutableStateOf(profile.name) }
    var host by rememberSaveable(profileId) { mutableStateOf(profile.host) }
    var portText by rememberSaveable(profileId) { mutableStateOf(profile.port.toString()) }
    var uuid by rememberSaveable(profileId) { mutableStateOf(profile.uuid) }
    var security by rememberSaveable(profileId) { mutableStateOf(profile.security) }
    var realityPublicKey by rememberSaveable(profileId) { mutableStateOf(profile.realityPublicKey.orEmpty()) }
    var realityShortId by rememberSaveable(profileId) { mutableStateOf(profile.realityShortId.orEmpty()) }
    var statusMessage by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var statusIsError by rememberSaveable(profileId) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(profileId) { mutableStateOf(false) }
    var testingServer by rememberSaveable(profileId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактирование профиля") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                        )
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя профиля") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uuid,
                onValueChange = { uuid = it },
                label = { Text("UUID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = security,
                onValueChange = { security = it },
                label = { Text("Security (tls/reality)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = realityPublicKey,
                onValueChange = { realityPublicKey = it },
                label = { Text("Reality Public Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = realityShortId,
                onValueChange = { realityShortId = it },
                label = { Text("Reality Short ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            statusMessage?.let {
                Text(
                    text = it,
                    color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    testingServer = true
                    scope.launch {
                        vm.testServerEndpoint(host, portText.toIntOrNull() ?: -1).fold(
                            onSuccess = {
                                statusIsError = false
                                statusMessage = "Сервер доступен: $it мс. Проверка не подтверждает корректность UUID/TLS."
                            },
                            onFailure = {
                                statusIsError = true
                                statusMessage = "Сервер недоступен. Проверьте адрес и порт."
                            },
                        )
                        testingServer = false
                    }
                },
                enabled = !testingServer,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (testingServer) "Проверка…" else "Проверить сервер перед сохранением") }

            Button(
                onClick = {
                    val updated = profile.copy(
                        name = name,
                        host = host,
                        port = portText.toIntOrNull() ?: -1,
                        uuid = uuid,
                        security = security,
                        realityPublicKey = realityPublicKey,
                        realityShortId = realityShortId,
                    )
                    vm.updateProfile(profileId, updated) { result ->
                        if (result.isSuccess) {
                            statusIsError = false
                            statusMessage = context.getString(R.string.save_success)
                            onBack()
                        } else {
                            statusIsError = true
                            statusMessage = UserErrorText.from(context, result.exceptionOrNull())
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Удалить профиль") }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удаление профиля") },
            text = { Text("Удалить этот профиль? Действие необратимо.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteProfile(profileId) { result ->
                        if (result.isSuccess) {
                            onBack()
                        } else {
                            statusIsError = true
                            statusMessage = UserErrorText.from(context, result.exceptionOrNull())
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
            },
        )
    }
}
