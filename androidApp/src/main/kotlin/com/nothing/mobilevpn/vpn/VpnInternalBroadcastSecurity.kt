package com.nothing.mobilecore.vpn

/**
 * Signature-level permission for internal runtime broadcasts.
 * Prevents spoofed state/connect intents from other apps on API levels
 * where dynamic receivers can't rely on NOT_EXPORTED flags.
 */
object VpnInternalBroadcastSecurity {
    const val PERMISSION = "com.nothing.mobilecore.permission.INTERNAL_BROADCAST"
}
