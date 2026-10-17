package com.nothing.mobilecore.ui.diagnostics

import androidx.lifecycle.ViewModel
import com.nothing.mobilecore.platform.EventLogStore
import com.nothing.mobilecore.platform.TunnelStats
import com.nothing.mobilecore.platform.TunnelStatsMonitor
import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.domain.VpnSessionState
import kotlinx.coroutines.flow.StateFlow

class DiagnosticsViewModel(
    controller: VpnSessionController,
    statsMonitor: TunnelStatsMonitor,
    private val probe: ConnectivityProbePort,
    private val eventLog: EventLogStore,
) : ViewModel() {

    val sessionState: StateFlow<VpnSessionState> = controller.state
    val tunnelStats: StateFlow<TunnelStats> = statsMonitor.stats

    suspend fun externalIp(): Result<String> = probe.externalIp()

    fun events(): List<String> = eventLog.read()
    fun clearEvents() = eventLog.clear()
}
