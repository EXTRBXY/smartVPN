package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode {
    FULL_TUN,
    /** Only [TunnelPolicy.allowedPackageNames] use the VPN; all other apps bypass. */
    PER_APP_ALLOWED,
    /** All apps use the VPN except [TunnelPolicy.allowedPackageNames] (bypass / split exclude). */
    PER_APP_EXCLUDED,
}

@Serializable
enum class DnsMode {
    SYSTEM,
    DOH,
}

@Serializable
data class TunnelPolicy(
    val routingMode: RoutingMode = RoutingMode.FULL_TUN,
    /**
     * Meaning depends on [routingMode]:
     * - [RoutingMode.PER_APP_ALLOWED]: packages that use the tunnel.
     * - [RoutingMode.PER_APP_EXCLUDED]: packages that bypass the tunnel.
     */
    val allowedPackageNames: List<String> = emptyList(),
    /** IPv4 CIDR for TUN address (must match VpnService and sing-box inbound). */
    val tunIpv4Cidr: String = "198.18.0.1/30",
    val mtu: Int = 1500,
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val dohServer: String = "1.1.1.1",
    val dohPath: String = "/dns-query",
    val dohSni: String? = null,
    /** If true, session failures must fail closed (no automatic reconnect attempts). */
    val failClosed: Boolean = true,
    val singBoxLogLevel: String = "info",
    val tunStack: String = "mixed",
)

private val cidrRegex = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}/([0-9]|[12][0-9]|3[0-2])$")
private val dohHostRegex = Regex("^[a-zA-Z0-9.-]+$")
private val acceptedLogLevels = setOf("trace", "debug", "info", "warn", "error")
private val acceptedTunStacks = setOf("mixed", "system", "gvisor")

fun TunnelPolicy.validate(): Result<Unit> = runCatching {
    require(mtu in 1280..9000) { "invalid_mtu" }
    require(cidrRegex.matches(tunIpv4Cidr.trim())) { "invalid_tun_ipv4_cidr" }
    if (routingMode == RoutingMode.PER_APP_ALLOWED) {
        require(allowedPackageNames.isNotEmpty()) { "per_app_requires_packages" }
    }
    if (dnsMode == DnsMode.DOH) {
        require(dohHostRegex.matches(dohServer.trim())) { "invalid_doh_server" }
        require(dohPath.startsWith("/")) { "invalid_doh_path" }
    }
    val logLevel = singBoxLogLevel.trim().lowercase()
    require(logLevel in acceptedLogLevels) { "invalid_singbox_log_level" }
    val stack = tunStack.trim().lowercase()
    require(stack in acceptedTunStacks) { "invalid_tun_stack" }

    // Ограничиваем размер списка и пустые значения, чтобы не создавать невалидные include/exclude.
    val normalizedCount = allowedPackageNames.count { it.isNotBlank() }
    require(normalizedCount == allowedPackageNames.size) { "invalid_package_name" }
    require(normalizedCount <= 512) { "too_many_packages" }
}
