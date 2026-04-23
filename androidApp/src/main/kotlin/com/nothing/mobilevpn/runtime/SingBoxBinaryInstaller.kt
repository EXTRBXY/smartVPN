package com.nothing.mobilevpn.runtime

import android.content.Context
import java.io.File

/**
 * Copies sing-box binary from assets to app private storage
 * and sets executable permissions for runtime start.
 */
class SingBoxBinaryInstaller(private val context: Context) {
    private val assetPath = "singbox/arm64-v8a/sing-box"
    private val outputFile = File(context.filesDir, "bin/sing-box")

    fun installIfNeeded(): File {
        if (!outputFile.exists()) {
            outputFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        outputFile.setReadable(true, true)
        outputFile.setWritable(true, true)
        outputFile.setExecutable(true, true)
        return outputFile
    }
}
