package dev.brahmkshatriya.echo.remote

import android.util.Log
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class EchoWebSocketClient(
    serverUri: URI,
    private val onMessage: suspend (RemoteMessage) -> Unit,
    private val onConnectionStateChanged: suspend (ConnectionState) -> Unit = {}
) : WebSocketClient(serverUri) {

    private val scope = CoroutineScope(Dispatchers.IO) + CoroutineName("EchoWebSocketClient")

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 3000L

    companion object {
        private const val TAG = "EchoWebSocketClient"
        private const val PING_INTERVAL_MS = 15000L
    }

    init {
        // Set connection timeout
        connectionLostTimeout = 30

        // Start ping task to keep connection alive
        scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (isOpen) {
                    try {
                        sendMessage(RemoteMessage.Ping())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send ping", e)
                    }
                }
            }
        }
    }

    override fun onOpen(handshakedata: ServerHandshake) {
        Log.i(TAG, "Connected to server")
        reconnectAttempts = 0
        updateConnectionState(ConnectionState.CONNECTED)
    }

    override fun onMessage(message: String) {
        scope.launch {
            try {
                val remoteMessage = message.toData<RemoteMessage>().getOrThrow()
                Log.d(TAG, "Received message: ${remoteMessage::class.simpleName}")

                // Handle ping/pong automatically
                when (remoteMessage) {
                    is RemoteMessage.Ping -> sendMessage(RemoteMessage.Pong(remoteMessage.timestamp))
                    is RemoteMessage.Pong -> {} // Connection is alive
                    else -> onMessage(remoteMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: $message", e)
            }
        }
    }

    override fun onMessage(bytes: ByteBuffer?) {
        // Binary messages not used in this protocol
        Log.w(TAG, "Received unexpected binary message")
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Connection closed: $reason (code: $code, remote: $remote)")
        updateConnectionState(ConnectionState.DISCONNECTED)

        // Auto-reconnect if connection was lost unexpectedly
        if (remote && reconnectAttempts < maxReconnectAttempts) {
            scope.launch {
                reconnectAttempts++
                Log.i(TAG, "Attempting to reconnect ($reconnectAttempts/$maxReconnectAttempts)...")
                delay(reconnectDelayMs)
                if (!isOpen && !isClosing) {
                    try {
                        reconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Reconnection failed", e)
                        updateConnectionState(ConnectionState.ERROR)
                    }
                }
            }
        }
    }

    override fun onError(ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
        updateConnectionState(ConnectionState.ERROR)
    }

    /**
     * Connect to the server
     */
    fun connectAsync() {
        if (!isOpen && !isClosing) {
            updateConnectionState(ConnectionState.CONNECTING)
            scope.launch {
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed", e)
                    updateConnectionState(ConnectionState.ERROR)
                }
            }
        }
    }

    /**
     * Send a message to the server
     */
    fun sendMessage(message: RemoteMessage) {
        try {
            if (isOpen) {
                val json = message.toJson()
                send(json)
                Log.d(TAG, "Sent message: ${message::class.simpleName}")
            } else {
                Log.w(TAG, "Cannot send message, not connected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }

    /**
     * Disconnect from server gracefully
     */
    fun disconnectGracefully(reason: String = "User disconnected") {
        try {
            reconnectAttempts = maxReconnectAttempts // Prevent auto-reconnect
            sendMessage(RemoteMessage.Disconnect(reason))
            close(1000, reason)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
            closeBlocking()
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
        scope.launch {
            try {
                onConnectionStateChanged(state)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onConnectionStateChanged", e)
            }
        }
    }
}

