package com.nothing.mobilecore.shared.application

import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VpnSessionControllerRecoveryTest {
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

    private class FakeProfileRepository(
        private val profile: VlessProfile,
        initialPolicy: TunnelPolicy = TunnelPolicy(routingMode = RoutingMode.FULL_TUN, failClosed = false),
    ) : ProfileRepositoryPort {
        private val profilesFlow = MutableStateFlow(listOf(profile))
        private var policy = initialPolicy
        override fun observeProfiles(): Flow<List<VlessProfile>> = profilesFlow
        override suspend fun listProfiles(): List<VlessProfile> = profilesFlow.value
        override suspend fun upsert(profile: VlessProfile) {
            profilesFlow.value = listOf(profile)
        }
        override suspend fun delete(profileId: String) {}
        override suspend fun getActiveProfileId(): String? = profile.id
        override suspend fun setActiveProfileId(id: String?) {}
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
        override suspend fun startVpn(policy: TunnelPolicy, singBoxConfigPath: String): Result<Unit> {
            if (connectDelayMs > 0) delay(connectDelayMs)
            startCalls++
            return if (startCalls <= failStartTimes) {
                Result.failure(IllegalStateException("start_failed"))
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun stopVpn() = Unit
        override suspend fun stopVpnAndAwait(): Result<Unit> {
            stopAwaitCalls++
            if (stopDelayMs > 0) delay(stopDelayMs)
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
