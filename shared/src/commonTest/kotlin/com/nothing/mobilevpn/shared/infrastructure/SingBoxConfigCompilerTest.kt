package com.nothing.mobilevpn.shared.infrastructure

import com.nothing.mobilevpn.shared.domain.DnsMode
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.shared.domain.VlessProfile
import kotlin.test.Test
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
        val json = SingBoxConfigCompiler().compile(profile, policy)
        assertTrue(json.contains("\"type\": \"tun\""))
        assertTrue(json.contains("\"type\": \"vless\""))
        assertTrue(json.contains("198.18.0.1/30"))
        assertTrue(json.contains("hijack-dns"))
    }
}
