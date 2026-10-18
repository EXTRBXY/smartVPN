package com.nothing.mobilecore.vpn

import com.nothing.mobilecore.platform.AutoConnectTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStartDecisionsTest {
    @Test
    fun appCommands() {
        assertEquals(ServiceStart.Connect, classifyServiceStart(NothingVpnService.ACTION_CONNECT))
        assertEquals(ServiceStart.Disconnect, classifyServiceStart(NothingVpnService.ACTION_DISCONNECT))
    }

    @Test
    fun nullIntent_isProcessRestart() {
        assertEquals(ServiceStart.Restore(AutoConnectTrigger.PROCESS_RESTART), classifyServiceStart(null))
    }

    @Test
    fun vpnServiceInterface_isAlwaysOn() {
        // Значение VpnService.SERVICE_INTERFACE: с ним система запускает Always-on VPN.
        assertEquals(ServiceStart.Restore(AutoConnectTrigger.ALWAYS_ON), classifyServiceStart("android.net.VpnService"))
    }

    @Test
    fun unknownAction_isIgnored() {
        assertEquals(ServiceStart.Ignore, classifyServiceStart("com.example.OTHER"))
    }
}
