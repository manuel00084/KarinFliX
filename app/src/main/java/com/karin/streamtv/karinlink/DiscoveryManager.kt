package com.karin.streamtv.karinlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DiscoveryManager(private val context: Context) {

    private companion object {
        const val TAG = "DiscoveryManager"
        const val SERVICE_TYPE = "_karinflinx._tcp."
        const val SERVICE_NAME = "KarinFLiX"
    }

    data class DiscoveredDevice(
        val name: String,
        val host: String,
        val port: Int,
        val deviceId: String,
        val deviceModel: String = "",
        val appVersion: String = "1.0"
    ) {
        val displayName: String get() = deviceModel.ifBlank { name }
    }

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredService: NsdServiceInfo? = null

    fun registerService(port: Int, deviceId: String, deviceModel: String) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$SERVICE_NAME-$deviceId"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("deviceId", deviceId)
                setAttribute("deviceModel", deviceModel)
                setAttribute("appVersion", "1.0")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    registeredService = info
                    Log.i(TAG, "Service registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "Service unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service: ${e.message}")
        }
    }

    fun startDiscovery() {
        try {
            if (nsdManager == null) {
                nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "Discovery started for $serviceType")
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType == SERVICE_TYPE && serviceInfo.serviceName.startsWith(SERVICE_NAME)) {
                        resolveService(serviceInfo)
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val currentDevices = _devices.value.toMutableList()
                    currentDevices.removeAll { it.name == serviceInfo.serviceName }
                    _devices.value = currentDevices
                    Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                }
                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "Discovery stopped")
                }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: $errorCode")
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val device = DiscoveredDevice(
                    name = info.serviceName,
                    host = host,
                    port = info.port,
                    deviceId = info.attributes["deviceId"]?.let { String(it) } ?: info.serviceName,
                    deviceModel = info.attributes["deviceModel"]?.let { String(it) } ?: "",
                    appVersion = info.attributes["appVersion"]?.let { String(it) } ?: "1.0"
                )
                val currentDevices = _devices.value.toMutableList()
                val existing = currentDevices.indexOfFirst { it.deviceId == device.deviceId }
                if (existing >= 0) {
                    currentDevices[existing] = device
                } else {
                    currentDevices.add(device)
                }
                _devices.value = currentDevices
                Log.d(TAG, "Device found: ${device.displayName} @ $host:$info.port")
            }
        })
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
    }

    fun destroy() {
        stopDiscovery()
        unregisterService()
        nsdManager = null
    }
}
