package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VlessSecurity
import com.nothing.mobilecore.shared.domain.VlessTransport
import com.nothing.mobilecore.shared.domain.XhttpExtra
import com.nothing.mobilecore.shared.domain.XhttpMode
import com.nothing.mobilecore.shared.domain.XhttpXmux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VlessLinkFormatterTest {
    @Test
    fun roundTrip_preservesUnicodeAndTransportFields() {
        val source = VlessProfile(
            id = "test",
            name = "Мой сервер + Wi-Fi",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
            type = VlessTransport.WS,
            security = VlessSecurity.TLS,
            sni = "example.com",
            wsPath = "/vpn?token=a+b&mode=1",
            wsHost = "cdn.example.com",
            alpn = listOf("h2", "http/1.1"),
        )
        val restored = VlessLinkParser.parse(VlessLinkFormatter.format(source))
        assertEquals(source.name, restored.name)
        assertEquals(source.uuid, restored.uuid)
        assertEquals(source.wsPath, restored.wsPath)
        assertEquals(source.alpn, restored.alpn)
    }

    @Test
    fun ipv6Authority_isBracketed() {
        val source = VlessProfile(
            id = "test",
            name = "IPv6",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "2001:db8::1",
            port = 443,
        )
        val link = VlessLinkFormatter.format(source)
        assertTrue(link.contains("@[2001:db8::1]:443"))
        assertEquals(source.host, VlessLinkParser.parse(link).host)
    }

    @Test
    fun roundTrip_xhttpDropsFlowAndKeepsExtra() {
        val source = VlessProfile(
            id = "test",
            name = "xhttp",
            uuid = "12345678-1234-4123-8123-123456789abc",
            host = "example.com",
            port = 443,
            type = VlessTransport.XHTTP,
            security = VlessSecurity.TLS,
            flow = "xtls-rprx-vision",
            xhttpPath = "/xhttp",
            xhttpHost = "cdn.example",
            xhttpMode = XhttpMode.PACKET_UP,
            xhttpExtra = XhttpExtra(
                xPaddingBytes = "100-200",
                xmux = XhttpXmux(maxConcurrency = "8-16"),
            ),
        )
        val restored = VlessLinkParser.parse(VlessLinkFormatter.format(source))
        assertEquals(VlessTransport.XHTTP, restored.type)
        assertEquals(null, restored.flow)
        assertEquals("/xhttp", restored.xhttpPath)
        assertEquals("cdn.example", restored.xhttpHost)
        assertEquals(XhttpMode.PACKET_UP, restored.xhttpMode)
        assertEquals(source.xhttpExtra, restored.xhttpExtra)
    }
}
