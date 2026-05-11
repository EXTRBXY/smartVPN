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
}
