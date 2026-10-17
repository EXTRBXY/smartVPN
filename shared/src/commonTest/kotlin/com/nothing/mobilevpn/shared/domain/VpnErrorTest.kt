package com.nothing.mobilecore.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VpnErrorTest {
    @Test
    fun codes_areUnique() {
        val codes = VpnError.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun fromCode_roundTripsEveryEntry() {
        VpnError.entries.forEach { assertEquals(it, VpnError.fromCode(it.code)) }
        assertNull(VpnError.fromCode("no_such_code"))
    }

    @Test
    fun vpnException_keepsTypedError() {
        assertEquals(VpnError.VpnRevoked, VpnException(VpnError.VpnRevoked).toVpnError())
    }

    @Test
    fun wrappedVpnException_isFoundInCauseChain() {
        val wrapped = IllegalStateException("outer", VpnException(VpnError.SmokeTimeout))
        assertEquals(VpnError.SmokeTimeout, wrapped.toVpnError())
    }

    @Test
    fun codeInsideCoreMessage_isRecognized() {
        // Ошибка из openTun возвращается через ядро обёрнутой в его текст.
        val fromCore = Exception("start service: open tun: vpn_permission_missing")
        assertEquals(VpnError.VpnPermissionMissing, fromCore.toVpnError())
    }

    @Test
    fun coreMessages_areClassifiedByText() {
        assertEquals(VpnError.CoreTlsFailed, Exception("tls: handshake failure").toVpnError())
        assertEquals(VpnError.CoreDnsFailed, Exception("lookup example.com: dns error").toVpnError())
        assertEquals(VpnError.CoreServerUnreachable, Exception("dial tcp: i/o timeout").toVpnError())
        assertEquals(VpnError.Unknown, Exception("something odd").toVpnError())
        assertEquals(VpnError.Unknown, Exception().toVpnError())
    }

    @Test
    fun policyValidation_returnsTypedError() {
        val result = TunnelPolicy(mtu = 100).validate()
        assertEquals(VpnError.InvalidMtu, result.exceptionOrNull()?.toVpnError())
    }
}
