package com.nothing.mobilecore.quicksettings

import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState
import org.junit.Test
import org.junit.Assert.assertEquals

class TileModelTest {
    private val stopped = TunnelStatus.Stopped()

    @Test
    fun runningTunnel_isOn() {
        val model = TileModel.from(
            hasProfile = true,
            state = VpnSessionState.Connected,
            tunnel = TunnelStatus.Running(sessionId = 1, startedAtMillis = 42),
            actionInProgress = false,
        )
        assertEquals(TileModel.On(42), model)
    }

    @Test
    fun connectedStateWithoutRunningTunnel_isNotOn() {
        // Контроллер ещё не обработал остановку туннеля: истина — статус туннеля.
        val model = TileModel.from(true, VpnSessionState.Connected, stopped, actionInProgress = false)
        assertEquals(TileModel.Off, model)
    }

    @Test
    fun startingTunnel_isConnecting() {
        val model = TileModel.from(true, VpnSessionState.Connecting, TunnelStatus.Starting(1), false)
        assertEquals(TileModel.Connecting, model)
    }

    @Test
    fun recoveringBetweenAttempts_isConnecting() {
        // Между попытками туннель кратко остановлен — плитка не должна мигать «Выкл».
        val model = TileModel.from(true, VpnSessionState.Recovering(2, 3), stopped, false)
        assertEquals(TileModel.Connecting, model)
    }

    @Test
    fun stoppingTunnel_isDisconnecting() {
        val model = TileModel.from(
            true,
            VpnSessionState.Connected,
            TunnelStatus.Stopping,
            actionInProgress = false,
        )
        assertEquals(TileModel.Disconnecting, model)
    }

    @Test
    fun pendingClickBeforeStateChange_isBusy() {
        assertEquals(TileModel.Connecting, TileModel.from(true, VpnSessionState.Disconnected, stopped, true))
    }

    @Test
    fun noProfile_isUnavailable() {
        assertEquals(TileModel.NoProfile, TileModel.from(false, VpnSessionState.Disconnected, stopped, false))
    }

    @Test
    fun stoppedTunnel_isOff() {
        assertEquals(TileModel.Off, TileModel.from(true, VpnSessionState.Disconnected, stopped, false))
    }
}
