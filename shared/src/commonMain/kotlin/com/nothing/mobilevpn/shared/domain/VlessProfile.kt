package com.nothing.mobilevpn.shared.domain

import kotlinx.serialization.Serializable

@Serializable
data class VlessProfile(
    val id: String,
    val name: String,
    val uuid: String,
    val host: String,
    val port: Int,
    val type: String = "tcp",
    val security: String = "tls",
    val encryption: String = "none",
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
)
