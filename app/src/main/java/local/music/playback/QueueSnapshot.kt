package local.music.playback

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Persistence contains no play flag: process recreation always restores paused. */
data class SavedTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
)

data class QueueSnapshot(val tracks: List<SavedTrack>, val index: Int, val positionMs: Long, val shuffle: Boolean = false, val repeat: Int = 0) {
    fun encode(): String = JSONObject().apply {
        put("version", 1)
        put("index", index)
        put("shuffle", shuffle); put("repeat", repeat.coerceIn(0, 2))
        put("positionMs", positionMs.coerceAtLeast(0))
        put("tracks", JSONArray().apply {
            tracks.forEach { track ->
                put(JSONObject().apply {
                    put("uri", track.uri); put("title", track.title)
                    put("artist", track.artist); put("album", track.album)
                    put("artworkUri", track.artworkUri)
                })
            }
        })
    }.toString()

    companion object {
        fun decode(json: String?): QueueSnapshot? = try {
            require(json != null && json.length <= MAX_QUEUE_JSON_CHARS)
            val root = JSONObject(json)
            if (root.optInt("version") != 1) null else {
                val values = root.getJSONArray("tracks")
                require(values.length() <= MAX_QUEUE_TRACKS)
                val oldIndex = root.optInt("index", 0).coerceIn(0, (values.length() - 1).coerceAtLeast(0))
                val retained = (0 until values.length()).mapNotNull { originalIndex ->
                    val item = values.optJSONObject(originalIndex) ?: return@mapNotNull null
                    val uri = item.optString("uri")
                    if (!isLocalAudioUri(uri)) return@mapNotNull null
                    originalIndex to SavedTrack(
                        uri, boundedText(item.optString("title", "Untitled track")),
                        boundedText(item.optString("artist", "Unknown artist")),
                        boundedText(item.optString("album", "Unknown album")),
                        item.optString("artworkUri").takeIf(::isLocalArtworkUri),
                    )
                }
                if (retained.isEmpty()) null else {
                    val exactIndex = retained.indexOfFirst { it.first == oldIndex }
                    val newIndex = if (exactIndex >= 0) exactIndex else
                        retained.indexOfFirst { it.first > oldIndex }.takeIf { it >= 0 } ?: retained.lastIndex
                    QueueSnapshot(
                        retained.map { it.second }, newIndex,
                        if (exactIndex >= 0) root.optLong("positionMs", 0).coerceAtLeast(0) else 0,
                        root.optBoolean("shuffle", false), root.optInt("repeat", 0).takeIf { it in 0..2 } ?: 0,
                    )
                }
            }
        } catch (_: Exception) { null }
    }
}

/** The offline player never restores network URLs or arbitrary content providers. */
fun isLocalAudioUri(value: String) = isMediaUri(value, "media")
fun isLocalArtworkUri(value: String) = isMediaUri(value, "albumart")

private fun isMediaUri(value: String, kind: String): Boolean = try {
    require(value.length <= 256)
    val uri = URI(value)
    uri.scheme == "content" && uri.authority == "media" &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == uri.path &&
        Regex("/[A-Za-z0-9_-]+/audio/$kind/[0-9]{1,19}").matches(uri.path.orEmpty()) &&
        uri.path.substringAfterLast('/').toLongOrNull()?.let { it >= 0 } == true
} catch (_: Exception) { false }
