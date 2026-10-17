package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VlessSecurity
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.toVpnError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileServiceTest {
    private val profile = VlessProfile(
        id = "p1",
        name = "test",
        uuid = "12345678-1234-4123-8123-123456789abc",
        host = "example.com",
        port = 443,
    )

    @Test
    fun delete_blocksActiveProfileWhileTunnelBusy() = runBlocking {
        val repo = VpnSessionControllerRecoveryTest.FakeProfileRepository(profile)
        val service = ProfileService(repo, isTunnelBusy = { true })

        val result = service.delete("p1")

        assertEquals(VpnError.ProfileDeleteRequiresDisconnect, result.exceptionOrNull()?.toVpnError())
        assertEquals(1, repo.listProfiles().size)
    }

    @Test
    fun delete_clearsActiveIdWhenIdle() = runBlocking {
        val repo = VpnSessionControllerRecoveryTest.FakeProfileRepository(profile)
        val service = ProfileService(repo, isTunnelBusy = { false })

        assertTrue(service.delete("p1").isSuccess)
        assertTrue(repo.listProfiles().isEmpty())
        assertNull(repo.getActiveProfileId())
    }

    @Test
    fun update_keepsIdAndTrimsFields() = runBlocking {
        val repo = VpnSessionControllerRecoveryTest.FakeProfileRepository(profile)
        val service = ProfileService(repo, isTunnelBusy = { false })

        val result = service.update("p1", profile.copy(name = " updated ", host = "new.example.com ", port = 8443))

        assertTrue(result.isSuccess)
        val updated = repo.listProfiles().single()
        assertEquals("p1", updated.id)
        assertEquals("updated", updated.name)
        assertEquals("new.example.com", updated.host)
        assertEquals(8443, updated.port)
    }

    @Test
    fun update_rejectsChangedId() = runBlocking {
        val service = ProfileService(VpnSessionControllerRecoveryTest.FakeProfileRepository(profile), isTunnelBusy = { false })

        val result = service.update("p1", profile.copy(id = "other"))

        assertEquals(VpnError.ProfileIdImmutable, result.exceptionOrNull()?.toVpnError())
    }

    @Test
    fun update_requiresBothRealityKeys() = runBlocking {
        val service = ProfileService(VpnSessionControllerRecoveryTest.FakeProfileRepository(profile), isTunnelBusy = { false })

        val result = service.update("p1", profile.copy(security = VlessSecurity.REALITY, realityPublicKey = "pbk"))

        assertEquals(VpnError.ProfileRealityIncomplete, result.exceptionOrNull()?.toVpnError())
    }

    @Test
    fun update_dropsRealityKeysForTls() = runBlocking {
        val repo = VpnSessionControllerRecoveryTest.FakeProfileRepository(profile)
        val service = ProfileService(repo, isTunnelBusy = { false })

        service.update("p1", profile.copy(security = VlessSecurity.TLS, realityPublicKey = "pbk", realityShortId = "sid")).getOrThrow()

        assertNull(repo.listProfiles().single().realityPublicKey)
        assertNull(repo.listProfiles().single().realityShortId)
    }

    @Test
    fun update_acceptsUuidVersion7LikeLinkParser() = runBlocking {
        // Раньше форма принимала только UUID v1–5, а парсер ссылок — v1–8.
        val service = ProfileService(VpnSessionControllerRecoveryTest.FakeProfileRepository(profile), isTunnelBusy = { false })

        val result = service.update("p1", profile.copy(uuid = "018f6b2e-1234-7123-8123-123456789abc"))

        assertTrue(result.isSuccess)
    }

    @Test
    fun importLink_activatesOnlyWhenAsked() = runBlocking {
        val repo = VpnSessionControllerRecoveryTest.FakeProfileRepository(profile)
        val service = ProfileService(repo, isTunnelBusy = { false })
        val link = "vless://12345678-1234-4123-8123-123456789abc@second.example:443?security=tls&type=tcp#second"

        val imported = service.importLink(link, activate = false).getOrThrow()
        assertEquals("p1", repo.getActiveProfileId())

        service.importLink(link, activate = true).getOrThrow()
        assertEquals(imported.id, repo.getActiveProfileId())
    }
}
