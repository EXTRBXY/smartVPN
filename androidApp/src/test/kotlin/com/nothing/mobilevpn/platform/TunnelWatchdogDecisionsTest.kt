package com.nothing.mobilecore.platform

import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelWatchdogDecisionsTest {
    private val running = TunnelStatus.Running(sessionId = 3, startedAtMillis = 1)

    @Test
    fun runningAndRequested_schedules() {
        assertEquals(WatchdogScheduleAction.SCHEDULE, decideWatchdogSchedule(connectionRequested = true, tunnel = running))
    }

    @Test
    fun userDisconnect_cancelsInAnyTunnelState() {
        listOf(running, TunnelStatus.Stopping, TunnelStatus.Stopped(sessionId = 3), TunnelStatus.Starting(4)).forEach {
            assertEquals(WatchdogScheduleAction.CANCEL, decideWatchdogSchedule(connectionRequested = false, tunnel = it))
        }
    }

    @Test
    fun transientStatesWhileRequested_keepWatchdog() {
        listOf(
            TunnelStatus.Starting(4),
            TunnelStatus.Stopping,
            TunnelStatus.Stopped(sessionId = 3),
            TunnelStatus.Stopped(sessionId = 3, error = VpnError.VpnRevoked),
            TunnelStatus.Stopped(),
        ).forEach {
            assertEquals(WatchdogScheduleAction.KEEP, decideWatchdogSchedule(connectionRequested = true, tunnel = it))
        }
    }

    @Test
    fun restore_onlyInFreshProcess() {
        assertTrue(shouldWatchdogRestore(TunnelStatus.Stopped()))
        assertFalse(shouldWatchdogRestore(TunnelStatus.Stopped(sessionId = 3)))
        assertFalse(shouldWatchdogRestore(TunnelStatus.Stopped(sessionId = 3, error = VpnError.VpnRevoked)))
        assertFalse(shouldWatchdogRestore(running))
        assertFalse(shouldWatchdogRestore(TunnelStatus.Starting(4)))
        assertFalse(shouldWatchdogRestore(TunnelStatus.Stopping))
    }
}
