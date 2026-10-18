package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.platform.storage.AndroidLegacySettingsSource
import com.nothing.mobilecore.platform.storage.AndroidLegacySource
import com.nothing.mobilecore.platform.storage.KeystoreVaultCipher
import com.nothing.mobilecore.platform.storage.VaultBootstrap
import com.nothing.mobilecore.platform.storage.VaultFile
import com.nothing.mobilecore.platform.storage.VaultStore
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.ProfileService
import com.nothing.mobilecore.shared.application.SingBoxConfigCompilerPort
import com.nothing.mobilecore.shared.application.VpnSessionController
import com.nothing.mobilecore.shared.infrastructure.SingBoxConfigCompiler
import com.nothing.mobilecore.vpn.TunnelStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class NothingVpnGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Зашифрованное хранилище профилей и политики; загружается в [NothingVpnApp.onCreate]. */
    private val vaultFile = VaultFile(File(appContext.noBackupFilesDir, "vault/state.bin"), KeystoreVaultCipher())
    internal val vaultStore = VaultStore(vaultFile)
    internal val vaultBootstrap = VaultBootstrap(vaultFile, AndroidLegacySource(appContext), AndroidLegacySettingsSource(appContext))

    /** Фоновые записи настроек; живёт столько же, сколько процесс. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appPreferences = AppPreferences(
        vaultStore,
        scope,
        onWriteFailed = { ConnectionEventLog.add(appContext, "Settings write failed: ${it.javaClass.simpleName}") },
    )
    val profileRepository: ProfileRepositoryPort = ProfileRepositoryAndroid(vaultStore)
    val configCompiler: SingBoxConfigCompilerPort = SingBoxConfigCompiler()
    val singBoxCoreInfo = AndroidSingBoxCoreInfo(appContext)
    val tunnelStatus = TunnelStatusRepository(appContext)
    val systemVpn = AndroidSystemVpn(appContext, tunnelStatus)
    val sessionConfigWriter = AndroidSessionConfigWriter(appContext)
    val tunnelStatsMonitor = TunnelStatsMonitor(tunnelStatus.status)
    val connectivityProbe: ConnectivityProbePort = AndroidConnectivityProbe()
    val wifiInfo = WifiInfoProvider(appContext)
    val installedApps = InstalledAppsRepository(appContext)
    val eventLog = EventLogStore(appContext)

    val sessionController = VpnSessionController(
        profileRepository = profileRepository,
        configCompiler = configCompiler,
        singBoxCoreInfo = singBoxCoreInfo,
        systemVpn = systemVpn,
        configWriter = sessionConfigWriter,
        eventLog = { ConnectionEventLog.add(appContext, it) },
    )
    val profileService = ProfileService(profileRepository, isTunnelBusy = { sessionController.isBusy })
}
