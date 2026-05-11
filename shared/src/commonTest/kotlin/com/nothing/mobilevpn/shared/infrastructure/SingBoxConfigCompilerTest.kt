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
}
