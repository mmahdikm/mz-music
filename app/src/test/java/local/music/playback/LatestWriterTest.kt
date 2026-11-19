package local.music.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class LatestWriterTest {
    @Test fun pendingWritesAreCoalescedAndCloseDrainsTheNewestValue() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val writer = LatestWriter<Int>(write = {
            if (it == 0) { entered.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)) }
            writes.add(it); true
        }, completed = { assertTrue(it); done.countDown() })
        writer.submit(0)
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        repeat(1000) { writer.submit(it + 1) }
        writer.close(); release.countDown()
        assertTrue(done.await(3,TimeUnit.SECONDS))
        assertEquals(listOf(0,1000),writes)
    }
    @Test fun failedWritesAreReportedAndRetryCanSucceed() {
        val failed = CountDownLatch(1); val saved = CountDownLatch(1)
        val writer = LatestWriter<Int>(write = { if (it == 0) error("disk full") else true },
            completed = { if (it) saved.countDown() else failed.countDown() })
        writer.submit(0); assertTrue(failed.await(3,TimeUnit.SECONDS))
        writer.submit(1); assertTrue(saved.await(3,TimeUnit.SECONDS))
        writer.close()
    }
    @Test fun staleMainThreadAcknowledgementsAreDiscarded() {
        val tasks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val written = CountDownLatch(2)
        val firstQueued = CountDownLatch(1); val bothQueued = CountDownLatch(2)
        val acknowledgements = mutableListOf<Boolean>()
        val writer = LatestWriter<Int>(write = { written.countDown(); it > 0 },
            completed = { acknowledgements.add(it) }, dispatch = { tasks.add(it); firstQueued.countDown(); bothQueued.countDown() })
        writer.submit(0)
        assertTrue(firstQueued.await(3,TimeUnit.SECONDS))
        writer.submit(1)
        assertTrue(written.await(3,TimeUnit.SECONDS))
        writer.close()
        assertTrue(bothQueued.await(3,TimeUnit.SECONDS))
        assertEquals(2,tasks.size)
        tasks.toList().forEach { it() }
        assertEquals(listOf(true),acknowledgements)
    }
}
