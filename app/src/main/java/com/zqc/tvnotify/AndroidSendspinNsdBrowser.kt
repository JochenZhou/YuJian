package com.zqc.tvnotify

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.sendspin.protocol.NsdBrowser
import com.sendspin.protocol.NsdServiceEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque

class AndroidSendspinNsdBrowser(context: Context) : NsdBrowser {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    override fun browse(serviceType: String): Flow<NsdServiceEvent> = callbackFlow {
        val pending = ArrayDeque<NsdServiceInfo>()
        val found = mutableSetOf<String>()
        var resolving = false
        var stopped = false

        lateinit var discovery: NsdManager.DiscoveryListener
        lateinit var resolveNext: () -> Unit

        resolveNext = {
            if (!stopped && !resolving && pending.isNotEmpty()) {
                val next = pending.removeFirst()
                resolving = true
                try {
                    manager.resolveService(next, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                            resolving = false
                            Log.w(TAG, "Sendspin NSD resolve failed: $code")
                            resolveNext()
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            resolving = false
                            val host = info.host?.hostAddress
                            if (!host.isNullOrEmpty() && info.port > 0) {
                                val path = info.attributes["path"]?.toString(Charsets.UTF_8)
                                    ?.takeIf { it.startsWith("/") } ?: "/sendspin"
                                val formattedHost = if (host.contains(":")) "[$host]" else host
                                trySend(NsdServiceEvent.ServiceResolved(info.serviceName, formattedHost, info.port, path))
                            }
                            resolveNext()
                        }
                    })
                } catch (error: Exception) {
                    resolving = false
                    Log.w(TAG, "Sendspin NSD resolve exception", error)
                    resolveNext()
                }
            }
        }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (found.add(service.serviceName)) {
                    trySend(NsdServiceEvent.ServiceFound(service.serviceName))
                    pending.addLast(service)
                    resolveNext()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                pending.removeAll { it.serviceName == service.serviceName }
                trySend(NsdServiceEvent.ServiceLost(service.serviceName))
            }

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                trySend(NsdServiceEvent.BrowseError(code))
                try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
                close()
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) {
                trySend(NsdServiceEvent.BrowseError(code))
            }

            override fun onDiscoveryStopped(type: String) = Unit
        }

        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (error: Exception) {
            trySend(NsdServiceEvent.BrowseError(-1))
            close(error)
        }
        awaitClose {
            stopped = true
            pending.clear()
            found.clear()
            try { manager.stopServiceDiscovery(discovery) } catch (_: Exception) { }
        }
    }

    companion object {
        private const val TAG = "YuJianSendspinNsd"
    }
}
