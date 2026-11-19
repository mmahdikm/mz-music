package local.music.playback

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class QueueModesTest {
    private val track = SavedTrack("content://media/external/audio/media/1", "Title", "Artist", "Album", null)
    @Test fun modesRoundTripWithoutAutoplay() {
        val value = QueueSnapshot(listOf(track), 0, 1234, true, 2)
        assertEquals(value, QueueSnapshot.decode(value.encode()))
        assertFalse(JSONObject(value.encode()).has("playWhenReady"))
    }
    @Test fun legacyQueuesAndInvalidModesRestoreSafeDefaults() {
        val value = JSONObject(QueueSnapshot(listOf(track), 0, 0).encode())
        value.remove("shuffle"); value.remove("repeat")
        assertFalse(QueueSnapshot.decode(value.toString())!!.shuffle)
        assertEquals(0, QueueSnapshot.decode(value.toString())!!.repeat)
        value.put("repeat", 999)
        assertEquals(0, QueueSnapshot.decode(value.toString())!!.repeat)
    }
    @Test fun playNextIsActuallyNextInShuffledTraversal() {
        val updated = orderWithNext(listOf(2, 0, 3, 1), 0, 1)
        assertArrayEquals(intArrayOf(3, 0, 1, 4, 2), updated)
        assertEquals((0..4).toList(), updated.sorted())
    }
    @Test fun insertionAfterLastShuffledEntryStillWorks() {
        assertArrayEquals(intArrayOf(2, 0, 1, 3), orderWithNext(listOf(2, 0, 1), 1, 3))
        assertArrayEquals(intArrayOf(0, 1), orderWithNext(listOf(0), 0, 1))
    }
    @Test fun privateQueuePagesFitBelowHalfTheBinderBudgetEvenWithEscapedMetadata() {
        val hostile = "\u0000".repeat(MAX_METADATA_CHARS)
        val item = track.copy(title = hostile, artist = hostile, album = hostile)
        val json = QueueSnapshot(List(QueueSession.PAGE_SIZE) { item }, 0, 0).encode()
        assertTrue("UTF-16 JSON must fit with room for Bundle/Binder overhead", json.length * 2 < 512 * 1024)
    }
    @Test fun malformedShuffleOrdersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { orderWithNext(listOf(0, 0), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { orderWithNext(listOf(0), -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { orderWithNext(listOf(0), 0, 2) }
    }
}
