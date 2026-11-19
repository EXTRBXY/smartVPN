package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.VpnError
import com.nothing.mobilecore.shared.domain.XhttpExtra
import com.nothing.mobilecore.shared.domain.XhttpXmux
import com.nothing.mobilecore.shared.domain.vpnFail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Разбор JSON `extra` из ссылки vless://. Имена Xray и snake_case sing-box сводятся к [XhttpExtra].
 * Неизвестные ключи — ошибка. Поля только сервера отбрасываются: клиенту они не нужны.
 */
internal object XhttpExtraParser {
    data class Parsed(
        val mode: String?,
        val extra: XhttpExtra?,
    )

    private val json = Json { isLenient = false }

    private val aliases = mapOf(
        "headers" to "headers",
        "xpaddingbytes" to "xPaddingBytes",
        "x_padding_bytes" to "xPaddingBytes",
        "xpaddingobfsmode" to "xPaddingObfsMode",
        "x_padding_obfs_mode" to "xPaddingObfsMode",
        "xpaddingkey" to "xPaddingKey",
        "x_padding_key" to "xPaddingKey",
        "xpaddingheader" to "xPaddingHeader",
        "x_padding_header" to "xPaddingHeader",
        "xpaddingplacement" to "xPaddingPlacement",
        "x_padding_placement" to "xPaddingPlacement",
        "xpaddingmethod" to "xPaddingMethod",
        "x_padding_method" to "xPaddingMethod",
        "uplinkhttpmethod" to "uplinkHTTPMethod",
        "uplink_http_method" to "uplinkHTTPMethod",
        "sessionidplacement" to "sessionPlacement",
        "sessionplacement" to "sessionPlacement",
        "session_placement" to "sessionPlacement",
        "sessionidkey" to "sessionKey",
        "sessionkey" to "sessionKey",
        "session_key" to "sessionKey",
        "seqplacement" to "seqPlacement",
        "seq_placement" to "seqPlacement",
        "seqkey" to "seqKey",
        "seq_key" to "seqKey",
        "uplinkdataplacement" to "uplinkDataPlacement",
        "uplink_data_placement" to "uplinkDataPlacement",
        "uplinkdatakey" to "uplinkDataKey",
        "uplink_data_key" to "uplinkDataKey",
        "uplinkchunksize" to "uplinkChunkSize",
        "uplink_chunk_size" to "uplinkChunkSize",
        "nogrpcheader" to "noGRPCHeader",
        "no_grpc_header" to "noGRPCHeader",
        "scmaxeachpostbytes" to "scMaxEachPostBytes",
        "sc_max_each_post_bytes" to "scMaxEachPostBytes",
        "scminpostsintervalms" to "scMinPostsIntervalMs",
        "sc_min_posts_interval_ms" to "scMinPostsIntervalMs",
        "xmux" to "xmux",
        "mode" to "mode",
    )

    private val dropped = setOf(
        "nosseheader", "no_sse_header",
        "scmaxbufferedposts", "sc_max_buffered_posts",
        "scstreamupserversecs", "sc_stream_up_server_secs",
        "servermaxheaderbytes", "server_max_header_bytes",
        "trustedxforwardedfor", "trusted_x_forwarded_for",
        "domainstrategy", "domain_strategy",
    )

    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed(mode = null, extra = null)
        val element = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            vpnFail(VpnError.XhttpInvalid)
        }
        val objectNode = element as? JsonObject ?: vpnFail(VpnError.XhttpInvalid)
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((key, value) in objectNode) {
            val folded = key.filter { it != '_' }.lowercase()
            if (folded in dropped || key.lowercase() in dropped) continue
            val canonical = aliases[key.lowercase()] ?: aliases[folded] ?: vpnFail(VpnError.XhttpInvalid)
            val previous = fields.put(canonical, value)
            if (previous != null && previous != value) vpnFail(VpnError.XhttpInvalid)
        }
        val mode = fields.remove("mode")?.let { it.asText() ?: vpnFail(VpnError.XhttpInvalid) }
        val extra = XhttpExtra(
            headers = fields.remove("headers")?.asStringMap(),
            xPaddingBytes = fields.remove("xPaddingBytes")?.asRangeText(),
            xPaddingObfsMode = fields.remove("xPaddingObfsMode")?.asBoolean(),
            xPaddingKey = fields.remove("xPaddingKey")?.asText(),
            xPaddingHeader = fields.remove("xPaddingHeader")?.asText(),
            xPaddingPlacement = fields.remove("xPaddingPlacement")?.asText(),
            xPaddingMethod = fields.remove("xPaddingMethod")?.asText(),
            uplinkHTTPMethod = fields.remove("uplinkHTTPMethod")?.asText(),
            sessionPlacement = fields.remove("sessionPlacement")?.asText(),
            sessionKey = fields.remove("sessionKey")?.asText(),
            seqPlacement = fields.remove("seqPlacement")?.asText(),
            seqKey = fields.remove("seqKey")?.asText(),
            uplinkDataPlacement = fields.remove("uplinkDataPlacement")?.asText(),
            uplinkDataKey = fields.remove("uplinkDataKey")?.asText(),
            uplinkChunkSize = fields.remove("uplinkChunkSize")?.asRangeText(),
            noGRPCHeader = fields.remove("noGRPCHeader")?.asBoolean(),
            scMaxEachPostBytes = fields.remove("scMaxEachPostBytes")?.asRangeText(),
            scMinPostsIntervalMs = fields.remove("scMinPostsIntervalMs")?.asRangeText(),
            xmux = fields.remove("xmux")?.asXmux(),
        )
        if (fields.isNotEmpty()) vpnFail(VpnError.XhttpInvalid)
        val stored = if (extra == XhttpExtra()) null else extra
        return Parsed(mode = mode, extra = stored)
    }

    private fun kotlinx.serialization.json.JsonElement.asText(): String? {
        val primitive = this as? JsonPrimitive ?: vpnFail(VpnError.XhttpInvalid)
        if (!primitive.isString) vpnFail(VpnError.XhttpInvalid)
        return primitive.content
    }

    private fun kotlinx.serialization.json.JsonElement.asRangeText(): String? {
        val primitive = this as? JsonPrimitive ?: vpnFail(VpnError.XhttpInvalid)
        return if (primitive.isString) primitive.content else primitive.content
    }

    private fun kotlinx.serialization.json.JsonElement.asBoolean(): Boolean? {
        val primitive = this as? JsonPrimitive ?: vpnFail(VpnError.XhttpInvalid)
        return primitive.booleanOrNull ?: vpnFail(VpnError.XhttpInvalid)
    }

    private fun kotlinx.serialization.json.JsonElement.asStringMap(): Map<String, String>? {
        val objectNode = this as? JsonObject ?: vpnFail(VpnError.XhttpInvalid)
        if (objectNode.isEmpty()) return null
        return objectNode.mapValues { (_, value) ->
            val primitive = value as? JsonPrimitive ?: vpnFail(VpnError.XhttpInvalid)
            if (!primitive.isString) vpnFail(VpnError.XhttpInvalid)
            primitive.content
        }
    }

    private fun kotlinx.serialization.json.JsonElement.asXmux(): XhttpXmux? {
        val objectNode = this as? JsonObject ?: vpnFail(VpnError.XhttpInvalid)
        if (objectNode.isEmpty()) return null
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        val names = mapOf(
            "maxconcurrency" to "maxConcurrency",
            "max_concurrency" to "maxConcurrency",
            "maxconnections" to "maxConnections",
            "max_connections" to "maxConnections",
            "cmaxreusetimes" to "cMaxReuseTimes",
            "c_max_reuse_times" to "cMaxReuseTimes",
            "hmaxrequesttimes" to "hMaxRequestTimes",
            "h_max_request_times" to "hMaxRequestTimes",
            "hmaxreusablesecs" to "hMaxReusableSecs",
            "h_max_reusable_secs" to "hMaxReusableSecs",
            "hkeepaliveperiod" to "hKeepAlivePeriod",
            "h_keep_alive_period" to "hKeepAlivePeriod",
        )
        for ((key, value) in objectNode) {
            val canonical = names[key.lowercase()] ?: vpnFail(VpnError.XhttpInvalid)
            if (fields.put(canonical, value) != null) vpnFail(VpnError.XhttpInvalid)
        }
        val xmux = XhttpXmux(
            maxConcurrency = fields.remove("maxConcurrency")?.asRangeText(),
            maxConnections = fields.remove("maxConnections")?.asRangeText(),
            cMaxReuseTimes = fields.remove("cMaxReuseTimes")?.asRangeText(),
            hMaxRequestTimes = fields.remove("hMaxRequestTimes")?.asRangeText(),
            hMaxReusableSecs = fields.remove("hMaxReusableSecs")?.asRangeText(),
            hKeepAlivePeriod = fields.remove("hKeepAlivePeriod")?.asLong(),
        )
        if (fields.isNotEmpty()) vpnFail(VpnError.XhttpInvalid)
        return xmux
    }

    private fun kotlinx.serialization.json.JsonElement.asLong(): Long? {
        val primitive = this as? JsonPrimitive ?: vpnFail(VpnError.XhttpInvalid)
        if (primitive.isString || this is JsonArray) vpnFail(VpnError.XhttpInvalid)
        return primitive.longOrNull ?: vpnFail(VpnError.XhttpInvalid)
    }
}
