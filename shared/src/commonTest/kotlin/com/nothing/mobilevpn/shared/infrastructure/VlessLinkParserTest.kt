package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.VlessSecurity
import com.nothing.mobilecore.shared.domain.VlessTransport
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.VpnException
import com.nothing.mobilecore.shared.domain.XhttpExtra
import com.nothing.mobilecore.shared.domain.XhttpMode
import com.nothing.mobilecore.shared.domain.XhttpXmux
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

    @Test
    fun parse_xhttp_andLegacySplithttp() {
        val link =
            "vless://12345678-1234-4123-8123-123456789abc@example.com:443" +
                "?type=xhttp&security=reality&encryption=none&pbk=aaa&sid=bbb&sni=real.example" +
                "&path=%2Fxhttp&host=cdn.example&mode=stream-up&flow=xtls-rprx-vision" +
                "&extra=%7B%22xPaddingBytes%22%3A%22100-200%22%2C%22noGRPCHeader%22%3Atrue%7D#x"
        val parsed = VlessLinkParser.parse(link)
        assertEquals(VlessTransport.XHTTP, parsed.type)
        assertEquals("/xhttp", parsed.xhttpPath)
        assertEquals("cdn.example", parsed.xhttpHost)
        assertEquals(XhttpMode.STREAM_UP, parsed.xhttpMode)
        assertEquals(null, parsed.flow)
        assertEquals(null, parsed.wsPath)
        assertEquals(XhttpExtra(xPaddingBytes = "100-200", noGRPCHeader = true), parsed.xhttpExtra)

        val legacy = VlessLinkParser.parse(
            "vless://12345678-1234-4123-8123-123456789abc@example.com:443?type=splithttp&security=tls&encryption=none",
        )
        assertEquals(VlessTransport.XHTTP, legacy.type)
        assertEquals(XhttpMode.AUTO, legacy.xhttpMode)
    }

    @Test
    fun parse_xhttp_rejectsContradictingModeAndUnknownExtra() {
        val mode = assertFailsWith<VpnException> {
            VlessLinkParser.parse(
                "vless://12345678-1234-4123-8123-123456789abc@h:1?type=xhttp&security=tls" +
                    "&mode=auto&extra=%7B%22mode%22%3A%22packet-up%22%7D",
            )
        }
        assertEquals(VpnError.XhttpInvalid, mode.error)

        val unknown = assertFailsWith<VpnException> {
            VlessLinkParser.parse(
                "vless://12345678-1234-4123-8123-123456789abc@h:1?type=xhttp&security=tls" +
                    "&extra=%7B%22downloadSettings%22%3A%7B%7D%7D",
            )
        }
        assertEquals(VpnError.XhttpInvalid, unknown.error)
    }

    @Test
    fun grpcMode_staysInExtraQuery() {
        val parsed = VlessLinkParser.parse(
            "vless://12345678-1234-4123-8123-123456789abc@example.com:443?type=grpc&security=tls&mode=gun&serviceName=svc",
        )
        assertEquals(VlessTransport.GRPC, parsed.type)
        assertEquals("gun", parsed.extraQuery["mode"])
        assertEquals("svc", parsed.grpcServiceName)
    }
}
