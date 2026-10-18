package com.nothing.mobilecore.libbox

import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.nothing.mobilecore.platform.SafeAndroidLog
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * Реализация [PlatformInterface] для Android (по мотивам SFA [PlatformInterfaceWrapper]).
 * [openTun] реализуется в [com.nothing.mobilecore.vpn.NothingVpnService].
 */
interface MobilePlatformInterface : PlatformInterface {
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
    }

    override fun openTun(options: TunOptions): Int {
        error("openTun must be implemented by VpnService")
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        try {
            val uid =
                LibboxAndroidBootstrap.connectivity.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort),
                )
            if (uid == Process.INVALID_UID) error("android: connection owner not found")
            val packages = LibboxAndroidBootstrap.packageManager.getPackagesForUid(uid)
            val owner = ConnectionOwner()
            owner.userId = uid
            owner.userName = packages?.firstOrNull() ?: ""
            owner.setAndroidPackageNames(
                StringArray(packages?.toList()?.iterator() ?: emptyList<String>().iterator()),
            )
            return owner
        } catch (e: Exception) {
            SafeAndroidLog.e("MobilePlatformInterface", "getConnectionOwnerUid", e)
            throw e
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun registerMyInterface(name: String) {
    }

    override fun sendNotification(notification: Notification) {
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = LibboxAndroidBootstrap.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in networks) {
            val boxInterface = LibboxNetworkInterface()
            val linkProperties = LibboxAndroidBootstrap.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities =
                LibboxAndroidBootstrap.connectivity.getNetworkCapabilities(network) ?: continue
            boxInterface.name = linkProperties.interfaceName
            val networkInterface =
                networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer =
                StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
            boxInterface.type =
                when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
            boxInterface.index = networkInterface.index
            runCatching {
                boxInterface.mtu = networkInterface.mtu
            }.onFailure {
                SafeAndroidLog.e(
                    "MobilePlatformInterface",
                    "failed to get mtu for interface ${boxInterface.name}",
                    it,
                )
            }
            boxInterface.addresses =
                StringArray(
                    networkInterface.interfaceAddresses.mapTo(mutableListOf()) { it.toPrefix() }.iterator(),
                )
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) {
                dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            }
            if (networkInterface.isPointToPoint) {
                dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            }
            if (networkInterface.supportsMulticast()) {
                dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            }
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
    }

    override fun readWIFIState(): WIFIState? {
        @Suppress("DEPRECATION")
        val wifiInfo = LibboxAndroidBootstrap.wifiManager.connectionInfo ?: return null
        var ssid = wifiInfo.ssid
        if (ssid == "<unknown ssid>") {
            return WIFIState("", "")
        }
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return WIFIState(ssid, wifiInfo.bssid)
    }

    override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

    /**
     * Имя устройства для Tailscale (как в SFA): из настроек Android, иначе «производитель модель».
     * Наш конфиг Tailscale не использует; метод обязателен по интерфейсу.
     */
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // Ниже — возможности ядра, которым в SFA нужен root (оболочка/SSH/SFTP, мост, таблица соседей).
    // Приложение работает без root и их не использует: сообщаем ядру, что они недоступны.

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("android: platform shell is not supported")
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environment: StringIterator,
        term: String,
        rows: Int,
        columns: Int,
    ): ShellSession = error("android: platform shell is not supported")

    override fun lookupUser(name: String): PlatformUser = error("android: user lookup is not supported")

    override fun lookupSFTPServer(): String = error("android: sftp server is not supported")

    override fun readSystemSSHHostKey(): String = error("android: ssh host key is not supported")

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession = error("android: bridge is not supported")

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }
}
