package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.DnsMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingBoxConfigCompilerTest {
    private val profile = VlessProfile(
        id = "abc",
        name = "n",
        uuid = "12345678-1234-4123-8123-123456789abc",
        host = "example.com",
        port = 443,
        type = "tcp",
        security = "tls",
        sni = "example.com",
    )
    private val policy = TunnelPolicy(
        tunIpv4Cidr = "198.18.0.1/30",
        mtu = 1500,
        dnsMode = DnsMode.SYSTEM,
    )

    @Test
    fun compile_containsCoreKeys() {
        val json = SingBoxConfigCompiler().compile(profile, policy).getOrThrow()
        assertTrue(json.contains("\"type\":\"tun\"") || json.contains("\"type\": \"tun\""))
        assertTrue(json.contains("\"type\":\"vless\"") || json.contains("\"type\": \"vless\""))
        assertTrue(json.contains("198.18.0.1/30"))
        assertTrue(json.contains("hijack-dns"))
        assertTrue(json.contains("\"tag\":\"proxy-node\"") || json.contains("\"tag\": \"proxy-node\""))
        assertTrue(json.contains("\"type\":\"selector\"") || json.contains("\"type\": \"selector\""))
    }

    @Test
    fun compile_failsForRealityWithoutKeys() {
        val realityProfile = profile.copy(security = "reality", realityPublicKey = null, realityShortId = null)
        val result = SingBoxConfigCompiler().compile(realityProfile, policy)
        assertFalse(result.isSuccess)
    }

    @Test
    fun compile_failsWhenProfileSecretMissing() {
        val invalid = profile.copy(uuid = "")
        val result = SingBoxConfigCompiler().compile(invalid, policy)
        assertFalse(result.isSuccess)
    }

    @Test
    fun compile_includesDohResolverForDohPolicy() {
        val dohPolicy = policy.copy(
            dnsMode = DnsMode.DOH,
            dohServer = "dns.google",
            dohPath = "/dns-query",
            dohSni = "dns.google",
        )
        val json = SingBoxConfigCompiler().compile(profile, dohPolicy).getOrThrow()
        assertTrue(json.contains("\"type\":\"https\"") || json.contains("\"type\": \"https\""))
        assertTrue(json.contains("dns.google"))
        assertTrue(json.contains("\"domain_resolver\":\"local\"") || json.contains("\"domain_resolver\": \"local\""))
        assertFalse(json.contains("\"detour\":\"direct\"") || json.contains("\"detour\": \"direct\""))
    }

    @Test
    fun compile_usesLocalDnsForSystemPolicy() {
        val systemPolicy = policy.copy(dnsMode = DnsMode.SYSTEM)
        val json = SingBoxConfigCompiler().compile(profile, systemPolicy).getOrThrow()
        assertTrue(json.contains("\"tag\":\"local\"") || json.contains("\"tag\": \"local\""))
        assertFalse(json.contains("\"detour\":\"direct\""))
    }

    @Test
    fun compile_dohByIpDoesNotRequireDomainResolver() {
        val dohPolicy = policy.copy(
            dnsMode = DnsMode.DOH,
            dohServer = "1.1.1.1",
            dohPath = "/dns-query",
            dohSni = "cloudflare-dns.com",
        )
        val json = SingBoxConfigCompiler().compile(profile, dohPolicy).getOrThrow()
        assertFalse(json.contains("\"domain_resolver\":\"local\"") || json.contains("\"domain_resolver\": \"local\""))
        assertTrue(json.contains("\"tag\":\"local\"") || json.contains("\"tag\": \"local\""))
    }
}
