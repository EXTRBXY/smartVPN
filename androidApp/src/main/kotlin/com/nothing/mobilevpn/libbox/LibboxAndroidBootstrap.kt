package com.nothing.mobilevpn.libbox

import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.nothing.mobilevpn.BuildConfig
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Контекст процесса и однократная инициализация [Libbox.setup] (как в SFA).
 */
object LibboxAndroidBootstrap {
    lateinit var application: Application
        private set

    private val setupDone = AtomicBoolean(false)

    val connectivity: ConnectivityManager
        get() = application.getSystemService(ConnectivityManager::class.java)!!

    val packageManager: PackageManager
        get() = application.packageManager

    val wifiManager: WifiManager
        get() = application.getSystemService(WifiManager::class.java)!!

    fun attachBase(app: Application) {
        application = app
    }

    fun ensureSetup() {
        if (!setupDone.compareAndSet(false, true)) return
        val baseDir = application.filesDir.apply { mkdirs() }
        val workingDir = (application.getExternalFilesDir(null) ?: application.filesDir).apply { mkdirs() }
        val tempDir = application.cacheDir.apply { mkdirs() }
        runCatching {
            Libbox.setup(
                SetupOptions().also {
                    it.basePath = baseDir.path
                    it.workingPath = workingDir.path
                    it.tempPath = tempDir.path
                    it.fixAndroidStack = true
                    it.logMaxLines = 3000
                    it.debug = BuildConfig.DEBUG
                },
            )
        }.getOrElse {
            setupDone.set(false)
            throw it
        }
        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag().replace('-', '_'))
        }
    }
}
