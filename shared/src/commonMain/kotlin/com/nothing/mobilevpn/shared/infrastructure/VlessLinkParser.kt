package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.VlessEncryption
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VlessSecurity
import com.nothing.mobilecore.shared.domain.VlessTransport
import com.nothing.mobilecore.shared.domain.VlessUuid
import com.nothing.mobilecore.shared.domain.XhttpExtra
import com.nothing.mobilecore.shared.domain.XhttpMode
import com.nothing.mobilecore.shared.domain.XhttpPolicy
import com.nothing.mobilecore.shared.domain.canonicalJson
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.ensure
import com.nothing.mobilecore.shared.domain.vpnFail
import okio.ByteString.Companion.toByteString

object VlessLinkParser {
    fun parse(link: String): VlessProfile {
        ensure(link.isNotBlank()) { VpnError.LinkInvalid }
        ensure(link.trim().startsWith("vless://", ignoreCase = true)) { VpnError.LinkNotVless }
        val uri = parseVlessUri(link.trim())
        ensure(uri.scheme.equals("vless", ignoreCase = true)) { VpnError.LinkNotVless }

        val userInfo = uri.userInfo ?: vpnFail(VpnError.LinkInvalidUuid)
        ensure(VlessUuid.isValid(userInfo)) { VpnError.LinkInvalidUuid }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: vpnFail(VpnError.LinkInvalidHostOrPort)
        val port = if (uri.port >= 0) uri.port else vpnFail(VpnError.LinkInvalidHostOrPort)
        ensure(port in 1..65535) { VpnError.LinkInvalidHostOrPort }

        val rawFragment = uri.fragment
        val name = if (rawFragment.isNullOrBlank()) "$host:$port" else decodeUrl(rawFragment)

        val query = parseQuery(uri.query)
        val rawType = query["type"]?.trim().orEmpty().ifEmpty { VlessTransport.TCP.wire }
        val type = if (rawType.equals("splithttp", ignoreCase = true)) {
            VlessTransport.XHTTP
        } else {
            VlessTransport.parse(rawType) ?: vpnFail(VpnError.LinkUnsupported)
        }
        val security = VlessSecurity.parse(query["security"] ?: VlessSecurity.TLS.wire) ?: vpnFail(VpnError.LinkUnsupported)
        val encryption = VlessEncryption.parse(query["encryption"] ?: VlessEncryption.NONE.wire)
            ?: vpnFail(VpnError.LinkUnsupported)
        val xhttp = type == VlessTransport.XHTTP

        val fingerprint = query["fp"]?.trim()?.takeIf { it.isNotEmpty() }
        val sni = query["sni"] ?: query["serverName"] ?: query["servername"]
        val sniTrim = sni?.trim()?.takeIf { it.isNotEmpty() }
        val flow = query["flow"]?.trim()?.takeIf { it.isNotEmpty() }
        val alpnRaw = query["alpn"]
        val alpn = if (alpnRaw.isNullOrBlank()) {
            emptyList()
        } else {
            alpnRaw.split(',', ';').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        }
        val pbk = query["pbk"]?.trim()?.takeIf { it.isNotEmpty() }
        val sid = query["sid"]?.trim()?.takeIf { it.isNotEmpty() }
        val path = query["path"]?.trim()?.takeIf { it.isNotEmpty() }
        val hostHeader = query["host"]?.trim()?.takeIf { it.isNotEmpty() }
        val grpcService = (query["serviceName"] ?: query["servicename"])?.trim()?.takeIf { it.isNotEmpty() }
        val parsedExtra = if (xhttp) XhttpExtraParser.parse(query["extra"]) else null
        val paddingQuery = (query["x_padding_bytes"] ?: query["xPaddingBytes"])?.trim()?.takeIf { it.isNotEmpty() }
        val xhttpExtra = if (xhttp) {
            val fromExtra = parsedExtra?.extra
            when {
                paddingQuery == null -> fromExtra
                fromExtra?.xPaddingBytes == null -> (fromExtra ?: XhttpExtra()).copy(xPaddingBytes = paddingQuery)
                fromExtra.xPaddingBytes != paddingQuery -> vpnFail(VpnError.XhttpInvalid)
                else -> fromExtra
            }
        } else {
            null
        }
        val xhttpMode = if (xhttp) {
            val queryMode = query["mode"]?.trim()?.takeIf { it.isNotEmpty() }
            val extraMode = parsedExtra?.mode?.trim()?.takeIf { it.isNotEmpty() }
            if (queryMode != null && extraMode != null && !queryMode.equals(extraMode, ignoreCase = true)) {
                vpnFail(VpnError.XhttpInvalid)
            }
            XhttpMode.parse(queryMode ?: extraMode ?: XhttpMode.AUTO.wire) ?: vpnFail(VpnError.XhttpInvalid)
        } else {
            XhttpMode.AUTO
        }

        val extra = linkedMapOf<String, String>()
        for ((k, v) in query) {
            if (!isKnownKey(k, xhttp)) extra[k] = v
        }

        if (security == VlessSecurity.REALITY) {
            ensure(pbk != null) { VpnError.LinkRealityIncomplete }
            ensure(sid != null) { VpnError.LinkRealityIncomplete }
            ensure(sniTrim != null) { VpnError.LinkRealityIncomplete }
        }

        val profile = VlessProfile(
            id = "",
            name = sanitizeName(name),
            uuid = userInfo,
            host = host,
            port = port,
            type = type,
            security = security,
            encryption = encryption,
            sni = sniTrim,
            alpn = alpn,
            fingerprint = fingerprint,
            flow = if (xhttp) null else flow,
            realityPublicKey = pbk,
            realityShortId = sid,
            wsPath = if (xhttp) null else path,
            wsHost = if (xhttp) null else hostHeader,
            grpcServiceName = if (xhttp) null else grpcService,
            xhttpPath = if (xhttp) path else null,
            xhttpHost = if (xhttp) hostHeader else null,
            xhttpMode = xhttpMode,
            xhttpExtra = xhttpExtra,
            extraQuery = extra,
        )
        XhttpPolicy.requireValid(profile)
        return profile.copy(id = stableId(profile))
    }

    private fun parseVlessUri(raw: String): ParsedUri {
        val withoutScheme = raw.removePrefix("vless:").removePrefix("VLESS:")
        ensure(withoutScheme.startsWith("//")) { VpnError.LinkInvalid }
        val rest = withoutScheme.drop(2)
        val fragmentIdx = rest.indexOf('#')
        val beforeFrag = if (fragmentIdx >= 0) rest.substring(0, fragmentIdx) else rest
        val fragment = if (fragmentIdx >= 0) rest.substring(fragmentIdx + 1) else null

        val qIdx = beforeFrag.indexOf('?')
        val authorityAndPath = if (qIdx >= 0) beforeFrag.substring(0, qIdx) else beforeFrag
        val query = if (qIdx >= 0) beforeFrag.substring(qIdx + 1) else null

        val atIdx = authorityAndPath.indexOf('@')
        ensure(atIdx > 0) { VpnError.LinkInvalidUuid }
        val userInfo = authorityAndPath.substring(0, atIdx)
        val hostPort = authorityAndPath.substring(atIdx + 1)
        val host: String
        val port: Int
        if (hostPort.startsWith('[')) {
            val endBracket = hostPort.indexOf(']')
            ensure(endBracket > 0) { VpnError.LinkInvalidHostOrPort }
            host = hostPort.substring(1, endBracket)
            val after = hostPort.substring(endBracket + 1)
            port = if (after.startsWith(':')) after.drop(1).toIntOrNull() ?: -1 else -1
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: -1
            } else {
                host = hostPort
                port = -1
            }
        }
        return ParsedUri("vless", userInfo, host, port, query, fragment)
    }

    private data class ParsedUri(
        val scheme: String,
        val userInfo: String?,
        val host: String?,
        val port: Int,
        val query: String?,
        val fragment: String?,
    )

    private fun decodeUrl(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '+' -> {
                    out.append(' ')
                    i++
                }
                s[i] == '%' && i + 2 < s.length -> {
                    val bytes = mutableListOf<Byte>()
                    while (i + 2 < s.length && s[i] == '%') {
                        val hex = s.substring(i + 1, i + 3).toIntOrNull(16) ?: break
                        bytes.add(hex.toByte())
                        i += 3
                    }
                    if (bytes.isNotEmpty()) {
                        out.append(bytes.toByteArray().decodeToString())
                    } else {
                        out.append(s[i])
                        i++
                    }
                }
                else -> {
                    out.append(s[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val q = query.trimStart('?')
        if (q.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val idx = part.indexOf('=')
            if (idx < 0) {
                out[decodeUrl(part)] = ""
            } else {
                val k = decodeUrl(part.substring(0, idx))
                val v = decodeUrl(part.substring(idx + 1))
                out[k] = v
            }
        }
        return out
    }

    private fun isKnownKey(key: String, xhttp: Boolean): Boolean {
        val k = key.lowercase()
        if (k in setOf(
                "type", "security", "encryption", "fp", "alpn", "sni", "servername", "flow",
                "pbk", "sid", "path", "host", "servicename",
            ) || key == "serverName"
        ) {
            return true
        }
        return xhttp && k in setOf("mode", "extra", "x_padding_bytes", "xpaddingbytes")
    }

    private fun sanitizeName(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "Unnamed"
        return if (n.length > 64) n.substring(0, 64) else n
    }

    private fun stableId(p: VlessProfile): String {
        val xhttpKey = if (p.type == VlessTransport.XHTTP) {
            "|${p.xhttpPath}|${p.xhttpHost}|${p.xhttpMode.wire}|${p.xhttpExtra?.canonicalJson().orEmpty()}"
        } else {
            ""
        }
        val key = "${p.uuid}|${p.host}|${p.port}|${p.type.wire}|${p.security.wire}|${p.sni}|${p.flow}|${p.wsPath}|${p.wsHost}|${p.grpcServiceName}$xhttpKey"
        val hash = key.encodeToByteArray().toByteString().sha256().hex()
        return hash.take(16).lowercase()
    }
}
