package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState

/**
 * Что делать автоматизации при очередном поводе подключиться (сеть, загрузка, health-check).
 * [markRequested] — запомнить намерение «VPN нужен» (недоверенный Wi-Fi); ставится и тогда,
 * когда само подключение пропускается, — как в прежнем `maybeConnect`.
 */
internal data class AutoConnectDecision(val markRequested: Boolean, val skip: SkipReason?) {
    val connect: Boolean get() = skip == null
}

internal enum class SkipReason {
    TRUSTED_WIFI,
    NOT_REQUESTED,
    ALREADY_ACTIVE,
    VPN_PERMISSION_REQUIRED,
}

/** Входные данные решения: всё, что [VpnAutomationCoordinator] читает из платформы. */
internal data class AutoConnectInput(
    val settings: AutomationSettings,
    val connectionRequested: Boolean,
    val fromBoot: Boolean,
    val wifiSsid: String?,
    val tunnel: TunnelStatus,
    val session: VpnSessionState,
    val vpnPermissionNeeded: Boolean,
)

/**
 * Чистая функция решения об автоподключении (без Android и побочных эффектов), чтобы её можно
 * было покрыть unit-тестами. Порядок проверок повторяет прежний `maybeConnect`.
 */
internal fun decideAutoConnect(input: AutoConnectInput): AutoConnectDecision {
    val s = input.settings
    val wifi = input.wifiSsid
    if (s.connectOnUntrustedWifi && wifi != null && wifi in s.trustedWifiNames) {
        return AutoConnectDecision(markRequested = false, skip = SkipReason.TRUSTED_WIFI)
    }
    val requestedReconnect = (s.autoReconnect || input.fromBoot) && input.connectionRequested
    val untrustedWifi = s.connectOnUntrustedWifi && wifi != null
    fun skip(reason: SkipReason) = AutoConnectDecision(markRequested = untrustedWifi, skip = reason)
    if (!requestedReconnect && !untrustedWifi) return skip(SkipReason.NOT_REQUESTED)
    if (input.tunnel is TunnelStatus.Running || input.tunnel is TunnelStatus.Starting) return skip(SkipReason.ALREADY_ACTIVE)
    if (input.vpnPermissionNeeded) return skip(SkipReason.VPN_PERMISSION_REQUIRED)
    val session = input.session
    if (session is VpnSessionState.Connecting || session is VpnSessionState.Recovering || session is VpnSessionState.Connected) {
        return skip(SkipReason.ALREADY_ACTIVE)
    }
    return AutoConnectDecision(markRequested = untrustedWifi, skip = null)
}

/** Перезапускать ли туннель после очередного провала health-check. */
internal fun shouldRestartAfterHealthFailures(
    consecutiveFailures: Int,
    settings: AutomationSettings,
    connectionRequested: Boolean,
): Boolean = consecutiveFailures >= HEALTH_FAILURES_BEFORE_RESTART && settings.autoReconnect && connectionRequested

internal const val HEALTH_FAILURES_BEFORE_RESTART = 2
