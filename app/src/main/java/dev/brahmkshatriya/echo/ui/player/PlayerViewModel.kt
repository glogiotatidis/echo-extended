package dev.brahmkshatriya.echo.ui.player

import android.content.SharedPreferences
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.ThumbRating
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverWithDownloads
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToNextCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToQueueCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.playCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.radioCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.resumeCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.sleepTimer
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.getController
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.remote.ConnectionState
import dev.brahmkshatriya.echo.remote.RemoteMessage
import dev.brahmkshatriya.echo.ui.remote.RemoteViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(UnstableApi::class)
class PlayerViewModel(
    val app: App,
    val playerState: PlayerState,
    val settings: SharedPreferences,
    val cache: SimpleCache,
    val extensions: ExtensionLoader,
    downloader: Downloader,
) : ViewModel() {
    private val downloadFlow = downloader.flow

    // Remote control support
    var remoteViewModel: RemoteViewModel? = null

    val browser = MutableStateFlow<MediaController?>(null)
    private fun withBrowser(block: suspend (MediaController) -> Unit) {
        viewModelScope.launch {
            val browser = browser.first { it != null }!!
            block(browser)
        }
    }

    /**
     * Check if we're connected to a remote player
     */
    private fun isControllingRemote(): Boolean {
        val remote = remoteViewModel
        val state = remote?.connectionState?.value
        val device = remote?.connectedDevice?.value
        
        val result = remote != null && 
                     state == ConnectionState.CONNECTED && 
                     device != null
        
        android.util.Log.d("PlayerViewModel", "isControllingRemote check:")
        android.util.Log.d("PlayerViewModel", "  remoteViewModel: ${remote != null}")
        android.util.Log.d("PlayerViewModel", "  connectionState: $state")
        android.util.Log.d("PlayerViewModel", "  connectedDevice: ${device?.name}")
        android.util.Log.d("PlayerViewModel", "  → Result: $result")
        
        return result
    }

    /**
     * Send command to remote player if connected, otherwise use local browser
     */
    private fun withBrowserOrRemote(
        remoteMessage: (() -> RemoteMessage)? = null,
        localBlock: suspend (MediaController) -> Unit
    ) {
        val controlling = isControllingRemote()
        if (controlling && remoteMessage != null) {
            // Send to remote player
            android.util.Log.i("PlayerViewModel", "🌐 Sending to REMOTE player: ${remoteMessage()::class.simpleName}")
            remoteViewModel?.sendCommand(remoteMessage())
        } else {
            // Use local player
            android.util.Log.i("PlayerViewModel", "📱 Using LOCAL player (controlling=$controlling, hasMessage=${remoteMessage != null})")
            withBrowser(localBlock)
        }
    }

    var queue: List<MediaItem> = emptyList()
    val queueFlow = MutableSharedFlow<Unit>()
    private val context = app.context
    val controllerFutureRelease = getController(context) { player ->
        browser.value = player
        player.addListener(PlayerUiListener(player, this))

        if (player.mediaItemCount != 0) return@getController
        if (!settings.getBoolean(KEEP_QUEUE, true)) return@getController

        player.sendCustomCommand(resumeCommand, Bundle.EMPTY)
    }

    override fun onCleared() {
        super.onCleared()
        controllerFutureRelease()
    }

    fun play(position: Int) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.PlayQueueItem(position) },
            localBlock = {
                it.seekTo(position, 0)
                it.playWhenReady = true
            }
        )
    }

    fun seek(position: Int) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.PlayQueueItem(position) },
            localBlock = { it.seekTo(position, 0) }
        )
    }

    fun removeQueueItem(position: Int) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.RemoveQueueItem(position) },
            localBlock = { it.removeMediaItem(position) }
        )
    }

    fun moveQueueItems(fromPos: Int, toPos: Int) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.MoveQueueItem(fromPos, toPos) },
            localBlock = { it.moveMediaItem(fromPos, toPos) }
        )
    }

    fun clearQueue() {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.ClearQueue() },
            localBlock = { it.clearMediaItems() }
        )
    }

    fun seekTo(pos: Long) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.Seek(pos) },
            localBlock = { it.seekTo(pos) }
        )
    }

    fun seekToAdd(position: Int) {
        // For relative seek, we need to get current position first
        // For remote, send relative message; for local, calculate and seek
        if (isControllingRemote()) {
            remoteViewModel?.sendCommand(RemoteMessage.SeekRelative(position.toLong()))
        } else {
            withBrowser { it.seekTo(max(0, it.currentPosition + position)) }
        }
    }

    fun setPlaying(isPlaying: Boolean) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.PlayPause(isPlaying) },
            localBlock = {
                it.prepare()
                it.playWhenReady = isPlaying
            }
        )
    }

    fun next() {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.Next() },
            localBlock = { it.seekToNextMediaItem() }
        )
    }

    fun previous() {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.Previous() },
            localBlock = { it.seekToPrevious() }
        )
    }

    fun setShuffle(isShuffled: Boolean, changeCurrent: Boolean = false) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.SetShuffleMode(isShuffled) },
            localBlock = {
                it.shuffleModeEnabled = isShuffled
                if (changeCurrent) it.seekTo(0, 0)
            }
        )
    }

    fun setRepeat(repeatMode: Int) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.SetRepeatMode(repeatMode) },
            localBlock = { it.repeatMode = repeatMode }
        )
    }

    suspend fun isLikeClient(extensionId: String): Boolean = withContext(Dispatchers.IO) {
        extensions.music.getExtension(extensionId)?.isClient<LikeClient>() ?: false
    }

    private fun createException(throwable: Throwable) {
        viewModelScope.launch { app.throwFlow.emit(throwable) }
    }

    fun likeCurrent(isLiked: Boolean) {
        withBrowserOrRemote(
            remoteMessage = { RemoteMessage.LikeTrack(isLiked) },
            localBlock = { controller ->
                val future = controller.setRating(ThumbRating(isLiked))
                app.context.listenFuture(future) { sessionResult ->
                    sessionResult.getOrElse { createException(it) }
                }
            }
        )
    }

    fun setSleepTimer(timer: Long) {
        withBrowser { it.sendCustomCommand(sleepTimer, bundleOf("ms" to timer)) }
    }

    fun changeTrackSelection(trackGroup: TrackGroup, index: Int) {
        withBrowser {
            it.trackSelectionParameters = it.trackSelectionParameters
                .buildUpon()
                .clearOverride(trackGroup)
                .addOverride(TrackSelectionOverride(trackGroup, index))
                .build()
        }
    }

    private fun changeCurrent(newItem: MediaItem) {
        withBrowser { player ->
            val oldPosition = player.currentPosition
            player.replaceMediaItem(player.currentMediaItemIndex, newItem)
            player.prepare()
            player.seekTo(oldPosition)
        }
    }

    fun changeServer(server: Streamable) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.serverWithDownloads(app.context).indexOf(server).takeIf { it != -1 }
            ?: return
        changeCurrent(MediaItemUtils.buildServer(item, index))
    }

    fun changeBackground(background: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.backgrounds.indexOf(background)
        changeCurrent(MediaItemUtils.buildBackground(item, index))
    }

    fun changeSubtitle(subtitle: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.subtitles.indexOf(subtitle)
        changeCurrent(MediaItemUtils.buildSubtitle(item, index))
    }

    fun changeCurrentSource(index: Int) {
        val item = playerState.current.value?.mediaItem ?: return
        changeCurrent(MediaItemUtils.buildSource(item, index))
    }

    fun setQueue(id: String, list: List<Track>, index: Int, context: EchoMediaItem?) {
        withBrowser { controller ->
            val mediaItems = list.map {
                MediaItemUtils.build(
                    app,
                    downloadFlow.value,
                    MediaState.Unloaded(id, it),
                    context
                )
            }
            controller.setMediaItems(mediaItems, index, list[index].playedDuration ?: 0)
            controller.prepare()
        }
    }

    fun radio(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        app.messageFlow.emit(
            Message(app.context.getString(R.string.loading_radio_for_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(radioCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    fun play(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.playing_x, item.title))
        )

        if (isControllingRemote()) {
            remoteViewModel?.sendCommand(RemoteMessage.PlayItem(item, id, loaded, shuffle = false))
        } else {
            withBrowser {
                it.sendCustomCommand(playCommand, Bundle().apply {
                    putString("extId", id)
                    putSerialized("item", item)
                    putBoolean("loaded", loaded)
                    putBoolean("shuffle", false)
                })
            }
        }
    }

    fun shuffle(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.shuffling_x, item.title))
        )

        if (isControllingRemote()) {
            remoteViewModel?.sendCommand(RemoteMessage.PlayItem(item, id, loaded, shuffle = true))
        } else {
            withBrowser {
                it.sendCustomCommand(playCommand, Bundle().apply {
                    putString("extId", id)
                    putSerialized("item", item)
                    putBoolean("loaded", loaded)
                    putBoolean("shuffle", true)
                })
            }
        }
    }


    fun addToQueue(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_queue, item.title))
        )

        if (isControllingRemote()) {
            remoteViewModel?.sendCommand(RemoteMessage.AddToQueue(item, id, loaded))
        } else {
            withBrowser {
                it.sendCustomCommand(addToQueueCommand, Bundle().apply {
                    putString("extId", id)
                    putSerialized("item", item)
                    putBoolean("loaded", loaded)
                })
            }
        }
    }

    fun addToNext(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (!(browser.value?.mediaItemCount == 0 && item is Track)) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_next, item.title))
        )

        if (isControllingRemote()) {
            remoteViewModel?.sendCommand(RemoteMessage.AddToNext(item, id, loaded))
        } else {
            withBrowser {
                it.sendCustomCommand(addToNextCommand, Bundle().apply {
                    putString("extId", id)
                    putSerialized("item", item)
                    putBoolean("loaded", loaded)
                })
            }
        }
    }

    val progress = MutableStateFlow(0L to 0L)
    val discontinuity = MutableStateFlow(0L)
    val totalDuration = MutableStateFlow<Long?>(null)

    val buffering = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val nextEnabled = MutableStateFlow(false)
    val previousEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(0)
    val shuffleMode = MutableStateFlow(false)

    val tracksFlow = MutableStateFlow<Tracks?>(null)
    val serverAndTracks = tracksFlow.combine(playerState.serverChanged) { tracks, _ -> tracks }
        .combine(playerState.current) { tracks, current ->
            val server = playerState.servers[current?.mediaItem?.mediaId]?.getOrNull()
            val index = current?.mediaItem?.sourceIndex
            Triple(tracks, server, index)
        }.stateIn(viewModelScope, SharingStarted.Lazily, Triple(null, null, null))

    companion object {
        const val KEEP_QUEUE = "keep_queue"
    }
}
