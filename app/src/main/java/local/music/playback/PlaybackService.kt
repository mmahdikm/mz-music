package local.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import local.music.MainActivity
import local.music.library.Song
import local.music.sound.*
import java.util.UUID

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var sessionPlayer: RecoveringPlayer
    private lateinit var store: PlaybackStore
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var destroyed = false
    private val soundBridge = SoundBridge()
    private lateinit var soundStore: SoundStore
    private var soundDocument = SoundDocument()
    private var soundReady = false
    private var soundRate = 0
    private var soundMediaId = ""
    private var soundLoudness = LoudnessInfo()
    private var queueRestoreCancelled = false
    private var queueRevision = 0L
    private var queueDirty = true
    private var savedTracks: List<SavedTrack> = emptyList()
    private var queueSaveError: String? = null
    private var soundSaveError: String? = null
    private var storageMessage: String? = null
    private var clearingQueue = false
    private val saveSound = Runnable { if (soundReady && !destroyed) soundStore.save(soundDocument) }

    private val checkpoint = object : Runnable {
        override fun run() {
            if (player.isPlaying) save()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStore(this) { success ->
            if (!destroyed) {
                queueSaveError = if (success) null else "Could not save or erase the queue. Free storage and retry."
                if (clearingQueue) {
                    storageMessage = if (success) "Queue and saved position cleared. Music files are unchanged." else null
                    clearingQueue = false
                }
                publishSound()
            }
        }
        soundStore = SoundStore(this) { success ->
            if (!destroyed) {
                soundSaveError = if (success) null else "Sound settings could not be saved. Free storage and retry."
                publishSound()
            }
        }
        val renderers = SoundRenderersFactory(this, soundBridge) { mediaId, info, rate ->
            handler.post {
                if (!destroyed && player.currentMediaItem?.mediaId == mediaId) {
                    soundMediaId = mediaId; soundLoudness = info; soundRate = rate
                    publishSound()
                }
            }
        }
        player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        sessionPlayer = RecoveringPlayer(player)
        session = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    if (isOwnController(controller)) {
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                                .buildUpon().add(SoundSession.command).add(QueueSession.command).build())
                            .setSessionExtras(soundExtras()).build()
                    }
                    return if (controller.isTrusted) MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(SessionCommands.EMPTY)
                        .setAvailablePlayerCommands(MediaPolicy.externalCommands).build()
                    else MediaSession.ConnectionResult.reject()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == QueueSession.ACTION) {
                        if (!isOwnController(controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                        return Futures.immediateFuture(runCatching { changeQueue(args) }.getOrElse {
                            SessionResult(SessionError.ERROR_BAD_VALUE, Bundle().apply { putString("error", it.message?.take(160) ?: "Queue change failed.") })
                        })
                    }
                    val result = when {
                        !isOwnController(controller) -> SessionError.ERROR_PERMISSION_DENIED
                        customCommand.customAction != SoundSession.ACTION -> SessionError.ERROR_NOT_SUPPORTED
                        !soundReady -> SessionError.ERROR_INVALID_STATE
                        else -> runCatching { changeSound(args); SessionResult.RESULT_SUCCESS }
                            .getOrDefault(SessionError.ERROR_BAD_VALUE)
                    }
                    return Futures.immediateFuture(SessionResult(result))
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    return runCatching {
                        require(player.mediaItemCount + mediaItems.size <= MAX_QUEUE_TRACKS)
                        Futures.immediateFuture(acceptQueue(controller, mediaItems))
                    }.getOrElse { Futures.immediateFailedFuture(it) }
                }

                override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = runCatching {
                    val accepted = acceptQueue(controller, mediaItems)
                    require(startIndex == C.INDEX_UNSET || startIndex in accepted.indices || accepted.isEmpty())
                    Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(accepted, startIndex, startPositionMs))
                }.getOrElse { Futures.immediateFailedFuture(it) }
            }).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) { queueDirty = true; queueRevision++ }
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && player.currentMediaItem?.mediaId != soundMediaId) {
                    soundMediaId = ""; soundLoudness = LoudnessInfo(); soundRate = 0
                    publishSound()
                }
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )) save()
            }
        })
        soundStore.load { document ->
            if (!destroyed) {
                soundDocument = document
                soundBridge.settings = document.settings
                sessionPlayer.privateListening = document.settings.privateListening
                soundReady = true
                publishSound()
            }
        }
        store.load { saved ->
            if (!destroyed) {
                // A user's new queue always wins over a slower disk restoration.
                if (!queueRestoreCancelled && player.mediaItemCount == 0 && saved != null) {
                    player.setMediaItems(saved.tracks.map {
                        Song(it.uri, it.title, it.artist, it.album, it.artworkUri, 0).toMediaItem()
                    }, saved.index, saved.positionMs)
                    player.shuffleModeEnabled = saved.shuffle
                    player.repeatMode = saved.repeat
                    player.playWhenReady = false
                    // Defer decoding until Play: inaccessible files can still be skipped.
                }
                initialized = true
                save()
            }
        }
        handler.post(checkpoint)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private fun isOwnController(controller: MediaSession.ControllerInfo) =
        controller.uid == Process.myUid() && controller.packageName == packageName

    private fun acceptQueue(controller: MediaSession.ControllerInfo, items: List<MediaItem>): MutableList<MediaItem> {
        require(isOwnController(controller) && items.size <= MAX_QUEUE_TRACKS)
        val sanitized = items.map(MediaPolicy::sanitize).toMutableList()
        queueRestoreCancelled = true
        clearingQueue = false
        storageMessage = null
        return sanitized
    }

    private fun soundExtras() = SoundSession.bundle(soundDocument, soundReady, soundLoudness, soundRate).apply {
        putString("soundError", soundSaveError ?: queueSaveError)
        putString("storageMessage", storageMessage)
    }

    private fun publishSound() {
        if (!::session.isInitialized || destroyed) return
        val extras = soundExtras()
        session.connectedControllers.filter(::isOwnController).forEach { session.setSessionExtras(it, extras) }
    }

    private fun changeSound(args: Bundle) {
        val settings = soundDocument.settings
        fun name(): String = args.getString("name").orEmpty().trim().also { value ->
            require(value.isNotEmpty() && value.length <= 40)
        }
        val next = when (args.getString("op")) {
            "tuned" -> {
                require(!settings.tuned || args.getBoolean("value") || args.getBoolean("confirmed"))
                soundDocument.copy(settings = settings.copy(tuned = args.getBoolean("value")))
            }
            "limitBoost" -> {
                require(args.getBoolean("value") || args.getBoolean("confirmed"))
                soundDocument.copy(settings = settings.copy(limitLevelingBoost = args.getBoolean("value")))
            }
            "private" -> soundDocument.copy(settings = settings.copy(privateListening = args.getBoolean("value")))
            "clearQueue" -> {
                require(args.getBoolean("confirmed"))
                queueRestoreCancelled = true
                initialized = true
                clearingQueue = true
                storageMessage = "Clearing saved queue…"
                player.stop()
                player.clearMediaItems()
                queueDirty = true
                save()
                publishSound()
                return
            }
            "band" -> {
                val index = args.getInt("index", -1); require(index in 0..9)
                val value = args.getDouble("value", Double.NaN); require(value.isFinite())
                soundDocument.copy(settings = settings.copy(gainsDb = settings.gainsDb.mapIndexed { i, old -> if (i == index) value else old }).validated())
            }
            "preamp" -> {
                val value = args.getDouble("value", Double.NaN); require(value.isFinite())
                soundDocument.copy(settings = settings.copy(preampDb = value).validated())
            }
            "leveling" -> soundDocument.copy(settings = settings.copy(leveling = Leveling.valueOf(args.getString("value").orEmpty())))
            "reset" -> soundDocument.copy(settings = settings.copy(gainsDb = List(10) { 0.0 }, preampDb = 0.0, presetId = "flat"))
            "select" -> {
                val preset = soundDocument.presets.first { it.id == args.getString("id") }
                soundDocument.copy(settings = settings.copy(gainsDb = preset.gainsDb, preampDb = preset.preampDb, presetId = preset.id))
            }
            "save" -> {
                val id = settings.presetId
                require(id != null && id != "flat")
                soundDocument.copy(presets = soundDocument.presets.map {
                    if (it.id == id) it.copy(gainsDb = settings.gainsDb, preampDb = settings.preampDb) else it
                })
            }
            "saveAs" -> {
                require(soundDocument.presets.size < 50)
                val value = name(); require(soundDocument.presets.none { it.name.equals(value, true) })
                val id = UUID.randomUUID().toString()
                soundDocument.copy(settings = settings.copy(presetId = id),
                    presets = soundDocument.presets + SoundPreset(id, value, settings.gainsDb, settings.preampDb))
            }
            "rename" -> {
                val preset = soundDocument.presets.first { it.id == args.getString("id") }; require(!preset.builtIn)
                val value = name(); require(soundDocument.presets.none { it.id != preset.id && it.name.equals(value, true) })
                soundDocument.copy(presets = soundDocument.presets.map { if (it.id == preset.id) it.copy(name = value) else it })
            }
            "delete" -> {
                val preset = soundDocument.presets.first { it.id == args.getString("id") }; require(!preset.builtIn)
                soundDocument.copy(settings = settings.copy(presetId = settings.presetId.takeUnless { it == preset.id }),
                    presets = soundDocument.presets.filterNot { it.id == preset.id })
            }
            else -> error("Unknown sound operation")
        }
        soundDocument = next
        soundBridge.settings = next.settings
        sessionPlayer.privateListening = next.settings.privateListening
        handler.removeCallbacks(saveSound)
        handler.postDelayed(saveSound, 200)
        publishSound()
    }

    private fun changeQueue(args: Bundle): SessionResult {
        val op = args.getString("op")
        if (op == "get") {
            val offset = args.getInt("offset")
            require(offset in 0..player.mediaItemCount)
            if (offset > 0) require(args.getLong("revision", -1) == queueRevision) { "Queue changed. Try again." }
            val tracks = (offset until minOf(offset + QueueSession.PAGE_SIZE, player.mediaItemCount)).map { i ->
                val item = player.getMediaItemAt(i)
                val m = item.mediaMetadata
                SavedTrack(item.mediaId, m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(),
                    m.albumTitle?.toString().orEmpty(), m.artworkUri?.toString())
            }
            return SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                putLong("revision", queueRevision); putInt("total", player.mediaItemCount)
                putString("page", QueueSnapshot(tracks, 0, 0).encode())
            })
        }
        if (op == "enqueue" || op == "next") {
            require(player.mediaItemCount < MAX_QUEUE_TRACKS) { "The queue is limited to 2,000 songs." }
            val snapshot = QueueSnapshot.decode(args.getString("song"))
            require(snapshot?.tracks?.size == 1) { "Invalid device song." }
            val item = MediaPolicy.sanitize(snapshot!!.tracks.single().song().toMediaItem())
            queueRestoreCancelled = true; initialized = true; clearingQueue = false; storageMessage = null
            val current = player.currentMediaItemIndex
            val insertion = if (op == "next" && player.mediaItemCount > 0) current + 1 else player.mediaItemCount
            val order = mutableListOf<Int>()
            if (op == "next" && player.shuffleModeEnabled && player.mediaItemCount > 0) {
                val timeline = player.currentTimeline
                var index = timeline.getFirstWindowIndex(true)
                while (index != C.INDEX_UNSET && order.size < MAX_QUEUE_TRACKS) {
                    order += index
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                }
            }
            player.addMediaItem(insertion, item)
            if (order.isNotEmpty()) player.setShuffleOrder(DefaultShuffleOrder(orderWithNext(order, current, insertion), System.nanoTime()))
            if (op == "next" && player.repeatMode == Player.REPEAT_MODE_ONE) player.repeatMode = Player.REPEAT_MODE_OFF
        } else {
            require(args.getLong("revision", -1) == queueRevision) { "Queue changed. Try again." }
            val index = args.getInt("index", -1)
            require(index in 0 until player.mediaItemCount) { "This queue entry is no longer available." }
            when (op) {
                "move" -> {
                    val to = args.getInt("to", -1)
                    require(to in 0 until player.mediaItemCount)
                    player.moveMediaItem(index, to)
                }
                "remove" -> player.removeMediaItem(index)
                "play" -> { player.seekTo(index, 0); player.prepare(); player.play() }
                else -> error("Unknown queue command.")
            }
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun save() {
        if (!initialized || destroyed) return
        if (queueDirty) {
        savedTracks = (0 until minOf(player.mediaItemCount, MAX_QUEUE_TRACKS)).map { index ->
            val item = player.getMediaItemAt(index)
            val meta = item.mediaMetadata
            SavedTrack(
                item.localConfiguration?.uri?.toString() ?: item.mediaId,
                meta.title?.toString() ?: "Untitled track",
                meta.artist?.toString() ?: "Unknown artist",
                meta.albumTitle?.toString() ?: "Unknown album",
                meta.artworkUri?.toString(),
            )
        }
        queueDirty = false
        }
        store.save(QueueSnapshot(savedTracks, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition, player.shuffleModeEnabled, player.repeatMode))
    }

    override fun onDestroy() {
        save()
        if (soundReady) soundStore.save(soundDocument)
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        session.release()
        player.release()
        store.close()
        soundStore.close()
        super.onDestroy()
    }
}
