package com.nothing.mobilevpn.shared.domain

sealed class VpnSessionState {
    data object Disconnected : VpnSessionState()
    data object Connecting : VpnSessionState()
    data object Connected : VpnSessionState()
    data object Disconnecting : VpnSessionState()
    data class Error(val message: String) : VpnSessionState()
}
