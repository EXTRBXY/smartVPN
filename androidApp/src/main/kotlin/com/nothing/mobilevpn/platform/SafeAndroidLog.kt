package com.nothing.mobilecore.platform

import android.util.Log
import com.nothing.mobilecore.BuildConfig
import com.nothing.mobilecore.shared.infrastructure.LogRedaction

internal object SafeAndroidLog {
    fun e(tag: String, prefix: String, t: Throwable?) {
        if (t == null) {
            Log.e(tag, LogRedaction.redactForLog(prefix))
            return
        }
        if (BuildConfig.DEBUG) {
            val msg =
                LogRedaction.redactForLog(prefix) + ": " + LogRedaction.redactForLog(t.message ?: "")
            Log.e(tag, msg, t)
        } else {
            Log.e(tag, "${LogRedaction.redactForLog(prefix)}: ${t.javaClass.simpleName}")
        }
    }
}
