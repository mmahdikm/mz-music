package local.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import local.music.library.Song

/** App-only paginated reads avoid publishing private queue contents to system controllers. */
object QueueSession {
    const val ACTION = "local.music.QUEUE"
    // Worst-case escaped metadata still stays below the Binder transaction budget.
    const val PAGE_SIZE = 25
    val command get() = SessionCommand(ACTION, Bundle.EMPTY)
}

data class QueueUiState(val songs: List<Song> = emptyList(), val revision: Long = -1,
    val loading: Boolean = false, val error: String? = null)

fun SavedTrack.song() = Song(uri, title, artist, album, artworkUri, 0)
fun Song.saved() = SavedTrack(uri, title, artist, album, artworkUri)

/** Insert a new item immediately after the current item in the existing shuffled traversal. */
fun orderWithNext(order: List<Int>, current: Int, inserted: Int): IntArray {
    require(order.sorted() == order.indices.toList() && current in order.indices && inserted in 0..order.size)
    val shifted = order.map { if (it >= inserted) it + 1 else it }.toMutableList()
    val shiftedCurrent = if (current >= inserted) current + 1 else current
    shifted.add(shifted.indexOf(shiftedCurrent) + 1, inserted)
    return shifted.toIntArray()
}
