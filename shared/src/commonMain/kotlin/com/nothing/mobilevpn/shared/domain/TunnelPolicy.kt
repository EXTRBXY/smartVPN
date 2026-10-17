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
    val dohSni: String? = "cloudflare-dns.com",
    /** Domain suffixes resolved by Android/local DNS instead of the encrypted resolver. */
    val localDnsDomains: List<String> = emptyList(),
    val systemDnsPackageNames: List<String> = emptyList(),
    /**
     * If true, a failed connection attempt is stopped immediately instead of being retried.
     * This is a retry policy, not an Android kill switch or lockdown-VPN setting.
     */
    val failClosed: Boolean = true,
    val singBoxLogLevel: String = "info",
    val tunStack: String = "mixed",
)

private val dohHostRegex = Regex("^[a-zA-Z0-9.-]+$")
private val acceptedLogLevels = setOf("trace", "debug", "info", "warn", "error")
private val acceptedTunStacks = setOf("mixed", "system", "gvisor")

fun TunnelPolicy.validate(): Result<Unit> = runCatching {
    ensure(mtu in 1280..9000) { VpnError.InvalidMtu }
    ensure(isValidIpv4Cidr(tunIpv4Cidr)) { VpnError.InvalidTunIpv4Cidr }
    if (routingMode == RoutingMode.PER_APP_ALLOWED) {
        ensure(allowedPackageNames.isNotEmpty()) { VpnError.PerAppRequiresPackages }
    }
    if (dnsMode == DnsMode.DOH) {
        val host = dohServer.trim()
        ensure(dohHostRegex.matches(host)) { VpnError.InvalidDohServer }
        ensure(!host.all { it.isDigit() || it == '.' } || isValidIpv4(host)) { VpnError.InvalidDohServer }
        ensure(dohPath.startsWith("/")) { VpnError.InvalidDohPath }
        val sni = dohSni?.trim().orEmpty()
        if (isIpHost(host) && sni.isBlank()) {
            vpnFail(VpnError.InvalidDohSniForIp)
        }
    }
    val logLevel = singBoxLogLevel.trim().lowercase()
    ensure(logLevel in acceptedLogLevels) { VpnError.InvalidSingBoxLogLevel }
    val stack = tunStack.trim().lowercase()
    ensure(stack in acceptedTunStacks) { VpnError.InvalidTunStack }

    // Ограничиваем размер списка и пустые значения, чтобы не создавать невалидные include/exclude.
    val normalizedCount = allowedPackageNames.count { it.isNotBlank() }
    ensure(normalizedCount == allowedPackageNames.size) { VpnError.InvalidPackageName }
    ensure(normalizedCount <= 4096) { VpnError.TooManyPackages }
    ensure(localDnsDomains.size <= 128) { VpnError.TooManyLocalDnsDomains }
    ensure(systemDnsPackageNames.size <= 4096 && systemDnsPackageNames.all { it.isNotBlank() }) { VpnError.InvalidPackageName }
    ensure(localDnsDomains.all { domain ->
        val value = domain.trim().trimStart('.')
        value.isNotEmpty() && value.length <= 253 && dohHostRegex.matches(value)
    }) { VpnError.InvalidLocalDnsDomain }
}
