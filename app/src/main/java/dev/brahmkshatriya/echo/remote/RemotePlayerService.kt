package dev.brahmkshatriya.echo.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.remote.connection.ConnectionManager
import dev.brahmkshatriya.echo.remote.connection.PairingDialog
import dev.brahmkshatriya.echo.remote.discovery.DeviceDiscoveryManager
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.java_websocket.WebSocket
import org.koin.android.ext.android.inject

/**
 * Background service that runs when device is in Player mode.
 * Handles incoming connections from controllers and executes playback commands.
 */
class RemotePlayerService : Service() {

    private val app by inject<App>()
    private val playerState by inject<PlayerState>()
    private val extensionLoader by inject<ExtensionLoader>()

    private lateinit var discoveryManager: DeviceDiscoveryManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var extensionValidator: ExtensionValidator
    private lateinit var stateSynchronizer: PlayerStateSynchronizer

    // PlayerViewModel will be injected when needed for executing commands
    private var playerViewModel: PlayerViewModel? = null

    private var webSocketServer: EchoWebSocketServer? = null
    private val scope = CoroutineScope(Dispatchers.Main) + CoroutineName("RemotePlayerService")

    // Map to track which socket belongs to which controller
    private val controllerSockets = mutableMapOf<WebSocket, String>()

    // Pending connections awaiting user approval
    private val _pendingConnectionRequests = MutableStateFlow<List<ConnectionManager.PendingConnection>>(emptyList())

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): RemotePlayerService = this@RemotePlayerService
    }

    companion object {
        private const val TAG = "RemotePlayerService"
        private const val ACTION_START = "dev.brahmkshatriya.echo.remote.START_PLAYER"
        private const val ACTION_STOP = "dev.brahmkshatriya.echo.remote.STOP_PLAYER"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "echo_remote_player"

        fun startService(context: Context) {
            val intent = Intent(context, RemotePlayerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RemotePlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RemotePlayerService created")

        // Initialize components
        discoveryManager = DeviceDiscoveryManager(this)
        connectionManager = ConnectionManager(this)
        extensionValidator = ExtensionValidator(extensionLoader)
        stateSynchronizer = PlayerStateSynchronizer(playerState)

        // Setup state synchronizer callback to broadcast to all controllers
        stateSynchronizer.onStateChanged = { state ->
            webSocketServer?.broadcast(state)
        }

        stateSynchronizer.onQueueChanged = { queueUpdate ->
            webSocketServer?.broadcast(queueUpdate)
        }

        stateSynchronizer.onPositionChanged = { positionUpdate ->
            webSocketServer?.broadcast(positionUpdate)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification())
                startPlayerMode()
            }
            ACTION_STOP -> stopPlayerMode()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Player Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Echo is accepting remote connections"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, dev.brahmkshatriya.echo.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Player Mode Active")
            .setContentText("Echo is accepting remote connections")
            .setSmallIcon(dev.brahmkshatriya.echo.R.drawable.ic_sensors)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startPlayerMode() {
        if (webSocketServer != null) {
            Log.w(TAG, "Player mode already started")
            return
        }

        Log.i(TAG, "Starting player mode")

        try {
            // Start WebSocket server
            Log.i(TAG, "Creating WebSocket server on port ${EchoWebSocketServer.DEFAULT_PORT}")
            webSocketServer = EchoWebSocketServer(
                port = EchoWebSocketServer.DEFAULT_PORT,
                onMessage = { socket, message -> handleControllerMessage(socket, message) },
                onConnect = { socket -> handleControllerConnect(socket) },
                onDisconnect = { socket, reason -> handleControllerDisconnect(socket, reason) }
            )

            Log.i(TAG, "Starting WebSocket server...")
            webSocketServer?.start()

            // Wait a bit for server to start
            Thread.sleep(100)

            val serverAddress = webSocketServer?.address
            Log.i(TAG, "WebSocket server started at $serverAddress")

            // Register NSD service
            Log.i(TAG, "Registering NSD service...")
            discoveryManager.registerService(
                deviceName = "${DeviceDiscoveryManager.DEFAULT_SERVICE_NAME_PREFIX}_${android.os.Build.MODEL}",
                port = EchoWebSocketServer.DEFAULT_PORT
            )

            Log.i(TAG, "Player mode started successfully - Server running on port ${EchoWebSocketServer.DEFAULT_PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting player mode", e)
            e.printStackTrace()

            // Cleanup on error
            webSocketServer?.stop()
            webSocketServer = null
        }
    }

    private fun stopPlayerMode() {
        Log.i(TAG, "Stopping player mode")

        // Cleanup
        webSocketServer?.shutdown()
        webSocketServer = null
        discoveryManager.unregisterService()
        stateSynchronizer.stopSync()
        controllerSockets.clear()

        stopSelf()
    }

    private suspend fun handleControllerConnect(socket: WebSocket) {
        Log.i(TAG, "Controller connected: ${socket.remoteSocketAddress}")
    }

    private suspend fun handleControllerDisconnect(socket: WebSocket, reason: String) {
        val controllerId = controllerSockets.remove(socket)
        Log.i(TAG, "Controller disconnected: $controllerId, reason: $reason")
    }

    private suspend fun handleControllerMessage(socket: WebSocket, message: RemoteMessage) {
        when (message) {
            is RemoteMessage.ConnectionRequest -> {
                handleConnectionRequest(socket, message)
            }

            is RemoteMessage.PlayPause -> {
                Log.d(TAG, "PlayPause command: ${message.isPlaying}")
                playerViewModel?.setPlaying(message.isPlaying)
            }

            is RemoteMessage.Seek -> {
                Log.d(TAG, "Seek command: ${message.position}")
                playerViewModel?.seekTo(message.position)
            }

            is RemoteMessage.Next -> {
                Log.d(TAG, "Next command")
                playerViewModel?.next()
            }

            is RemoteMessage.Previous -> {
                Log.d(TAG, "Previous command")
                playerViewModel?.previous()
            }

            is RemoteMessage.SetShuffleMode -> {
                Log.d(TAG, "SetShuffleMode: ${message.enabled}")
                playerViewModel?.setShuffle(message.enabled)
            }

            is RemoteMessage.SetRepeatMode -> {
                Log.d(TAG, "SetRepeatMode: ${message.mode}")
                playerViewModel?.setRepeat(message.mode)
            }

            is RemoteMessage.PlayItem -> {
                handlePlayItem(socket, message)
            }

            is RemoteMessage.AddToQueue -> {
                handleAddToQueue(socket, message)
            }

            is RemoteMessage.AddToNext -> {
                handleAddToNext(socket, message)
            }

            is RemoteMessage.RemoveQueueItem -> {
                Log.d(TAG, "RemoveQueueItem: ${message.position}")
                playerViewModel?.removeQueueItem(message.position)
            }

            is RemoteMessage.MoveQueueItem -> {
                Log.d(TAG, "MoveQueueItem: ${message.fromPosition} -> ${message.toPosition}")
                playerViewModel?.moveQueueItems(message.fromPosition, message.toPosition)
            }

            is RemoteMessage.ClearQueue -> {
                Log.d(TAG, "ClearQueue")
                playerViewModel?.clearQueue()
            }

            is RemoteMessage.PlayQueueItem -> {
                Log.d(TAG, "PlayQueueItem: ${message.position}")
                playerViewModel?.play(message.position)
            }

            is RemoteMessage.LikeTrack -> {
                Log.d(TAG, "LikeTrack: ${message.isLiked}")
                playerViewModel?.likeCurrent(message.isLiked)
            }

            is RemoteMessage.Ping -> {
                webSocketServer?.sendMessage(socket, RemoteMessage.Pong(message.timestamp))
            }

            is RemoteMessage.Disconnect -> {
                Log.i(TAG, "Controller requested disconnect: ${message.reason}")
                socket.close(1000, message.reason)
            }

            else -> {
                Log.w(TAG, "Unhandled message type: ${message::class.simpleName}")
            }
        }
    }

    private fun handleConnectionRequest(socket: WebSocket, request: RemoteMessage.ConnectionRequest) {
        Log.i(TAG, "Connection request from ${request.deviceName}")
        controllerSockets[socket] = request.deviceId

        // Let ConnectionManager handle it (will show pairing dialog via pending connections)
        connectionManager.handleConnectionRequest(socket, request)
    }

    private fun handlePlayItem(socket: WebSocket, message: RemoteMessage.PlayItem) {
        // Validate extension
        val validation = extensionValidator.validateExtension(message.extensionId)
        if (validation is ExtensionValidator.ValidationResult.Invalid) {
            webSocketServer?.sendError(
                socket,
                validation.code,
                validation.message,
                "Missing extensions: ${validation.missingExtensions.joinToString()}"
            )
            return
        }

        Log.d(TAG, "PlayItem: ${message.item.title} from ${message.extensionId}")
        if (message.shuffle) {
            playerViewModel?.shuffle(message.extensionId, message.item, message.loaded)
        } else {
            playerViewModel?.play(message.extensionId, message.item, message.loaded)
        }
    }

    private fun handleAddToQueue(socket: WebSocket, message: RemoteMessage.AddToQueue) {
        val validation = extensionValidator.validateExtension(message.extensionId)
        if (validation is ExtensionValidator.ValidationResult.Invalid) {
            webSocketServer?.sendError(socket, validation.code, validation.message)
            return
        }

        Log.d(TAG, "AddToQueue: ${message.item.title}")
        playerViewModel?.addToQueue(message.extensionId, message.item, message.loaded)
    }

    private fun handleAddToNext(socket: WebSocket, message: RemoteMessage.AddToNext) {
        val validation = extensionValidator.validateExtension(message.extensionId)
        if (validation is ExtensionValidator.ValidationResult.Invalid) {
            webSocketServer?.sendError(socket, validation.code, validation.message)
            return
        }

        Log.d(TAG, "AddToNext: ${message.item.title}")
        playerViewModel?.addToNext(message.extensionId, message.item, message.loaded)
    }

    fun getConnectionManager(): ConnectionManager = connectionManager

    fun getDiscoveryManager(): DeviceDiscoveryManager = discoveryManager

    fun getStateSynchronizer(): PlayerStateSynchronizer = stateSynchronizer

    /**
     * Set the PlayerViewModel for executing playback commands
     */
    fun setPlayerViewModel(viewModel: PlayerViewModel) {
        this.playerViewModel = viewModel
        Log.i(TAG, "PlayerViewModel set for remote control")
    }

    /**
     * Check if there are any connected controllers
     */
    fun hasConnectedControllers(): Boolean {
        return controllerSockets.isNotEmpty()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "RemotePlayerService destroyed")

        webSocketServer?.shutdown()
        discoveryManager.cleanup()
        connectionManager.cleanup()
        stateSynchronizer.stopSync()
    }
}

