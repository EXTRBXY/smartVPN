package com.nothing.mobilecore.shared.infrastructure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
    @Test
    fun redactForLog_hidesSessionPathWindows() {
        val source = "failed to open C:\\data\\user\\0\\pkg\\no_backup\\sing-box-session-123.json"
        val redacted = LogRedaction.redactForLog(source)
        assertFalse(redacted.contains("sing-box-session-123.json"))
        assertTrue(redacted.contains("[path]"))
    }

    @Test
    fun redactForLog_hidesSessionPathUnix() {
        val source = "failed to open /data/user/0/pkg/no_backup/sing-box-session-123.json"
        val redacted = LogRedaction.redactForLog(source)
        assertFalse(redacted.contains("sing-box-session-123.json"))
        assertTrue(redacted.contains("[path]"))
    }
}
