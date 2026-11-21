package com.example.phonecontrol.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque

data class DiscoveredAgent(
    val id: String,
    val name: String,
    val host: String,
    val port: Int
)

/**
 * One-shot mDNS discovery with a resolve queue (Android NSD only supports one
 * resolve at a time reliably). Discovery stops automatically after a timeout;
 * manual IP entry is always available.
 *
 * A generation counter invalidates late callbacks after stop/restart.
 */
class NsdDiscovery(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onFound(agent: DiscoveredAgent)
        fun onLost(id: String)
        fun onScanChanged(scanning: Boolean)
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryRunning = false
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var generation = 0
    private val stopRunnable = Runnable { stopInternal() }

    fun start(timeoutMs: Long = 8_000L) {
        mainHandler.post {
            if (discoveryListener != null) return@post
            val nsd = nsdManager
            if (nsd == null) {
                listener.onScanChanged(false)
                return@post
            }

            acquireMulticastLock()
            generation++
            val scanGeneration = generation
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    mainHandler.post {
                        if (scanGeneration != generation) return@post
                        discoveryRunning = true
                        listener.onScanChanged(true)
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    mainHandler.post {
                        if (scanGeneration != generation) return@post
                        resolveQueue.addLast(serviceInfo)
                        resolveNext(scanGeneration)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    mainHandler.post {
                        if (scanGeneration != generation) return@post
                        listener.onLost(serviceInfo.serviceName.orEmpty())
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    mainHandler.post {
                        if (scanGeneration != generation) return@post
                        discoveryRunning = false
                        listener.onScanChanged(false)
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "start discovery failed: $errorCode")
                    mainHandler.post {
                        if (scanGeneration != generation) return@post
                        discoveryRunning = false
                        listener.onScanChanged(false)
                        stopInternal()
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "stop discovery failed: $errorCode")
                    mainHandler.post {
                        if (scanGeneration != generation) return@post
                        discoveryRunning = false
                        listener.onScanChanged(false)
                    }
                }
            }

            discoveryListener = discovery
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                discoveryRunning = true
                mainHandler.removeCallbacks(stopRunnable)
                mainHandler.postDelayed(stopRunnable, timeoutMs)
            } catch (t: Throwable) {
                Log.w(TAG, "discoverServices failed", t)
                discoveryListener = null
                discoveryRunning = false
                listener.onScanChanged(false)
                releaseMulticastLock()
            }
        }
    }

    fun stop() {
        mainHandler.post { stopInternal() }
    }

    private fun stopInternal() {
        mainHandler.removeCallbacks(stopRunnable)
        generation++
        val discovery = discoveryListener
        discoveryListener = null
        resolveQueue.clear()
        resolving = false
        if (discovery != null && discoveryRunning) {
            try {
                nsdManager?.stopServiceDiscovery(discovery)
            } catch (t: Throwable) {
                Log.w(TAG, "stopServiceDiscovery failed", t)
            }
        }
        discoveryRunning = false
        releaseMulticastLock()
        listener.onScanChanged(false)
    }

    private fun resolveNext(scanGeneration: Int) {
        if (resolving || scanGeneration != generation) return
        val serviceInfo = resolveQueue.pollFirst() ?: return
        val nsd = nsdManager ?: return
        resolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed ${serviceInfo.serviceName}: $errorCode")
                mainHandler.post {
                    if (scanGeneration != generation) return@post
                    resolving = false
                    resolveNext(scanGeneration)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                val name = serviceInfo.serviceName.orEmpty()
                mainHandler.post {
                    if (scanGeneration != generation) return@post
                    resolving = false
                    if (!host.isNullOrBlank() && port in 1..65535) {
                        listener.onFound(
                            DiscoveredAgent(
                                id = name,
                                name = name,
                                host = host,
                                port = port
                            )
                        )
                    }
                    resolveNext(scanGeneration)
                }
            }
        }

        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(serviceInfo, resolveListener)
        } catch (t: Throwable) {
            Log.w(TAG, "resolveService failed", t)
            resolving = false
            resolveNext(scanGeneration)
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("phonecontrol-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "multicast lock failed", t)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "multicast unlock failed", t)
        }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_phonecontrol._tcp."
        private const val TAG = "NsdDiscovery"
    }
}
