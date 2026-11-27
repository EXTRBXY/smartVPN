package com.nothing.mobilecore.platform.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nothing.mobilecore.MainActivity
import com.nothing.mobilecore.NothingVpnApp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.shared.domain.AppRelease
import com.nothing.mobilecore.shared.domain.AppUpdatePolicy
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class UpdateBusy { IDLE, CHECKING, DOWNLOADING }

sealed interface UpdateSignal {
    data object CheckFailed : UpdateSignal
    data object UpToDate : UpdateSignal
    data object DownloadFailed : UpdateSignal
    data class Install(val file: java.io.File) : UpdateSignal
}

/**
 * Проверка релиза, пока жив процесс (VPN его держит): сразу и далее каждые 30 минут,
 * сам запрос — не чаще чем раз в 23,5 часа. Ручная проверка в настройках этот интервал не ждёт.
 */
class AppUpdateController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val client: GitHubReleaseClient,
    private val downloader: UpdateApkDownloader,
    private val currentVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val availableState = MutableStateFlow<AppRelease?>(null)
    private val promptState = MutableStateFlow<AppRelease?>(null)
    private val busyState = MutableStateFlow(UpdateBusy.IDLE)
    private val installReadyState = MutableStateFlow(false)
    private val downloadProgressState = MutableStateFlow<UpdateDownloadProgress?>(null)
    private val signalsState = MutableSharedFlow<UpdateSignal>(extraBufferCapacity = 4)
    private val gate = Mutex()
    private var dismissedTag = ""
    private var lastCheckAt: Long? = null
    private lateinit var scope: CoroutineScope

    val available: StateFlow<AppRelease?> = availableState.asStateFlow()
    val prompt: StateFlow<AppRelease?> = promptState.asStateFlow()
    val busy: StateFlow<UpdateBusy> = busyState.asStateFlow()
    val installReady: StateFlow<Boolean> = installReadyState.asStateFlow()
    val downloadProgress: StateFlow<UpdateDownloadProgress?> = downloadProgressState.asStateFlow()
    val signals: SharedFlow<UpdateSignal> = signalsState.asSharedFlow()

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                check(manual = false)
                delay(AppUpdatePolicy.POLL_INTERVAL_MS)
            }
        }
    }

    fun checkNow() {
        if (!::scope.isInitialized) return
        scope.launch(Dispatchers.Main.immediate) { check(manual = true) }
    }

    fun dismiss(tag: String) {
        if (promptState.value?.tag != tag) return
        dismissedTag = tag
        preferences.dismissUpdate(tag)
        promptState.value = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun prepareInstall() {
        if (!::scope.isInitialized) return
        scope.launch(Dispatchers.Main.immediate) {
            if (!gate.tryLock()) return@launch
            try {
                val release = availableState.value ?: return@launch
                val cached = withContext(Dispatchers.IO) { downloader.cached(release) }
                val file = cached ?: run {
                    installReadyState.value = false
                    busyState.value = UpdateBusy.DOWNLOADING
                    withContext(Dispatchers.IO) {
                        downloader.download(release) { downloadProgressState.value = it }
                    }
                }
                installReadyState.value = true
                signalsState.tryEmit(UpdateSignal.Install(file))
            } catch (_: IOException) {
                signalsState.tryEmit(UpdateSignal.DownloadFailed)
            } finally {
                busyState.value = UpdateBusy.IDLE
                downloadProgressState.value = null
                gate.unlock()
            }
        }
    }

    private suspend fun check(manual: Boolean) {
        if (!gate.tryLock()) return
        try {
            if (lastCheckAt == null) {
                preferences.awaitReady()
                dismissedTag = preferences.updateDismissedTag()
                val cached = preferences.lastUpdateCheck()
                setAvailableRelease(AppUpdatePolicy.availableRelease(cached?.release, currentVersion))
                lastCheckAt = when {
                    cached == null -> 0
                    cached.release != null && availableState.value == null -> 0
                    else -> cached.checkedAtEpochMs
                }
            }
            if (!manual && !AppUpdatePolicy.isCheckDue(checkNotNull(lastCheckAt), clock())) return
            busyState.value = UpdateBusy.CHECKING
            val release = try {
                withContext(Dispatchers.IO) { client.latest() }
            } catch (_: IOException) {
                if (manual) signalsState.tryEmit(UpdateSignal.CheckFailed)
                return
            }
            val checkedAt = clock()
            val available = AppUpdatePolicy.availableRelease(release, currentVersion)
            lastCheckAt = checkedAt
            preferences.recordUpdateCheck(checkedAt, available)
            publish(available, manual)
            if (manual && available == null) signalsState.tryEmit(UpdateSignal.UpToDate)
        } finally {
            busyState.value = UpdateBusy.IDLE
            gate.unlock()
        }
    }

    private suspend fun setAvailableRelease(release: AppRelease?) {
        installReadyState.value = release != null && withContext(Dispatchers.IO) { downloader.cached(release) != null }
        availableState.value = release
    }

    private suspend fun publish(release: AppRelease?, manual: Boolean) {
        val previousTag = availableState.value?.tag
        setAvailableRelease(release)
        if (previousTag != release?.tag) {
            promptState.value = null
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
        if (release == null || manual || previousTag == release.tag ||
            !AppUpdatePolicy.shouldNotify(dismissedTag, release.tag)
        ) return

        promptState.value = release
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NothingVpnApp.CHANNEL_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, release.semver))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 1002
    }
}
