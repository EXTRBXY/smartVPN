package com.nothing.mobilecore.ui.dns

import com.nothing.mobilecore.shared.application.ConnectivityProbePort
import com.nothing.mobilecore.shared.application.ProfileRepositoryPort
import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.toVpnError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsViewModelTest {
    private val repo = FakeRepository()
    private val probe = object : ConnectivityProbePort {
        var lastPort = -1
        override suspend fun tcpConnectLatency(host: String, port: Int): Result<Long> {
            lastPort = port
            return Result.success(12)
        }
        override suspend fun externalIp(): Result<String> = Result.success("203.0.113.1")
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun saveDnsCustom_persistsValidPolicy() = runTest {
        val vm = DnsViewModel(repo, probe)
        var result: Result<Unit>? = null

        vm.saveDnsCustom("dns.google", "/dns-query", "dns.google", listOf(".corp.local", " ")) { result = it }

        assertTrue(result!!.isSuccess)
        assertEquals(DnsMode.DOH, repo.policy.dnsMode)
        assertEquals(listOf("corp.local"), repo.policy.localDnsDomains)
        assertFalse(vm.dnsSaving.value)
    }

    @Test
    fun saveDnsCustom_rejectsInvalidPolicyWithTypedError() = runTest {
        val vm = DnsViewModel(repo, probe)
        var result: Result<Unit>? = null

        vm.saveDnsCustom("dns.google", "no-slash", null, emptyList()) { result = it }

        assertEquals(VpnError.InvalidDohPath, result!!.exceptionOrNull()!!.toVpnError())
        assertEquals(DnsMode.SYSTEM, repo.policy.dnsMode)
        assertFalse(vm.dnsSaving.value)
    }

    @Test
    fun testDnsEndpoint_probesHttpsPort() = runTest {
        val vm = DnsViewModel(repo, probe)

        assertEquals(12L, vm.testDnsEndpoint("1.1.1.1").getOrThrow())
        assertEquals(443, probe.lastPort)
    }

    private class FakeRepository : ProfileRepositoryPort {
        var policy = TunnelPolicy()
        override fun observeProfiles(): Flow<List<VlessProfile>> = MutableStateFlow(emptyList())
        override suspend fun listProfiles(): List<VlessProfile> = emptyList()
        override suspend fun upsert(profile: VlessProfile) = Unit
        override suspend fun delete(profileId: String) = Unit
        override suspend fun getActiveProfileId(): String? = null
        override suspend fun setActiveProfileId(id: String?) = Unit
        override suspend fun getTunnelPolicy(): TunnelPolicy = policy
        override suspend fun setTunnelPolicy(policy: TunnelPolicy) {
            this.policy = policy
        }
    }
}
