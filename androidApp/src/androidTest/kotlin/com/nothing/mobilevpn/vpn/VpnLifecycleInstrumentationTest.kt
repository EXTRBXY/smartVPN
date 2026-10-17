package com.nothing.mobilecore.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nothing.mobilecore.NothingVpnApp
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLifecycleInstrumentationTest {
    @Test
    fun freshProcess_startsWithStoppedTunnel() {
        val app = ApplicationProvider.getApplicationContext<NothingVpnApp>()
        assertTrue(app.graph.tunnelStatus.status.value is TunnelStatus.Stopped)
    }

    @Test
    fun legacyRunningFlag_isDeletedOnStartup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("nothing_vpn_running_flag", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("running", true).commit()

        TunnelStatusRepository(context)

        assertFalse(
            context.getSharedPreferences("nothing_vpn_running_flag", android.content.Context.MODE_PRIVATE)
                .getBoolean("running", false),
        )
    }

    @Test
    fun contracts_useNeutralActionPrefix() {
        assertEquals("com.nothing.mobilecore.action.CONNECT", NothingVpnService.ACTION_CONNECT)
        assertEquals("com.nothing.mobilecore.action.DISCONNECT", NothingVpnService.ACTION_DISCONNECT)
    }

    @Test
    fun stopVpnAndAwait_returnsWhenAlreadyStopped() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NothingVpnApp>()

        val result = app.graph.systemVpn.stopVpnAndAwait()

        assertTrue(result.isSuccess)
    }

    @Test
    fun updatingProfile_replacesStoredSecret() = runBlocking {
        val repository = ApplicationProvider.getApplicationContext<NothingVpnApp>().graph.profileRepository
        val id = "instrumentation-secret-update"
        val original = VlessProfile(
            id = id,
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val replacement = "87654321-4321-4123-8123-cba987654321"
        try {
            repository.upsert(original)
            repository.upsert(original.copy(uuid = replacement))
            assertEquals(replacement, repository.listProfiles().single { it.id == id }.uuid)
        } finally {
            repository.delete(id)
        }
    }
}
