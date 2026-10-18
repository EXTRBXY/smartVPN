package com.nothing.mobilecore.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.toVpnError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        startWatchdogSync()
    }

    /**
     * Держит [TunnelWatchdog] в соответствии с состоянием. Ждёт хранилище: до загрузки
     * `connectionRequested` читался бы как false и снимал сторожа в только что поднятом процессе.
     */
    private fun startWatchdogSync() {
        scope.launch {
            preferences.awaitReady()
            combine(preferences.connectionRequestedFlow, graph.tunnelStatus.status, ::decideWatchdogSchedule)
                .distinctUntilChanged()
                .collect { TunnelWatchdog.apply(app, it) }
        }
    }

    suspend fun onBootCompleted() {
        preferences.awaitReady()
        if (preferences.automation().autoStartAfterBoot) {
            preferences.connectionRequested = true
            maybeConnect("device boot", AutoConnectTrigger.BOOT)
        }
    }

    /**
     * Система запустила сервис сама: после смерти процесса (`intent == null`) или как Always-on.
     * Конфиг собирается заново из хранилища через `VpnSessionController` — так же, как при
     * обычном подключении. Возвращает true, если туннель поднят (иначе сервис может остановиться).
     */
    internal suspend fun restoreTunnel(trigger: AutoConnectTrigger): Boolean {
        val reason = when (trigger) {
            AutoConnectTrigger.ALWAYS_ON -> "always-on"
            AutoConnectTrigger.PROCESS_RESTART -> "process restart"
            AutoConnectTrigger.BOOT -> "device boot"
            AutoConnectTrigger.AUTOMATIC -> "service start"
        }
        return maybeConnect(reason, trigger)
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

    /** true — туннель поднят этим вызовом или уже работал; false — пропуск или ошибка. */
    private suspend fun maybeConnect(
        reason: String,
        trigger: AutoConnectTrigger = AutoConnectTrigger.AUTOMATIC,
    ): Boolean = connectMutex.withLock {
        preferences.awaitReady()
        val decision = decideAutoConnect(
            AutoConnectInput(
                settings = preferences.automation(),
                connectionRequested = preferences.connectionRequested,
                trigger = trigger,
                wifiSsid = currentWifiName(),
                tunnel = graph.tunnelStatus.status.value,
                session = graph.sessionController.state.value,
                vpnPermissionNeeded = graph.systemVpn.vpnPermissionNeeded(),
            ),
        )
        if (decision.markRequested) preferences.connectionRequested = true
        when (decision.skip) {
            null -> Unit
            SkipReason.ALREADY_ACTIVE -> return true
            SkipReason.VPN_PERMISSION_REQUIRED -> {
                ConnectionEventLog.add(app, "Automatic connection skipped: VPN permission required")
                return false
            }
            else -> {
                if (trigger != AutoConnectTrigger.AUTOMATIC) {
                    ConnectionEventLog.add(app, "Automatic connection skipped ($reason): ${decision.skip.name.lowercase()}")
                }
                return false
            }
        }
        ConnectionEventLog.add(app, "Automatic connection: $reason")
        graph.sessionController.connectSuspend()
            .onFailure { ConnectionEventLog.add(app, "Automatic connection failed: ${it.toVpnError().code}") }
            .isSuccess
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
                    ConnectionEventLog.add(app, "Health check failed ($consecutiveFailures): ${result.exceptionOrNull()?.toVpnError()?.code}")
                    if (shouldRestartAfterHealthFailures(consecutiveFailures, settings, preferences.connectionRequested)) {
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
