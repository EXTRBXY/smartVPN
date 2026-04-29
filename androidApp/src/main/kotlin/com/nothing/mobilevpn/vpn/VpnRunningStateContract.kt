package com.nothing.mobilevpn.vpn

/** Локальный broadcast: VPN поднят или полностью остановлен (для плитки Quick Settings и др.). */
object VpnRunningStateContract {
    const val ACTION = "com.nothing.mobilevpn.action.VPN_RUNNING_STATE"
    const val EXTRA_RUNNING = "running"
}
