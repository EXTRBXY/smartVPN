package com.nothing.mobilecore.shared.domain

/**
 * Проверка XHTTP перед сохранением и перед компиляцией.
 * Допустимые значения совпадают с разбором транспорта в sing-box, где есть `type: xhttp`
 * (режимы, placement, ненулевой `x_padding_bytes`).
 */
object XhttpPolicy {
    /** Ядро отвергает нулевой padding и подставляет этот диапазон, если поле уже задано. */
    const val DEFAULT_PADDING = "100-1000"
    const val DEFAULT_PATH = "/"

    private const val MAX_TEXT = 1024
    private const val MAX_HEADERS = 32

    private val range = Regex("""^(\d{1,9})(?:-(\d{1,9}))?$""")
    private val headerName = Regex("""^[!#$%&'*+.^_`|~0-9A-Za-z-]+$""")

    private val paddingPlacements = setOf("cookie", "header", "query", "queryInHeader")
    private val paddingMethods = setOf("repeat-x", "tokenish")
    private val sessionPlacements = setOf("path", "cookie", "header", "query")
    private val uplinkPlacements = setOf("auto", "body", "header", "cookie")

    fun requireValid(profile: VlessProfile) {
        if (profile.type != VlessTransport.XHTTP) return
        ensureText(profile.xhttpPath)
        ensureText(profile.xhttpHost)
        profile.xhttpExtra?.let { requireValid(it, profile.xhttpMode) }
    }

    private fun requireValid(extra: XhttpExtra, mode: XhttpMode) {
        ensureHeaders(extra.headers)
        extra.xPaddingBytes?.let { padding ->
            ensure(validRange(padding) && !zeroRange(padding)) { VpnError.XhttpInvalid }
        }
        ensureRange(extra.uplinkChunkSize)
        ensureRange(extra.scMaxEachPostBytes)
        ensureRange(extra.scMinPostsIntervalMs)
        ensureText(extra.xPaddingKey)
        ensureText(extra.xPaddingHeader)
        ensureText(extra.sessionKey)
        ensureText(extra.seqKey)
        ensureText(extra.uplinkDataKey)
        extra.xPaddingPlacement?.let { ensure(it in paddingPlacements) { VpnError.XhttpInvalid } }
        extra.xPaddingMethod?.let { ensure(it in paddingMethods) { VpnError.XhttpInvalid } }
        extra.sessionPlacement?.let { ensure(it in sessionPlacements) { VpnError.XhttpInvalid } }
        extra.seqPlacement?.let { ensure(it in sessionPlacements) { VpnError.XhttpInvalid } }
        extra.uplinkDataPlacement?.let { placement ->
            ensure(placement in uplinkPlacements) { VpnError.XhttpInvalid }
            if (placement == "header" || placement == "cookie") {
                ensure(mode == XhttpMode.PACKET_UP) { VpnError.XhttpInvalid }
            }
        }
        extra.uplinkHTTPMethod?.let { method ->
            ensureText(method)
            if (method.equals("GET", ignoreCase = true)) {
                ensure(mode == XhttpMode.PACKET_UP) { VpnError.XhttpInvalid }
            }
        }
        extra.xmux?.let { requireValid(it) }
    }

    private fun requireValid(xmux: XhttpXmux) {
        ensureRange(xmux.maxConcurrency)
        ensureRange(xmux.maxConnections)
        ensureRange(xmux.cMaxReuseTimes)
        ensureRange(xmux.hMaxRequestTimes)
        ensureRange(xmux.hMaxReusableSecs)
        if (xmux.maxConcurrency != null && !zeroRange(xmux.maxConcurrency) &&
            xmux.maxConnections != null && !zeroRange(xmux.maxConnections)
        ) {
            vpnFail(VpnError.XhttpInvalid)
        }
    }

    private fun ensureRange(value: String?) {
        if (value != null) ensure(validRange(value)) { VpnError.XhttpInvalid }
    }

    private fun ensureText(value: String?) {
        if (value == null) return
        ensure(value.length <= MAX_TEXT && value.none { it.isISOControl() }) { VpnError.XhttpInvalid }
    }

    private fun ensureHeaders(headers: Map<String, String>?) {
        if (headers == null) return
        ensure(headers.size <= MAX_HEADERS) { VpnError.XhttpInvalid }
        for ((name, value) in headers) {
            ensure(headerName.matches(name)) { VpnError.XhttpInvalid }
            ensure(!name.equals("Host", ignoreCase = true)) { VpnError.XhttpInvalid }
            ensureText(value)
        }
    }

    private fun validRange(value: String): Boolean {
        val match = range.matchEntire(value) ?: return false
        val from = match.groupValues[1].toLong()
        val to = match.groupValues[2].toLongOrNull() ?: return true
        return from <= to
    }

    private fun zeroRange(value: String): Boolean {
        val match = range.matchEntire(value) ?: return false
        val from = match.groupValues[1].toLong()
        val to = match.groupValues[2].toLongOrNull() ?: from
        return from <= 0 || to <= 0
    }
}
