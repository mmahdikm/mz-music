package local.music.playback

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One in-flight write and one replaceable pending value; never an unbounded task backlog. */
class LatestWriter<T>(
    private val write: (T) -> Boolean,
    private val completed: (Boolean) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {
    private var pending: T? = null
    private var running = false
    private var closed = false
    private var generation = 0L

    @Synchronized fun submit(value: T) {
        check(!closed)
        pending = value
        generation++
        if (running) return
        running = true
        executor.execute {
            while (true) {
                val next = synchronized(this) {
                    val value = pending
                    pending = null
                    if (value == null) { running = false; return@execute }
                    value to generation
                }
                val success = runCatching { write(next.first) }.getOrDefault(false)
                dispatch {
                    synchronized(this) { if (generation == next.second) completed(success) }
                }
            }
        }
    }

    @Synchronized fun close() { closed = true; executor.shutdown() }
}
