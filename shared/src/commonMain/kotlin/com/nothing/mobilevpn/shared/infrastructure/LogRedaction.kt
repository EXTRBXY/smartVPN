package com.nothing.mobilevpn.shared.infrastructure

object LogRedaction {
    private val uuidRegex = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", RegexOption.IGNORE_CASE)
    private val realityPbkRegex = Regex("pbk=[^&\\s]+", RegexOption.IGNORE_CASE)
    private val realitySidRegex = Regex("sid=[^&\\s]+", RegexOption.IGNORE_CASE)

    fun redactForLog(text: String): String =
        text
            .replace(uuidRegex, "[uuid]")
            .replace(Regex("vless://[^@]+@"), "vless://[redacted]@")
            .replace(realityPbkRegex, "pbk=[redacted]")
            .replace(realitySidRegex, "sid=[redacted]")

    fun redactUserMessage(text: String): String = redactForLog(text)
}
