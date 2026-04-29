package com.nothing.mobilevpn.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nothing.mobilevpn.BuildConfig
import com.nothing.mobilevpn.MainActivity
import com.nothing.mobilevpn.NothingVpnApp
import com.nothing.mobilevpn.R
import com.nothing.mobilevpn.libbox.DefaultNetworkMonitor
import com.nothing.mobilevpn.libbox.LibboxAndroidBootstrap
import com.nothing.mobilevpn.libbox.MobilePlatformInterface
import com.nothing.mobilevpn.libbox.toAndroidIpPrefix
import com.nothing.mobilevpn.platform.SafeAndroidLog
import com.nothing.mobilevpn.shared.domain.RoutingMode
import com.nothing.mobilevpn.shared.infrastructure.LogRedaction
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.Notification as LibboxNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import android.Manifest

private const val VPN_LOG_TAG = "NothingVpnService"

/**
 * Официальный путь sing-box на Android: [CommandServer] + [MobilePlatformInterface.openTun]
 * (см. sing-box-for-android / experimental/libbox).
 */
class NothingVpnService : VpnService(), MobilePlatformInterface {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunPfd: ParcelFileDescriptor? = null
    private lateinit var commandServer: CommandServer
    private val handler = VpnBoxHandler()

    private var sessionConfigPath: String? = null
    private var lastPolicy: TunnelPolicy? = null

    @Volatile
    var systemProxyAvailable = false

    @Volatile
    var systemProxyEnabled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                teardown()
                VpnRunningState.publish(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH) ?: return START_NOT_STICKY
                val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON) ?: return START_NOT_STICKY
                val policy = policyJsonFormat.decodeFromString(TunnelPolicy.serializer(), policyJson)
                val notification = buildNotification().build()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                scope.launch {
                    runCatching {
                        connectSuspend(policy, configPath)
                    }.onFailure { e ->
                        sendConnectResult(false, e.message ?: "connectVpn_failed")
                        SafeAndroidLog.e(VPN_LOG_TAG, "connectVpn failed", e)
                        teardown()
                        stopSelf()
                    }
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        VpnRunningState.publish(applicationContext, false)
        super.onDestroy()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder =
            Builder()
                .setSession(SESSION_NAME)
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toAndroidIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        builder.addRoute(inet6RouteAddress.next().toAndroidIpPrefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toAndroidIpPrefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet6RouteExcludeAddress.next().toAndroidIpPrefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }
            }

            val includePackage = options.includePackage
            if (includePackage.hasNext()) {
                while (includePackage.hasNext()) {
                    try {
                        builder.addAllowedApplication(includePackage.next())
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }

            val excludePackage = options.excludePackage
            if (excludePackage.hasNext()) {
                while (excludePackage.hasNext()) {
                    try {
                        builder.addDisallowedApplication(excludePackage.next())
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            if (systemProxyEnabled) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort,
                        options.httpProxyBypassDomain.toStringList(),
                    ),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        tunPfd = pfd
        return pfd.fd
    }

    override fun sendNotification(notification: LibboxNotification) {
        ContextCompat.getMainExecutor(this).execute {
            val nm = getSystemService(NotificationManager::class.java)!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        notification.identifier,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val builder =
                NotificationCompat.Builder(this, notification.identifier)
                    .setShowWhen(false)
                    .setContentTitle(notification.title)
                    .setContentText(notification.body)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
            if (!notification.subtitle.isNullOrBlank()) {
                builder.setContentInfo(notification.subtitle)
            }
            if (!notification.openURL.isNullOrBlank()) {
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(Intent.ACTION_VIEW, Uri.parse(notification.openURL)),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            nm.notify(notification.typeID, builder.build())
        }
    }

    private suspend fun connectSuspend(policy: TunnelPolicy, configPath: String) {
        teardown()
        VpnRunningState.publish(this, false)
        LibboxAndroidBootstrap.ensureSetup()
        sessionConfigPath = configPath
        lastPolicy = policy
        val content = File(configPath).readText()
        if (content.isBlank()) error("empty_config")

        commandServer = CommandServer(handler, this)
        commandServer.start()
        DefaultNetworkMonitor.start()

        try {
            commandServer.startOrReloadService(content, buildOverrideOptions(policy))
        } catch (e: Exception) {
            throw e
        }

        if (commandServer.needWIFIState()) {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (fine != PackageManager.PERMISSION_GRANTED) {
                error("wifi_state_needs_fine_location")
            }
        }

        sendConnectResult(true, null)
    }

    private fun buildOverrideOptions(policy: TunnelPolicy): OverrideOptions =
        OverrideOptions().also { o ->
            o.autoRedirect = false
            when (policy.routingMode) {
                RoutingMode.FULL_TUN -> {
                    o.excludePackage = MobilePlatformInterface.StringArray(listOf(packageName).iterator())
                }
                RoutingMode.PER_APP_ALLOWED -> {
                    val pkgs = (policy.allowedPackageNames + packageName).distinct()
                    o.includePackage = MobilePlatformInterface.StringArray(pkgs.iterator())
                }
                RoutingMode.PER_APP_EXCLUDED -> {
                    val pkgs = (policy.allowedPackageNames + packageName).distinct()
                    o.excludePackage = MobilePlatformInterface.StringArray(pkgs.iterator())
                }
            }
        }

    private fun teardown() {
        sessionConfigPath?.let { path ->
            runCatching { File(path).delete() }
        }
        tunPfd?.close()
        tunPfd = null
        runBlocking {
            runCatching { DefaultNetworkMonitor.stop() }
        }
        if (::commandServer.isInitialized) {
            runCatching { commandServer.closeService() }
            runCatching { commandServer.close() }
        }
        sessionConfigPath = null
        lastPolicy = null
        systemProxyAvailable = false
        systemProxyEnabled = false
    }

    private fun sendConnectResult(ok: Boolean, message: String?) {
        VpnRunningState.publish(this, ok)
        sendBroadcast(
            Intent(VpnConnectResultContract.ACTION).apply {
                setPackage(packageName)
                putExtra(VpnConnectResultContract.EXTRA_OK, ok)
                message?.let { putExtra(VpnConnectResultContract.EXTRA_MESSAGE, it) }
            },
        )
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, NothingVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NothingVpnApp.CHANNEL_VPN)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(pending)
            .addAction(0, getString(R.string.action_disconnect), disconnect)
    }

    private inner class VpnBoxHandler : CommandServerHandler {
        override fun serviceStop() {
            tunPfd?.close()
            tunPfd = null
            runCatching {
                if (::commandServer.isInitialized) {
                    commandServer.closeService()
                }
            }
        }

        override fun serviceReload() {
            val path = sessionConfigPath ?: return
            val pol = lastPolicy ?: return
            scope.launch {
                runCatching {
                    val json = File(path).readText()
                    commandServer.startOrReloadService(json, buildOverrideOptions(pol))
                }.onFailure { SafeAndroidLog.e(VPN_LOG_TAG, "serviceReload", it) }
            }
        }

        override fun getSystemProxyStatus(): SystemProxyStatus? {
            val status = SystemProxyStatus()
            status.available = systemProxyAvailable
            status.enabled = systemProxyEnabled
            return status
        }

        override fun setSystemProxyEnabled(isEnabled: Boolean) {
            systemProxyEnabled = isEnabled
            serviceReload()
        }

        override fun triggerNativeCrash() {
        }

        override fun writeDebugMessage(message: String?) {
            if (BuildConfig.DEBUG && message != null) {
                Log.d("sing-box", LogRedaction.redactForLog(message))
            }
        }
    }

    companion object {
        const val SESSION_NAME = "NothingMobileVpn"

        const val ACTION_CONNECT = "com.nothing.mobilevpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.nothing.mobilevpn.action.DISCONNECT"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_POLICY_JSON = "policy_json"
        private const val NOTIFICATION_ID = NothingVpnApp.NOTIFICATION_ID

        private val policyJsonFormat = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private fun StringIterator.toStringList(): List<String> {
    val out = ArrayList<String>()
    while (hasNext()) {
        out.add(next())
    }
    return out
}
