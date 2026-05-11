package com.nothing.mobilecore.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nothing.mobilecore.platform.AndroidSystemVpn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLifecycleInstrumentationTest {
    @Test
    fun disconnectAction_clearsPersistedRunningState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VpnRunningState.publish(context, true)
        AndroidSystemVpn(context).stopVpn()

        val deadline = System.currentTimeMillis() + 4_000L
        var running = VpnRunningState.readPersisted(context)
        while (running && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            running = VpnRunningState.readPersisted(context)
        }

        assertFalse(running)
    }

    @Test
    fun contracts_useNeutralActionPrefix() {
        assertEquals("com.nothing.mobilecore.action.CONNECT", NothingVpnService.ACTION_CONNECT)
        assertEquals("com.nothing.mobilecore.action.DISCONNECT", NothingVpnService.ACTION_DISCONNECT)
        assertEquals("com.nothing.mobilecore.action.VPN_CONNECT_RESULT", VpnConnectResultContract.ACTION)
        assertEquals("com.nothing.mobilecore.action.VPN_RUNNING_STATE", VpnRunningStateContract.ACTION)
    }

    @Test
    fun stopVpnAndAwait_returnsWhenAlreadyStopped() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VpnRunningState.publish(context, false)

        val result = AndroidSystemVpn(context).stopVpnAndAwait()

        assertTrue(result.isSuccess)
    }
}
