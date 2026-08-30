package com.nothing.mobilecore.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nothing.mobilecore.NothingVpnApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as NothingVpnApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.automation.onBootCompleted()
            } finally {
                pending.finish()
            }
        }
    }
}
