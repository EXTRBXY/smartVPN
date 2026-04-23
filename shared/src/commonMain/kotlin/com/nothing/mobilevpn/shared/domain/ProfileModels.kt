package com.nothing.mobilevpn.shared.domain

import kotlinx.serialization.Serializable

@Serializable
data class VlessProfile(
    val id: String,
    val host: String,
    val port: Int,
    val uuid: String,
    val flow: String? = null,
    val security: String = "tls",
)
