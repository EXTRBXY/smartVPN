package com.nothing.mobilecore.ui

import android.content.Context
import androidx.annotation.StringRes
import com.nothing.mobilecore.R
import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.toVpnError

/** Текст ошибки для пользователя. Новый [VpnError] без строки здесь не скомпилируется. */
object UserErrorText {
    fun from(context: Context, error: Throwable?): String =
        from(context, error?.toVpnError())

    fun from(context: Context, error: VpnError?): String =
        context.getString(error?.let(::textRes) ?: R.string.user_error_generic)

    @StringRes
    private fun textRes(error: VpnError): Int = when (error) {
        VpnError.NoActiveProfile, VpnError.ProfileNotFound -> R.string.user_error_select_profile
        VpnError.NoReachableProfile -> R.string.user_error_no_reachable_profile
        VpnError.ProfileSecretUnavailable -> R.string.user_error_secret_unavailable
        VpnError.ProfileIdImmutable -> R.string.user_error_profile_id_immutable
        VpnError.ProfileDeleteRequiresDisconnect -> R.string.user_error_profile_delete_requires_disconnect
        VpnError.ProfileNameRequired -> R.string.user_error_profile_name_required
        VpnError.ProfileHostRequired -> R.string.user_error_profile_host_required
        VpnError.ProfilePortInvalid -> R.string.user_error_profile_port_invalid
        VpnError.ProfileUuidInvalid -> R.string.user_error_profile_uuid_invalid
        VpnError.ProfileRealityIncomplete -> R.string.user_error_profile_reality_incomplete

        VpnError.LinkNotVless -> R.string.user_error_only_vless
        VpnError.LinkInvalid -> R.string.user_error_link_invalid
        VpnError.LinkInvalidUuid -> R.string.user_error_invalid_uuid
        VpnError.LinkInvalidHostOrPort -> R.string.user_error_invalid_host_or_port
        VpnError.LinkUnsupported -> R.string.user_error_link_unsupported
        VpnError.LinkRealityIncomplete -> R.string.user_error_reality_profile

        VpnError.PerAppRequiresPackages, VpnError.InvalidPackageName, VpnError.TooManyPackages ->
            R.string.user_error_split_tunnel_packages
        VpnError.InvalidMtu, VpnError.InvalidTunIpv4Cidr, VpnError.InvalidTunStack, VpnError.InvalidSingBoxLogLevel ->
            R.string.user_error_policy_network_params
        VpnError.InvalidDohServer, VpnError.InvalidDohPath -> R.string.user_error_doh_params
        VpnError.InvalidDohSniForIp -> R.string.user_error_doh_sni_for_ip
        VpnError.InvalidLocalDnsDomain, VpnError.TooManyLocalDnsDomains -> R.string.user_error_local_dns_domains

        VpnError.VpnPermissionMissing -> R.string.user_error_vpn_permission
        VpnError.VpnConnectTimeout -> R.string.user_error_timeout
        VpnError.VpnStartFailed, VpnError.VpnStartSuperseded, VpnError.EmptyConfig -> R.string.user_error_vpn_start_failed
        VpnError.VpnStopFailed, VpnError.VpnStopTimeout -> R.string.user_error_vpn_stop_failed
        VpnError.VpnRevoked -> R.string.user_error_vpn_revoked
        VpnError.VpnNotRunningAfterConnect -> R.string.user_error_tunnel_not_running
        VpnError.TunEstablishFailed -> R.string.user_error_tun_establish
        VpnError.WifiStateNeedsFineLocation -> R.string.user_error_needs_location

        VpnError.SmokeProxyFailed -> R.string.user_error_health_proxy
        VpnError.SmokeTimeout -> R.string.user_error_health_timeout

        VpnError.ExternalIpFailed -> R.string.user_error_external_ip

        VpnError.CoreTlsFailed -> R.string.user_error_core_tls
        VpnError.CoreDnsFailed -> R.string.user_error_core_dns
        VpnError.CoreServerUnreachable -> R.string.user_error_core_unreachable

        VpnError.Unknown -> R.string.user_error_generic
    }
}
