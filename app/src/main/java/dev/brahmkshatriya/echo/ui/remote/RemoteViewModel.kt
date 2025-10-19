package dev.brahmkshatriya.echo.ui.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.remote.ConnectionState
import dev.brahmkshatriya.echo.remote.RemoteControllerService
import dev.brahmkshatriya.echo.remote.RemoteDevice
import dev.brahmkshatriya.echo.remote.RemoteMessage
import dev.brahmkshatriya.echo.remote.RemotePlayerService
import dev.brahmkshatriya.echo.remote.connection.ConnectionManager
import dev.brahmkshatriya.echo.remote.connection.PairingDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemoteViewModel(
    context: Context,
    private val settings: SharedPreferences
) : ViewModel() {

    private val context = context.applicationContext

    private val _isPlayerModeEnabled = MutableStateFlow(false)
    val isPlayerModeEnabled: StateFlow<Boolean> = _isPlayerModeEnabled.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<RemoteDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<RemoteDevice?>(null)
    val connectedDevice: StateFlow<RemoteDevice?> = _connectedDevice.asStateFlow()

    private val _pendingConnections = MutableStateFlow<List<ConnectionManager.PendingConnection>>(emptyList())
    val pendingConnections: StateFlow<List<ConnectionManager.PendingConnection>> = _pendingConnections.asStateFlow()

    private val _isBeingControlled = MutableStateFlow(false)
    val isBeingControlled: StateFlow<Boolean> = _isBeingControlled.asStateFlow()

    private val _controllerName = MutableStateFlow<String?>(null)
    val controllerName: StateFlow<String?> = _controllerName.asStateFlow()

    private var playerService: RemotePlayerService? = null
    private var controllerService: RemoteControllerService? = null

    // Callback for showing pairing dialog
    var onShowPairingDialog: ((ConnectionManager.PendingConnection) -> Unit)? = null

    private val playerServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RemotePlayerService.LocalBinder
            playerService = binder.getService()

            // Observe pending connections and show pairing dialog
            viewModelScope.launch {
                playerService?.getConnectionManager()?.pendingConnections?.collect { pending ->
                    _pendingConnections.value = pending

                    // Show pairing dialog for new pending connections
                    pending.forEach { pendingConnection ->
                        onShowPairingDialog?.invoke(pendingConnection)
                    }

                    // Update controlled state
                    _isBeingControlled.value = binder.getService().hasConnectedControllers()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
        }
    }

    private val controllerServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RemoteControllerService.LocalBinder
            controllerService = binder.getService()

            // Observe discovered devices
            viewModelScope.launch {
                controllerService?.getDiscoveryManager()?.discoveredDevices?.collect { devices ->
                    _discoveredDevices.value = devices
                }
            }

            // Observe connection state
            viewModelScope.launch {
                controllerService?.getConnectionManager()?.connectionState?.collect { state ->
                    _connectionState.value = state
                }
            }

            // Observe connected device
            viewModelScope.launch {
                controllerService?.getConnectionManager()?.currentConnection?.collect { device ->
                    _connectedDevice.value = device
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controllerService = null
        }
    }

    companion object {
        private const val TAG = "RemoteViewModel"
        const val PLAYER_MODE_ENABLED = "remote_player_mode_enabled"
    }

    init {
        // Load player mode state
        _isPlayerModeEnabled.value = settings.getBoolean(PLAYER_MODE_ENABLED, false)

        // Start appropriate service if enabled
        if (_isPlayerModeEnabled.value) {
            startPlayerMode()
        }
    }

    /**
     * Enable or disable player mode
     */
    fun setPlayerModeEnabled(enabled: Boolean) {
        _isPlayerModeEnabled.value = enabled
        settings.edit().putBoolean(PLAYER_MODE_ENABLED, enabled).apply()

        if (enabled) {
            startPlayerMode()
        } else {
            stopPlayerMode()
        }

        Log.i(TAG, "Player mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Start player mode service
     */
    private fun startPlayerMode() {
        try {
            Log.i(TAG, "Starting player mode service...")
            RemotePlayerService.startService(context)
            val intent = Intent(context, RemotePlayerService::class.java)
            context.bindService(intent, playerServiceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "Player mode service started and bound")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting player mode", e)
            e.printStackTrace()
        }
    }

    /**
     * Stop player mode service
     */
    private fun stopPlayerMode() {
        try {
            context.unbindService(playerServiceConnection)
            RemotePlayerService.stopService(context)
            playerService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping player mode", e)
        }
    }

    /**
     * Start discovering devices (Controller mode)
     */
    fun startDiscovery() {
        if (controllerService == null) {
            // Start controller service
            RemoteControllerService.startService(context)
            val intent = Intent(context, RemoteControllerService::class.java)
            context.bindService(intent, controllerServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Stop discovering devices
     */
    fun stopDiscovery() {
        try {
            controllerService?.getDiscoveryManager()?.stopDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }

    /**
     * Connect to a remote device
     */
    fun connectToDevice(device: RemoteDevice) {
        controllerService?.connectToDevice(device)
    }

    /**
     * Disconnect from current device
     */
    fun disconnect() {
        controllerService?.getConnectionManager()?.disconnect()
    }

    /**
     * Accept a pending connection request (Player mode)
     */
    fun acceptConnection(pending: ConnectionManager.PendingConnection, trustDevice: Boolean = false) {
        playerService?.getConnectionManager()?.acceptConnection(pending, trustDevice)
        _isBeingControlled.value = true
        _controllerName.value = pending.deviceName
    }

    /**
     * Reject a pending connection request (Player mode)
     */
    fun rejectConnection(pending: ConnectionManager.PendingConnection) {
        playerService?.getConnectionManager()?.rejectConnection(pending)
    }

    /**
     * Show pairing dialog for a pending connection
     */
    fun showPairingDialog(context: Context, pending: ConnectionManager.PendingConnection) {
        PairingDialog.show(
            context = context,
            deviceName = pending.deviceName,
            onAccept = { trustDevice ->
                acceptConnection(pending, trustDevice)
            },
            onReject = {
                rejectConnection(pending)
            }
        )
    }

    /**
     * Send a control command to the connected player (Controller mode)
     */
    fun sendCommand(message: RemoteMessage) {
        controllerService?.sendCommand(message)
    }

    override fun onCleared() {
        super.onCleared()

        try {
            if (playerService != null) {
                context.unbindService(playerServiceConnection)
            }
            if (controllerService != null) {
                stopDiscovery()
                context.unbindService(controllerServiceConnection)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

