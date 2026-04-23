package com.nothing.mobilevpn.vpn

/**
 * Результат попытки поднять sing-box после [NothingVpnService.ACTION_CONNECT].
 * Отправляется только в пакет приложения ([Intent.setPackage]).
 */
object VpnConnectResultContract {
    const val ACTION = "com.nothing.mobilevpn.action.VPN_CONNECT_RESULT"
    const val EXTRA_OK = "ok"
    const val EXTRA_MESSAGE = "message"
}
