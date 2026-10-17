package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VpnSessionControllerRecoveryTest {
    @Test
    fun connecting_ignoresExpectedIntermediateStoppedBroadcast() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(
            profile,
            TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = true),
        )
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0, connectDelayMs = 100)
        val ctrl = VpnSessionController(
            repo,
            compiler,
            SingBoxCoreInfoPort { "1.0.0" },
            systemVpn,
            SessionConfigWriter { "/tmp/session.json" },
        )

        val connecting = async { ctrl.connectSuspend() }
        delay(10)
        systemVpn.tunnelStatus.value = TunnelStatus.Stopped()
        delay(10)

        assertTrue(ctrl.state.value is VpnSessionState.Connecting)
        assertNull(ctrl.lastError.value)
        assertTrue(connecting.await().isSuccess)
        assertTrue(ctrl.state.value is VpnSessionState.Connected)
    }

    @Test
    fun connectSuspend_retriesUntilSuccess() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile, TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = false))
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 2)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val result = ctrl.connectSuspend()

        assertTrue(result.isSuccess)
        assertEquals(VpnSessionState.Connected, ctrl.state.value)
        assertEquals(3, systemVpn.startCalls)
    }

    @Test
    fun connectSuspend_failsWhenProfileSecretUnavailable() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile)
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val result = ctrl.connectSuspend()

        assertTrue(result.isFailure)
        assertEquals(0, systemVpn.startCalls)
    }

    @Test
    fun connectSuspend_retriesWhenSmokeCheckFails() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile, TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = false))
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0, failSmokeTimes = 2)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val result = ctrl.connectSuspend()

        assertTrue(result.isSuccess)
        assertEquals(3, systemVpn.startCalls)
    }

    @Test
    fun connectSuspend_failClosedStopsAfterFirstFailure() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile, TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = true))
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 3)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val result = ctrl.connectSuspend()

        assertTrue(result.isFailure)
        assertEquals(1, systemVpn.startCalls)
    }

    @Test
    fun connectSuspend_singleFlightPreventsDuplicateStarts() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile, TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = false))
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0, connectDelayMs = 100)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val first = async { ctrl.connectSuspend() }
        val second = async { ctrl.connectSuspend() }
        val results = awaitAll(first, second)

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, systemVpn.startCalls)
    }

    @Test
    fun disconnectSuspend_waitsForConfirmedStop() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile, TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = false))
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0, stopDelayMs = 50)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)
        ctrl.connectSuspend()

        val result = ctrl.disconnectSuspend()

        assertTrue(result.isSuccess)
        assertEquals(1, systemVpn.stopAwaitCalls)
        assertEquals(VpnSessionState.Disconnected, ctrl.state.value)
    }

    @Test
    fun deleteProfileSuspend_blocksDeletingActiveWhileConnected() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile)
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)
        ctrl.connectSuspend()

        val result = ctrl.deleteProfileSuspend("p1")

        assertTrue(result.isFailure)
        assertEquals("profile_delete_requires_disconnect", result.exceptionOrNull()?.message)
        assertEquals(1, repo.listProfiles().size)
    }

    @Test
    fun deleteProfileSuspend_clearsActiveIdWhenDisconnected() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile)
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val result = ctrl.deleteProfileSuspend("p1")

        assertTrue(result.isSuccess)
        assertTrue(repo.listProfiles().isEmpty())
        assertNull(repo.getActiveProfileId())
    }

    @Test
    fun updateProfileSuspend_updatesFieldsWithoutChangingId() = runBlocking {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val repo = FakeProfileRepository(profile)
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val writer = SessionConfigWriter { "/tmp/session.json" }
        val ctrl = VpnSessionController(repo, compiler, SingBoxCoreInfoPort { "1.0.0" }, systemVpn, writer)

        val result = ctrl.updateProfileSuspend(
            profileId = "p1",
            draft = profile.copy(name = "updated", host = "new.example.com", port = 8443),
        )

        assertTrue(result.isSuccess)
        val updated = repo.listProfiles().single()
        assertEquals("p1", updated.id)
        assertEquals("updated", updated.name)
        assertEquals("new.example.com", updated.host)
        assertEquals(8443, updated.port)
    }

    @Test
    fun externalStop_movesConnectedToDisconnectedWithReason() = runBlocking {
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val ctrl = newController(systemVpn)
        assertTrue(ctrl.connectSuspend().isSuccess)

        systemVpn.tunnelStatus.value = TunnelStatus.Stopped(sessionId = 1, error = "vpn_revoked")

        awaitState(ctrl, VpnSessionState.Disconnected)
        assertEquals("vpn_revoked", ctrl.lastError.value)
    }

    @Test
    fun externalUserStop_doesNotReportError() = runBlocking {
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val ctrl = newController(systemVpn)
        assertTrue(ctrl.connectSuspend().isSuccess)

        systemVpn.tunnelStatus.value = TunnelStatus.Stopped(sessionId = 1)

        awaitState(ctrl, VpnSessionState.Disconnected)
        assertNull(ctrl.lastError.value)
    }

    @Test
    fun connectAfterExternalStop_startsTunnelAgain() = runBlocking {
        // Раньше: отключение из уведомления при закрытом приложении оставляло контроллер в Connected,
        // и следующий connect «успешно» ничего не делал — плитка зависала на «Подключение…».
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val ctrl = newController(systemVpn)
        assertTrue(ctrl.connectSuspend().isSuccess)
        systemVpn.tunnelStatus.value = TunnelStatus.Stopped(sessionId = 1)
        awaitState(ctrl, VpnSessionState.Disconnected)

        assertTrue(ctrl.connectSuspend().isSuccess)

        assertEquals(2, systemVpn.startCalls)
        assertEquals(VpnSessionState.Connected, ctrl.state.value)
    }

    @Test
    fun connectedWhileTunnelStopped_isNotTreatedAsAlreadyConnected() = runBlocking {
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val ctrl = newController(systemVpn)
        assertTrue(ctrl.connectSuspend().isSuccess)
        // Статус туннеля сменился, а контроллер мог ещё не успеть его обработать.
        systemVpn.tunnelStatus.value = TunnelStatus.Stopped(sessionId = 1)

        assertTrue(ctrl.connectSuspend().isSuccess)

        assertEquals(2, systemVpn.startCalls)
    }

    @Test
    fun runningTunnelWhileDisconnected_isReflectedAsConnected() = runBlocking {
        val systemVpn = FakeSystemVpn(failStartTimes = 0)
        val ctrl = newController(systemVpn)

        systemVpn.tunnelStatus.value = TunnelStatus.Running(sessionId = 7, startedAtMillis = 0L)

        awaitState(ctrl, VpnSessionState.Connected)
    }

    private fun newController(systemVpn: FakeSystemVpn): VpnSessionController {
        val profile = VlessProfile(
            id = "p1",
            name = "test",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
        )
        val compiler = object : SingBoxConfigCompilerPort {
            override fun compile(profile: VlessProfile, policy: TunnelPolicy): Result<String> = Result.success("{}")
        }
        return VpnSessionController(
            FakeProfileRepository(profile, TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = true)),
            compiler,
            SingBoxCoreInfoPort { "1.0.0" },
            systemVpn,
            SessionConfigWriter { "/tmp/session.json" },
        )
    }

    private suspend fun awaitState(ctrl: VpnSessionController, expected: VpnSessionState) {
        withTimeout(2_000) { ctrl.state.first { it == expected } }
    }

    private class FakeProfileRepository(
        private val profile: VlessProfile,
        initialPolicy: TunnelPolicy = TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = false),
    ) : ProfileRepositoryPort {
        private val profilesFlow = MutableStateFlow(listOf(profile))
        private var policy = initialPolicy
        private var activeProfileId: String? = profile.id
        override fun observeProfiles(): Flow<List<VlessProfile>> = profilesFlow
        override suspend fun listProfiles(): List<VlessProfile> = profilesFlow.value
        override suspend fun upsert(profile: VlessProfile) {
            val current = profilesFlow.value.toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                current[idx] = profile
            } else {
                current.add(profile)
            }
            profilesFlow.value = current
        }
        override suspend fun delete(profileId: String) {
            profilesFlow.value = profilesFlow.value.filterNot { it.id == profileId }
            if (activeProfileId == profileId) {
                activeProfileId = null
            }
        }
        override suspend fun getActiveProfileId(): String? = activeProfileId
        override suspend fun setActiveProfileId(id: String?) {
            activeProfileId = id
        }
        override suspend fun getTunnelPolicy(): TunnelPolicy = policy
        override suspend fun setTunnelPolicy(policy: TunnelPolicy) {
            this.policy = policy
        }
    }

    private class FakeSystemVpn(
        private val failStartTimes: Int,
        private val failSmokeTimes: Int = 0,
        private val connectDelayMs: Long = 0,
        private val stopDelayMs: Long = 0,
    ) : SystemVpnPort {
        var startCalls: Int = 0
            private set
        var stopAwaitCalls: Int = 0
            private set
        private var smokeCalls: Int = 0
        override val tunnelStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped())
        override suspend fun startVpn(policy: TunnelPolicy, singBoxConfigPath: String): Result<Unit> {
            if (connectDelayMs > 0) delay(connectDelayMs)
            startCalls++
            return if (startCalls <= failStartTimes) {
                tunnelStatus.value = TunnelStatus.Stopped(startCalls.toLong(), "start_failed")
                Result.failure(IllegalStateException("start_failed"))
            } else {
                tunnelStatus.value = TunnelStatus.Running(startCalls.toLong(), startedAtMillis = 0L)
                Result.success(Unit)
            }
        }

        override suspend fun stopVpn() {
            tunnelStatus.value = TunnelStatus.Stopped()
        }
        override suspend fun stopVpnAndAwait(): Result<Unit> {
            stopAwaitCalls++
            if (stopDelayMs > 0) delay(stopDelayMs)
            tunnelStatus.value = TunnelStatus.Stopped()
            return Result.success(Unit)
        }
        override suspend fun smokeCheck(policy: TunnelPolicy): Result<Unit> {
            smokeCalls++
            return if (smokeCalls <= failSmokeTimes) {
                Result.failure(IllegalStateException("smoke_tcp_failed"))
            } else {
                Result.success(Unit)
            }
        }
        override fun vpnPermissionNeeded(): Boolean = false
    }
}
