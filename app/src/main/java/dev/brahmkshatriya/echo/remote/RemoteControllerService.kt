package dev.brahmkshatriya.echo.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.remote.connection.ConnectionManager
import dev.brahmkshatriya.echo.remote.discovery.DeviceDiscoveryManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.plus
import org.koin.android.ext.android.inject

/**
 * Background service for Controller mode.
 * Manages connection to remote player and sends control commands.
 */
class RemoteControllerService : Service() {
    
    private val extensionLoader by inject<ExtensionLoader>()
    
    private lateinit var discoveryManager: DeviceDiscoveryManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var extensionValidator: ExtensionValidator
    
    private val scope = CoroutineScope(Dispatchers.Main) + CoroutineName("RemoteControllerService")
    
    // Remote player state received from the connected player
    private val _remotePlayerState = MutableStateFlow<RemoteMessage.PlayerState?>(null)
    val remotePlayerState: StateFlow<RemoteMessage.PlayerState?> = _remotePlayerState.asStateFlow()
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): RemoteControllerService = this@RemoteControllerService
    }
    
    companion object {
        private const val TAG = "RemoteControllerService"
        private const val ACTION_START = "dev.brahmkshatriya.echo.remote.START_CONTROLLER"
        private const val ACTION_STOP = "dev.brahmkshatriya.echo.remote.STOP_CONTROLLER"
        
        fun startService(context: Context) {
            val intent = Intent(context, RemoteControllerService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, RemoteControllerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RemoteControllerService created")
        
        // Initialize components
        discoveryManager = DeviceDiscoveryManager(this)
        connectionManager = ConnectionManager(this)
        extensionValidator = ExtensionValidator(extensionLoader)
        
        // Setup callback to receive messages from player
        connectionManager.onMessageReceived = { message ->
            handlePlayerMessage(message)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startControllerMode()
            ACTION_STOP -> stopControllerMode()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    private fun startControllerMode() {
        Log.i(TAG, "Starting controller mode")
        
        // Start discovery to find available players
        discoveryManager.startDiscovery()
    }
    
    private fun stopControllerMode() {
        Log.i(TAG, "Stopping controller mode")
        
        // Cleanup
        discoveryManager.stopDiscovery()
        connectionManager.disconnect()
        _remotePlayerState.value = null
        
        stopSelf()
    }
    
    /**
     * Connect to a remote player device
     */
    fun connectToDevice(device: RemoteDevice) {
        val deviceName = android.os.Build.MODEL
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        val installedExtensions = extensionValidator.getInstalledExtensionIds()
        
        connectionManager.connectToDevice(
            device = device,
            deviceName = deviceName,
            deviceId = deviceId,
            installedExtensions = installedExtensions
        )
    }
    
    /**
     * Send a control command to the connected player
     */
    fun sendCommand(message: RemoteMessage) {
        connectionManager.sendMessage(message)
    }
    
    /**
     * Handle messages received from the player
     */
    private suspend fun handlePlayerMessage(message: RemoteMessage) {
        when (message) {
            is RemoteMessage.PlayerState -> {
                _remotePlayerState.value = message
                Log.d(TAG, "Received player state update")
            }
            
            is RemoteMessage.QueueUpdate -> {
                Log.d(TAG, "Received queue update: ${message.queue.size} tracks")
            }
            
            is RemoteMessage.PositionUpdate -> {
                // Update position in local state
                _remotePlayerState.value = _remotePlayerState.value?.copy(
                    position = message.position,
                    duration = message.duration
                )
            }
            
            is RemoteMessage.Error -> {
                Log.e(TAG, "Error from player: ${message.code} - ${message.message}")
                // TODO: Show error to user
            }
            
            else -> {
                Log.d(TAG, "Received message: ${message::class.simpleName}")
            }
        }
    }
    
    fun getConnectionManager(): ConnectionManager = connectionManager
    
    fun getDiscoveryManager(): DeviceDiscoveryManager = discoveryManager
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "RemoteControllerService destroyed")
        
        discoveryManager.cleanup()
        connectionManager.cleanup()
    }
}

