package com.nothing.mobilecore.shared.infrastructure

object LogRedaction {
    private val uuidRegex = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", RegexOption.IGNORE_CASE)
    private val realityPbkRegex = Regex("pbk=[^&\\s]+", RegexOption.IGNORE_CASE)
    private val realitySidRegex = Regex("sid=[^&\\s]+", RegexOption.IGNORE_CASE)
    private val windowsPathRegex = Regex("[A-Za-z]:\\\\[^\\s\"]+")
    private val unixPathRegex = Regex("/[^\\s\"]+")
    private val vlessUserInfoRegex = Regex("vless://[^@]+@")
    private val sessionFileRegex = Regex("sing-box-session-[^\\s\"']+\\.json")

    fun redactForLog(text: String): String =
        text
            .replace(uuidRegex, "[uuid]")
            .replace(vlessUserInfoRegex, "vless://[redacted]@")
            .replace(realityPbkRegex, "pbk=[redacted]")
            .replace(realitySidRegex, "sid=[redacted]")
            .replace(windowsPathRegex) { path ->
                if (sessionFileRegex.containsMatchIn(path.value)) "[path]" else path.value
            }
            .replace(unixPathRegex) { path ->
                if (sessionFileRegex.containsMatchIn(path.value)) "[path]" else path.value
            }
}
