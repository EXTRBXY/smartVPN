package com.nothing.mobilecore.vpn

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
import com.nothing.mobilecore.BuildConfig
import com.nothing.mobilecore.MainActivity
import com.nothing.mobilecore.NothingVpnApp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.libbox.DefaultNetworkMonitor
import com.nothing.mobilecore.libbox.LibboxAndroidBootstrap
import com.nothing.mobilecore.libbox.MobilePlatformInterface
import com.nothing.mobilecore.libbox.toAndroidIpPrefix
import com.nothing.mobilecore.platform.AndroidSessionConfigWriter
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.platform.ConnectionEventLog
import com.nothing.mobilecore.platform.SafeAndroidLog
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.infrastructure.LogRedaction
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.ensure
import com.nothing.mobilecore.shared.domain.toVpnError
import com.nothing.mobilecore.shared.domain.vpnFail
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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val tunnelStatus by lazy { (application as NothingVpnApp).graph.tunnelStatus }

    /** Сессия, к которой относятся публикуемые статусы (см. [TunnelStatus]). */
    @Volatile
    private var currentSessionId: Long? = null

    /** true, пока мы сами закрываем ядро: его колбэк serviceStop в этот момент не наш запрос. */
    @Volatile
    private var tearingDown = false

    private var sessionConfigPath: String? = null

    /**
     * Конфиг и политика текущей сессии — только в памяти, для [VpnBoxHandler.serviceReload].
     * Файл с секретами удаляется до старта ядра, поэтому перечитать его при reload нельзя;
     * в памяти процесса эти данные и так есть у libbox. Очищаются в [teardownCore].
     */
    @Volatile
    private var sessionConfig: String? = null

    @Volatile
    private var lastPolicy: TunnelPolicy? = null
    private val lifecycleMutex = Mutex()

    @Volatile
    var systemProxyAvailable = false

    @Volatile
    var systemProxyEnabled = false

    override fun onCreate() {
        super.onCreate()
        AndroidSessionConfigWriter.cleanupStartupStaleOnly(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                if (intent.getBooleanExtra(EXTRA_USER_DISCONNECT, false)) {
                    AppPreferences(this).connectionRequested = false
                }
                ConnectionEventLog.add(this, "VPN service: disconnect")
                requestStop(error = null)
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                ConnectionEventLog.add(this, "VPN service: connection started")
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)
                if (sessionId < 0 || configPath == null || policyJson == null) {
                    stopSelfIfIdle(startId)
                    return START_NOT_STICKY
                }
                val policy = policyJsonFormat.decodeFromString(TunnelPolicy.serializer(), policyJson)
                val notification = buildNotification().build()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                currentSessionId = sessionId
                tunnelStatus.publish(TunnelStatus.Starting(sessionId))
                scope.launch {
                    lifecycleMutex.withLock {
                        runCatching {
                            connectSuspend(policy, configPath, sessionId)
                        }.onFailure { e ->
                            val error = e.toVpnError()
                            ConnectionEventLog.add(this@NothingVpnService, "VPN service: start failed (${error.code})")
                            SafeAndroidLog.e(VPN_LOG_TAG, "connectVpn failed", e)
                            requestStopLocked(error = error)
                        }
                    }
                }
                return START_STICKY
            }
        }
        // null-intent: система перезапустила START_STICKY-сервис после смерти процесса. Конфиг с
        // секретами к этому моменту удалён, поднять туннель здесь нельзя — переподключение делает
        // VpnAutomationCoordinator (запускается в NothingVpnApp.onCreate), если оно запрошено.
        ConnectionEventLog.add(this, "VPN service: restarted without command")
        stopSelfIfIdle(startId)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Разрешение VPN отозвано: пользователь отключил в настройках или включён другой VPN.
        requestStop(error = VpnError.VpnRevoked)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopForegroundCompat()
        runBlocking(Dispatchers.IO) {
            lifecycleMutex.withLock {
                teardown()
            }
        }
        if (tunnelStatus.status.value !is TunnelStatus.Stopped) {
            tunnelStatus.publish(TunnelStatus.Stopped(sessionId = currentSessionId))
        }
        scope.coroutineContext.cancelChildren()
        super.onDestroy()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) vpnFail(VpnError.VpnPermissionMissing)

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

        val pfd = builder.establish() ?: vpnFail(VpnError.TunEstablishFailed)
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
            if (!notification.openURL.isNullOrBlank() && isSafeNotificationUrl(notification.openURL)) {
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

    private suspend fun connectSuspend(policy: TunnelPolicy, configPath: String, sessionId: Long) {
        teardown()
        AndroidSessionConfigWriter.cleanupPreConnect(applicationContext, keepPath = configPath)
        LibboxAndroidBootstrap.ensureSetup()
        sessionConfigPath = configPath
        val configFile = File(configPath)
        val content = configFile.readText()
        ensure(content.isNotBlank()) { VpnError.EmptyConfig }
        // libbox consumes the JSON string, so the secret-bearing file is no longer needed.
        // Delete it before starting the core; crash recovery therefore does not retain credentials.
        runCatching { configFile.delete() }
        sessionConfigPath = null

        commandServer = CommandServer(handler, this)
        commandServer.start()
        DefaultNetworkMonitor.start()

        commandServer.startOrReloadService(content, buildOverrideOptions(policy))
        sessionConfig = content
        lastPolicy = policy

        if (commandServer.needWIFIState()) {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (fine != PackageManager.PERMISSION_GRANTED) {
                vpnFail(VpnError.WifiStateNeedsFineLocation)
            }
        }

        ConnectionEventLog.add(this, "VPN service: tunnel ready")
        tunnelStatus.publish(TunnelStatus.Running(sessionId, System.currentTimeMillis()))
    }

    private fun buildOverrideOptions(policy: TunnelPolicy): OverrideOptions =
        OverrideOptions().also { o ->
            o.autoRedirect = false
            when (policy.routingMode) {
                RoutingMode.FULL_TUN -> {
                    // No package filter: the app's own diagnostic requests also traverse the
                    // tunnel. Core outbound sockets are protected in autoDetectInterfaceControl.
                }
                RoutingMode.PER_APP_ALLOWED -> {
                    val pkgs = (policy.allowedPackageNames + packageName).distinct()
                    o.includePackage = MobilePlatformInterface.StringArray(pkgs.iterator())
                }
                RoutingMode.PER_APP_EXCLUDED -> {
                    val pkgs = policy.allowedPackageNames.distinct()
                    o.excludePackage = MobilePlatformInterface.StringArray(pkgs.iterator())
                }
            }
        }

    private suspend fun teardown() {
        tearingDown = true
        try {
            teardownCore()
        } finally {
            tearingDown = false
        }
    }

    private suspend fun teardownCore() {
        sessionConfigPath?.let { path ->
            runCatching { File(path).delete() }
        }
        AndroidSessionConfigWriter.cleanupSessionConfigs(applicationContext, keepPath = null)
        tunPfd?.close()
        tunPfd = null
        runCatching { DefaultNetworkMonitor.stop() }
        if (::commandServer.isInitialized) {
            runCatching { commandServer.closeService() }
            runCatching { commandServer.close() }
        }
        sessionConfigPath = null
        sessionConfig = null
        lastPolicy = null
        systemProxyAvailable = false
        systemProxyEnabled = false
    }

    private fun requestStop(error: VpnError?) {
        if (tunnelStatus.status.value !is TunnelStatus.Stopped) {
            tunnelStatus.publish(TunnelStatus.Stopping)
        }
        scope.launch {
            lifecycleMutex.withLock {
                requestStopLocked(error)
            }
        }
    }

    private suspend fun requestStopLocked(error: VpnError?) {
        withContext(Dispatchers.IO) { teardown() }
        // Повторная остановка уже остановленного сервиса не должна затирать причину прошлой остановки.
        if (tunnelStatus.status.value !is TunnelStatus.Stopped) {
            tunnelStatus.publish(TunnelStatus.Stopped(sessionId = currentSessionId, error = error))
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopSelfIfIdle(startId: Int) {
        if (tunPfd == null && tunnelStatus.status.value is TunnelStatus.Stopped) {
            stopSelf(startId)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
            Intent(this, NothingVpnService::class.java).setAction(ACTION_DISCONNECT)
                .putExtra(EXTRA_USER_DISCONNECT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NothingVpnApp.CHANNEL_VPN)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(pending)
            .addAction(0, getString(R.string.action_disconnect), disconnect)
    }

    private fun isSafeNotificationUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching {
            val uri = Uri.parse(raw)
            val host = uri.host?.lowercase() ?: return@runCatching false
            uri.scheme.equals("https", ignoreCase = true) &&
                !isLocalOrPrivateHost(host)
        }.getOrDefault(false)
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val normalized = host.trim().trim('[', ']')
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            return true
        }
        if (normalized == "::1" || normalized == "::" || normalized == "0:0:0:0:0:0:0:1") {
            return true
        }
        if (normalized.contains(':')) {
            return normalized.startsWith("fe80:") ||
                normalized.startsWith("fc") ||
                normalized.startsWith("fd")
        }
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            first == 127 ||
            first == 0 ||
            (first == 169 && second == 254) ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31) ||
            (first == 100 && second in 64..127)
    }

    private inner class VpnBoxHandler : CommandServerHandler {
        override fun serviceStop() {
            // Ядро само попросило остановиться: гасим сервис целиком, иначе статус остался бы Running.
            if (tearingDown) return
            requestStop(error = null)
        }

        override fun serviceReload() {
            scope.launch {
                lifecycleMutex.withLock {
                    // Сессия могла закончиться, пока ждали мьютекс.
                    val config = sessionConfig ?: return@withLock
                    val policy = lastPolicy ?: return@withLock
                    runCatching {
                        commandServer.startOrReloadService(config, buildOverrideOptions(policy))
                    }.onFailure { e ->
                        SafeAndroidLog.e(VPN_LOG_TAG, "serviceReload", e)
                        ConnectionEventLog.add(this@NothingVpnService, "VPN service: reload failed (${e.toVpnError().code})")
                        requestStopLocked(error = e.toVpnError())
                    }
                }
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

        const val ACTION_CONNECT = "com.nothing.mobilecore.action.CONNECT"
        const val ACTION_DISCONNECT = "com.nothing.mobilecore.action.DISCONNECT"
        const val EXTRA_USER_DISCONNECT = "user_disconnect"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_SESSION_ID = "session_id"
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
