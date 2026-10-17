package com.nothing.mobilecore.platform

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.nothing.mobilecore.shared.application.SystemVpnPort
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.vpn.NothingVpnService
import com.nothing.mobilecore.vpn.TunnelStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class AndroidSystemVpn(
    private val context: Context,
    private val statusRepository: TunnelStatusRepository,
) : SystemVpnPort {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override val tunnelStatus: StateFlow<TunnelStatus> = statusRepository.status

    override suspend fun startVpn(policy: TunnelPolicy, singBoxConfigPath: String): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            val sessionId = statusRepository.newSessionId()
            val intent = Intent(context, NothingVpnService::class.java).apply {
                action = NothingVpnService.ACTION_CONNECT
                putExtra(NothingVpnService.EXTRA_SESSION_ID, sessionId)
                putExtra(NothingVpnService.EXTRA_CONFIG_PATH, singBoxConfigPath)
                putExtra(
                    NothingVpnService.EXTRA_POLICY_JSON,
                    json.encodeToString(TunnelPolicy.serializer(), policy),
                )
            }
            ContextCompat.startForegroundService(context, intent)
            val outcome = withTimeoutOrNull(30_000L) {
                tunnelStatus.first { it.isOutcomeOf(sessionId) }
            }
            when (outcome) {
                null -> Result.failure(Exception("vpn_connect_timeout"))
                is TunnelStatus.Running ->
                    if (outcome.sessionId == sessionId) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("vpn_start_superseded"))
                    }
                is TunnelStatus.Stopped -> Result.failure(Exception(outcome.error ?: "vpn_start_failed"))
                else -> Result.failure(Exception("vpn_start_superseded"))
            }
        }

    override suspend fun stopVpn() {
        // Запуск сервиса из фона ради остановки уже остановленного туннеля на Android 8+ бросает
        // IllegalStateException, а делать ему нечего.
        if (tunnelStatus.value is TunnelStatus.Stopped) return
        runCatching { context.startService(disconnectIntent()) }
    }

    override suspend fun stopVpnAndAwait(): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (tunnelStatus.value is TunnelStatus.Stopped) return@withContext Result.success(Unit)
        runCatching { context.startService(disconnectIntent()) }
            .onFailure { return@withContext Result.failure(Exception("vpn_stop_failed")) }
        val stopped = withTimeoutOrNull(10_000L) {
            tunnelStatus.first { it is TunnelStatus.Stopped }
        }
        if (stopped != null) Result.success(Unit) else Result.failure(Exception("vpn_stop_timeout"))
    }

    private fun disconnectIntent(): Intent =
        Intent(context, NothingVpnService::class.java).setAction(NothingVpnService.ACTION_DISCONNECT)

    /** Итог попытки [sessionId]: туннель поднят, остановлен или его вытеснила более новая сессия. */
    private fun TunnelStatus.isOutcomeOf(sessionId: Long): Boolean = when (this) {
        is TunnelStatus.Running -> this.sessionId >= sessionId
        is TunnelStatus.Stopped -> this.sessionId?.let { it >= sessionId } == true
        is TunnelStatus.Starting -> this.sessionId > sessionId
        TunnelStatus.Stopping -> false
    }

    override suspend fun smokeCheck(policy: TunnelPolicy): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                withTimeout(7_000L) {
                    LibboxProxyProbe.test("proxy").getOrElse {
                        throw IllegalStateException("smoke_proxy_failed", it)
                    }
                    val probe = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                    try {
                        probe.connectTimeout = 3_000
                        probe.readTimeout = 3_000
                        probe.instanceFollowRedirects = false
                        if (probe.responseCode != 204) error("smoke_proxy_failed")
                    } finally {
                        probe.disconnect()
                    }
                    if (tunnelStatus.value !is TunnelStatus.Running) {
                        error("vpn_not_running_after_connect")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                error("smoke_timeout")
            }
        }
    }

    override fun vpnPermissionNeeded(): Boolean = VpnService.prepare(context) != null
}
