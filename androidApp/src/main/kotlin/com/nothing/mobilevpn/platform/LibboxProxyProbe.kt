package com.nothing.mobilecore.platform

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

/** Performs an internal libbox URL test through the selected outbound group. */
internal object LibboxProxyProbe {
    fun test(outboundGroup: String): Result<Unit> = runCatching {
        val client = CommandClient(NoOpHandler, CommandClientOptions())
        try {
            client.connect()
            client.urlTest(outboundGroup)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private object NoOpHandler : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String?) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
    }
}
