package com.nothing.mobilecore.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.shared.application.ProfileService
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImportViewModel(
    private val profileService: ProfileService,
) : ViewModel() {

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    /** Импорт ссылки vless:// из поля ввода; профиль сохраняется, активным не становится. */
    fun importLink(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        if (_importInProgress.value) return
        _importInProgress.value = true
        viewModelScope.launch {
            try {
                onResult(profileService.importLink(link, activate = false))
            } finally {
                _importInProgress.value = false
            }
        }
    }

    /** Импорт из QR-кода: профиль сохраняется и сразу становится активным. */
    fun importAndActivate(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        viewModelScope.launch { onResult(profileService.importLink(link, activate = true)) }
    }
}
