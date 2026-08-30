package com.nothing.mobilecore.shared.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunnelPolicyValidationTest {
    @Test
    fun validate_acceptsDefaultPolicy() {
        val result = TunnelPolicy().validate()
        assertTrue(result.isSuccess)
    }

    @Test
    fun validate_rejectsEmptyPerAppAllowedList() {
        val result = TunnelPolicy(routingMode = RoutingMode.PER_APP_ALLOWED, allowedPackageNames = emptyList()).validate()
        assertFalse(result.isSuccess)
    }

    @Test
    fun validate_rejectsInvalidDoHPath() {
        val result = TunnelPolicy(dnsMode = DnsMode.DOH, dohPath = "dns-query").validate()
        assertFalse(result.isSuccess)
    }

    @Test
    fun validate_acceptsValidDoHCustomPolicy() {
        val result = TunnelPolicy(
            dnsMode = DnsMode.DOH,
            dohServer = "dns.google",
            dohPath = "/dns-query",
            dohSni = "dns.google",
        ).validate()
        assertTrue(result.isSuccess)
    }

    @Test
    fun validate_rejectsInvalidDoHHost() {
        val result = TunnelPolicy(
            dnsMode = DnsMode.DOH,
            dohServer = "https://dns.google",
            dohPath = "/dns-query",
        ).validate()
        assertFalse(result.isSuccess)
    }

    @Test
    fun validate_rejectsIpDoHWithoutSni() {
        val result = TunnelPolicy(
            dnsMode = DnsMode.DOH,
            dohServer = "1.1.1.1",
            dohPath = "/dns-query",
            dohSni = null,
        ).validate()
        assertFalse(result.isSuccess)
    }

    @Test
    fun validate_acceptsIpDoHWithSni() {
        val result = TunnelPolicy(
            dnsMode = DnsMode.DOH,
            dohServer = "1.1.1.1",
            dohPath = "/dns-query",
            dohSni = "cloudflare-dns.com",
        ).validate()
        assertTrue(result.isSuccess)
    }

    @Test
    fun validate_rejectsOutOfRangeIpv4Cidr() {
        assertFalse(TunnelPolicy(tunIpv4Cidr = "999.18.0.1/30").validate().isSuccess)
        assertFalse(TunnelPolicy(tunIpv4Cidr = "198.18.0.1/33").validate().isSuccess)
    }

    @Test
    fun validate_rejectsInvalidNumericDohHost() {
        val result = TunnelPolicy(
            dnsMode = DnsMode.DOH,
            dohServer = "999.1.1.1",
            dohSni = "example.com",
        ).validate()
        assertFalse(result.isSuccess)
    }
}
