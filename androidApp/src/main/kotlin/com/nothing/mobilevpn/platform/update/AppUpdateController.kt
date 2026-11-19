package com.nothing.mobilecore.platform.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nothing.mobilecore.MainActivity
import com.nothing.mobilecore.NothingVpnApp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.shared.domain.AppRelease
import com.nothing.mobilecore.shared.domain.AppUpdatePolicy
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
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
    private val offerState = MutableStateFlow<AppRelease?>(null)
    private val busyState = MutableStateFlow(UpdateBusy.IDLE)
    private val signalsState = MutableSharedFlow<UpdateSignal>(extraBufferCapacity = 4)
    private val gate = Mutex()
    private val dismissedTag = AtomicReference<String?>(null)
    private lateinit var scope: CoroutineScope

    val offer: StateFlow<AppRelease?> = offerState.asStateFlow()
    val busy: StateFlow<UpdateBusy> = busyState.asStateFlow()
    val signals: SharedFlow<UpdateSignal> = signalsState.asSharedFlow()

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            preferences.awaitReady()
            dismissedTag.compareAndSet(null, preferences.updateDismissedTag())
            while (isActive) {
                check(force = false)
                delay(AppUpdatePolicy.POLL_INTERVAL_MS)
            }
        }
    }

    fun checkNow() {
        if (!::scope.isInitialized) return
        scope.launch { check(force = true) }
    }

    fun dismiss() {
        val tag = offerState.value?.tag ?: return
        dismissedTag.set(tag)
        preferences.dismissUpdate(tag)
        offerState.value = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun download() {
        val release = offerState.value ?: return
        if (!::scope.isInitialized) return
        scope.launch {
            if (!gate.tryLock()) return@launch
            busyState.value = UpdateBusy.DOWNLOADING
            try {
                val file = withContext(Dispatchers.IO) { downloader.download(release) }
                signalsState.tryEmit(UpdateSignal.Install(file))
            } catch (_: IOException) {
                signalsState.tryEmit(UpdateSignal.DownloadFailed)
            } finally {
                busyState.value = UpdateBusy.IDLE
                gate.unlock()
            }
        }
    }

    private suspend fun check(force: Boolean) {
        if (!gate.tryLock()) return
        try {
            if (!force && !AppUpdatePolicy.isCheckDue(preferences.updateLastCheckEpochMs(), clock())) return
            busyState.value = UpdateBusy.CHECKING
            val release = try {
                withContext(Dispatchers.IO) { client.latest() }
            } catch (_: IOException) {
                if (force) signalsState.tryEmit(UpdateSignal.CheckFailed)
                return
            }
            preferences.recordUpdateCheck(clock())
            val dismissed = dismissedTag.get().orEmpty()
            val offer = release?.takeIf {
                AppUpdatePolicy.isNewer(it.semver, currentVersion) &&
                    AppUpdatePolicy.shouldOffer(dismissed, it.tag) &&
                    AppUpdatePolicy.downloadUrlProblem(it.downloadUrl, requireFileName = true) == null
            }
            publish(offer)
            if (force && offer == null) signalsState.tryEmit(UpdateSignal.UpToDate)
        } finally {
            busyState.value = UpdateBusy.IDLE
            gate.unlock()
        }
    }

    private fun publish(release: AppRelease?) {
        val previous = offerState.value
        offerState.value = release
        if (release == null) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (previous?.tag == release.tag) return
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
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val NOTIFICATION_ID = 1002
    }
}
