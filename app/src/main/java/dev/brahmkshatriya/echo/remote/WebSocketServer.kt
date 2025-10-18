package dev.brahmkshatriya.echo.remote

import android.util.Log
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class EchoWebSocketServer(
    port: Int,
    private val onMessage: suspend (WebSocket, RemoteMessage) -> Unit,
    private val onConnect: suspend (WebSocket) -> Unit = {},
    private val onDisconnect: suspend (WebSocket, String) -> Unit = { _, _ -> }
) : WebSocketServer(InetSocketAddress(port)) {
    
    private val scope = CoroutineScope(Dispatchers.IO) + CoroutineName("EchoWebSocketServer")
    
    companion object {
        private const val TAG = "EchoWebSocketServer"
        const val DEFAULT_PORT = 8765
    }
    
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "New connection from ${conn.remoteSocketAddress}")
        scope.launch {
            try {
                onConnect(conn)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onConnect", e)
            }
        }
    }
    
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Connection closed: $reason (code: $code, remote: $remote)")
        scope.launch {
            try {
                onDisconnect(conn, reason)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDisconnect", e)
            }
        }
    }
    
    override fun onMessage(conn: WebSocket, message: String) {
        scope.launch {
            try {
                val remoteMessage = message.toData<RemoteMessage>().getOrThrow()
                Log.d(TAG, "Received message: ${remoteMessage::class.simpleName}")
                onMessage(conn, remoteMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: $message", e)
                sendError(conn, RemoteMessage.ErrorCode.UNKNOWN_ERROR, "Failed to parse message")
            }
        }
    }
    
    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // Binary messages not used in this protocol
        Log.w(TAG, "Received unexpected binary message")
    }
    
    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }
    
    override fun onStart() {
        Log.i(TAG, "WebSocket server started on port $port")
    }
    
    /**
     * Send a message to a specific client
     */
    fun sendMessage(conn: WebSocket, message: RemoteMessage) {
        try {
            if (conn.isOpen) {
                val json = message.toJson()
                conn.send(json)
                Log.d(TAG, "Sent message: ${message::class.simpleName}")
            } else {
                Log.w(TAG, "Cannot send message, connection is closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }
    
    /**
     * Broadcast a message to all connected clients
     */
    fun broadcast(message: RemoteMessage) {
        try {
            val json = message.toJson()
            broadcast(json)
            Log.d(TAG, "Broadcasted message: ${message::class.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting message", e)
        }
    }
    
    /**
     * Send an error message to a client
     */
    fun sendError(conn: WebSocket, code: RemoteMessage.ErrorCode, message: String, details: String? = null) {
        sendMessage(conn, RemoteMessage.Error(code, message, details))
    }
    
    /**
     * Get all currently connected clients
     */
    fun getConnectedClients(): Set<WebSocket> = connections.toSet()
    
    /**
     * Disconnect a specific client
     */
    fun disconnectClient(conn: WebSocket, reason: String = "Disconnected by server") {
        try {
            sendMessage(conn, RemoteMessage.Disconnect(reason))
            conn.close(1000, reason)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting client", e)
        }
    }
    
    /**
     * Gracefully shutdown the server
     */
    fun shutdown() {
        try {
            // Notify all clients before shutting down
            broadcast(RemoteMessage.Disconnect("Server shutting down"))
            stop(1000)
            Log.i(TAG, "Server shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
}

