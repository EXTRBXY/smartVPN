package com.nothing.mobilecore.libbox

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor

/**
 * Отслеживание «текущей» сети для libbox (аналогично по смыслу SFA, без копирования исходников).
 */
@OptIn(ObsoleteCoroutinesApi::class)
object DefaultNetworkListener {

    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()
        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    private val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkActor =
        actorScope.actor<NetworkMessage>(Dispatchers.Default) {
            val listeners = mutableMapOf<Any, (Network?) -> Unit>()
            var network: Network? = null
            val pending = arrayListOf<NetworkMessage.Get>()
            for (message in channel) {
                when (message) {
                    is NetworkMessage.Start -> {
                        if (listeners.isEmpty()) register()
                        listeners[message.key] = message.listener
                        network?.let { message.listener(it) }
                    }

                    is NetworkMessage.Get -> {
                        check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                        if (network == null) pending += message
                        else message.response.complete(network)
                    }

                    is NetworkMessage.Stop ->
                        if (listeners.isNotEmpty() &&
                            listeners.remove(message.key) != null &&
                            listeners.isEmpty()
                        ) {
                            network = null
                            unregister()
                        }

                    is NetworkMessage.Put -> {
                        network = message.network
                        pending.forEach { it.response.complete(message.network) }
                        pending.clear()
                        listeners.values.forEach { it(network) }
                    }

                    is NetworkMessage.Update ->
                        if (network == message.network) {
                            listeners.values.forEach { it(network) }
                        }

                    is NetworkMessage.Lost ->
                        if (network == message.network) {
                            network = null
                            listeners.values.forEach { it(null) }
                        }
                }
            }
        }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get(): Network = NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkActor.trySend(NetworkMessage.Put(network))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            networkActor.trySend(NetworkMessage.Update(network))
        }

        override fun onLost(network: Network) {
            networkActor.trySend(NetworkMessage.Lost(network))
        }
    }

    private val request =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun register() {
        val cm = LibboxAndroidBootstrap.connectivity
        // minSdk 26: ветки для более старых API (как в SFA) не нужны.
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                cm.registerBestMatchingNetworkCallback(request, Callback, mainHandler)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                cm.requestNetwork(request, Callback, mainHandler)
            else ->
                cm.registerDefaultNetworkCallback(Callback, mainHandler)
        }
    }

    private fun unregister() {
        runCatching { LibboxAndroidBootstrap.connectivity.unregisterNetworkCallback(Callback) }
    }
}
