package com.nothing.mobilecore.platform

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.nothing.mobilecore.shared.application.SystemVpnPort
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnException
import com.nothing.mobilecore.shared.domain.toVpnError
import com.nothing.mobilecore.vpn.NothingVpnService
import com.nothing.mobilecore.vpn.TunnelStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

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
                null -> Result.failure(VpnException(VpnError.VpnConnectTimeout))
                is TunnelStatus.Running ->
                    if (outcome.sessionId == sessionId) {
                        Result.success(Unit)
                    } else {
                        Result.failure(VpnException(VpnError.VpnStartSuperseded))
                    }
                is TunnelStatus.Stopped -> Result.failure(VpnException(outcome.error ?: VpnError.VpnStartFailed))
                else -> Result.failure(VpnException(VpnError.VpnStartSuperseded))
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
            .onFailure { return@withContext Result.failure(VpnException(VpnError.VpnStopFailed)) }
        val stopped = withTimeoutOrNull(10_000L) {
            tunnelStatus.first { it is TunnelStatus.Stopped }
        }
        if (stopped != null) Result.success(Unit) else Result.failure(VpnException(VpnError.VpnStopTimeout))
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

    override suspend fun smokeCheck(policy: TunnelPolicy): Result<Unit> {
        val started = SystemClock.elapsedRealtime()
        var httpStatus: Int? = null
        val result = try {
            httpStatus = withTimeoutOrNull(SMOKE_TIMEOUT_MS) {
                // Android может начать маршрутизировать через TUN чуть позже статуса Running.
                delay(TUN_SETTLE_MS)
                fetchProbeStatus()
            }
            val failure = when {
                httpStatus == null -> VpnError.SmokeTimeout
                httpStatus != 204 -> VpnError.SmokeProxyFailed
                tunnelStatus.value !is TunnelStatus.Running -> VpnError.VpnNotRunningAfterConnect
                else -> null
            }
            if (failure == null) Result.success(Unit) else Result.failure(VpnException(failure))
        } catch (error: CancellationException) {
            logProbeResult(started, "cancelled", httpStatus)
            throw error
        } catch (error: InterruptedIOException) {
            Result.failure(VpnException(VpnError.SmokeTimeout, error))
        } catch (error: IOException) {
            Result.failure(VpnException(VpnError.SmokeProxyFailed, error))
        }
        logProbeResult(started, result.exceptionOrNull()?.toVpnError()?.code ?: "ok", httpStatus)
        return result
    }

    private suspend fun fetchProbeStatus(): Int = suspendCancellableCoroutine { continuation ->
        val call = smokeHttp.newCall(smokeRequest)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response.use { it.code })
            }
        })
    }

    private fun logProbeResult(started: Long, result: String, httpStatus: Int?) {
        val elapsed = SystemClock.elapsedRealtime() - started
        val status = httpStatus?.let { ", HTTP $it" }.orEmpty()
        ConnectionEventLog.add(context, "VPN traffic check: $result in ${elapsed}ms$status")
    }

    override fun vpnPermissionNeeded(): Boolean = VpnService.prepare(context) != null

    private companion object {
        const val SMOKE_TIMEOUT_MS = 7_000L
        const val TUN_SETTLE_MS = 300L
        val smokeHttp = OkHttpClient.Builder()
            .callTimeout(SMOKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        val smokeRequest = Request.Builder()
            .url("https://www.gstatic.com/generate_204")
            .header("Connection", "close")
            .build()
    }
}
