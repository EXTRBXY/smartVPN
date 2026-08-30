package com.nothing.mobilecore.platform

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionConfigFileLifecycleTest {
    @Test
    fun cleanup_keepsRecentAndDeletesOldAndOverflow() {
        val dir = Files.createTempDirectory("session-cleanup-test").toFile()
        try {
            val keep = createSessionFile(dir, "sing-box-session-1.json", ageMs = 100)
            createSessionFile(dir, "sing-box-session-2.json", ageMs = 200)
            createSessionFile(dir, "sing-box-session-3.json", ageMs = 300)
            createSessionFile(dir, "sing-box-session-4.json", ageMs = 10_000)

            SessionConfigFileLifecycle.cleanup(
                dir = dir,
                keepPath = keep.absolutePath,
                maxRetainedFiles = 2,
                maxAgeMs = 1_000,
            )

            val remaining = dir.listFiles()
                ?.filter { it.name.startsWith("sing-box-session-") }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()

            assertTrue("sing-box-session-1.json" in remaining)
            assertEquals(2, remaining.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanupStartupStaleOnly_doesNotDeleteRecentFiles() {
        val dir = Files.createTempDirectory("session-startup-cleanup-test").toFile()
        try {
            val recent = createSessionFile(dir, "sing-box-session-recent.json", ageMs = 50)
            val stale = createSessionFile(dir, "sing-box-session-stale.json", ageMs = 10_000)
            val recentTmp = createSessionFile(dir, "sing-box-session-recent.json.tmp", ageMs = 50)
            val staleTmp = createSessionFile(dir, "sing-box-session-stale.json.tmp", ageMs = 10_000)

            SessionConfigFileLifecycle.cleanupStartupStaleOnly(
                dir = dir,
                maxAgeMs = 1_000,
            )

            assertTrue(recent.exists())
            assertTrue(!stale.exists())
            assertTrue(recentTmp.exists())
            assertTrue(!staleTmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanup_doesNotDeleteKeepPathAndTmpPair() {
        val dir = Files.createTempDirectory("session-keep-path-test").toFile()
        try {
            val keep = createSessionFile(dir, "sing-box-session-active.json", ageMs = 10_000)
            val keepTmp = createSessionFile(dir, "sing-box-session-active.json.tmp", ageMs = 10_000)
            createSessionFile(dir, "sing-box-session-old-1.json", ageMs = 10_000)
            createSessionFile(dir, "sing-box-session-old-2.json.tmp", ageMs = 10_000)

            SessionConfigFileLifecycle.cleanup(
                dir = dir,
                keepPath = keep.absolutePath,
                maxRetainedFiles = 1,
                maxAgeMs = 1_000,
            )

            assertTrue(keep.exists())
            assertTrue(keepTmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createSessionFile(dir: File, name: String, ageMs: Long): File {
        val file = File(dir, name)
        file.writeText("{}")
        file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }
}
