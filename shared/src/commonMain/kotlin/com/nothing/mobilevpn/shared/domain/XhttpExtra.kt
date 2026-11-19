package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиентские поля `extra` из ссылки XHTTP (имена как у Xray).
 * В конфиг sing-box их переводит компилятор; сырой JSON ссылки в хранилище не пишется.
 */
@Serializable
data class XhttpExtra(
    val headers: Map<String, String>? = null,
    val xPaddingBytes: String? = null,
    val xPaddingObfsMode: Boolean? = null,
    val xPaddingKey: String? = null,
    val xPaddingHeader: String? = null,
    val xPaddingPlacement: String? = null,
    val xPaddingMethod: String? = null,
    val uplinkHTTPMethod: String? = null,
    val sessionPlacement: String? = null,
    val sessionKey: String? = null,
    val seqPlacement: String? = null,
    val seqKey: String? = null,
    val uplinkDataPlacement: String? = null,
    val uplinkDataKey: String? = null,
    val uplinkChunkSize: String? = null,
    val noGRPCHeader: Boolean? = null,
    val scMaxEachPostBytes: String? = null,
    val scMinPostsIntervalMs: String? = null,
    val xmux: XhttpXmux? = null,
)

@Serializable
data class XhttpXmux(
    val maxConcurrency: String? = null,
    val maxConnections: String? = null,
    val cMaxReuseTimes: String? = null,
    val hMaxRequestTimes: String? = null,
    val hMaxReusableSecs: String? = null,
    val hKeepAlivePeriod: Long? = null,
)

private val xhttpCanonicalJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** Стабильная JSON-строка для ссылки и для ключа id. Порядок полей — порядок свойств модели. */
fun XhttpExtra.canonicalJson(): String = xhttpCanonicalJson.encodeToString(XhttpExtra.serializer(), this)
