package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.VlessSecurity
import com.nothing.mobilecore.shared.domain.VlessTransport
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VlessLinkParserTest {
    @Test
    fun parse_minimalTlsTcp() {
        val link =
            "vless://12345678-1234-4123-8123-123456789abc@example.com:443?encryption=none&security=tls&type=tcp#Test"
        val p = VlessLinkParser.parse(link)
        assertEquals("example.com", p.host)
        assertEquals(443, p.port)
        assertEquals("12345678-1234-4123-8123-123456789abc", p.uuid)
        assertEquals(VlessTransport.TCP, p.type)
        assertEquals(VlessSecurity.TLS, p.security)
        assertEquals("Test", p.name)
        // id получен прежним алгоритмом, когда type/security были строками: переход на enum
        // не должен менять id, иначе повторный импорт создаст дубликат профиля.
        assertEquals("818942c831d3a2a0", p.id)
    }

    @Test
    fun parse_reality() {
        val link =
            "vless://12345678-1234-4123-8123-123456789abc@example.com:443" +
                "?type=tcp&security=reality&encryption=none&pbk=aaa&sid=bbb&sni=real.example#r"
        val p = VlessLinkParser.parse(link)
        assertEquals(VlessSecurity.REALITY, p.security)
        assertEquals("aaa", p.realityPublicKey)
        assertEquals("bbb", p.realityShortId)
        assertEquals("real.example", p.sni)
    }

    @Test
    fun rejects_non_vless() {
        val e = assertFailsWith<VpnException> {
            VlessLinkParser.parse("https://example.com")
        }
        assertEquals(VpnError.LinkNotVless, e.error)
    }

    @Test
    fun rejects_bad_encryption() {
        val e = assertFailsWith<VpnException> {
            VlessLinkParser.parse(
                "vless://12345678-1234-4123-8123-123456789abc@h:1?encryption=aes128&type=tcp&security=tls",
            )
        }
        assertEquals(VpnError.LinkUnsupported, e.error)
    }
}
