package com.nothing.mobilecore.shared.domain

/**
 * Фактическое состояние туннеля на стороне платформы (VPN-сервис).
 *
 * Единственный источник истины о том, поднят ли туннель. Пишет его только платформенный
 * сервис; UI, плитка, автоматизация и [com.nothing.mobilecore.shared.application.VpnSessionController]
 * только читают. Состояние живёт в памяти процесса: после смерти процесса туннель гарантированно
 * не работает, поэтому начальное значение — [Stopped].
 *
 * [sessionId] связывает статус с конкретной попыткой запуска, чтобы ожидающий результат код
 * не принял за свой ответ статус предыдущей или следующей сессии.
 */
sealed interface TunnelStatus {
    data class Stopped(val sessionId: Long? = null, val error: String? = null) : TunnelStatus
    data class Starting(val sessionId: Long) : TunnelStatus
    data class Running(val sessionId: Long, val startedAtMillis: Long) : TunnelStatus
    data object Stopping : TunnelStatus
}
