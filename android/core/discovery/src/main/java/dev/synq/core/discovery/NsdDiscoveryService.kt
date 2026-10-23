package dev.synq.core.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NsdDiscoveryService(
    context: Context,
    private var identity: LocalDiscoveryIdentity,
    private val servicePort: Int = DEFAULT_PORT,
) : DiscoveryService {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var restartRunnable: Runnable? = null
    private var networkCallbackRegistered = false
    private val found = ConcurrentHashMap<String, DiscoveredPeer>()
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = mutablePeers.asStateFlow()
    private var running = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleRefresh()
        }
        override fun onLost(network: Network) {
            scheduleRefresh()
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "DISCOVERY service published name=${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "DISCOVERY publish failed code=$errorCode")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "DISCOVERY service unpublished")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "DISCOVERY unpublish failed code=$errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            Log.i(TAG, "DISCOVERY browsing started")
        }
        override fun onDiscoveryStopped(type: String) {
            Log.i(TAG, "DISCOVERY browsing stopped")
        }
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(TAG, "DISCOVERY browse start failed code=$errorCode")
            running = false
        }
        override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(TAG, "DISCOVERY browse stop failed code=$errorCode")
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName == registeredServiceName) return
            resolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            found.remove(info.serviceName)
            emitPeers()
            Log.i(TAG, "DISCOVERY peer lost service=${info.serviceName}")
        }
    }

    private val registeredServiceName = "SYNQ-${identity.deviceId.take(8)}"

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("SYNQ:Discovery")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.let { if (!it.isHeld) it.acquire() }
        }
        if (!networkCallbackRegistered) {
            runCatching {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            }
        }
        val info = NsdServiceInfo().apply {
            serviceName = registeredServiceName
            serviceType = SERVICE_TYPE
            port = servicePort
            setAttribute("id", identity.deviceId)
            setAttribute("name", identity.deviceName.take(64))
            setAttribute("version", PROTOCOL_VERSION.toString())
            setAttribute("platform", "android")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    @Synchronized
    override fun stop() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
        if (!running) return
        running = false
        if (networkCallbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        runCatching { nsd.unregisterService(registrationListener) }
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
        found.clear()
        emitPeers()
    }

    @Synchronized
    fun scheduleRefresh(delayMillis: Long = 800L) {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            synchronized(this@NsdDiscoveryService) {
                if (running) {
                    stop()
                    val restart = Runnable {
                        synchronized(this@NsdDiscoveryService) {
                            restartRunnable = null
                            start()
                        }
                    }
                    restartRunnable = restart
                    mainHandler.postDelayed(restart, 250L)
                }
            }
        }
        refreshRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    @Synchronized
    fun updateDeviceName(value: String) {
        val name = value.trim().take(64).ifBlank { "Android device" }
        if (name == identity.deviceName) return
        val restart = running
        if (restart) stop()
        identity = identity.copy(deviceName = name)
        if (restart) {
            val restartAction = Runnable {
                synchronized(this@NsdDiscoveryService) {
                    restartRunnable = null
                    start()
                }
            }
            restartRunnable = restartAction
            mainHandler.postDelayed(restartAction, 300L)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "DISCOVERY resolve failed code=$errorCode")
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = accept(serviceInfo)
        }
        // The legacy resolver is retained as the minSdk-compatible path. Calls are
        // serialized by NsdManager on supported releases and discovery hints remain untrusted.
        nsd.resolveService(info, listener)
    }

    private fun accept(info: NsdServiceInfo) {
        val attributes = info.attributes.mapValues { it.value ?: byteArrayOf() }
        val parsed = DiscoveryTxtRecord.parse(info.serviceName, attributes)
        val host = resolvedHost(info)
        found[parsed.key] = parsed.copy(host = host?.hostAddress, port = info.port.takeIf { it in 1..65535 })
        emitPeers()
        Log.i(TAG, "DISCOVERY peer discovered service=${info.serviceName}")
    }

    @Suppress("DEPRECATION")
    private fun resolvedHost(info: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host

    private fun emitPeers() {
        mutablePeers.value = found.values.sortedBy { it.deviceNameHint.lowercase() }
    }

    companion object {
        const val SERVICE_TYPE = "_synq._tcp."
        const val DEFAULT_PORT = 42_458
        const val PROTOCOL_VERSION = 1
        private const val TAG = "SYNQ"
    }
}

data class LocalDiscoveryIdentity(val deviceId: String, val deviceName: String) {
    init { require(runCatching { UUID.fromString(deviceId) }.isSuccess) }
}
