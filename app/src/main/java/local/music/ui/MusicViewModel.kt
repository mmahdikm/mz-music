package local.music.ui

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import local.music.library.AudioPermission
import local.music.library.MusicLibrary
import local.music.library.Song
import local.music.collection.*
import local.music.playback.*
import local.music.playback.PlaybackService
import local.music.playback.MAX_QUEUE_TRACKS
import local.music.sound.SoundSession
import local.music.sound.SoundUiState
import androidx.media3.session.SessionResult

data class LibraryState(
    val permissionGranted: Boolean = false,
    val loading: Boolean = false,
    val songs: List<Song> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val query: String = "",
)

data class CollectionsState(
    val ready: Boolean = false, val busy: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(), val favorites: List<Song> = emptyList(),
    val selectedId: String? = null, val selectedSongs: List<Song> = emptyList(), val error: String? = null,
)

data class PlaybackState(
    val connected: Boolean = false,
    val connecting: Boolean = true,
    val track: Song? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val error: String? = null,
)

@androidx.annotation.OptIn(UnstableApi::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = MusicLibrary(application)
    private val _libraryState = MutableStateFlow(LibraryState())
    val libraryState = _libraryState.asStateFlow()
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState = _playbackState.asStateFlow()
    private val _soundState = MutableStateFlow(SoundUiState())
    val soundState = _soundState.asStateFlow()
    private val collections = CollectionRepository(application)
    private val _collectionsState = MutableStateFlow(CollectionsState())
    val collectionsState = _collectionsState.asStateFlow()
    private val _queueState = MutableStateFlow(QueueUiState())
    val queueState = _queueState.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var queueRequest = 0L
    private var query = ""
    private var controller: MediaController? = null
    private var scan: Job? = null
    private var libraryLimit = 200
    private var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayback()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshQueue()
        }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh(debounce = true) }
    }

    init {
        application.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        connect()
        refresh()
        collectionAction { }
    }

    fun connect() {
        if (controller != null || connection?.isDone == false) return
        connection?.let(MediaController::releaseFuture)
        _playbackState.update { it.copy(connecting = true, error = null) }
        val app = getApplication<Application>()
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    _soundState.value = SoundUiState.fromBundle(extras)
                }
                override fun onDisconnected(controller: MediaController) {
                    this@MusicViewModel.controller = null
                    queueRequest++
                    _queueState.value = QueueUiState(error = "Reconnect to load your queue.")
                    _soundState.update { it.copy(ready = false) }
                    _playbackState.update {
                        it.copy(connected = false, connecting = false, isPlaying = false,
                            playWhenReady = false, error = "Playback disconnected. Tap Reconnect to try again.")
                    }
                }
            }).buildAsync()
        connection = future
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
                _soundState.value = SoundUiState.fromBundle(controller!!.sessionExtras)
                updatePlayback()
                refreshQueue()
            } catch (_: Exception) {
                _playbackState.update {
                    it.copy(connected = false, connecting = false, error = "Could not connect to playback. Try again.")
                }
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun refresh(debounce: Boolean = false) {
        scan?.cancel()
        val permitted = AudioPermission.granted(getApplication())
        if (!permitted) {
            _libraryState.value = LibraryState()
            return
        }
        scan = viewModelScope.launch {
            if (debounce) delay(350)
            _libraryState.update { it.copy(permissionGranted = true, loading = true, error = null) }
            try {
                val page = library.read(libraryLimit, query)
                _libraryState.value = LibraryState(true, false, page.songs, hasMore = page.hasMore, query = query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                _libraryState.value = LibraryState(error = "Music access was removed. Allow access to browse again.")
            } catch (_: Exception) {
                _libraryState.update { it.copy(loading = false, error = "Could not read your music. Tap Refresh to try again.") }
            }
        }
    }

    fun search(value: String) {
        query = value.take(120)
        libraryLimit = 200
        _libraryState.update { it.copy(query = query, songs = emptyList(), hasMore = false) }
        refresh(debounce = true)
    }

    fun loadMore() {
        libraryLimit = (libraryLimit + 200).coerceAtMost(MAX_QUEUE_TRACKS)
        refresh()
    }

    fun playSong(song: Song, songs: List<Song> = _libraryState.value.songs) {
        val player = controller ?: return
        val index = songs.indexOfFirst { it.uri == song.uri }
        if (index < 0) return
        player.setMediaItems(songs.map(Song::toMediaItem), index, 0)
        player.prepare()
        player.play()
    }

    fun togglePlayback() {
        val player = controller ?: return
        if (player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun previous() = skip(next = false)
    fun next() = skip(next = true)

    private fun skip(next: Boolean) {
        val player = controller ?: return
        if (next && player.hasNextMediaItem()) player.seekToNextMediaItem()
        else if (!next && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
        else return
    }

    fun seek(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)) }

    fun soundCommand(op: String, values: Bundle.() -> Unit = {}) {
        val connected = controller ?: return
        val result = connected.sendCustomCommand(SoundSession.command, Bundle().apply { putString("op", op); values() })
        result.addListener({
            val success = runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
            if (!success) _soundState.update { it.copy(error = "Could not save that change. Use a unique preset name (up to 40 characters) and try again.") }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun updatePlayback() {
        val player = controller ?: return
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        _playbackState.value = PlaybackState(
            connected = true, connecting = false,
            track = item?.let {
                Song(it.mediaId, metadata.title?.toString() ?: "Untitled track",
                    metadata.artist?.toString() ?: "Unknown artist",
                    metadata.albumTitle?.toString() ?: "Unknown album",
                    metadata.artworkUri?.toString(), duration)
            },
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0), durationMs = duration,
            queueIndex = player.currentMediaItemIndex.coerceAtLeast(0), queueSize = player.mediaItemCount,
            shuffle = player.shuffleModeEnabled, repeat = player.repeatMode,
            canPrevious = player.hasPreviousMediaItem(), canNext = player.hasNextMediaItem(),
            error = player.playerError?.let(::playbackError),
        )
    }

    fun dismissMessage() { _message.value = null }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() { controller?.let { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    } } }

    private fun collectionAction(success: String? = null, action: suspend () -> Unit) {
        if (_collectionsState.value.busy) return
        _collectionsState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
                val data = collections.snapshot(_collectionsState.value.selectedId)
                _collectionsState.value = CollectionsState(true, false, data.playlists, data.favorites, data.selectedId, data.selectedSongs)
                if (success != null) _message.value = success
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                val error = if (e is IllegalArgumentException) e.message ?: "Could not change your collection."
                    else "Could not save or load your collection. Check device storage and retry."
                _collectionsState.update { it.copy(busy = false, error = error) }
                _message.value = error
            }
        }
    }
    fun retryCollections() = collectionAction { }
    fun selectPlaylist(id: String?) = collectionAction {
        _collectionsState.update { it.copy(selectedId = id, selectedSongs = emptyList()) }
    }
    fun createPlaylist(name: String, songs: List<Song> = emptyList()) = collectionAction("Playlist saved. Repeated songs are included once.") {
        val id = collections.create(name, songs)
        _collectionsState.update { it.copy(selectedId = id, selectedSongs = emptyList()) }
    }
    fun renamePlaylist(id: String, name: String) = collectionAction("Playlist renamed.") { collections.rename(id, name) }
    fun deletePlaylist(id: String) = collectionAction("Playlist deleted. Music files are unchanged.") { collections.delete(id) }
    fun addToPlaylist(id: String, song: Song) = collectionAction("Added to playlist.") { collections.add(id, song) }
    fun removeFromPlaylist(id: String, song: Song) = collectionAction("Removed from playlist. Music file is unchanged.") { collections.remove(id, song.uri) }
    fun moveInPlaylist(id: String, song: Song, delta: Int) = collectionAction { collections.move(id, song.uri, delta) }
    fun toggleFavorite(song: Song) = collectionAction { collections.favorite(song) }

    fun refreshQueue() {
        val connected = controller ?: return
        val request = ++queueRequest
        _queueState.update { it.copy(loading = true, error = null) }
        val songs = mutableListOf<Song>()
        fun page(revision: Long) {
            val result = connected.sendCustomCommand(QueueSession.command, Bundle().apply {
                putString("op", "get"); putInt("offset", songs.size); putLong("revision", revision)
            })
            result.addListener({
                if (request == queueRequest && controller === connected) {
                    try {
                        val response = result.get()
                        check(response.resultCode == SessionResult.RESULT_SUCCESS)
                        val extra = response.extras
                        val next = QueueSnapshot.decode(extra.getString("page"))?.tracks.orEmpty().map { it.song() }
                        val total = extra.getInt("total")
                        check(total in 0..MAX_QUEUE_TRACKS && (total == 0 || next.isNotEmpty()))
                        songs += next
                        if (songs.size < total) page(extra.getLong("revision"))
                        else _queueState.value = QueueUiState(songs.toList(), extra.getLong("revision"))
                    } catch (_: Exception) {
                        _queueState.update { it.copy(loading = false, error = "Queue changed or could not load. Tap Refresh queue.") }
                    }
                }
            }, ContextCompat.getMainExecutor(getApplication()))
        }
        page(-1)
    }

    fun enqueue(song: Song, next: Boolean) = queueCommand(if (next) "next" else "enqueue", if (next)
        "Added to play next. Repeat one is turned off for this action." else "Added to queue.") {
        putString("song", QueueSnapshot(listOf(song.saved()), 0, 0).encode())
    }
    fun moveQueue(index: Int, to: Int) = queueCommand("move") { putInt("index", index); putInt("to", to) }
    fun removeQueue(index: Int) = queueCommand("remove") { putInt("index", index) }
    fun playQueue(index: Int) = queueCommand("play") { putInt("index", index) }
    private fun queueCommand(op: String, message: String? = null, args: Bundle.() -> Unit) {
        val connected = controller ?: return
        val result = connected.sendCustomCommand(QueueSession.command, Bundle().apply {
            putString("op", op); putLong("revision", _queueState.value.revision); args()
        })
        result.addListener({
            val response = runCatching { result.get() }.getOrNull()
            _message.value = if (response?.resultCode == SessionResult.RESULT_SUCCESS) message
                else response?.extras?.getString("error") ?: "Could not change the queue. Refresh and retry."
            refreshQueue()
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        controller?.removeListener(listener)
        connection?.let(MediaController::releaseFuture)
        controller = null
    }
}

fun playbackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file is missing. Refresh your library or choose another track."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Music access is unavailable. Check the app's music permission in Settings."
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This file is damaged or its format is unsupported. Choose another track."
    else -> "This track could not be played. Try again or choose another track."
}
