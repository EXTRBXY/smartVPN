package com.nothing.mobilevpn.shared.infrastructure

import com.nothing.mobilevpn.shared.domain.VlessProfile
import okio.ByteString.Companion.toByteString

object VlessLinkParser {
    private val uuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    fun parse(link: String): VlessProfile {
        require(link.isNotBlank()) { "Empty link." }
        val uri = parseVlessUri(link.trim())
        require(uri.scheme.equals("vless", ignoreCase = true)) { "Only vless:// links are supported." }

        val userInfo = uri.userInfo ?: throw IllegalArgumentException("Missing UUID in userinfo.")
        require(uuidRegex.matches(userInfo)) { "Invalid UUID format." }
        val host = uri.host ?: throw IllegalArgumentException("Missing host.")
        val port = if (uri.port >= 0) uri.port else throw IllegalArgumentException("Invalid port.")
        require(port in 1..65535) { "Invalid port." }

        val rawFragment = uri.fragment
        val name = if (rawFragment.isNullOrBlank()) "$host:$port" else decodeUrl(rawFragment)

        val query = parseQuery(uri.query)
        val type = (query["type"] ?: "tcp").trim().lowercase()
        val security = (query["security"] ?: "tls").trim().lowercase()
        val encryption = (query["encryption"] ?: "none").trim().lowercase()
        require(type.isNotEmpty()) { "type is empty." }
        require(security.isNotEmpty()) { "security is empty." }
        require(encryption.isNotEmpty()) { "encryption is empty." }

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
        val wsPath = query["path"]?.trim()?.takeIf { it.isNotEmpty() }
        val wsHost = query["host"]?.trim()?.takeIf { it.isNotEmpty() }
        val grpcService = (query["serviceName"] ?: query["servicename"])?.trim()?.takeIf { it.isNotEmpty() }

        val extra = linkedMapOf<String, String>()
        for ((k, v) in query) {
            if (!isKnownKey(k)) extra[k] = v
        }

        require(encryption == "none") { "Only encryption=none is supported for VLESS links." }
        require(security in setOf("tls", "reality", "none")) { "Unsupported security. Expected tls|reality|none." }
        require(type in setOf("tcp", "ws", "grpc")) { "Unsupported type. Expected tcp|ws|grpc." }
        if (security == "reality") {
            require(pbk != null) { "Reality requires pbk." }
            require(sid != null) { "Reality requires sid." }
            require(sniTrim != null) { "Reality requires sni." }
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
            flow = flow,
            realityPublicKey = pbk,
            realityShortId = sid,
            wsPath = wsPath,
            wsHost = wsHost,
            grpcServiceName = grpcService,
            extraQuery = extra,
        )
        return profile.copy(id = stableId(profile))
    }

    private fun parseVlessUri(raw: String): ParsedUri {
        val withoutScheme = raw.removePrefix("vless:").removePrefix("VLESS:")
        require(withoutScheme.startsWith("//")) { "Invalid URI." }
        val rest = withoutScheme.drop(2)
        val fragmentIdx = rest.indexOf('#')
        val beforeFrag = if (fragmentIdx >= 0) rest.substring(0, fragmentIdx) else rest
        val fragment = if (fragmentIdx >= 0) rest.substring(fragmentIdx + 1) else null

        val qIdx = beforeFrag.indexOf('?')
        val authorityAndPath = if (qIdx >= 0) beforeFrag.substring(0, qIdx) else beforeFrag
        val query = if (qIdx >= 0) beforeFrag.substring(qIdx + 1) else null

        val atIdx = authorityAndPath.indexOf('@')
        require(atIdx > 0) { "Missing UUID in userinfo." }
        val userInfo = authorityAndPath.substring(0, atIdx)
        val hostPort = authorityAndPath.substring(atIdx + 1)
        val host: String
        val port: Int
        if (hostPort.startsWith('[')) {
            val endBracket = hostPort.indexOf(']')
            require(endBracket > 0) { "Invalid host." }
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
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.append(hex.toChar())
                        i += 3
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

    private fun isKnownKey(key: String): Boolean {
        val k = key.lowercase()
        return k in setOf(
            "type", "security", "encryption", "fp", "alpn", "sni", "servername", "flow",
            "pbk", "sid", "path", "host", "servicename",
        ) || key == "serverName"
    }

    private fun sanitizeName(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "Unnamed"
        return if (n.length > 64) n.substring(0, 64) else n
    }

    private fun stableId(p: VlessProfile): String {
        val key = "${p.uuid}|${p.host}|${p.port}|${p.type}|${p.security}|${p.sni}|${p.flow}|${p.wsPath}|${p.wsHost}|${p.grpcServiceName}"
        val hash = key.encodeToByteArray().toByteString().sha256().hex()
        return hash.take(16).lowercase()
    }
}
