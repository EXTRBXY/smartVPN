package com.nothing.mobilecore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.nothing.mobilecore.libbox.LibboxAndroidBootstrap
import com.nothing.mobilecore.platform.NothingVpnGraph
import com.nothing.mobilecore.platform.ConnectionEventLog
import com.nothing.mobilecore.platform.VpnAutomationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.nothing.mobilecore.platform.storage.BootstrapOutcome

class NothingVpnApp : Application() {

    /** Scope на время жизни процесса: для операций, которые не должны обрываться вместе с плиткой/экраном. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val graph by lazy { NothingVpnGraph(this) }
    val automation by lazy { VpnAutomationCoordinator(this, graph) }

    override fun onCreate() {
        super.onCreate()
        loadStorage()
        LibboxAndroidBootstrap.attachBase(this)
        LibboxAndroidBootstrap.ensureSetup()
        automation.start()
        graph.tunnelStatsMonitor.start()
        val ch = NotificationChannel(
            CHANNEL_VPN,
            getString(R.string.vpn_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * Загрузка (и при необходимости миграция) зашифрованного хранилища в фоне. Репозиторий
     * профилей ждёт её завершения ([com.nothing.mobilecore.platform.storage.VaultStore.awaitReady]).
     */
    private fun loadStorage() {
        applicationScope.launch(Dispatchers.IO) {
            val result = runCatching { graph.vaultBootstrap.load() }
            result.onSuccess { (vault, outcome) ->
                graph.vaultStore.initialize(vault)
                if (outcome != BootstrapOutcome.LOADED) {
                    ConnectionEventLog.add(this@NothingVpnApp, "Storage: ${outcome.name.lowercase()}")
                }
            }.onFailure { e ->
                graph.vaultStore.initializeReadOnly()
                ConnectionEventLog.add(this@NothingVpnApp, "Storage: load failed, read-only (${e.javaClass.simpleName})")
            }
        }
    }

    companion object {
        const val CHANNEL_VPN = "nothing_vpn"
        const val NOTIFICATION_ID = 1001
    }
}
