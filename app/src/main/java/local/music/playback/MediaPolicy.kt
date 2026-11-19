package local.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

const val MAX_QUEUE_TRACKS = 2000
const val MAX_METADATA_CHARS = 512
const val MAX_QUEUE_JSON_CHARS = 8 * 1024 * 1024

fun boundedText(value: String) = value.take(MAX_METADATA_CHARS)

@UnstableApi
object MediaPolicy {
    // No full timeline, queue edits, volume, speed, audio attributes, or release command.
    val externalCommands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
    ).build()

    fun sanitize(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        require(isLocalAudioUri(uri))
        val metadata = item.mediaMetadata
        return MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(boundedText(metadata.title?.toString() ?: "Untitled track"))
                .setArtist(boundedText(metadata.artist?.toString() ?: "Unknown artist"))
                .setAlbumTitle(boundedText(metadata.albumTitle?.toString() ?: "Unknown album"))
                .setArtworkUri(metadata.artworkUri?.takeIf { isLocalArtworkUri(it.toString()) })
                .setIsPlayable(true).build()
        ).build()
    }
}
