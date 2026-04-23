package com.nothing.mobilevpn.shared.domain

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
    val singBoxLogLevel: String = "info",
    val tunStack: String = "mixed",
)
