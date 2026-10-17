package com.nothing.mobilecore.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.shared.domain.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VpnAutomationCoordinator(
    context: Context,
    private val graph: NothingVpnGraph,
) {
    private val app = context.applicationContext
    private val preferences = graph.appPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectMutex = Mutex()
    private var healthJob: Job? = null
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                delay(700)
                maybeConnect("network available")
            }
        }
    }

    fun start() {
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback,
            )
        }.onFailure { ConnectionEventLog.add(app, "Network monitor unavailable: ${it.javaClass.simpleName}") }
        startHealthMonitor()
    }

    suspend fun onBootCompleted() {
        if (preferences.automation().autoStartAfterBoot) {
            preferences.connectionRequested = true
            maybeConnect("device boot", fromBoot = true)
        }
    }

    fun requestConnect(source: String) {
        preferences.connectionRequested = true
        ConnectionEventLog.add(app, "Connection requested: $source")
    }

    fun requestDisconnect(source: String) {
        preferences.connectionRequested = false
        ConnectionEventLog.add(app, "Disconnect requested: $source")
    }

    fun currentWifiName(): String? = graph.wifiInfo.currentSsid()

    private suspend fun maybeConnect(reason: String, fromBoot: Boolean = false): Unit = connectMutex.withLock {
        val settings = preferences.automation()
        val requestedReconnect = (settings.autoReconnect || fromBoot) && preferences.connectionRequested
        val wifi = currentWifiName()
        if (settings.connectOnUntrustedWifi && wifi != null && wifi in settings.trustedWifiNames) return
        val untrustedWifi = settings.connectOnUntrustedWifi && wifi != null && wifi !in settings.trustedWifiNames
        if (!requestedReconnect && !untrustedWifi) return
        if (untrustedWifi) preferences.connectionRequested = true
        val tunnel = graph.tunnelStatus.status.value
        if (tunnel is TunnelStatus.Running || tunnel is TunnelStatus.Starting) return
        if (graph.systemVpn.vpnPermissionNeeded()) {
            ConnectionEventLog.add(app, "Automatic connection skipped: VPN permission required")
            return
        }
        val state = graph.sessionController.state.value
        if (state is VpnSessionState.Connecting || state is VpnSessionState.Recovering || state is VpnSessionState.Connected) return
        ConnectionEventLog.add(app, "Automatic connection: $reason")
        graph.sessionController.connectSuspend().onFailure {
            ConnectionEventLog.add(app, "Automatic connection failed: ${it.message ?: it.javaClass.simpleName}")
        }
        Unit
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = scope.launch {
            var consecutiveFailures = 0
            while (true) {
                delay(60_000)
                val settings = preferences.automation()
                if (!settings.periodicHealthCheck || graph.tunnelStatus.status.value !is TunnelStatus.Running) {
                    consecutiveFailures = 0
                    continue
                }
                val policy = runCatching { graph.profileRepository.getTunnelPolicy() }.getOrNull() ?: continue
                val result = graph.systemVpn.smokeCheck(policy)
                if (result.isSuccess) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    ConnectionEventLog.add(app, "Health check failed ($consecutiveFailures): ${result.exceptionOrNull()?.message}")
                    if (consecutiveFailures >= 2 && settings.autoReconnect && preferences.connectionRequested) {
                        consecutiveFailures = 0
                        ConnectionEventLog.add(app, "Restarting tunnel after health-check failures")
                        graph.sessionController.disconnectSuspend()
                        delay(1_000)
                        maybeConnect("health recovery")
                    }
                }
            }
        }
    }
}
