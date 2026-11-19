package local.music.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

/** All controllers, including headsets, can leave an errored track with Next/Previous. */
@UnstableApi
class RecoveringPlayer(private val delegate: Player) : ForwardingSimpleBasePlayer(delegate) {
    // Fail closed while private preferences load, before any restored metadata is published.
    var privateListening = true
        set(value) { if (field != value) { field = value; invalidateState() } }
    private var sourceTimeline: Timeline? = null
    private var hiddenTimeline: Timeline? = null
    private var hiddenIndex = -1
    private var hiddenPrivate = false

    override fun getState(): State {
        val state = super.getState()
        val index = state.currentMediaItemIndex
        if (sourceTimeline !== state.timeline || hiddenIndex != index || hiddenPrivate != privateListening) {
            sourceTimeline = state.timeline
            hiddenIndex = index
            hiddenPrivate = privateListening
            val hideAll = privateListening
            hiddenTimeline = object : ForwardingTimeline(state.timeline) {
                override fun getWindow(windowIndex: Int, window: Timeline.Window, projectionUs: Long): Timeline.Window {
                    super.getWindow(windowIndex, window, projectionUs)
                    // Legacy Android sessions expose one global queue. Never put the library's
                    // unplayed titles/URIs in it, even when the current song is public.
                    if (hideAll || windowIndex != index) window.mediaItem = MediaItem.Builder()
                        .setMediaId("private-$windowIndex")
                        .setMediaMetadata(if (hideAll) PRIVATE_METADATA else QUEUED_METADATA).build()
                    window.manifest = null
                    return window
                }
            }
        }
        val metadata = if (privateListening) PRIVATE_METADATA else MediaMetadata.Builder()
            .setTitle(boundedText(state.currentMetadata.title?.toString() ?: "Untitled track"))
            .setArtist(boundedText(state.currentMetadata.artist?.toString() ?: "Unknown artist"))
            .setAlbumTitle(boundedText(state.currentMetadata.albumTitle?.toString() ?: "Unknown album"))
            .setArtworkUri(state.currentMetadata.artworkUri?.takeIf { isLocalArtworkUri(it.toString()) })
            .setIsPlayable(true).build()
        return state.buildUpon().setPlaylist(hiddenTimeline!!, Tracks.EMPTY, metadata)
            .setPlaylistMetadata(MediaMetadata.EMPTY).setTimedMetadata(Metadata())
            .setPlayerError(if (privateListening) state.playerError?.let {
                PlaybackException("Playback unavailable", null, it.errorCode)
            } else state.playerError)
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val hadError = delegate.playerError != null
        val oldIndex = delegate.currentMediaItemIndex
        val result = super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (hadError && delegate.currentMediaItemIndex != oldIndex) {
            delegate.prepare()
            delegate.play()
        }
        return result
    }

    companion object {
        private val PRIVATE_METADATA = MediaMetadata.Builder().setTitle("Private listening")
            .setArtist("mz music").setIsPlayable(true).build()
        private val QUEUED_METADATA = MediaMetadata.Builder().setTitle("Queued track").setIsPlayable(true).build()
    }
}
