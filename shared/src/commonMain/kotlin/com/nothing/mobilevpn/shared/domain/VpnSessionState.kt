package com.nothing.mobilecore.shared.domain

sealed class VpnSessionState {
    data object Disconnected : VpnSessionState()
    data object Connecting : VpnSessionState()
    data class Recovering(val attempt: Int, val maxAttempts: Int) : VpnSessionState()
    data object Connected : VpnSessionState()
    data object Disconnecting : VpnSessionState()
    data class Error(val error: VpnError) : VpnSessionState()
}
