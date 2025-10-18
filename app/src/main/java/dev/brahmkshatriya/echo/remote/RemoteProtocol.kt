package dev.brahmkshatriya.echo.remote

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.Serializable

/**
 * Protocol for remote control communication between Echo devices.
 * All messages are serializable for transmission over WebSocket.
 */
@Serializable
sealed class RemoteMessage {

    // Connection & Handshake Messages
    @Serializable
    data class ConnectionRequest(
        val deviceName: String,
        val deviceId: String,
        val installedExtensions: List<String>
    ) : RemoteMessage()

    @Serializable
    data class ConnectionResponse(
        val accepted: Boolean,
        val deviceName: String,
        val reason: String? = null
    ) : RemoteMessage()

    @Serializable
    data class Disconnect(val reason: String = "User disconnected") : RemoteMessage()

    // Playback Control Messages
    @Serializable
    data class PlayPause(val isPlaying: Boolean) : RemoteMessage()

    @Serializable
    data class Seek(val position: Long) : RemoteMessage()

    @Serializable
    data class SeekRelative(val delta: Long) : RemoteMessage()

    @Serializable
    data class Next : RemoteMessage()

    @Serializable
    data class Previous : RemoteMessage()

    @Serializable
    data class SetShuffleMode(val enabled: Boolean) : RemoteMessage()

    @Serializable
    data class SetRepeatMode(val mode: Int) : RemoteMessage()

    @Serializable
    data class VolumeChange(val volume: Float) : RemoteMessage()

    // Queue Management Messages
    @Serializable
    data class SetQueue(
        val tracks: List<Track>,
        val startIndex: Int,
        val extensionId: String,
        val context: EchoMediaItem? = null
    ) : RemoteMessage()

    @Serializable
    data class AddToQueue(
        val item: EchoMediaItem,
        val extensionId: String,
        val loaded: Boolean
    ) : RemoteMessage()

    @Serializable
    data class AddToNext(
        val item: EchoMediaItem,
        val extensionId: String,
        val loaded: Boolean
    ) : RemoteMessage()

    @Serializable
    data class PlayItem(
        val item: EchoMediaItem,
        val extensionId: String,
        val loaded: Boolean,
        val shuffle: Boolean = false
    ) : RemoteMessage()

    @Serializable
    data class RemoveQueueItem(val position: Int) : RemoteMessage()

    @Serializable
    data class MoveQueueItem(val fromPosition: Int, val toPosition: Int) : RemoteMessage()

    @Serializable
    data class ClearQueue : RemoteMessage()

    @Serializable
    data class PlayQueueItem(val position: Int) : RemoteMessage()

    // State Synchronization Messages
    @Serializable
    data class PlayerState(
        val currentTrack: Track?,
        val extensionId: String?,
        val position: Long,
        val duration: Long,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val shuffleMode: Boolean,
        val repeatMode: Int,
        val queue: List<Track>,
        val currentIndex: Int,
        val isLiked: Boolean = false
    ) : RemoteMessage()

    @Serializable
    data class QueueUpdate(
        val queue: List<Track>,
        val currentIndex: Int
    ) : RemoteMessage()

    @Serializable
    data class PositionUpdate(
        val position: Long,
        val duration: Long
    ) : RemoteMessage()

    // Like/Unlike Messages
    @Serializable
    data class LikeTrack(val isLiked: Boolean) : RemoteMessage()

    // Error Messages
    @Serializable
    data class Error(
        val code: ErrorCode,
        val message: String,
        val details: String? = null
    ) : RemoteMessage()

    @Serializable
    enum class ErrorCode {
        EXTENSION_NOT_FOUND,
        INCOMPATIBLE_EXTENSION,
        PLAYBACK_ERROR,
        NETWORK_ERROR,
        UNKNOWN_ERROR
    }

    // Heartbeat for connection keepalive
    @Serializable
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : RemoteMessage()

    @Serializable
    data class Pong(val timestamp: Long = System.currentTimeMillis()) : RemoteMessage()
}

/**
 * Represents a remote device on the network
 */
@Serializable
data class RemoteDevice(
    val name: String,
    val address: String,
    val port: Int,
    val deviceId: String
)

/**
 * Connection state between devices
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

