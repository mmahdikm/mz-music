package local.music.collection

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.music.library.Song
import local.music.playback.boundedText
import local.music.playback.isLocalAudioUri
import local.music.playback.isLocalArtworkUri
import java.util.UUID

data class CollectionSnapshot(val playlists: List<PlaylistSummary>, val favorites: List<Song>,
    val selectedId: String?, val selectedSongs: List<Song>)

/** All database operations run off the main/audio threads. No listening events are collected. */
class CollectionRepository(context: Context) {
    private val database = database(context)
    companion object {
        @Volatile private var instance: CollectionDatabase? = null
        private fun database(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CollectionDatabase::class.java, "collections.db").build().also { instance = it }
        }
    }
    private val dao get() = database.collections()
    suspend fun snapshot(id: String?): CollectionSnapshot = withContext(Dispatchers.IO) {
        var result: CollectionSnapshot? = null
        database.runInTransaction {
            val playlists = dao.playlists()
            val selected = id?.takeIf { value -> playlists.any { it.id == value } }
            result = CollectionSnapshot(playlists, dao.favorites().map { Song(it.uri, it.title, it.artist, it.album, it.artworkUri, it.durationMs) },
                selected, selected?.let { dao.tracks(it).map { row -> row.song() } } ?: emptyList())
        }
        result!!
    }
    suspend fun create(name: String, songs: List<Song>): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        database.runInTransaction {
            val clean = CollectionRules.name(name)
            require(dao.playlists().size < CollectionRules.MAX_PLAYLISTS) { "You can save up to 100 playlists." }
            require(dao.playlists().none { CollectionRules.key(it.name) == CollectionRules.key(clean) }) { "That playlist name already exists." }
            dao.insertPlaylist(PlaylistEntity(id, clean, CollectionRules.key(clean)))
            replace(id, songs)
        }
        id
    }
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            require(dao.playlist(id) != null) { "This playlist no longer exists." }
            val clean = CollectionRules.name(name)
            require(dao.playlists().none { it.id != id && CollectionRules.key(it.name) == CollectionRules.key(clean) }) { "That playlist name already exists." }
            dao.updatePlaylist(PlaylistEntity(id, clean, CollectionRules.key(clean)))
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.deletePlaylist(id) }
    suspend fun add(id: String, song: Song) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            require(dao.playlist(id) != null) { "This playlist no longer exists." }
            val songs = dao.tracks(id).map { it.song() }
            require(songs.none { it.uri == song.uri }) { "This song is already in that playlist." }
            replace(id, songs + song)
        }
    }
    suspend fun remove(id: String, uri: String) = withContext(Dispatchers.IO) {
        database.runInTransaction { replace(id, dao.tracks(id).filterNot { it.uri == uri }.map { it.song() }) }
    }
    suspend fun move(id: String, uri: String, delta: Int) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val songs = dao.tracks(id).map { it.song() }
            val index = songs.indexOfFirst { it.uri == uri }
            replace(id, CollectionRules.moved(songs, index, index + delta))
        }
    }
    suspend fun favorite(song: Song) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            if (dao.isFavorite(song.uri)) dao.unfavorite(song.uri) else {
                require(dao.favoriteCount() < CollectionRules.MAX_SONGS) { "You can save up to 2,000 favorites." }
                val s = clean(song)
                dao.favorite(FavoriteEntity(s.uri, s.title, s.artist, s.album, s.artworkUri, s.durationMs))
            }
        }
    }
    private fun replace(id: String, songs: List<Song>) {
        require(songs.size <= CollectionRules.MAX_SONGS) { "A playlist can contain up to 2,000 songs." }
        val rows = songs.distinctBy { it.uri }.mapIndexed { index, song ->
            val s = clean(song)
            PlaylistTrack(id, s.uri, index, s.title, s.artist, s.album, s.artworkUri, s.durationMs)
        }
        dao.clearTracks(id)
        dao.insertTracks(rows)
    }
    private fun clean(s: Song): Song {
        require(isLocalAudioUri(s.uri)) { "This song is not an accessible device music entry." }
        return s.copy(title = boundedText(s.title), artist = boundedText(s.artist), album = boundedText(s.album),
            artworkUri = s.artworkUri?.takeIf(::isLocalArtworkUri), durationMs = s.durationMs.coerceAtLeast(0))
    }
    private fun PlaylistTrack.song() = Song(uri, title, artist, album, artworkUri, durationMs)
}
