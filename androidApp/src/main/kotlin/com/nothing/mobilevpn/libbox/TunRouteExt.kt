package com.nothing.mobilecore.libbox

import android.net.IpPrefix
import android.os.Build
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.RoutePrefix
import java.net.InetAddress

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RoutePrefix.toAndroidIpPrefix(): IpPrefix {
    val addr = InetAddress.getByName(address())
    return IpPrefix(addr, prefix().toInt())
}
