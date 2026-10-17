package com.nothing.mobilecore.vpn

import android.content.Context
import com.nothing.mobilecore.quicksettings.VpnQuickTileRefresh
import com.nothing.mobilecore.shared.domain.TunnelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Единственный источник истины о туннеле в процессе приложения. Пишет только [NothingVpnService];
 * плитка, UI, автоматизация и контроллер подписываются на [status].
 *
 * Состояние намеренно не сохраняется на диск: сервис живёт в том же процессе, поэтому после
 * смерти процесса (убит системой, перезагрузка) туннель гарантированно не работает и начальное
 * [TunnelStatus.Stopped] верно. Сохранённый флаг из прошлых версий давал «Вкл» на плитке
 * без туннеля и блокировал автозапуск после загрузки.
 */
class TunnelStatusRepository(context: Context) {
    private val app = context.applicationContext
    private val sessionIds = AtomicLong(0)
    private val mutableStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped())

    val status: StateFlow<TunnelStatus> = mutableStatus.asStateFlow()

    init {
        // Флаг «запущено» из версий до перехода на StateFlow: после обновления он мог остаться true.
        app.deleteSharedPreferences(LEGACY_RUNNING_PREFS)
    }

    fun newSessionId(): Long = sessionIds.incrementAndGet()

    fun publish(status: TunnelStatus) {
        mutableStatus.value = status
        VpnQuickTileRefresh.requestUpdate(app)
    }

    private companion object {
        const val LEGACY_RUNNING_PREFS = "nothing_vpn_running_flag"
    }
}
