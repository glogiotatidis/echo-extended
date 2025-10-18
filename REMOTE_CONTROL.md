# Remote Control Feature

Echo now supports remote control functionality, allowing one Echo device ("controller") to control playback on another Echo device ("player") over the same WiFi network.

## Features

### Player Mode
- **Enable/Disable**: Toggle player mode in Settings → Remote Control
- **Network Discovery**: Automatically advertises the device on the local network via NSD (Network Service Discovery)
- **Connection Approval**: User confirmation required for incoming controller connections
- **Trusted Devices**: Option to trust devices for automatic future connections
- **WebSocket Server**: Listens for control commands on port 8765

### Controller Mode
- **Device Discovery**: Automatically finds Echo players on the same network
- **Manual Connection**: Fallback option to connect via IP address
- **Playback Control**: Full control over play/pause, seek, next/previous
- **Queue Management**: Add, remove, move tracks in the queue
- **Volume Control**: Adjust player volume remotely
- **Real-time Sync**: Player state synchronizes in real-time to controller

### Extension Compatibility
- **Validation**: Checks that required extensions exist on both devices
- **Error Handling**: Clear error messages when extensions are missing
- **Plugin-Based**: Works seamlessly with Echo's plugin architecture

## Architecture

### Core Components

1. **RemoteProtocol** (`remote/RemoteProtocol.kt`)
   - Defines all message types for device communication
   - Serializable messages for WebSocket transmission

2. **WebSocketServer** (`remote/WebSocketServer.kt`)
   - Handles incoming connections from controllers
   - Broadcasts state changes to connected clients

3. **WebSocketClient** (`remote/WebSocketClient.kt`)
   - Connects to remote players
   - Auto-reconnects on connection loss

4. **DeviceDiscoveryManager** (`remote/discovery/DeviceDiscoveryManager.kt`)
   - NSD-based automatic device discovery
   - Service registration and resolution

5. **ConnectionManager** (`remote/connection/ConnectionManager.kt`)
   - Handles connection lifecycle
   - Manages trusted devices list
   - Pairing dialog coordination

6. **PlayerStateSynchronizer** (`remote/PlayerStateSynchronizer.kt`)
   - Syncs player state between devices
   - Position updates, queue changes, etc.

7. **ExtensionValidator** (`remote/ExtensionValidator.kt`)
   - Validates extension compatibility
   - Provides clear error messages

8. **RemotePlayerService** & **RemoteControllerService**
   - Background services for player and controller modes
   - Integrate with PlayerViewModel for command execution

### Communication Flow

#### Player Mode Setup:
1. User enables "Player Mode" in settings
2. RemotePlayerService starts
3. WebSocket server binds to port 8765
4. NSD service registers on network
5. Waits for controller connections

#### Controller Connection:
1. User opens device discovery
2. DeviceDiscoveryManager discovers players via NSD
3. User selects a player device
4. Controller sends connection request
5. Player shows pairing dialog (user accepts/rejects)
6. If accepted, connection established
7. Controller sends extension list for validation
8. If compatible, state synchronization begins

#### Command Execution:
1. User interacts with controller UI
2. Controller sends RemoteMessage to player
3. Player validates command and extension compatibility
4. Player executes command via PlayerViewModel
5. Player broadcasts updated state to all controllers
6. Controllers update their UI with new state

## Protocol Messages

### Connection Messages
- `ConnectionRequest`: Initial connection with device info
- `ConnectionResponse`: Accept/reject response
- `Disconnect`: Graceful disconnection

### Playback Control
- `PlayPause`: Toggle playback
- `Seek`: Jump to position
- `Next`/`Previous`: Navigate tracks
- `SetShuffleMode`: Enable/disable shuffle
- `SetRepeatMode`: Change repeat mode

### Queue Management
- `SetQueue`: Replace entire queue
- `AddToQueue`: Append to queue
- `AddToNext`: Insert after current
- `RemoveQueueItem`: Delete from queue
- `MoveQueueItem`: Reorder queue
- `PlayQueueItem`: Jump to queue position

### State Synchronization
- `PlayerState`: Complete player state
- `QueueUpdate`: Queue changes
- `PositionUpdate`: Playback position

### Heartbeat
- `Ping`/`Pong`: Connection keepalive

## Security

- **Local Network Only**: No internet exposure
- **User Confirmation**: Required for new connections
- **Trusted Devices**: Optional for convenience
- **No Authentication**: Same network assumed trusted

## Permissions

Required permissions added to AndroidManifest.xml:
- `INTERNET` - Network communication
- `ACCESS_WIFI_STATE` - Check network status
- `CHANGE_WIFI_MULTICAST_STATE` - NSD functionality

## Usage

### Enable Player Mode
1. Open Echo app
2. Go to Settings (gear icon)
3. Select "Remote Control"
4. Toggle "Enable Player Mode"
5. Device is now discoverable on the network

### Control from Another Device
1. Open Echo app on controller device
2. Go to Settings → Remote Control
3. Tap "Discover Devices"
4. Select the player device from the list
5. On the player device, accept the connection request
6. Control playback from the controller

## Building

The remote control feature is included in all build variants:
- `assembleDebug` - Debug builds with remote control
- `assembleNightly` - Nightly builds with remote control
- `assembleStable` - Stable/release builds with remote control

No special build configuration required.

## Dependencies

- `Java-WebSocket:1.5.7` - WebSocket client/server
- All other dependencies are standard Echo dependencies

## Future Enhancements

- UI for device discovery bottom sheet
- Visual connection status indicators
- Multiple simultaneous controllers support
- Connection quality indicators
- Custom port configuration
- SSL/TLS encryption option
- Audio streaming (not just control)

## Troubleshooting

**Devices not discovering each other**
- Ensure both devices are on the same WiFi network
- Check that multicast is enabled on the network
- Try manual IP connection as fallback

**Connection rejected**
- Verify player mode is enabled on target device
- Check that required extensions are installed on both devices

**Commands not executing**
- Ensure stable network connection
- Check player device logs for errors
- Verify extension compatibility

## Technical Notes

- Follows Echo project conventions (Koin DI, CoroutineScope naming, etc.)
- Uses existing PlayerViewModel for command execution
- Minimal UI impact (settings + optional indicators)
- Backward compatible (feature is opt-in via settings)

