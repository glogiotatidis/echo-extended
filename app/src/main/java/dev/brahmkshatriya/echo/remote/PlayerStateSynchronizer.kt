package dev.brahmkshatriya.echo.remote

import android.util.Log
import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLiked
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class PlayerStateSynchronizer(
    private val playerState: PlayerState
) {

    private val scope = CoroutineScope(Dispatchers.Main) + CoroutineName("PlayerStateSynchronizer")

    private val _syncedState = MutableStateFlow<RemoteMessage.PlayerState?>(null)
    val syncedState: StateFlow<RemoteMessage.PlayerState?> = _syncedState.asStateFlow()

    private var syncJob: Job? = null
    private var positionUpdateJob: Job? = null

    // Callback to broadcast state changes
    var onStateChanged: ((RemoteMessage.PlayerState) -> Unit)? = null
    var onQueueChanged: ((RemoteMessage.QueueUpdate) -> Unit)? = null
    var onPositionChanged: ((RemoteMessage.PositionUpdate) -> Unit)? = null

    private var lastQueue: List<MediaItem> = emptyList()
    private var lastCurrentIndex: Int = -1
    private var lastIsPlaying: Boolean = false
    private var lastPosition: Long = 0L

    companion object {
        private const val TAG = "PlayerStateSynchronizer"
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L
    }

    /**
     * Start synchronizing player state
     */
    fun startSync(
        queue: List<MediaItem>,
        currentPosition: Long,
        duration: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        shuffleMode: Boolean,
        repeatMode: Int
    ) {
        if (syncJob?.isActive == true) {
            Log.w(TAG, "Sync already active")
            return
        }

        Log.i(TAG, "Starting player state synchronization")

        syncJob = scope.launch {
            // Observe player state changes
            playerState.current.collect { current ->
                if (current != null) {
                    val state = createPlayerState(
                        current = current,
                        queue = queue,
                        position = currentPosition,
                        duration = duration,
                        isBuffering = isBuffering,
                        shuffleMode = shuffleMode,
                        repeatMode = repeatMode
                    )

                    _syncedState.value = state
                    onStateChanged?.invoke(state)

                    // Detect queue changes
                    if (hasQueueChanged(queue, current.index)) {
                        val queueUpdate = RemoteMessage.QueueUpdate(
                            queue = queue.map { it.track },
                            currentIndex = current.index
                        )
                        onQueueChanged?.invoke(queueUpdate)
                        lastQueue = queue
                        lastCurrentIndex = current.index
                    }

                    // Start/stop position updates based on playback state
                    if (current.isPlaying && !lastIsPlaying) {
                        startPositionUpdates(duration)
                    } else if (!current.isPlaying && lastIsPlaying) {
                        stopPositionUpdates()
                    }
                    lastIsPlaying = current.isPlaying
                }
            }
        }
    }

    /**
     * Stop synchronizing player state
     */
    fun stopSync() {
        Log.i(TAG, "Stopping player state synchronization")
        syncJob?.cancel()
        syncJob = null
        stopPositionUpdates()
        _syncedState.value = null
    }

    /**
     * Update queue information
     */
    fun updateQueue(queue: List<MediaItem>, currentIndex: Int) {
        if (hasQueueChanged(queue, currentIndex)) {
            val queueUpdate = RemoteMessage.QueueUpdate(
                queue = queue.map { it.track },
                currentIndex = currentIndex
            )
            onQueueChanged?.invoke(queueUpdate)
            lastQueue = queue
            lastCurrentIndex = currentIndex
        }
    }

    /**
     * Update position periodically while playing
     */
    private fun startPositionUpdates(duration: Long) {
        stopPositionUpdates()

        positionUpdateJob = scope.launch {
            while (true) {
                delay(POSITION_UPDATE_INTERVAL_MS)

                val current = playerState.current.value
                if (current != null && current.isPlaying) {
                    // Position is estimated here; actual position should come from player
                    val positionUpdate = RemoteMessage.PositionUpdate(
                        position = lastPosition,
                        duration = duration
                    )
                    onPositionChanged?.invoke(positionUpdate)
                } else {
                    break
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Create a complete player state message
     */
    private fun createPlayerState(
        current: PlayerState.Current,
        queue: List<MediaItem>,
        position: Long,
        duration: Long,
        isBuffering: Boolean,
        shuffleMode: Boolean,
        repeatMode: Int
    ): RemoteMessage.PlayerState {
        return RemoteMessage.PlayerState(
            currentTrack = current.track,
            extensionId = current.mediaItem.extensionId,
            position = position,
            duration = duration,
            isPlaying = current.isPlaying,
            isBuffering = isBuffering,
            shuffleMode = shuffleMode,
            repeatMode = repeatMode,
            queue = queue.map { it.track },
            currentIndex = current.index,
            isLiked = current.mediaItem.isLiked
        )
    }

    /**
     * Check if queue has changed
     */
    private fun hasQueueChanged(newQueue: List<MediaItem>, newIndex: Int): Boolean {
        if (newQueue.size != lastQueue.size) return true
        if (newIndex != lastCurrentIndex) return true

        return newQueue.zip(lastQueue).any { (new, old) ->
            new.mediaId != old.mediaId
        }
    }

    /**
     * Broadcast a full state update
     */
    fun broadcastFullState(
        queue: List<MediaItem>,
        currentIndex: Int,
        position: Long,
        duration: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        shuffleMode: Boolean,
        repeatMode: Int
    ) {
        val current = playerState.current.value ?: return

        val state = RemoteMessage.PlayerState(
            currentTrack = current.track,
            extensionId = current.mediaItem.extensionId,
            position = position,
            duration = duration,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            shuffleMode = shuffleMode,
            repeatMode = repeatMode,
            queue = queue.map { it.track },
            currentIndex = currentIndex,
            isLiked = current.mediaItem.isLiked
        )

        _syncedState.value = state
        onStateChanged?.invoke(state)
    }
}

