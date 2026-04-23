package com.nothing.mobilevpn.platform

import android.content.Context
import com.nothing.mobilevpn.shared.application.SessionConfigWriter
import java.io.File

class AndroidSessionConfigWriter(
    context: Context,
) : SessionConfigWriter {
    private val cacheDir = context.applicationContext.cacheDir

    override fun writeSessionConfig(json: String): String {
        val f = File(cacheDir, "sing-box-session.json")
        f.writeText(json)
        return f.absolutePath
    }
}
