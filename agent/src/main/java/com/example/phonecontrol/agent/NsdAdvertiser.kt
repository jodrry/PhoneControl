package com.example.phonecontrol.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Publishes _phonecontrol._tcp on the LAN using Android NSD/mDNS so the
 * controller can find this device without typing an IP address.
 */
class NsdAdvertiser(
    context: Context,
    private val onStateChanged: (MdnsState) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var stopped = false

    fun start(port: Int) {
        stopped = false
        if (registrationListener != null) return
        val nsd = nsdManager
        if (nsd == null) {
            onStateChanged(MdnsState.FAILED)
            return
        }
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = buildServiceName()
            serviceType = SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (!stopped) {
                    Log.i(TAG, "mDNS registered as ${info.serviceName}")
                    onStateChanged(MdnsState.ADVERTISING)
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                if (!stopped) onStateChanged(MdnsState.FAILED)
                releaseMulticastLock()
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                if (!stopped) onStateChanged(MdnsState.IDLE)
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService failed", e)
            registrationListener = null
            onStateChanged(MdnsState.FAILED)
            releaseMulticastLock()
        }
    }

    fun stop() {
        stopped = true
        val listener = registrationListener ?: run {
            releaseMulticastLock()
            return
        }
        registrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterService failed", e)
        }
        releaseMulticastLock()
        onStateChanged(MdnsState.IDLE)
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("phonecontrol-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "multicast unlock failed", e)
        }
        multicastLock = null
    }

    private fun buildServiceName(): String {
        val model = Build.MODEL.ifBlank { "Android" }
            .replace(' ', '-')
            .take(32)
        return "PhoneControl-$model"
    }

    companion object {
        const val SERVICE_TYPE = "_phonecontrol._tcp."
        private const val TAG = "NsdAdvertiser"
    }
}
