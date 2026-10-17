package com.nothing.mobilecore.shared.domain

/**
 * Ошибка, которую можно показать пользователю. Каждый код обязан иметь текст в UI
 * (`ui/UserErrorText.kt`, исчерпывающий `when` — без него проект не соберётся).
 *
 * [code] — стабильная строка для журналов и сравнения; секретов не содержит.
 */
enum class VpnError(val code: String) {
    // Профили
    NoActiveProfile("no_active_profile"),
    ProfileNotFound("profile_not_found"),
    NoReachableProfile("no_reachable_profile"),
    ProfileSecretUnavailable("profile_secret_unavailable"),
    ProfileIdImmutable("profile_id_immutable"),
    ProfileDeleteRequiresDisconnect("profile_delete_requires_disconnect"),
    ProfileNameRequired("profile_name_required"),
    ProfileHostRequired("profile_host_required"),
    ProfilePortInvalid("profile_port_invalid"),
    ProfileUuidInvalid("profile_uuid_invalid"),
    ProfileRealityIncomplete("profile_reality_incomplete"),

    // Импорт ссылки vless://
    LinkNotVless("link_not_vless"),
    LinkInvalid("link_invalid"),
    LinkInvalidUuid("link_invalid_uuid"),
    LinkInvalidHostOrPort("link_invalid_host_or_port"),
    LinkUnsupported("link_unsupported"),
    LinkRealityIncomplete("link_reality_incomplete"),

    // Политика туннеля
    PerAppRequiresPackages("per_app_requires_packages"),
    InvalidPackageName("invalid_package_name"),
    TooManyPackages("too_many_packages"),
    InvalidMtu("invalid_mtu"),
    InvalidTunIpv4Cidr("invalid_tun_ipv4_cidr"),
    InvalidTunStack("invalid_tun_stack"),
    InvalidSingBoxLogLevel("invalid_singbox_log_level"),
    InvalidDohServer("invalid_doh_server"),
    InvalidDohPath("invalid_doh_path"),
    InvalidDohSniForIp("invalid_doh_sni_for_ip"),
    InvalidLocalDnsDomain("invalid_local_dns_domain"),
    TooManyLocalDnsDomains("too_many_local_dns_domains"),

    // Жизненный цикл туннеля
    VpnPermissionMissing("vpn_permission_missing"),
    VpnConnectTimeout("vpn_connect_timeout"),
    VpnStartFailed("vpn_start_failed"),
    VpnStartSuperseded("vpn_start_superseded"),
    VpnStopFailed("vpn_stop_failed"),
    VpnStopTimeout("vpn_stop_timeout"),
    VpnRevoked("vpn_revoked"),
    VpnNotRunningAfterConnect("vpn_not_running_after_connect"),
    TunEstablishFailed("tun_establish_failed"),
    EmptyConfig("empty_config"),
    WifiStateNeedsFineLocation("wifi_state_needs_fine_location"),

    // Проверка туннеля после подключения
    SmokeProxyFailed("smoke_proxy_failed"),
    SmokeTimeout("smoke_timeout"),

    // Ошибки ядра sing-box, распознанные по тексту (см. [toVpnError])
    CoreTlsFailed("core_tls_failed"),
    CoreDnsFailed("core_dns_failed"),
    CoreServerUnreachable("core_server_unreachable"),

    Unknown("unknown"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: String?): VpnError? = code?.trim()?.let { byCode[it] }
    }
}

/** Исключение с типизированной ошибкой; `message` равен [VpnError.code]. */
class VpnException(val error: VpnError, cause: Throwable? = null) : Exception(error.code, cause)

/** Бросает [VpnException] с [error]. */
fun vpnFail(error: VpnError): Nothing = throw VpnException(error)

/** Бросает [VpnException] с [error], если [condition] ложно. */
inline fun ensure(condition: Boolean, error: () -> VpnError) {
    if (!condition) throw VpnException(error())
}

/**
 * Единственное место, где произвольное исключение превращается в [VpnError].
 *
 * Порядок: [VpnException] → точный код в message → код внутри message (ошибки, прошедшие через
 * ядро, приходят обёрнутыми в его текст) → эвристика по тексту ядра → [VpnError.Unknown].
 */
fun Throwable.toVpnError(): VpnError {
    generateSequence(this) { it.cause }.forEach { t ->
        if (t is VpnException) return t.error
    }
    val message = message.orEmpty()
    VpnError.fromCode(message)?.let { return it }
    VpnError.entries.firstOrNull { it != VpnError.Unknown && message.contains(it.code) }?.let { return it }
    return classifyCoreMessage(message)
}

private fun classifyCoreMessage(message: String): VpnError = when {
    message.contains("missing vpn permission", true) ||
        message.contains("not prepared or is revoked", true) -> VpnError.VpnPermissionMissing
    message.contains("tls", true) || message.contains("certificate", true) || message.contains("reality", true) ->
        VpnError.CoreTlsFailed
    message.contains("dns", true) || message.contains("resolve", true) -> VpnError.CoreDnsFailed
    message.contains("timeout", true) || message.contains("connect", true) -> VpnError.CoreServerUnreachable
    message.contains("tun", true) -> VpnError.TunEstablishFailed
    else -> VpnError.Unknown
}
