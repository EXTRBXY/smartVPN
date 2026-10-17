package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.domain.TunnelStatus
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TunnelStats(
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadTotal: Long = 0,
    val downloadTotal: Long = 0,
    val activeConnections: Int = 0,
)

class TunnelStatsMonitor(private val tunnelStatus: StateFlow<TunnelStatus>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = mutableStats.asStateFlow()

    fun start() {
        scope.launch {
            while (true) {
                if (tunnelStatus.value !is TunnelStatus.Running) {
                    delay(1_000)
                    continue
                }
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = 1_000_000_000L
                }
                val handler = Handler()
                val client = CommandClient(handler, options)
                if (runCatching { client.connect() }.isSuccess) {
                    while (handler.active) delay(1_000)
                } else {
                    delay(5_000)
                }
                runCatching { client.disconnect() }
            }
        }
    }

    private inner class Handler : CommandClientHandler {
        @Volatile var active = true
        override fun connected() = Unit
        override fun disconnected(message: String?) {
            active = false
            mutableStats.value = TunnelStats()
        }
        override fun writeStatus(message: StatusMessage) {
            mutableStats.value = TunnelStats(
                uploadBytesPerSecond = message.uplink.coerceAtLeast(0),
                downloadBytesPerSecond = message.downlink.coerceAtLeast(0),
                uploadTotal = message.uplinkTotal.coerceAtLeast(0),
                downloadTotal = message.downlinkTotal.coerceAtLeast(0),
                activeConnections = (message.connectionsIn + message.connectionsOut).coerceAtLeast(0),
            )
        }
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
    }
}
