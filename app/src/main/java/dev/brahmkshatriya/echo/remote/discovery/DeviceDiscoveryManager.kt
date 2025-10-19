package dev.brahmkshatriya.echo.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.brahmkshatriya.echo.remote.RemoteDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DeviceDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveredDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<RemoteDevice>> = _discoveredDevices.asStateFlow()

    private var isDiscovering = false
    private var isRegistered = false
    private var serviceName: String? = null

    companion object {
        private const val TAG = "DeviceDiscoveryManager"
        private const val SERVICE_TYPE = "_echo._tcp."
        const val DEFAULT_SERVICE_NAME_PREFIX = "Echo Player"
    }

    /**
     * Register this device as an Echo player on the network
     */
    fun registerService(
        deviceName: String = "${DEFAULT_SERVICE_NAME_PREFIX}_${android.os.Build.MODEL}",
        port: Int
    ) {
        if (isRegistered) {
            Log.w(TAG, "Service already registered")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                    this@DeviceDiscoveryManager.serviceName = nsdServiceInfo.serviceName
                    isRegistered = true
                    Log.i(TAG, "✅ NSD Service registered successfully: ${nsdServiceInfo.serviceName} on port $port")
                }
                
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "❌ NSD Service registration failed with error code: $errorCode")
                    Log.e(TAG, "Service name attempted: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}, port: ${serviceInfo.port}")
                    isRegistered = false
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service unregistered")
                    isRegistered = false
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Service unregistration failed: $errorCode")
                }
            }
        )
    }

    /**
     * Unregister this device from the network
     */
    fun unregisterService() {
        if (!isRegistered) return

        try {
            nsdManager.unregisterService(object : NsdManager.RegistrationListener {
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    isRegistered = false
                    serviceName = null
                    Log.i(TAG, "Service unregistered successfully")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Unregistration failed: $errorCode")
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
            isRegistered = false
        }
    }

    /**
     * Start discovering Echo players on the network
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.w(TAG, "Discovery already in progress")
            return
        }

        nsdManager.discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: $errorCode")
                    isDiscovering = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    isDiscovering = true
                    Log.i(TAG, "✅ NSD Discovery started for type: $serviceType")
                }
                
                override fun onDiscoveryStopped(serviceType: String) {
                    isDiscovering = false
                    Log.i(TAG, "Discovery stopped for type: $serviceType")
                }
                
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "📡 NSD Service found: ${serviceInfo.serviceName} (type: ${serviceInfo.serviceType})")

                    // Don't discover ourselves
                    if (serviceInfo.serviceName == serviceName) {
                        Log.d(TAG, "Ignoring own service")
                        return
                    }

                    // Resolve the service to get IP and port
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostAddress = serviceInfo.host?.hostAddress
                            Log.i(TAG, "✅ Service resolved: ${serviceInfo.serviceName}")
                            Log.i(TAG, "   Address: $hostAddress, Port: ${serviceInfo.port}")
                            
                            if (hostAddress == null) {
                                Log.e(TAG, "❌ Host address is null for ${serviceInfo.serviceName}")
                                return
                            }
                            
                            val device = RemoteDevice(
                                name = serviceInfo.serviceName,
                                address = hostAddress,
                                port = serviceInfo.port,
                                deviceId = UUID.randomUUID().toString() // Generate unique ID
                            )
                            
                            // Add to discovered devices
                            val currentDevices = _discoveredDevices.value.toMutableList()
                            // Remove old entry if exists (by name and address)
                            currentDevices.removeAll { 
                                it.name == device.name && it.address == device.address 
                            }
                            currentDevices.add(device)
                            _discoveredDevices.value = currentDevices
                            
                            Log.i(TAG, "✅ Added device to list: ${device.name} at ${device.address}:${device.port}")
                            Log.i(TAG, "   Total discovered devices: ${currentDevices.size}")
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")

                    // Remove from discovered devices
                    val currentDevices = _discoveredDevices.value.toMutableList()
                    currentDevices.removeAll { it.name == serviceInfo.serviceName }
                    _discoveredDevices.value = currentDevices
                }
            }
        )
    }

    /**
     * Stop discovering devices
     */
    fun stopDiscovery() {
        if (!isDiscovering) return

        try {
            nsdManager.stopServiceDiscovery(object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStopped(serviceType: String) {
                    isDiscovering = false
                    Log.i(TAG, "Discovery stopped")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: $errorCode")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
            isDiscovering = false
        }
    }

    /**
     * Clear all discovered devices
     */
    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopDiscovery()
        unregisterService()
        clearDiscoveredDevices()
    }
}

