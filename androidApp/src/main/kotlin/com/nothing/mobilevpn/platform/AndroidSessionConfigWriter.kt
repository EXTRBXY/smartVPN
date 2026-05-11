package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.shared.application.SessionConfigWriter
import java.io.File
import java.util.concurrent.TimeUnit

class AndroidSessionConfigWriter(
    context: Context,
) : SessionConfigWriter {
    /** Не попадает в автобэкап; только каталог приложения. */
    private val appContext = context.applicationContext
    private val dir = appContext.noBackupFilesDir.also { it.mkdirs() }

    init {
        SessionConfigFileLifecycle.cleanupStartupStaleOnly(
            dir = dir,
            maxAgeMs = TimeUnit.HOURS.toMillis(6),
        )
    }

    @Synchronized
    override fun writeSessionConfig(json: String): String {
        SessionConfigFileLifecycle.cleanup(
            dir = dir,
            keepPath = null,
            maxRetainedFiles = 2,
            maxAgeMs = TimeUnit.HOURS.toMillis(6),
        )
        val target = File(dir, "sing-box-session-${System.currentTimeMillis()}.json")
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            target.writeText(json)
            runCatching { tmp.delete() }
        }
        return target.absolutePath
    }

    fun cleanupSessionConfigs(keepPath: String? = null) {
        SessionConfigFileLifecycle.cleanup(
            dir = dir,
            keepPath = keepPath,
            maxRetainedFiles = 2,
            maxAgeMs = TimeUnit.HOURS.toMillis(6),
        )
    }

    companion object {
        fun cleanupStartupStaleOnly(context: Context) {
            val dir = context.applicationContext.noBackupFilesDir.also { it.mkdirs() }
            SessionConfigFileLifecycle.cleanupStartupStaleOnly(
                dir = dir,
                maxAgeMs = TimeUnit.HOURS.toMillis(6),
            )
        }

        fun cleanupPreConnect(context: Context, keepPath: String) {
            val dir = context.applicationContext.noBackupFilesDir.also { it.mkdirs() }
            SessionConfigFileLifecycle.cleanup(
                dir = dir,
                keepPath = keepPath,
                maxRetainedFiles = 2,
                maxAgeMs = TimeUnit.HOURS.toMillis(6),
            )
        }

        fun cleanupSessionConfigs(context: Context, keepPath: String? = null) {
            val dir = context.applicationContext.noBackupFilesDir.also { it.mkdirs() }
            SessionConfigFileLifecycle.cleanup(
                dir = dir,
                keepPath = keepPath,
                maxRetainedFiles = 2,
                maxAgeMs = TimeUnit.HOURS.toMillis(6),
            )
        }
    }
}

internal object SessionConfigFileLifecycle {
    private fun isSessionFile(file: File): Boolean {
        val name = file.name
        if (!name.startsWith("sing-box-session-")) return false
        return name.endsWith(".json") || name.endsWith(".json.tmp")
    }

    fun cleanupStartupStaleOnly(dir: File, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()
            ?.filter { it.isFile && isSessionFile(it) }
            ?: return
        files.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                runCatching { file.delete() }
            }
        }
    }

    fun cleanup(dir: File, keepPath: String?, maxRetainedFiles: Int, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val keepCanonical = keepPath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        val keepTmpCanonical = keepCanonical?.let { "$it.tmp" }
        val files = dir.listFiles()
            ?.filter { it.isFile && isSessionFile(it) }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        files.forEachIndexed { index, file ->
            val canonical = runCatching { file.canonicalPath }.getOrNull()
            if ((keepCanonical != null && canonical == keepCanonical) ||
                (keepTmpCanonical != null && canonical == keepTmpCanonical)
            ) {
                return@forEachIndexed
            }
            val tooOld = now - file.lastModified() > maxAgeMs
            val overLimit = index >= maxRetainedFiles
            if (tooOld || overLimit) {
                runCatching { file.delete() }
            }
        }
    }
}
