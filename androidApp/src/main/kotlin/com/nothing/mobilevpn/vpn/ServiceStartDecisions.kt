package com.nothing.mobilecore.vpn

import android.net.VpnService
import com.nothing.mobilecore.platform.AutoConnectTrigger

/** Чем вызван очередной `onStartCommand` сервиса. */
internal sealed interface ServiceStart {
    data object Connect : ServiceStart
    data object Disconnect : ServiceStart

    /**
     * Старт не от приложения: туннель нужно поднять заново из хранилища через
     * `VpnAutomationCoordinator` (секретов в `Intent` нет, файл конфига к этому моменту удалён).
     */
    data class Restore(val trigger: AutoConnectTrigger) : ServiceStart

    /** Неизвестное действие: ничего не делаем. */
    data object Ignore : ServiceStart
}

/**
 * Чистая классификация `Intent.action` (вынесена ради unit-тестов).
 * - `null` — система воссоздала `START_STICKY`-сервис после смерти процесса;
 * - [VpnService.SERVICE_INTERFACE] (`android.net.VpnService`) — запуск как Always-on VPN
 *   (загрузка, обновление пакета, включение Always-on в настройках).
 */
internal fun classifyServiceStart(action: String?): ServiceStart = when (action) {
    NothingVpnService.ACTION_CONNECT -> ServiceStart.Connect
    NothingVpnService.ACTION_DISCONNECT -> ServiceStart.Disconnect
    null -> ServiceStart.Restore(AutoConnectTrigger.PROCESS_RESTART)
    VpnService.SERVICE_INTERFACE -> ServiceStart.Restore(AutoConnectTrigger.ALWAYS_ON)
    else -> ServiceStart.Ignore
}
