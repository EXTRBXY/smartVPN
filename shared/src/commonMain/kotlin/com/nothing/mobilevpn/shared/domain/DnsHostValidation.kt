package com.nothing.mobilecore.shared.domain

private val ipv6Regex = Regex("^[0-9a-fA-F:]+$")

internal fun isValidIpv4(host: String): Boolean {
    val parts = host.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all(Char::isDigit) &&
            (part.length == 1 || part[0] != '0') &&
            part.toIntOrNull() in 0..255
    }
}

internal fun isValidIpv4Cidr(value: String): Boolean {
    val parts = value.trim().split('/')
    return parts.size == 2 && isValidIpv4(parts[0]) && parts[1].toIntOrNull() in 0..32
}

internal fun isIpHost(host: String): Boolean {
    val normalized = host.trim().trim('[', ']')
    return isValidIpv4(normalized) || isValidIpv6(normalized)
}

private fun isValidIpv6(host: String): Boolean {
    if (!ipv6Regex.matches(host) || host.count { it == ':' } < 2) return false
    if (host.count { it == ':' } > 7 && !host.contains("::")) return false
    if (host.indexOf("::") != host.lastIndexOf("::")) return false
    val groups = host.split(':').filter { it.isNotEmpty() }
    val maxGroups = if (host.contains("::")) 7 else 8
    return groups.size <= maxGroups && groups.all { it.length in 1..4 }
}
