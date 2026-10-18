package com.nothing.mobilecore.ui.dns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.InstalledApp
import com.nothing.mobilecore.platform.InstalledAppsRepository
import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.validate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Общий для экранов «DNS» и «Приложения с системным DNS». */
class DnsViewModel(
    private val profiles: ProfileRepositoryPort,
    private val probe: ConnectivityProbePort,
    private val installedApps: InstalledAppsRepository? = null,
) : ViewModel() {

    /** Установленные приложения для экрана «Приложения с системным DNS»; null — ещё грузятся. */
    val apps: StateFlow<List<InstalledApp>?> = installedApps?.apps ?: MutableStateFlow(emptyList())

    fun refreshApps() {
        installedApps?.let { repo -> viewModelScope.launch { repo.refresh() } }
    }

    private val _dnsSaving = MutableStateFlow(false)
    val dnsSaving: StateFlow<Boolean> = _dnsSaving.asStateFlow()

    suspend fun getTunnelPolicy(): TunnelPolicy = profiles.getTunnelPolicy()

    /** Время TCP-соединения с DoH-сервером на 443; не проверка DNS-ответа. */
    suspend fun testDnsEndpoint(host: String): Result<Long> = probe.tcpConnectLatency(host, DOH_PORT)

    fun saveDnsSystem(onResult: (Result<Unit>) -> Unit) {
        saveDnsPolicy(onResult) { cur -> cur.copy(dnsMode = DnsMode.SYSTEM, dohSni = null) }
    }

    fun saveSystemDnsApps(packages: List<String>, onResult: (Result<Unit>) -> Unit) {
        saveDnsPolicy(onResult) { cur -> cur.copy(systemDnsPackageNames = packages.distinct().sorted()) }
    }

    fun saveDnsCustom(
        host: String,
        path: String,
        sni: String?,
        localDomains: List<String>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        saveDnsPolicy(onResult) { cur ->
            cur.copy(
                dnsMode = DnsMode.DOH,
                dohServer = host.trim(),
                dohPath = path.trim(),
                dohSni = sni?.trim()?.takeIf { it.isNotBlank() },
                localDnsDomains = localDomains.map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }.distinct(),
            )
        }
    }

    private fun saveDnsPolicy(
        onResult: (Result<Unit>) -> Unit,
        transform: (TunnelPolicy) -> TunnelPolicy,
    ) {
        if (_dnsSaving.value) return
        _dnsSaving.value = true
        viewModelScope.launch {
            try {
                onResult(
                    runCatching {
                        val updated = transform(profiles.getTunnelPolicy())
                        updated.validate().getOrThrow()
                        profiles.setTunnelPolicy(updated)
                    },
                )
            } finally {
                _dnsSaving.value = false
            }
        }
    }

    private companion object {
        const val DOH_PORT = 443
    }
}
