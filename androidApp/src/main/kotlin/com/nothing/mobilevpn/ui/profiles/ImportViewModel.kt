package com.nothing.mobilecore.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImportViewModel(
    private val controller: VpnSessionController,
    private val profiles: ProfileRepositoryPort,
) : ViewModel() {

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    /** Импорт ссылки vless:// из поля ввода; профиль сохраняется, активным не становится. */
    fun importLink(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        if (_importInProgress.value) return
        _importInProgress.value = true
        viewModelScope.launch {
            try {
                onResult(runCatching { controller.importVlessLink(link).getOrThrow().also { profiles.upsert(it) } })
            } finally {
                _importInProgress.value = false
            }
        }
    }

    /** Импорт из QR-кода: профиль сохраняется и сразу становится активным. */
    fun importAndActivate(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        viewModelScope.launch {
            onResult(
                runCatching {
                    controller.importVlessLink(link).getOrThrow().also {
                        profiles.upsert(it)
                        profiles.setActiveProfileId(it.id)
                    }
                },
            )
        }
    }
}
