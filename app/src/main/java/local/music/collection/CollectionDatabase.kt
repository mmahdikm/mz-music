package local.music.collection

import androidx.room.*

@Entity(tableName = "playlists", indices = [Index(value = ["nameKey"], unique = true)])
data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val nameKey: String)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "uri"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"],
        childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["playlistId", "position"])])
data class PlaylistTrack(val playlistId: String, val uri: String, val position: Int,
    val title: String, val artist: String, val album: String, val artworkUri: String?, val durationMs: Long)

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val uri: String, val title: String, val artist: String,
    val album: String, val artworkUri: String?, val durationMs: Long)

data class PlaylistSummary(val id: String, val name: String, val count: Int)

@Dao
interface CollectionDao {
    @Query("SELECT p.id, p.name, COUNT(t.uri) AS count FROM playlists p LEFT JOIN playlist_tracks t ON p.id = t.playlistId GROUP BY p.id ORDER BY p.nameKey, p.id")
    fun playlists(): List<PlaylistSummary>
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    fun tracks(id: String): List<PlaylistTrack>
    @Query("SELECT * FROM favorites ORDER BY title COLLATE NOCASE, uri")
    fun favorites(): List<FavoriteEntity>
    @Query("SELECT * FROM playlists WHERE id = :id") fun playlist(id: String): PlaylistEntity?
    @Insert fun insertPlaylist(value: PlaylistEntity)
    @Update fun updatePlaylist(value: PlaylistEntity)
    @Query("DELETE FROM playlists WHERE id = :id") fun deletePlaylist(id: String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id") fun clearTracks(id: String)
    @Insert fun insertTracks(values: List<PlaylistTrack>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun favorite(value: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE uri = :uri") fun unfavorite(uri: String)
    @Query("SELECT COUNT(*) FROM favorites") fun favoriteCount(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uri = :uri)") fun isFavorite(uri: String): Boolean
}

@Database(entities = [PlaylistEntity::class, PlaylistTrack::class, FavoriteEntity::class], version = 1, exportSchema = true)
abstract class CollectionDatabase : RoomDatabase() { abstract fun collections(): CollectionDao }
