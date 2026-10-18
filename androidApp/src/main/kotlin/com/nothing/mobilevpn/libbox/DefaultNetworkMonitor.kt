package com.nothing.mobilecore.libbox

import android.net.Network
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null
    private val lifecycleMutex = Mutex()

    suspend fun start() {
        lifecycleMutex.withLock {
            DefaultNetworkListener.start(this) {
                defaultNetwork = it
                checkDefaultInterfaceUpdate(it)
            }
            defaultNetwork = LibboxAndroidBootstrap.connectivity.activeNetwork
        }
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            DefaultNetworkListener.stop(this)
            updateJob?.cancel()
            updateJob = null
            scope.coroutineContext.cancelChildren()
        }
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val listener = listener ?: return
        if (newNetwork != null) {
            updateJob?.cancel()
            updateJob = scope.launch {
                repeat(10) {
                    val linkProperties = LibboxAndroidBootstrap.connectivity.getLinkProperties(newNetwork)
                    if (linkProperties != null) {
                        val interfaceIndex = try {
                            NetworkInterface.getByName(linkProperties.interfaceName).index
                        } catch (_: Exception) {
                            delay(100)
                            return@repeat
                        }
                        listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                        return@launch
                    }
                    delay(100)
                }
            }
        } else {
            listener.updateDefaultInterface("", -1, false, false)
        }
    }
}
