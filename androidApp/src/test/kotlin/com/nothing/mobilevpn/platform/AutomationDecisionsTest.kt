package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDecisionsTest {
    private val off = AutomationSettings(
        autoStartAfterBoot = false,
        autoReconnect = false,
        connectOnUntrustedWifi = false,
        trustedWifiNames = emptySet(),
        periodicHealthCheck = false,
    )
    private val idle = AutoConnectInput(
        settings = off,
        connectionRequested = false,
        trigger = AutoConnectTrigger.AUTOMATIC,
        wifiSsid = null,
        tunnel = TunnelStatus.Stopped(),
        session = VpnSessionState.Disconnected,
        vpnPermissionNeeded = false,
    )

    @Test
    fun nothingEnabled_skips() {
        assertEquals(SkipReason.NOT_REQUESTED, decideAutoConnect(idle).skip)
    }

    @Test
    fun autoReconnect_connectsOnlyWhenUserWantsVpn() {
        val settings = off.copy(autoReconnect = true)
        assertEquals(SkipReason.NOT_REQUESTED, decideAutoConnect(idle.copy(settings = settings)).skip)

        val decision = decideAutoConnect(idle.copy(settings = settings, connectionRequested = true))
        assertTrue(decision.connect)
        assertFalse(decision.markRequested)
    }

    @Test
    fun boot_connectsWhenRequestedEvenWithoutAutoReconnect() {
        assertTrue(decideAutoConnect(idle.copy(trigger = AutoConnectTrigger.BOOT, connectionRequested = true)).connect)
    }

    @Test
    fun untrustedWifi_connectsAndMarksRequested() {
        val settings = off.copy(connectOnUntrustedWifi = true, trustedWifiNames = setOf("Home"))
        val decision = decideAutoConnect(idle.copy(settings = settings, wifiSsid = "Cafe"))
        assertTrue(decision.connect)
        assertTrue(decision.markRequested)
    }

    @Test
    fun trustedWifi_skipsEvenIfReconnectRequested() {
        val settings = off.copy(autoReconnect = true, connectOnUntrustedWifi = true, trustedWifiNames = setOf("Home"))
        val decision = decideAutoConnect(idle.copy(settings = settings, connectionRequested = true, wifiSsid = "Home"))
        assertEquals(SkipReason.TRUSTED_WIFI, decision.skip)
        assertFalse(decision.markRequested)
    }

    @Test
    fun unknownSsid_isNotTreatedAsUntrusted() {
        // Без разрешения на геоданные SSID неизвестен — не подключаемся «на всякий случай».
        val settings = off.copy(connectOnUntrustedWifi = true)
        assertEquals(SkipReason.NOT_REQUESTED, decideAutoConnect(idle.copy(settings = settings, wifiSsid = null)).skip)
    }

    @Test
    fun runningOrStartingTunnel_skips() {
        val wants = idle.copy(settings = off.copy(autoReconnect = true), connectionRequested = true)
        assertEquals(SkipReason.ALREADY_ACTIVE, decideAutoConnect(wants.copy(tunnel = TunnelStatus.Running(1, 0))).skip)
        assertEquals(SkipReason.ALREADY_ACTIVE, decideAutoConnect(wants.copy(tunnel = TunnelStatus.Starting(1))).skip)
        assertEquals(SkipReason.ALREADY_ACTIVE, decideAutoConnect(wants.copy(session = VpnSessionState.Connecting)).skip)
    }

    @Test
    fun missingVpnPermission_skipsButKeepsUntrustedWifiIntent() {
        val settings = off.copy(connectOnUntrustedWifi = true)
        val decision = decideAutoConnect(idle.copy(settings = settings, wifiSsid = "Cafe", vpnPermissionNeeded = true))
        assertEquals(SkipReason.VPN_PERMISSION_REQUIRED, decision.skip)
        assertTrue(decision.markRequested)
    }

    @Test
    fun healthRestart_requiresTwoFailuresAutoReconnectAndIntent() {
        val on = off.copy(autoReconnect = true)
        assertFalse(shouldRestartAfterHealthFailures(1, on, connectionRequested = true))
        assertTrue(shouldRestartAfterHealthFailures(2, on, connectionRequested = true))
        assertFalse(shouldRestartAfterHealthFailures(2, off, connectionRequested = true))
        assertFalse(shouldRestartAfterHealthFailures(2, on, connectionRequested = false))
    }

    @Test
    fun processRestart_restoresRequestedTunnelWithoutAutoReconnect() {
        val restart = idle.copy(trigger = AutoConnectTrigger.PROCESS_RESTART)
        assertEquals(SkipReason.NOT_REQUESTED, decideAutoConnect(restart).skip)

        val decision = decideAutoConnect(restart.copy(connectionRequested = true))
        assertTrue(decision.connect)
        assertFalse(decision.markRequested)
    }

    @Test
    fun processRestart_ignoresTrustedWifi() {
        // Туннель работал до смерти процесса — возвращаем его и в доверенной сети.
        val settings = off.copy(connectOnUntrustedWifi = true, trustedWifiNames = setOf("Home"))
        val decision = decideAutoConnect(
            idle.copy(settings = settings, trigger = AutoConnectTrigger.PROCESS_RESTART, connectionRequested = true, wifiSsid = "Home"),
        )
        assertTrue(decision.connect)
    }

    @Test
    fun alwaysOn_connectsAndMarksRequestedEvenIfUserDisconnectedBefore() {
        val settings = off.copy(connectOnUntrustedWifi = true, trustedWifiNames = setOf("Home"))
        val decision = decideAutoConnect(
            idle.copy(settings = settings, trigger = AutoConnectTrigger.ALWAYS_ON, connectionRequested = false, wifiSsid = "Home"),
        )
        assertTrue(decision.connect)
        assertTrue(decision.markRequested)
    }

    @Test
    fun alwaysOn_skipsWhenAlreadyActiveOrNoPermission() {
        val alwaysOn = idle.copy(trigger = AutoConnectTrigger.ALWAYS_ON)
        assertEquals(SkipReason.ALREADY_ACTIVE, decideAutoConnect(alwaysOn.copy(tunnel = TunnelStatus.Running(1, 0))).skip)
        assertEquals(SkipReason.ALREADY_ACTIVE, decideAutoConnect(alwaysOn.copy(session = VpnSessionState.Connecting)).skip)
        assertEquals(SkipReason.VPN_PERMISSION_REQUIRED, decideAutoConnect(alwaysOn.copy(vpnPermissionNeeded = true)).skip)
    }

    @Test
    fun automaticTrigger_stillNeedsAutoReconnect() {
        assertEquals(SkipReason.NOT_REQUESTED, decideAutoConnect(idle.copy(connectionRequested = true)).skip)
    }
}
