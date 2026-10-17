package com.nothing.mobilecore.ui

import android.content.Context
import com.nothing.mobilecore.R

object UserErrorText {
    fun from(context: Context, raw: String?): String {
        val code = raw?.trim().orEmpty()
        if (code.isEmpty()) return context.getString(R.string.user_error_generic)
        return when (code) {
            "no_active_profile", "profile_not_found" -> context.getString(R.string.user_error_select_profile)
            "per_app_requires_packages", "invalid_package_name", "too_many_packages" ->
                context.getString(R.string.user_error_split_tunnel_packages)
            "invalid_mtu", "invalid_tun_ipv4_cidr", "invalid_tun_stack", "invalid_singbox_log_level" ->
                context.getString(R.string.user_error_policy_network_params)
            "invalid_doh_server", "invalid_doh_path" ->
                context.getString(R.string.user_error_doh_params)
            "invalid_local_dns_domain", "too_many_local_dns_domains" ->
                "Проверьте список локальных DNS-доменов."
            "invalid_doh_sni_for_ip" ->
                context.getString(R.string.user_error_doh_sni_for_ip)
            "vpn_connect_timeout" ->
                context.getString(R.string.user_error_timeout)
            "vpn_revoked" ->
                context.getString(R.string.user_error_vpn_revoked)
            "vpn_not_running_after_connect" ->
                context.getString(R.string.user_error_tunnel_not_running)
            "profile_secret_unavailable" ->
                context.getString(R.string.user_error_secret_unavailable)
            "smoke_dns_failed" ->
                context.getString(R.string.user_error_health_dns)
            "smoke_tcp_failed" ->
                context.getString(R.string.user_error_health_tcp)
            "smoke_proxy_failed" ->
                context.getString(R.string.user_error_health_proxy)
            "smoke_timeout" ->
                context.getString(R.string.user_error_health_timeout)
            "wifi_state_needs_fine_location" ->
                context.getString(R.string.user_error_needs_location)
            "reality_public_key_missing", "reality_short_id_missing" ->
                context.getString(R.string.user_error_reality_profile)
            "Only vless:// links are supported." ->
                context.getString(R.string.user_error_only_vless)
            "Invalid UUID format." ->
                context.getString(R.string.user_error_invalid_uuid)
            "Missing host.", "Invalid port." ->
                context.getString(R.string.user_error_invalid_host_or_port)
            "profile_delete_requires_disconnect" ->
                context.getString(R.string.user_error_profile_delete_requires_disconnect)
            "profile_name_required" ->
                context.getString(R.string.user_error_profile_name_required)
            "profile_host_required" ->
                context.getString(R.string.user_error_profile_host_required)
            "profile_port_invalid" ->
                context.getString(R.string.user_error_profile_port_invalid)
            "profile_uuid_invalid" ->
                context.getString(R.string.user_error_profile_uuid_invalid)
            "profile_reality_incomplete" ->
                context.getString(R.string.user_error_profile_reality_incomplete)
            "profile_id_immutable" ->
                context.getString(R.string.user_error_profile_id_immutable)
            else -> when {
                code.contains("tls", true) || code.contains("certificate", true) || code.contains("reality", true) ->
                    "Не удалось установить защищённое соединение. Проверьте TLS/Reality и время на устройстве."
                code.contains("dns", true) || code.contains("resolve", true) ->
                    "Не удалось разрешить адрес. Проверьте настройки DNS."
                code.contains("timeout", true) || code.contains("connect", true) ->
                    "Сервер не отвечает. Проверьте сеть, адрес и порт профиля."
                code.contains("tun", true) ->
                    "Android не смог создать VPN-туннель. Проверьте разрешение VPN и наличие другого VPN-приложения."
                else -> context.getString(R.string.user_error_generic)
            }
        }
    }
}
