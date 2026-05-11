package com.nothing.mobilecore.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.nothing.mobilecore.shared.application.SystemVpnPort
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.vpn.VpnRunningState
import com.nothing.mobilecore.vpn.NothingVpnService
import com.nothing.mobilecore.vpn.VpnConnectResultContract
import com.nothing.mobilecore.vpn.VpnInternalBroadcastSecurity
import com.nothing.mobilecore.vpn.VpnRunningStateContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
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
                            app.registerReceiver(
                                receiver,
                                filter,
                                VpnInternalBroadcastSecurity.PERMISSION,
                                null,
                                Context.RECEIVER_NOT_EXPORTED,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            app.registerReceiver(
                                receiver,
                                filter,
                                VpnInternalBroadcastSecurity.PERMISSION,
                                null,
                            )
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

    override suspend fun stopVpnAndAwait(): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (!VpnRunningState.readPersisted(context)) {
            stopVpn()
            return@withContext Result.success(Unit)
        }
        try {
            withTimeout(10_000L) {
                suspendCancellableCoroutine { cont ->
                    val app = context.applicationContext
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            if (intent?.action != VpnRunningStateContract.ACTION) return
                            val running = intent.getBooleanExtra(VpnRunningStateContract.EXTRA_RUNNING, false)
                            if (running) return
                            runCatching { app.unregisterReceiver(this) }
                            if (!cont.isActive) return
                            cont.resume(Result.success(Unit))
                        }
                    }
                    val filter = IntentFilter(VpnRunningStateContract.ACTION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        app.registerReceiver(
                            receiver,
                            filter,
                            VpnInternalBroadcastSecurity.PERMISSION,
                            null,
                            Context.RECEIVER_NOT_EXPORTED,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        app.registerReceiver(
                            receiver,
                            filter,
                            VpnInternalBroadcastSecurity.PERMISSION,
                            null,
                        )
                    }
                    cont.invokeOnCancellation {
                        runCatching { app.unregisterReceiver(receiver) }
                    }
                    context.startService(
                        Intent(context, NothingVpnService::class.java).setAction(NothingVpnService.ACTION_DISCONNECT),
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(Exception("vpn_stop_timeout"))
        }
    }

    override suspend fun smokeCheck(policy: TunnelPolicy): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                withTimeout(7_000L) {
                    val dnsTarget = if (policy.dnsMode == DnsMode.DOH) policy.dohServer else "one.one.one.one"
                    runCatching { InetAddress.getByName(dnsTarget) }
                        .getOrElse { throw IllegalStateException("smoke_dns_failed") }
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("1.1.1.1", 443), 2_500)
                        }
                    }.getOrElse { throw IllegalStateException("smoke_tcp_failed") }
                    if (!VpnRunningState.readPersisted(context)) {
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
