package local.music.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Queue content changes independently of lightweight position checkpoints. Legacy v1 JSON still loads. */
class PlaybackStore(context: Context, onSaved: (Boolean) -> Unit) {
    private val legacy = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val content = context.getSharedPreferences("playback_content", Context.MODE_PRIVATE)
    private val position = context.getSharedPreferences("playback_position", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var storedTracks: List<SavedTrack>? = null
    private var generation: String? = null
    private val writer = LatestWriter<QueueSnapshot>(write = ::write,
        completed = onSaved, executor = worker, dispatch = { task -> main.post { task() } })

    fun load(onLoaded: (QueueSnapshot?) -> Unit) {
        worker.execute {
            val raw = content.getString("queue", null) ?: legacy.getString("queue", null)
            val saved = QueueSnapshot.decode(raw)
            val snapshot = if (saved == null) null else runCatching {
                val token = JSONObject(raw!!).optString("generation")
                if (token.isNotEmpty()) { generation = token; storedTracks = saved.tracks }
                if (token.isNotEmpty() && token == position.getString("generation", null)) saved.copy(
                    index = position.getInt("index", saved.index).coerceIn(saved.tracks.indices),
                    positionMs = position.getLong("position", saved.positionMs).coerceAtLeast(0),
                    shuffle = position.getBoolean("shuffle", saved.shuffle),
                    repeat = position.getInt("repeat", saved.repeat).takeIf { it in 0..2 } ?: 0,
                ) else saved
            }.getOrDefault(saved)
            main.post { onLoaded(snapshot) }
        }
    }

    private fun write(snapshot: QueueSnapshot): Boolean {
        if (snapshot.tracks.isEmpty()) {
            storedTracks = null; generation = null
            // Evaluate all removals even if one fails, and report failure to the UI.
            val old = legacy.edit().clear().commit()
            val tracks = content.edit().clear().commit()
            val checkpoint = position.edit().clear().commit()
            return old && tracks && checkpoint
        }
        if (storedTracks != snapshot.tracks || generation == null) {
            val token = UUID.randomUUID().toString()
            val json = JSONObject(snapshot.encode()).put("generation", token).toString()
            if (!content.edit().putString("queue", json).commit()) return false
            storedTracks = snapshot.tracks
            generation = token
        }
        // A checkpoint from a different queue generation is never restored.
        val checkpoint = position.edit().putString("generation", generation)
            .putInt("index", snapshot.index).putLong("position", snapshot.positionMs)
            .putBoolean("shuffle", snapshot.shuffle).putInt("repeat", snapshot.repeat).commit()
        val old = if (legacy.contains("queue")) legacy.edit().clear().commit() else true
        return checkpoint && old
    }

    fun save(snapshot: QueueSnapshot) { writer.submit(snapshot) }
    fun close() { writer.close() }
}
