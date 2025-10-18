package dev.brahmkshatriya.echo.remote.connection

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.brahmkshatriya.echo.remote.ConnectionState
import dev.brahmkshatriya.echo.remote.EchoWebSocketClient
import dev.brahmkshatriya.echo.remote.RemoteDevice
import dev.brahmkshatriya.echo.remote.RemoteMessage
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import java.net.URI

class ConnectionManager(context: Context) {

    private val preferences: SharedPreferences = context.getSettings()

    private val _currentConnection = MutableStateFlow<RemoteDevice?>(null)
    val currentConnection: StateFlow<RemoteDevice?> = _currentConnection.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pendingConnections = MutableStateFlow<List<PendingConnection>>(emptyList())
    val pendingConnections: StateFlow<List<PendingConnection>> = _pendingConnections.asStateFlow()

    private var webSocketClient: EchoWebSocketClient? = null

    // Callbacks for controller mode
    var onMessageReceived: (suspend (RemoteMessage) -> Unit)? = null
    var onConnectionStateChanged: (suspend (ConnectionState) -> Unit)? = null

    companion object {
        private const val TAG = "ConnectionManager"
        private const val PREF_TRUSTED_DEVICES = "remote_trusted_devices"
    }

    /**
     * Represents a pending connection request from a controller
     */
    data class PendingConnection(
        val socket: WebSocket,
        val deviceName: String,
        val deviceId: String,
        val installedExtensions: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Connect to a remote player device (Controller mode)
     */
    fun connectToDevice(
        device: RemoteDevice,
        deviceName: String,
        deviceId: String,
        installedExtensions: List<String>
    ) {
        if (webSocketClient != null && webSocketClient?.isOpen == true) {
            Log.w(TAG, "Already connected to a device")
            return
        }

        try {
            val uri = URI("ws://${device.address}:${device.port}")
            Log.i(TAG, "Connecting to $uri")

            webSocketClient = EchoWebSocketClient(
                serverUri = uri,
                onMessage = { message ->
                    handleControllerMessage(message)
                },
                onConnectionStateChanged = { state ->
                    _connectionState.value = state
                    onConnectionStateChanged?.invoke(state)

                    if (state == ConnectionState.CONNECTED) {
                        _currentConnection.value = device
                        // Send connection request with device info
                        webSocketClient?.sendMessage(
                            RemoteMessage.ConnectionRequest(
                                deviceName = deviceName,
                                deviceId = deviceId,
                                installedExtensions = installedExtensions
                            )
                        )
                    } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                        _currentConnection.value = null
                    }
                }
            )

            webSocketClient?.connectAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Handle incoming connection request (Player mode)
     */
    fun handleConnectionRequest(socket: WebSocket, request: RemoteMessage.ConnectionRequest) {
        val pending = PendingConnection(
            socket = socket,
            deviceName = request.deviceName,
            deviceId = request.deviceId,
            installedExtensions = request.installedExtensions
        )

        // Check if device is trusted
        if (isTrustedDevice(request.deviceId)) {
            Log.i(TAG, "Auto-accepting connection from trusted device: ${request.deviceName}")
            acceptConnection(pending)
        } else {
            // Add to pending list for user confirmation
            val currentPending = _pendingConnections.value.toMutableList()
            currentPending.add(pending)
            _pendingConnections.value = currentPending
            Log.i(TAG, "Connection request from ${request.deviceName} awaiting user approval")
        }
    }

    /**
     * Accept a pending connection request
     */
    fun acceptConnection(pending: PendingConnection, trustDevice: Boolean = false) {
        try {
            val response = RemoteMessage.ConnectionResponse(
                accepted = true,
                deviceName = android.os.Build.MODEL
            )

            pending.socket.send(Json.encodeToString(response))

            if (trustDevice) {
                addTrustedDevice(pending.deviceId, pending.deviceName)
            }

            // Remove from pending list
            val currentPending = _pendingConnections.value.toMutableList()
            currentPending.remove(pending)
            _pendingConnections.value = currentPending

            Log.i(TAG, "Accepted connection from ${pending.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting connection", e)
        }
    }

    /**
     * Reject a pending connection request
     */
    fun rejectConnection(pending: PendingConnection, reason: String = "Connection rejected by user") {
        try {
            val response = RemoteMessage.ConnectionResponse(
                accepted = false,
                deviceName = android.os.Build.MODEL,
                reason = reason
            )

            pending.socket.send(Json.encodeToString(response))
            pending.socket.close(1000, reason)

            // Remove from pending list
            val currentPending = _pendingConnections.value.toMutableList()
            currentPending.remove(pending)
            _pendingConnections.value = currentPending

            Log.i(TAG, "Rejected connection from ${pending.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting connection", e)
        }
    }

    /**
     * Disconnect from current remote device
     */
    fun disconnect(reason: String = "User disconnected") {
        webSocketClient?.disconnectGracefully(reason)
        webSocketClient = null
        _currentConnection.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected: $reason")
    }

    /**
     * Send a message to the connected device (Controller mode)
     */
    fun sendMessage(message: RemoteMessage) {
        webSocketClient?.sendMessage(message)
    }

    /**
     * Handle messages received from player (Controller mode)
     */
    private suspend fun handleControllerMessage(message: RemoteMessage) {
        when (message) {
            is RemoteMessage.ConnectionResponse -> {
                if (message.accepted) {
                    Log.i(TAG, "Connection accepted by ${message.deviceName}")
                    _connectionState.value = ConnectionState.CONNECTED
                } else {
                    Log.w(TAG, "Connection rejected: ${message.reason}")
                    disconnect(message.reason ?: "Connection rejected")
                }
            }
            else -> {
                // Forward to callback
                onMessageReceived?.invoke(message)
            }
        }
    }

    /**
     * Trusted devices management
     */
    private fun getTrustedDevices(): Set<String> {
        val json = preferences.getString(PREF_TRUSTED_DEVICES, null) ?: return emptySet()
        return try {
            Json.decodeFromString<Set<String>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing trusted devices", e)
            emptySet()
        }
    }

    private fun isTrustedDevice(deviceId: String): Boolean {
        return getTrustedDevices().contains(deviceId)
    }

    private fun addTrustedDevice(deviceId: String, deviceName: String) {
        val trusted = getTrustedDevices().toMutableSet()
        trusted.add(deviceId)
        preferences.edit()
            .putString(PREF_TRUSTED_DEVICES, Json.encodeToString(trusted))
            .apply()
        Log.i(TAG, "Added trusted device: $deviceName")
    }

    fun removeTrustedDevice(deviceId: String) {
        val trusted = getTrustedDevices().toMutableSet()
        trusted.remove(deviceId)
        preferences.edit()
            .putString(PREF_TRUSTED_DEVICES, Json.encodeToString(trusted))
            .apply()
        Log.i(TAG, "Removed trusted device")
    }

    fun clearTrustedDevices() {
        preferences.edit().remove(PREF_TRUSTED_DEVICES).apply()
        Log.i(TAG, "Cleared all trusted devices")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        disconnect()
        _pendingConnections.value = emptyList()
    }
}

