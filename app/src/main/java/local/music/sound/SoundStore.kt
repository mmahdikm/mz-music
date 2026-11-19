package local.music.sound

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import local.music.playback.LatestWriter

class SoundStore(context: Context, onSaved: (Boolean) -> Unit) {
    private val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val writer = LatestWriter<SoundDocument>(
        write = { document -> prefs.edit().putString("document", document.encode()).commit() },
        completed = onSaved, executor = worker, dispatch = { task -> main.post { task() } },
    )
    fun load(done: (SoundDocument) -> Unit) {
        worker.execute {
            val document = SoundDocument.decode(prefs.getString("document", null))
            main.post { done(document) }
        }
    }
    fun save(document: SoundDocument) {
        writer.submit(document)
    }
    fun close() { writer.close() }
}
