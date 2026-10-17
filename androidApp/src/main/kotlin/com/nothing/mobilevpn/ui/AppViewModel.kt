package com.nothing.mobilecore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nothing.mobilecore.platform.NothingVpnGraph
import com.nothing.mobilecore.platform.RoutingRuleSet
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.validate
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.shared.domain.vpnFail
import com.nothing.mobilecore.shared.infrastructure.VlessLinkFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL

class AppViewModel(
    private val graph: NothingVpnGraph,
) : ViewModel() {

    private val controller = graph.sessionController
    private val profiles = graph.profileRepository

    // Напрямую, без stateIn с начальным Disconnected: иначе при открытии экрана во время
    // активного туннеля на мгновение мелькало «Отключено».
    val sessionState: StateFlow<VpnSessionState> = controller.state
    val tunnelStatus: StateFlow<TunnelStatus> = controller.tunnelStatus

    val profileList: StateFlow<List<VlessProfile>> = profiles.observeProfiles().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId

    val lastError: StateFlow<VpnError?> = controller.lastError
    val tunnelStats = graph.tunnelStatsMonitor.stats

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress

    private val _splitTunnelSaving = MutableStateFlow(false)
    val splitTunnelSaving: StateFlow<Boolean> = _splitTunnelSaving
    private val _dnsSaving = MutableStateFlow(false)
    val dnsSaving: StateFlow<Boolean> = _dnsSaving

    init {
        viewModelScope.launch {
            _activeProfileId.value = profiles.getActiveProfileId()
        }
    }

    fun importLink(link: String, onResult: (Result<VlessProfile>) -> Unit) {
        if (_importInProgress.value) return
        viewModelScope.launch {
            _importInProgress.update { true }
            val result = runCatching {
                val r = controller.importVlessLink(link)
                r.onSuccess { profiles.upsert(it) }
                r
            }.getOrElse { Result.failure(it) }
            onResult(result)
            _importInProgress.update { false }
        }
    }

    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            profiles.setActiveProfileId(id)
            _activeProfileId.value = id
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            val cur = profiles.getTunnelPolicy()
            profiles.setTunnelPolicy(
                cur.copy(
                    routingMode = mode,
                    allowedPackageNames = if (mode == RoutingMode.FULL_TUN) emptyList() else cur.allowedPackageNames,
                ),
            )
        }
    }

    fun saveSplitTunnel(mode: RoutingMode, packages: List<String>, onResult: (Result<Unit>) -> Unit) {
        require(mode == RoutingMode.PER_APP_ALLOWED || mode == RoutingMode.PER_APP_EXCLUDED)
        if (_splitTunnelSaving.value) return
        viewModelScope.launch {
            _splitTunnelSaving.update { true }
            val result = runCatching {
                val cur = profiles.getTunnelPolicy()
                val updated = cur.copy(
                        routingMode = mode,
                        allowedPackageNames = packages.distinct().sorted(),
                )
                updated.validate().getOrElse { throw it }
                profiles.setTunnelPolicy(updated)
            }
            onResult(result)
            _splitTunnelSaving.update { false }
        }
    }

    suspend fun getTunnelPolicy(): TunnelPolicy = profiles.getTunnelPolicy()

    fun connect() {
        graph.appPreferences.connectionRequested = true
        graph.sessionController.connect()
    }

    fun switchActiveProfileAndReconnect(id: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                controller.disconnectSuspend().getOrThrow()
                profiles.setActiveProfileId(id)
                _activeProfileId.value = id
                graph.appPreferences.connectionRequested = true
                controller.connectSuspend().getOrThrow()
            }
            onResult(result)
        }
    }

    suspend fun findFastestProfile(): Result<Pair<String, Long>> = runCatching {
        coroutineScope {
            profiles.listProfiles().map { profile ->
                async { testProfileLatency(profile.id).getOrNull()?.let { profile.id to it } }
            }.awaitAll().filterNotNull().minByOrNull { it.second } ?: vpnFail(VpnError.NoReachableProfile)
        }
    }

    fun disconnect() {
        graph.appPreferences.connectionRequested = false
        viewModelScope.launch { graph.sessionController.disconnectSuspend() }
    }

    fun automationSettings() = graph.appPreferences.automation()
    fun setAutoStart(enabled: Boolean) = graph.appPreferences.setAutoStart(enabled)
    fun setAutoReconnect(enabled: Boolean) {
        graph.appPreferences.setAutoReconnect(enabled)
        viewModelScope.launch {
            val policy = profiles.getTunnelPolicy()
            profiles.setTunnelPolicy(policy.copy(failClosed = !enabled))
        }
    }
    fun setConnectOnUntrustedWifi(enabled: Boolean) = graph.appPreferences.setConnectOnUntrustedWifi(enabled)
    fun setPeriodicHealthCheck(enabled: Boolean) = graph.appPreferences.setPeriodicHealthCheck(enabled)
    fun setTrustedWifiNames(names: Set<String>) = graph.appPreferences.setTrustedWifiNames(names)
    fun routingRuleSets() = graph.appPreferences.routingRuleSets()
    fun saveRoutingRuleSet(name: String, mode: RoutingMode, packages: List<String>) =
        graph.appPreferences.saveRoutingRuleSet(RoutingRuleSet(name.trim(), mode, packages))
    fun deleteRoutingRuleSet(name: String) = graph.appPreferences.deleteRoutingRuleSet(name)

    suspend fun exportProfile(profileId: String): Result<String> = runCatching {
        val profile = profiles.listProfiles().firstOrNull { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
        VlessLinkFormatter.format(profile)
    }

    fun duplicateProfile(profileId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                val profile = profiles.listProfiles().firstOrNull { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
                profiles.upsert(profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} — копия", favorite = false))
            })
        }
    }

    fun setProfileFavorite(profileId: String, favorite: Boolean) {
        viewModelScope.launch {
            val profile = profiles.listProfiles().firstOrNull { it.id == profileId } ?: return@launch
            profiles.upsert(profile.copy(favorite = favorite))
        }
    }

    suspend fun testProfileLatency(profileId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = profiles.listProfiles().firstOrNull { it.id == profileId } ?: vpnFail(VpnError.ProfileNotFound)
            val started = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(profile.host, profile.port), 4_000) }
            (System.nanoTime() - started) / 1_000_000
        }
    }

    suspend fun testDnsEndpoint(host: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host.trim(), 443), 4_000) }
            (System.nanoTime() - started) / 1_000_000
        }
    }

    suspend fun testServerEndpoint(host: String, port: Int): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            require(host.isNotBlank() && port in 1..65535) { "profile_port_invalid" }
            val started = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host.trim(), port), 4_000) }
            (System.nanoTime() - started) / 1_000_000
        }
    }

    suspend fun externalIp(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.ipify.org").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "text/plain")
                require(connection.responseCode in 200..299) { "external_ip_failed" }
                connection.inputStream.bufferedReader().use { it.readText().trim() }
                    .also { require(it.length in 3..64) { "external_ip_failed" } }
            } finally {
                connection.disconnect()
            }
        }
    }

    fun updateProfile(profileId: String, draft: VlessProfile, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = graph.sessionController.updateProfileSuspend(profileId, draft)
            if (result.isSuccess) {
                _activeProfileId.value = profiles.getActiveProfileId()
            }
            onResult(result)
        }
    }

    fun deleteProfile(profileId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = graph.sessionController.deleteProfileSuspend(profileId)
            if (result.isSuccess) {
                _activeProfileId.value = profiles.getActiveProfileId()
            }
            onResult(result)
        }
    }

    suspend fun singBoxVersion(): String =
        controller.singBoxCoreVersion().getOrElse { throw it }

    fun saveDnsSystem(onResult: (Result<Unit>) -> Unit) {
        saveDnsPolicy(onResult) { cur ->
            cur.copy(
                dnsMode = DnsMode.SYSTEM,
                dohSni = null,
            )
        }
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
        viewModelScope.launch {
            _dnsSaving.update { true }
            val result = runCatching {
                val cur = profiles.getTunnelPolicy()
                val updated = transform(cur)
                updated.validate().getOrElse { throw it }
                profiles.setTunnelPolicy(updated)
            }
            onResult(result)
            _dnsSaving.update { false }
        }
    }

    class Factory(
        private val graph: NothingVpnGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}
