# Testing & Debugging Remote Control Feature

## 🎯 Quick Test Guide

### Prerequisites
- 2 Android devices with Echo installed
- Both devices connected to the **same WiFi network**
- WiFi network allows multicast (some corporate/guest networks block this)

### Device Setup

#### Device 1 (Player)
1. Open Echo app
2. Go to **Settings (⚙️)** → **Remote Control**  
3. Toggle **"Enable Player Mode"** ON
4. You should see a **persistent notification**: "Remote Player Mode Active"
5. Check logcat for these messages:
   ```
   RemotePlayerService: Creating WebSocket server on port 8765
   RemotePlayerService: WebSocket server started at /0.0.0.0:8765
   DeviceDiscoveryManager: ✅ NSD Service registered successfully: Echo Player_[Model] on port 8765
   ```

#### Device 2 (Controller)
1. Open Echo app
2. Go to **Settings (⚙️)** → **Remote Control**
3. Tap **"Discover Devices"**
4. Bottom sheet opens showing "Searching for devices..."
5. Device 1 should appear in the list as "Echo Player_[Model]"
6. Tap the device button
7. Check logcat for:
   ```
   DeviceDiscoveryManager: ✅ NSD Discovery started for type: _echo._tcp.
   DeviceDiscoveryManager: 📡 NSD Service found: Echo Player_[Model]
   DeviceDiscoveryManager: ✅ Service resolved: Echo Player_[Model]
   DeviceDiscoveryManager: Address: 192.168.x.x, Port: 8765
   ```

#### Device 1 (Player) - Accept Connection
1. Pairing dialog appears: "[Device 2] wants to connect"
2. Tap **"Accept"** (or "Trust Device" for auto-accept in future)
3. Connection established!

### What to Expect
- ✅ Controller sees player in device list
- ✅ Pairing dialog appears on player
- ✅ After accepting, controller can control playback
- ✅ Play/pause, seek, skip tracks work
- ✅ Queue changes sync in real-time

## 🐛 Troubleshooting Discovery Issues

### Issue: "No devices found"

#### Check #1: Is Player Mode actually running?
On **Player device**, check logcat:
```bash
adb logcat | grep RemotePlayerService
```

**Expected output:**
```
RemotePlayerService: RemotePlayerService created
RemotePlayerService: Creating WebSocket server on port 8765
RemotePlayerService: Starting WebSocket server...
RemotePlayerService: WebSocket server started at /0.0.0.0:8765
RemotePlayerService: Player mode started successfully
```

**If you don't see these logs:**
- Player Mode might not be enabled
- Service might have crashed - check for exceptions
- Foreground service notification should be visible

#### Check #2: Is NSD registration working?
On **Player device**, check logcat:
```bash
adb logcat | grep DeviceDiscoveryManager
```

**Expected output:**
```
DeviceDiscoveryManager: ✅ NSD Service registered successfully: Echo Player_Samsung on port 8765
```

**Common NSD errors:**
- **Error Code -1**: Service name conflict (another app using same name)
- **Error Code -2**: Client disconnected  
- **Error Code -3**: Registration failure

**Solutions:**
- Restart the app
- Check if another instance is running
- Disable and re-enable Player Mode

#### Check #3: Is WiFi multicast enabled?
On **Controller device**, check:
```bash
adb logcat | grep DeviceDiscoveryManager
```

**Expected:**
```
DeviceDiscoveryManager: ✅ NSD Discovery started for type: _echo._tcp.
DeviceDiscoveryManager: 📡 NSD Service found: Echo Player_Samsung
```

**If discovery starts but finds nothing:**
- WiFi router might block multicast/mDNS
- Devices might be on different subnets (e.g., 2.4GHz vs 5GHz)
- Try **Manual Connection** with Player's IP address

#### Check #4: Firewall/Network issues
Some WiFi networks block:
- Multicast DNS (mDNS) - used by NSD
- Custom ports (8765)
- Device-to-device communication

**Test with manual connection:**
1. Find Player's IP: Settings → About Phone → Status → IP address  
2. On Controller: Tap "Manual Connection"
3. Enter Player's IP (e.g., `192.168.1.100`)
4. If this works → NSD is blocked by network
5. If this fails → WebSocket server issue

### Issue: Port not open / Connection refused

#### Verify WebSocket server is listening
On **Player device**:
```bash
# Check if port 8765 is listening
adb shell netstat -an | grep 8765
```

**Expected:**
```
tcp 0 0 0.0.0.0:8765 0.0.0.0:* LISTEN
```

**If port not listening:**
- WebSocket server failed to start
- Permission issue (unlikely - we have INTERNET permission)
- Port already in use by another app

**Solution:**
- Check logcat for WebSocket errors
- Try killing and restarting the app
- Ensure notification "Remote Player Mode Active" is showing

#### Test connection manually
On **Controller device**:
```bash
# Replace with Player's actual IP
adb shell nc -v <player-ip> 8765
```

**If connection refused:**
- Firewall blocking port
- Server not running
- Wrong IP/network

## 📊 Diagnostic Logs

### Full Log Collection

#### Player Device:
```bash
adb logcat -s RemotePlayerService:* DeviceDiscoveryManager:* EchoWebSocketServer:* ConnectionManager:*
```

#### Controller Device:
```bash
adb logcat -s RemoteControllerService:* DeviceDiscoveryManager:* EchoWebSocketClient:* ConnectionManager:*
```

### Key Log Markers

✅ **Player mode starting correctly:**
```
RemotePlayerService: Starting player mode
EchoWebSocketServer: WebSocket server started on port 8765
DeviceDiscoveryManager: ✅ NSD Service registered successfully
```

✅ **Discovery working:**
```
DeviceDiscoveryManager: ✅ NSD Discovery started
DeviceDiscoveryManager: 📡 NSD Service found: Echo Player_...
DeviceDiscoveryManager: ✅ Service resolved: Echo Player_...
DeviceDiscoveryManager: ✅ Added device to list: ...
```

✅ **Connection established:**
```
EchoWebSocketClient: Connected to server
RemotePlayerService: Controller connected: /192.168.x.x
RemotePlayerService: Connection request from [Device Name]
ConnectionManager: Accepted connection from [Device Name]
```

## 🔧 Common Fixes

### Fix #1: Enable WiFi Multicast
Some Android devices disable multicast by default:

```java
WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
WifiManager.MulticastLock lock = wifi.createMulticastLock("echo_multicast");
lock.acquire();
```

**Note**: We have `CHANGE_WIFI_MULTICAST_STATE` permission, but might need to explicitly acquire the lock.

### Fix #2: Use 0.0.0.0 instead of localhost
WebSocket server should bind to `0.0.0.0` (all interfaces) not `127.0.0.1` (localhost only).

**Our code:** ✅ Already uses `InetSocketAddress(port)` which defaults to 0.0.0.0

### Fix #3: Wait for NSD registration
NSD registration is asynchronous. Ensure we don't proceed until registration succeeds.

**Our code:** ✅ Uses callback-based registration

### Fix #4: Service lifecycle
Foreground service must stay alive.

**Our code:** ✅ Now uses foreground service with notification

## 🎮 Manual Connection Workaround

If NSD discovery doesn't work (network restrictions):

1. Find Player IP manually:
   - Player Device → Settings → About Phone → Status
   - Note the IP address (e.g., `192.168.1.100`)

2. On Controller:
   - Open "Discover Devices"
   - Tap "Manual Connection"
   - Enter IP address
   - Port defaults to 8765
   - Tap "Connect"

## 📱 APK Download

The latest debug APK with all fixes is available at:
https://github.com/glogiotatidis/echo-extended/actions

**Direct Download** (no unzipping needed):
- Artifact name: `app-debug`
- File: `app-debug.apk` (ready to install)
- Compression: None (compression-level: 0)

## 🔍 Next Steps for Investigation

If discovery still fails after all checks:

1. **Capture full logs** from both devices during discovery
2. **Verify NSD works** with a test app (e.g., "NSD Chat" from Play Store)
3. **Test on different WiFi network** (home network vs mobile hotspot)
4. **Check Android version** - NSD behavior varies by OS version
5. **Try third-party NSD library** if Android's NSD has issues

## 💡 Alternative Discovery Methods

If NSD proves unreliable, consider:

1. **QR Code pairing**: Player shows QR with IP, Controller scans
2. **Bluetooth for pairing**: Exchange IP addresses via Bluetooth
3. **Cloud relay**: Temporary server to exchange IP addresses
4. **Manual PIN entry**: Player shows 4-digit code, Controller enters it

For now, **Manual Connection** provides a reliable fallback!

