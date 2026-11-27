package com.nothing.mobilecore.libbox

import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.NetworkInterface

object DefaultNetworkMonitor {
    @Volatile
    var defaultNetwork: Network? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val updateLock = Any()
    private var registration: Any? = null
    private var listener: InterfaceUpdateListener? = null
    private var updateJob: Job? = null
    private var report: ((String) -> Unit)? = null
    private var appliedNetwork: Network? = null
    private var appliedInterface = -1

    suspend fun start(log: (String) -> Unit) {
        lifecycleMutex.withLock {
            val connectivity = LibboxAndroidBootstrap.connectivity
            val initialNetwork = connectivity.activeNetwork?.takeIf { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            val token = Any()
            synchronized(updateLock) {
                registration = token
                report = log
                defaultNetwork = initialNetwork
                scheduleUpdate()
            }
            DefaultNetworkListener.start(this) { network ->
                synchronized(updateLock) {
                    if (registration === token) {
                        if (defaultNetwork != network) {
                            val capabilities = network?.let(connectivity::getNetworkCapabilities)
                            val transport = when {
                                network == null -> "unavailable"
                                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                                else -> "other"
                            }
                            report?.invoke("VPN underlying network: $transport")
                        }
                        defaultNetwork = network
                        scheduleUpdate()
                    }
                }
            }
        }
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            synchronized(updateLock) {
                registration = null
                defaultNetwork = null
                listener = null
                report = null
                appliedNetwork = null
                appliedInterface = -1
                updateJob?.cancel()
                updateJob = null
            }
            DefaultNetworkListener.stop(this)
        }
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        synchronized(updateLock) {
            this.listener = listener
            scheduleUpdate()
        }
    }

    // updateLock protects both invalidation and publication to libbox.
    private fun scheduleUpdate() {
        updateJob?.cancel()
        updateJob = null
        val receiver = listener ?: return
        if (registration == null) return
        val network = defaultNetwork
        if (network == null) {
            appliedNetwork = null
            appliedInterface = -1
            receiver.updateDefaultInterface("", -1, false, false)
            return
        }
        updateJob = scope.launch {
            repeat(10) {
                val name = LibboxAndroidBootstrap.connectivity.getLinkProperties(network)?.interfaceName
                val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
                if (name != null && index != null && index >= 0) {
                    val updateContext = coroutineContext
                    synchronized(updateLock) {
                        updateContext.ensureActive()
                        receiver.updateDefaultInterface(name, index, false, false)
                        if (appliedNetwork != network || appliedInterface != index) {
                            appliedNetwork = network
                            appliedInterface = index
                            report?.invoke("VPN underlying interface: updated")
                        }
                    }
                    return@launch
                }
                delay(100)
            }
        }
    }
}
