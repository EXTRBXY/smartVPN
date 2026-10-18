package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.domain.TunnelStatus

/** Что сделать с задачей-сторожем ([TunnelWatchdogJobService]) при смене состояния. */
internal enum class WatchdogScheduleAction {
    /** Туннель работает и нужен пользователю: задача должна быть запланирована. */
    SCHEDULE,

    /** Пользователь отключил VPN: сторож не нужен. */
    CANCEL,

    /**
     * Промежуточное состояние (старт, остановка, `Stopped` при переподключении или после ошибки):
     * ничего не меняем, иначе reconnect снимал бы сторожа.
     */
    KEEP,
}

/**
 * Чистое решение о планировании сторожа. Ставим только при `Running`, снимаем только когда
 * пользователь больше не хочет VPN (`connectionRequested == false`).
 */
internal fun decideWatchdogSchedule(connectionRequested: Boolean, tunnel: TunnelStatus): WatchdogScheduleAction = when {
    !connectionRequested -> WatchdogScheduleAction.CANCEL
    tunnel is TunnelStatus.Running -> WatchdogScheduleAction.SCHEDULE
    else -> WatchdogScheduleAction.KEEP
}

/**
 * Восстанавливать ли туннель при срабатывании сторожа. Только если в этом процессе туннель ещё
 * ни разу не запускался (`Stopped` без `sessionId` — начальное значение процесса): значит, прежний
 * процесс погиб, а система не перезапустила сервис. В живом процессе после остановки с ошибкой
 * переподключением управляет обычная автоматизация (`autoReconnect`), сторож в это не вмешивается.
 * Остальные условия (`connectionRequested`, разрешение VPN) проверяет `decideAutoConnect`.
 */
internal fun shouldWatchdogRestore(tunnel: TunnelStatus): Boolean =
    tunnel is TunnelStatus.Stopped && tunnel.sessionId == null
