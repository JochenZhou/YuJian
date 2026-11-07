package com.zqc.tvnotify

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android 原生 NsdManager (DNS-SD) 门面封装：
 * 1. 规避普通应用无法绑定 UDP 5353 端口的系统限制（手机上由系统 mdnsd 接管）；
 * 2. 注册 _sendspin._tcp 客户端服务，让 Music Assistant 局域网直接发现电视/手机播放器；
 * 3. 注册 _tvnotify._tcp 基础服务，让 Home Assistant 局域网直接发现玉简；
 * 4. 监听并自动发现局域网 Music Assistant 的 _sendspin-server._tcp 广播，实现即开即连。
 */
class SendspinNsdHelper(private val context: Context) {

    companion object {
        private const val TAG = "SendspinNSD"
        const val SENDSPIN_CLIENT_SERVICE_TYPE = "_sendspin._tcp"
        const val SENDSPIN_SERVER_SERVICE_TYPE = "_sendspin-server._tcp"
        const val TVNOTIFY_SERVICE_TYPE = "_tvnotify._tcp"
    }

    data class DiscoveredServer(
        val name: String,
        val host: String,
        val port: Int,
        val path: String
    ) {
        val wsUrl: String get() = "ws://$host:$port$path"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var sendspinRegistrationListener: NsdManager.RegistrationListener? = null
    private var tvnotifyRegistrationListener: NsdManager.RegistrationListener? = null
    private var serverDiscoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    var isSendspinRegistered = false
        private set

    @Volatile
    var isTvnotifyRegistered = false
        private set

    val discoveredServers = CopyOnWriteArrayList<DiscoveredServer>()

    /**
     * 注册 Sendspin 播放器客户端服务到系统 mDNS
     */
    fun registerSendspinPlayer(port: Int, clientName: String, onRegistered: (() -> Unit)? = null) {
        val nsd = nsdManager ?: run {
            Log.w(TAG, "系统未提供 NsdManager 服务")
            return
        }
        if (port <= 0) return

        unregisterSendspinPlayer()

        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = clientName
                serviceType = SENDSPIN_CLIENT_SERVICE_TYPE
                this.port = port
                setAttribute("path", "/sendspin")
                setAttribute("name", clientName)
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(regInfo: NsdServiceInfo) {
                    Log.i(TAG, "NsdManager 成功广播 Sendspin 客户端: ${regInfo.serviceName} :$port")
                    isSendspinRegistered = true
                    onRegistered?.invoke()
                }

                override fun onRegistrationFailed(regInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NsdManager 广播 Sendspin 失败: errorCode=$errorCode")
                    isSendspinRegistered = false
                }

                override fun onServiceUnregistered(regInfo: NsdServiceInfo) {
                    Log.i(TAG, "NsdManager 取消广播 Sendspin 客户端")
                    isSendspinRegistered = false
                }

                override fun onUnregistrationFailed(regInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NsdManager 取消广播 Sendspin 异常: errorCode=$errorCode")
                }
            }

            sendspinRegistrationListener = listener
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "调用 NsdManager.registerService(Sendspin) 异常", e)
        }
    }

    fun unregisterSendspinPlayer() {
        val listener = sendspinRegistrationListener ?: return
        sendspinRegistrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "注销 Sendspin 广播异常", e)
        }
        isSendspinRegistered = false
    }

    /**
     * 注册 _tvnotify._tcp 服务（供 Home Assistant 自动发现）
     */
    fun registerTvNotify(port: Int, instanceName: String, token: String, version: String) {
        val nsd = nsdManager ?: return
        if (port <= 0) return

        unregisterTvNotify()

        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = instanceName
                serviceType = TVNOTIFY_SERVICE_TYPE
                this.port = port
                setAttribute("path", "/notify")
                setAttribute("token", token)
                setAttribute("version", version)
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(regInfo: NsdServiceInfo) {
                    Log.i(TAG, "NsdManager 成功广播 TvNotify: ${regInfo.serviceName} :$port")
                    isTvnotifyRegistered = true
                }

                override fun onRegistrationFailed(regInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NsdManager 广播 TvNotify 失败: errorCode=$errorCode")
                    isTvnotifyRegistered = false
                }

                override fun onServiceUnregistered(regInfo: NsdServiceInfo) {
                    isTvnotifyRegistered = false
                }

                override fun onUnregistrationFailed(regInfo: NsdServiceInfo, errorCode: Int) {}
            }

            tvnotifyRegistrationListener = listener
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "调用 NsdManager.registerService(TvNotify) 异常", e)
        }
    }

    fun unregisterTvNotify() {
        val listener = tvnotifyRegistrationListener ?: return
        tvnotifyRegistrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "注销 TvNotify 广播异常", e)
        }
        isTvnotifyRegistered = false
    }

    /**
     * 开始扫描局域网内的 Music Assistant Sendspin 服务端 (_sendspin-server._tcp)
     */
    fun startDiscoveringServers(onServerDiscovered: ((DiscoveredServer) -> Unit)? = null) {
        val nsd = nsdManager ?: return
        if (serverDiscoveryListener != null) return

        try {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.i(TAG, "已启动局域网 Sendspin 服务端扫描: $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.i(TAG, "发现潜在 Sendspin 服务: ${service.serviceName}, type=${service.serviceType}")
                    // 异步解析主机名与端口
                    try {
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "解析 Sendspin 服务端失败: errorCode=$errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = serviceInfo.port
                                if (port <= 0) return

                                val pathBytes = serviceInfo.attributes?.get("path")
                                val path = if (pathBytes != null && pathBytes.isNotEmpty()) {
                                    String(pathBytes, Charsets.UTF_8)
                                } else {
                                    "/sendspin"
                                }

                                val name = serviceInfo.serviceName ?: "Music Assistant"
                                val server = DiscoveredServer(name, host, port, path)
                                if (discoveredServers.none { it.host == host && it.port == port }) {
                                    discoveredServers.add(server)
                                    Log.i(TAG, "成功解析 Music Assistant 服务端: ${server.wsUrl}")
                                    onServerDiscovered?.invoke(server)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "resolveService 异常", e)
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    discoveredServers.removeAll { it.name == service.serviceName }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "局域网 Sendspin 服务端扫描已停止")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "启动 Sendspin 服务端扫描失败: errorCode=$errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            serverDiscoveryListener = listener
            nsd.discoverServices(SENDSPIN_SERVER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices 启动异常", e)
        }
    }

    fun stopDiscoveringServers() {
        val listener = serverDiscoveryListener ?: return
        serverDiscoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "停止服务扫描异常", e)
        }
    }

    fun releaseAll() {
        unregisterSendspinPlayer()
        unregisterTvNotify()
        stopDiscoveringServers()
    }
}
