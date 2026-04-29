package com.nothing.mobilevpn.platform

import android.content.Context
import com.nothing.mobilevpn.shared.application.SessionConfigWriter
import java.io.File

class AndroidSessionConfigWriter(
    context: Context,
) : SessionConfigWriter {
    /** Не попадает в автобэкап; только каталог приложения. */
    private val dir = context.applicationContext.noBackupFilesDir.also { it.mkdirs() }

    override fun writeSessionConfig(json: String): String {
        val f = File(dir, "sing-box-session.json")
        f.writeText(json)
        return f.absolutePath
    }
}
