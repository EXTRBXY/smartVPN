package com.nothing.mobilevpn.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.nothing.mobilevpn.shared.application.SystemVpnPort
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.vpn.NothingVpnService
import com.nothing.mobilevpn.vpn.VpnConnectResultContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class AndroidSystemVpn(private val context: Context) : SystemVpnPort {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun startVpn(policy: TunnelPolicy, singBoxConfigPath: String): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            try {
                withTimeout(30_000L) {
                    suspendCancellableCoroutine { cont ->
                        val app = context.applicationContext
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(ctx: Context?, intent: Intent?) {
                                if (intent?.action != VpnConnectResultContract.ACTION) return
                                runCatching { app.unregisterReceiver(this) }
                                if (!cont.isActive) return
                                val ok = intent.getBooleanExtra(VpnConnectResultContract.EXTRA_OK, false)
                                val msg = intent.getStringExtra(VpnConnectResultContract.EXTRA_MESSAGE)
                                if (ok) {
                                    cont.resume(Result.success(Unit))
                                } else {
                                    cont.resume(Result.failure(Exception(msg ?: "vpn_start_failed")))
                                }
                            }
                        }
                        val filter = IntentFilter(VpnConnectResultContract.ACTION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                        } else {
                            @Suppress("DEPRECATION")
                            app.registerReceiver(receiver, filter)
                        }
                        cont.invokeOnCancellation {
                            runCatching { app.unregisterReceiver(receiver) }
                        }
                        val intent = Intent(context, NothingVpnService::class.java).apply {
                            action = NothingVpnService.ACTION_CONNECT
                            putExtra(NothingVpnService.EXTRA_CONFIG_PATH, singBoxConfigPath)
                            putExtra(
                                NothingVpnService.EXTRA_POLICY_JSON,
                                json.encodeToString(TunnelPolicy.serializer(), policy),
                            )
                        }
                        ContextCompat.startForegroundService(context, intent)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Result.failure(Exception("vpn_connect_timeout"))
            }
        }

    override suspend fun stopVpn() {
        context.startService(
            Intent(context, NothingVpnService::class.java).setAction(NothingVpnService.ACTION_DISCONNECT),
        )
    }

    override fun vpnPermissionNeeded(): Boolean = VpnService.prepare(context) != null
}
