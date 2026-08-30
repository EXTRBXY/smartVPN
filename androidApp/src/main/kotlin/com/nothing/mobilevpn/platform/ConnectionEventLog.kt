package com.nothing.mobilecore.platform

import android.content.Context
import com.nothing.mobilecore.shared.infrastructure.LogRedaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConnectionEventLog {
    private const val PREFS = "nothing_vpn_diagnostics"
    private const val KEY = "events"
    private const val MAX_EVENTS = 80

    @Synchronized
    fun add(context: Context, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeMessage = LogRedaction.redactForLog(message).replace(Regex("[\r\n]+"), " ").take(240)
        val updated = (listOf("$timestamp  $safeMessage") + read(context)).take(MAX_EVENTS)
        prefs.edit().putString(KEY, updated.joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, "")
        .orEmpty()
        .lineSequence()
        .filter { it.isNotBlank() }
        .toList()

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
