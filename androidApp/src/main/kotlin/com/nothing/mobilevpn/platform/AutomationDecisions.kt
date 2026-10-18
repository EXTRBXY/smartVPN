package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState

/**
 * Что делать автоматизации при очередном поводе подключиться (сеть, загрузка, health-check).
 * [markRequested] — запомнить намерение «VPN нужен» (недоверенный Wi-Fi, Always-on); ставится и тогда,
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

/** Повод для попытки автоподключения. */
internal enum class AutoConnectTrigger {
    /** Появилась сеть или восстановление после health-check: нужен `autoReconnect`. */
    AUTOMATIC,

    /** Загрузка устройства / обновление пакета при `autoStartAfterBoot`. */
    BOOT,

    /**
     * Система воссоздала сервис после смерти процесса (`START_STICKY`, `intent == null`).
     * Пользователь не отключал VPN, значит возвращаем туннель независимо от `autoReconnect`.
     */
    PROCESS_RESTART,

    /** Система запустила сервис как Always-on VPN (`VpnService.SERVICE_INTERFACE`). */
    ALWAYS_ON,
}

/** Входные данные решения: всё, что [VpnAutomationCoordinator] читает из платформы. */
internal data class AutoConnectInput(
    val settings: AutomationSettings,
    val connectionRequested: Boolean,
    val trigger: AutoConnectTrigger,
    val wifiSsid: String?,
    val tunnel: TunnelStatus,
    val session: VpnSessionState,
    val vpnPermissionNeeded: Boolean,
)

/**
 * Чистая функция решения об автоподключении (без Android и побочных эффектов), чтобы её можно
 * было покрыть unit-тестами. Порядок проверок повторяет прежний `maybeConnect`.
 *
 * Always-on и перезапуск процесса возвращают туннель, который система или пользователь уже
 * требовали, поэтому «доверенный Wi-Fi» их не останавливает. Always-on к тому же сам по себе
 * означает «VPN нужен» и помечает подключение запрошенным.
 */
internal fun decideAutoConnect(input: AutoConnectInput): AutoConnectDecision {
    val s = input.settings
    val wifi = input.wifiSsid
    val trigger = input.trigger
    val restoring = trigger == AutoConnectTrigger.PROCESS_RESTART || trigger == AutoConnectTrigger.ALWAYS_ON
    if (!restoring && s.connectOnUntrustedWifi && wifi != null && wifi in s.trustedWifiNames) {
        return AutoConnectDecision(markRequested = false, skip = SkipReason.TRUSTED_WIFI)
    }
    val requestedReconnect = when (trigger) {
        AutoConnectTrigger.AUTOMATIC -> s.autoReconnect && input.connectionRequested
        AutoConnectTrigger.BOOT, AutoConnectTrigger.PROCESS_RESTART -> input.connectionRequested
        AutoConnectTrigger.ALWAYS_ON -> true
    }
    val untrustedWifi = s.connectOnUntrustedWifi && wifi != null
    val markRequested = trigger == AutoConnectTrigger.ALWAYS_ON || untrustedWifi
    fun skip(reason: SkipReason) = AutoConnectDecision(markRequested = markRequested, skip = reason)
    if (!requestedReconnect && !untrustedWifi) return skip(SkipReason.NOT_REQUESTED)
    if (input.tunnel is TunnelStatus.Running || input.tunnel is TunnelStatus.Starting) return skip(SkipReason.ALREADY_ACTIVE)
    if (input.vpnPermissionNeeded) return skip(SkipReason.VPN_PERMISSION_REQUIRED)
    val session = input.session
    if (session is VpnSessionState.Connecting || session is VpnSessionState.Recovering || session is VpnSessionState.Connected) {
        return skip(SkipReason.ALREADY_ACTIVE)
    }
    return AutoConnectDecision(markRequested = markRequested, skip = null)
}

/** Перезапускать ли туннель после очередного провала health-check. */
internal fun shouldRestartAfterHealthFailures(
    consecutiveFailures: Int,
    settings: AutomationSettings,
    connectionRequested: Boolean,
): Boolean = consecutiveFailures >= HEALTH_FAILURES_BEFORE_RESTART && settings.autoReconnect && connectionRequested

internal const val HEALTH_FAILURES_BEFORE_RESTART = 2
