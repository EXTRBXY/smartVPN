package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.VlessProfile

object VlessLinkFormatter {
    fun format(profile: VlessProfile): String {
        require(profile.uuid.isNotBlank()) { "profile_secret_unavailable" }
        val query = linkedMapOf<String, String>()
        query["encryption"] = profile.encryption
        query["security"] = profile.security
        query["type"] = profile.type
        profile.sni?.let { query["sni"] = it }
        profile.fingerprint?.let { query["fp"] = it }
        profile.flow?.let { query["flow"] = it }
        if (profile.alpn.isNotEmpty()) query["alpn"] = profile.alpn.joinToString(",")
        profile.realityPublicKey?.let { query["pbk"] = it }
        profile.realityShortId?.let { query["sid"] = it }
        profile.wsPath?.let { query["path"] = it }
        profile.wsHost?.let { query["host"] = it }
        profile.grpcServiceName?.let { query["serviceName"] = it }
        profile.extraQuery.forEach { (key, value) -> query.putIfAbsent(key, value) }
        val host = if (':' in profile.host && !profile.host.startsWith('[')) "[${profile.host}]" else profile.host
        val encodedQuery = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "vless://${profile.uuid}@$host:${profile.port}?$encodedQuery#${encode(profile.name)}"
    }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val safe = unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code || unsigned == '-'.code || unsigned == '_'.code ||
                unsigned == '.'.code || unsigned == '~'.code
            if (safe) append(unsigned.toChar()) else append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
