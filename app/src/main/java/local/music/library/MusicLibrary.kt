package local.music.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.music.playback.boundedText
import local.music.playback.MAX_QUEUE_TRACKS

data class LibraryPage(val songs: List<Song>, val hasMore: Boolean)

object AudioPermission {
    val name: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}

class MusicLibrary(private val context: Context) {
    suspend fun read(limit: Int, query: String = ""): LibraryPage = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_QUEUE_TRACKS)
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val songs = mutableListOf<Song>()
        var more = false
        val term = query.trim().take(120).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val match = listOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
            .joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" }
        context.contentResolver.query(
            collection, columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0" + if (term.isEmpty()) "" else " AND ($match)",
            if (term.isEmpty()) null else Array(3) { "%$term%" },
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC, ${MediaStore.Audio.Media._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (songs.size == limit) { more = true; break }
                val id = cursor.getLong(0)
                val title = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(6) ?: "Untitled track"
                val artist = cursor.getString(2).displayOr("Unknown artist")
                val album = cursor.getString(3).displayOr("Unknown album")
                val albumId = cursor.getLong(4)
                songs += Song(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = boundedText(title), artist = boundedText(artist), album = boundedText(album),
                    artworkUri = if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null,
                    durationMs = cursor.getLong(5).coerceAtLeast(0),
                )
            }
        } ?: error("Android could not open the music library. Try refreshing.")
        LibraryPage(songs, more)
    }

    private fun String?.displayOr(fallback: String): String =
        this?.takeUnless { it.isBlank() || it == "<unknown>" } ?: fallback
}
