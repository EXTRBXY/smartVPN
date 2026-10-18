package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode {
    FULL_TUN,
    /** Через VPN идут только [TunnelPolicy.includedPackageNames]; остальные приложения — мимо. */
    PER_APP_ALLOWED,
    /** Через VPN идут все приложения, кроме [TunnelPolicy.excludedPackageNames]. */
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
     * Приложения для режима [RoutingMode.PER_APP_ALLOWED]. Хранятся независимо от режима:
     * переключение на «весь трафик» или «в обход» выбор не стирает. Ключ в хранилище —
     * исторический `allowedPackageNames` (до схемы v2 в нём лежал общий список обоих режимов).
     */
    @SerialName("allowedPackageNames")
    val includedPackageNames: List<String> = emptyList(),
    /** Приложения для режима [RoutingMode.PER_APP_EXCLUDED] — идут мимо VPN. */
    val excludedPackageNames: List<String> = emptyList(),
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
     * true — одна попытка подключения без повторов; false — до 3 попыток с паузами.
     * Это политика повторов, а не Kill Switch Android. В хранилище ключ прежний (`failClosed`),
     * чтобы не терять сохранённое значение.
     */
    @SerialName("failClosed")
    val singleConnectAttempt: Boolean = true,
    val singBoxLogLevel: SingBoxLogLevel = SingBoxLogLevel.INFO,
    val tunStack: TunStack = TunStack.MIXED,
)

private val dohHostRegex = Regex("^[a-zA-Z0-9.-]+$")

fun TunnelPolicy.validate(): Result<Unit> = runCatching {
    ensure(mtu in 1280..9000) { VpnError.InvalidMtu }
    ensure(isValidIpv4Cidr(tunIpv4Cidr)) { VpnError.InvalidTunIpv4Cidr }
    if (routingMode == RoutingMode.PER_APP_ALLOWED) {
        ensure(includedPackageNames.isNotEmpty()) { VpnError.PerAppRequiresPackages }
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
    // Ограничиваем размер списка и пустые значения, чтобы не создавать невалидные include/exclude.
    listOf(includedPackageNames, excludedPackageNames).forEach { packages ->
        ensure(packages.all { it.isNotBlank() }) { VpnError.InvalidPackageName }
        ensure(packages.size <= 4096) { VpnError.TooManyPackages }
    }
    ensure(localDnsDomains.size <= 128) { VpnError.TooManyLocalDnsDomains }
    ensure(systemDnsPackageNames.size <= 4096 && systemDnsPackageNames.all { it.isNotBlank() }) { VpnError.InvalidPackageName }
    ensure(localDnsDomains.all { domain ->
        val value = domain.trim().trimStart('.')
        value.isNotEmpty() && value.length <= 253 && dohHostRegex.matches(value)
    }) { VpnError.InvalidLocalDnsDomain }
}

/** Пакеты, которые реально применяются в текущем [TunnelPolicy.routingMode]. */
val TunnelPolicy.activePackageNames: List<String>
    get() = when (routingMode) {
        RoutingMode.FULL_TUN -> emptyList()
        RoutingMode.PER_APP_ALLOWED -> includedPackageNames
        RoutingMode.PER_APP_EXCLUDED -> excludedPackageNames
    }

/**
 * Перенос из схемы с одним общим списком (хранилище v1): в режиме «в обход» тот список
 * означал исключения, поэтому переносим его в [TunnelPolicy.excludedPackageNames].
 */
fun TunnelPolicy.splitLegacyPackageList(): TunnelPolicy =
    if (routingMode == RoutingMode.PER_APP_EXCLUDED && excludedPackageNames.isEmpty() && includedPackageNames.isNotEmpty()) {
        copy(includedPackageNames = emptyList(), excludedPackageNames = includedPackageNames)
    } else {
        this
    }
