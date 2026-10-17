package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.Serializable

@Serializable
data class VlessProfile(
    val id: String,
    val name: String,
    val uuid: String,
    val host: String,
    val port: Int,
    val type: VlessTransport = VlessTransport.TCP,
    val security: VlessSecurity = VlessSecurity.TLS,
    val encryption: VlessEncryption = VlessEncryption.NONE,
    val sni: String? = null,
    val alpn: List<String> = emptyList(),
    val fingerprint: String? = null,
    val flow: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val wsPath: String? = null,
    val wsHost: String? = null,
    val grpcServiceName: String? = null,
    val extraQuery: Map<String, String> = emptyMap(),
    val favorite: Boolean = false,
    val sortOrder: Int = 0,
)

/** UUID VLESS: версии 1–8 (RFC 9562). Единая проверка для парсера ссылок и формы редактирования. */
object VlessUuid {
    private val regex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    fun isValid(value: String): Boolean = regex.matches(value)
}

/** Для Reality обязательны оба ключа; для остальных режимов безопасности ключи не нужны. */
fun VlessProfile.hasCompleteRealityKeys(): Boolean =
    security != VlessSecurity.REALITY || (!realityPublicKey.isNullOrBlank() && !realityShortId.isNullOrBlank())
