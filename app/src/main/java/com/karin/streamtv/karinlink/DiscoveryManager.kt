package com.karin.streamtv.karinlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.karin.streamtv.BuildConfig
import com.karin.streamtv.karinlink.protocol.Capability
import com.karin.streamtv.karinlink.protocol.DiscoveredPeer
import com.karin.streamtv.karinlink.protocol.LinkProtocol
import com.karin.streamtv.karinlink.protocol.PeerInfo
import com.karin.streamtv.karinlink.protocol.ServiceRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Advertises this device on the LAN and lists the peers it can see.
 *
 * Everything on the wire is derived from [LinkProtocol] and [ServiceRecord] so
 * registration and discovery cannot drift apart. The previous version kept its
 * own copies of both the service type (`_karinflinx._tcp.`) and the TXT keys,
 * while the Python backend browsed for `_karinflix._tcp.`; devices registered
 * one type and searched for another, so pairing never discovered anything.
 *
 * @param selfDeviceId this device's id, used to ignore our own announcements.
 */
class DiscoveryManager(
    context: Context,
    private val selfDeviceId: String = ""
) {

    private companion object {
        const val TAG = "DiscoveryManager"
        const val MAX_TRACKED_SERVICES = 64
    }

    private val _devices = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val devices: StateFlow<List<DiscoveredPeer>> = _devices

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null
    private var advertisedPort = 0

    /**
     * Android permits exactly one in-flight [NsdManager.resolveService]; a
     * second concurrent call fails with `FAILURE_ALREADY_ACTIVE`. Discovery
     * finds services far faster than they resolve, so requests are queued
     * instead of racing.
     */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Service instances already queued or resolved, to skip repeat announcements. */
    private val tracked = LinkedHashSet<String>()

    /** Maps mDNS instance name to device id, so `onServiceLost` can evict the right peer. */
    private val deviceIdByService = HashMap<String, String>()

    // ── Advertising ───────────────────────────────────────────────

    /**
     * Publishes this device on [port] so peers can find it.
     *
     * Re-registering the same port is a no-op: mDNS registration is rate
     * limited and a redundant call can evict the existing record.
     */
    @Synchronized
    fun registerService(port: Int, peer: PeerInfo) {
        if (port <= 0) {
            Log.w(TAG, "Not advertising: invalid port $port")
            return
        }
        if (registrationListener != null && advertisedPort == port) return

        unregisterService()

        val manager = nsdManager ?: run {
            Log.w(TAG, "NSD unavailable on this device; peers will not see us")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = ServiceRecord.instanceName(peer.deviceId)
            // Must match exactly what peers browse for. Android appends the
            // domain itself, so the constant intentionally has no `.local.`.
            serviceType = LinkProtocol.SERVICE_TYPE
            setPort(port)
            ServiceRecord.attributes(peer, port).forEach { (k, v) -> setAttribute(k, v) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android may rename on collision; the registered name is the
                // only one peers will see, so record the truth.
                Log.i(TAG, "Advertising ${info.serviceName} on port $port")
                registeredServiceName = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: ${describe(errorCode)}")
                registrationListener = null
                registeredServiceName = null
                advertisedPort = 0
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Stopped advertising")
                registeredServiceName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: ${describe(errorCode)}")
            }
        }

        registrationListener = listener
        advertisedPort = port
        runCatching { manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.e(TAG, "registerService threw: ${it.message}")
                registrationListener = null
                advertisedPort = 0
            }
    }

    @Synchronized
    fun unregisterService() {
        val listener = registrationListener ?: return
        registrationListener = null
        advertisedPort = 0
        registeredServiceName = null
        runCatching { nsdManager?.unregisterService(listener) }
    }

    // ── Browsing ──────────────────────────────────────────────────

    /**
     * Starts browsing. Safe to call repeatedly; an already running discovery
     * is left alone.
     */
    @Synchronized
    fun startDiscovery() {
        if (discoveryListener != null) return
        val manager = nsdManager ?: run {
            Log.w(TAG, "NSD unavailable; cannot browse for peers")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Browsing for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!matchesOurs(serviceInfo)) return
                if (serviceInfo.serviceName == registeredServiceName) return
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                tracked.remove(serviceInfo.serviceName)
                val gone = deviceIdByService.remove(serviceInfo.serviceName) ?: return
                val remaining = _devices.value.filterNot { it.deviceId == gone }
                if (remaining.size != _devices.value.size) {
                    _devices.value = remaining
                    Log.d(TAG, "Lost $gone")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // Clear the handle so startDiscovery() is not a no-op: the
                // platform stops discovery on network changes and expects the
                // app to browse again.
                Log.i(TAG, "Discovery stopped, will re-browse on demand")
                discoveryListener = null
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: ${describe(errorCode)}")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: ${describe(errorCode)}")
            }
        }

        discoveryListener = listener
        runCatching { manager.discoverServices(LinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.e(TAG, "discoverServices threw: ${it.message}")
                discoveryListener = null
            }
    }

    @Synchronized
    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
        resolveQueue.clear()
        resolving = false
    }

    /**
     * True when [info] is a KARIN Link service.
     *
     * Normalised before comparing: Android reports the type as registered on
     * some builds and with `.local.` appended on others, and the old exact
     * match filtered out every peer on the second kind.
     */
    internal fun matchesOurs(info: NsdServiceInfo): Boolean = ServiceRecord.isOurServiceType(info.serviceType)

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        if (!tracked.add(info.serviceName)) return
        if (tracked.size > MAX_TRACKED_SERVICES) {
            val oldest = tracked.first()
            tracked.remove(oldest)
            deviceIdByService.remove(oldest)
        }
        resolveQueue.addLast(info)
        drainResolves()
    }

    private fun drainResolves() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        val manager = nsdManager ?: return
        resolving = true

        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${info.serviceName}: ${describe(errorCode)}")
                    tracked.remove(info.serviceName)
                    finishResolve()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    try {
                        onResolved(info)
                    } finally {
                        finishResolve()
                    }
                }
            })
        }.onFailure {
            Log.w(TAG, "resolveService threw for ${next.serviceName}: ${it.message}")
            tracked.remove(next.serviceName)
            finishResolve()
        }
    }

    private fun finishResolve() {
        resolving = false
        drainResolves()
    }

    private fun onResolved(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: return
        val peer = ServiceRecord.parse(
            attrs = readAttributes(info),
            serviceName = info.serviceName,
            host = host,
            port = info.port
        )
        if (peer == null) {
            Log.d(TAG, "Ignoring ${info.serviceName}: unusable record")
            return
        }
        if (selfDeviceId.isNotBlank() && peer.deviceId == selfDeviceId) {
            Log.d(TAG, "Ignoring our own announcement ${info.serviceName}")
            return
        }

        deviceIdByService[info.serviceName] = peer.deviceId
        val current = _devices.value.toMutableList()
        val at = current.indexOfFirst { it.deviceId == peer.deviceId }
        if (at >= 0) current[at] = peer else current.add(peer)
        _devices.value = current
        Log.d(TAG, "Found ${peer.displayName} at $host:${peer.port} caps=${Capability.encode(peer.capabilities)}")
    }

    /**
     * Reads the TXT record.
     *
     * `NsdServiceInfo.getAttributes()` is deprecated from API 33 and the
     * replacement accessor is not present across the SDK stubs this project
     * builds against, so the map stays. It is also the only route that works on
     * API 23, which is this app's minimum.
     */
    @Suppress("DEPRECATION")
    private fun readAttributes(info: NsdServiceInfo): Map<String, String> {
        val attrs = runCatching { info.attributes }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>(attrs.size)
        for ((key, value) in attrs) {
            // Values are opaque bytes; UTF-8 is what setAttribute wrote.
            out[key] = runCatching { String(value, Charsets.UTF_8) }.getOrNull() ?: continue
        }
        return out
    }

    @Synchronized
    fun destroy() {
        stopDiscovery()
        unregisterService()
        tracked.clear()
        deviceIdByService.clear()
        resolveQueue.clear()
        _devices.value = emptyList()
    }

    // ── Local identity ────────────────────────────────────────────

    /**
     * What this build can actually do, advertised in the TXT record so a peer
     * can pick a transport before connecting instead of probing capabilities
     * after.
     */
    fun localCapabilities(): Set<Capability> = setOf(
        Capability.PLAY,
        Capability.REMOTE,
        Capability.PROXY,
        Capability.REMUX
    )

    fun localPeerInfo(deviceId: String, deviceName: String, deviceModel: String = ""): PeerInfo = PeerInfo(
        deviceId = deviceId,
        deviceName = deviceName,
        appVersion = BuildConfig.VERSION_NAME,
        deviceModel = deviceModel,
        capabilities = localCapabilities()
    )

    /** NsdManager only defines these two codes; anything else is opaque. */
    private fun describe(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "already active"
        NsdManager.FAILURE_INTERNAL_ERROR -> "internal error"
        else -> "code $errorCode"
    }
}
