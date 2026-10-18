package com.nothing.mobilecore.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.nothing.mobilecore.NothingVpnApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Сторож туннеля: периодическая persisted-задача JobScheduler, пока VPN работает и нужен
 * пользователю. Нужна на прошивках (ColorOS/realme), которые после краша процесса не
 * перезапускают `START_STICKY`-сервис и не стартуют Always-on: JobScheduler поднимет процесс
 * сам, а [TunnelWatchdogJobService] вернёт туннель. Решения — в `TunnelWatchdogDecisions.kt`.
 */
internal object TunnelWatchdog {
    private const val JOB_ID = 0x564E5701
    private val PERIOD_MILLIS = TimeUnit.MINUTES.toMillis(15)

    fun apply(context: Context, action: WatchdogScheduleAction) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        when (action) {
            WatchdogScheduleAction.SCHEDULE -> if (scheduler.getPendingJob(JOB_ID) == null) {
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, TunnelWatchdogJobService::class.java))
                    .setPeriodic(PERIOD_MILLIS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                val result = runCatching { scheduler.schedule(job) }.getOrDefault(JobScheduler.RESULT_FAILURE)
                ConnectionEventLog.add(
                    context,
                    if (result == JobScheduler.RESULT_SUCCESS) "Watchdog scheduled" else "Watchdog schedule failed",
                )
            }
            WatchdogScheduleAction.CANCEL -> if (scheduler.getPendingJob(JOB_ID) != null) {
                scheduler.cancel(JOB_ID)
                ConnectionEventLog.add(context, "Watchdog cancelled")
            }
            WatchdogScheduleAction.KEEP -> Unit
        }
    }
}

/** Срабатывание сторожа; в новом процессе `NothingVpnApp.onCreate` уже выполнен. */
class TunnelWatchdogJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as NothingVpnApp
        if (!shouldWatchdogRestore(app.graph.tunnelStatus.status.value)) return false
        app.applicationScope.launch {
            try {
                ConnectionEventLog.add(app, "Watchdog: tunnel missing after process start")
                app.automation.restoreTunnel(AutoConnectTrigger.PROCESS_RESTART)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** Периодическая задача всё равно сработает в следующем периоде; подключение не прерываем. */
    override fun onStopJob(params: JobParameters): Boolean = false
}
