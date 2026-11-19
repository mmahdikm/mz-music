package local.music.playback

import org.junit.Assert.*
import org.junit.Test

class QueueSnapshotTest {
    private fun track(id: Int) = SavedTrack(
        "content://media/external/audio/media/$id", "Track $id 🎵", "Artist", "Album",
        "content://media/external/audio/albumart/7",
    )

    @Test fun roundTripPreservesQueueOrderMetadataAndPosition() {
        val snapshot = QueueSnapshot(listOf(track(9), track(3), track(7)), 1, 102_400)
        assertEquals(snapshot, QueueSnapshot.decode(snapshot.encode()))
        assertFalse(snapshot.encode().contains("playWhenReady"))
    }

    @Test fun invalidStorageAndFutureVersionsAreIgnored() {
        listOf(null, "", "broken", "{}", "{\"version\":2,\"tracks\":[]}").forEach {
            assertNull(QueueSnapshot.decode(it))
        }
        assertNull(QueueSnapshot.decode(QueueSnapshot(emptyList(), 0, 0).encode()))
    }

    @Test fun removingUnsafeItemBeforeCurrentTrackPreservesCurrentTrack() {
        val snapshot = QueueSnapshot(listOf(track(1).copy(uri = "https://example.com/a.mp3"), track(2), track(3)), 1, 55_000)
        val restored = QueueSnapshot.decode(snapshot.encode())!!
        assertEquals(listOf(track(2), track(3)), restored.tracks)
        assertEquals(0, restored.index)
        assertEquals(55_000L, restored.positionMs)
    }

    @Test fun removedCurrentItemMovesToNextAndResetsPosition() {
        val snapshot = QueueSnapshot(listOf(track(1), track(2).copy(uri = "file:///private/audio.mp3"), track(3)), 1, 55_000)
        val restored = QueueSnapshot.decode(snapshot.encode())!!
        assertEquals(track(3), restored.tracks[restored.index])
        assertEquals(0L, restored.positionMs)
    }

    @Test fun invalidIndexAndNegativePositionAreClamped() {
        val snapshot = QueueSnapshot(listOf(track(1), track(2)), 99, -50)
        val restored = QueueSnapshot.decode(snapshot.encode())!!
        assertEquals(1, restored.index)
        assertEquals(0L, restored.positionMs)
    }

    @Test fun onlyDeviceAudioUrisAreAllowed() {
        assertTrue(isLocalAudioUri("content://media/external/audio/media/42"))
        assertTrue(isLocalAudioUri("content://media/1234-5678/audio/media/42"))
        listOf("https://host/audio/test", "file:///audio/music", "content://other/audio/media/1",
            "content://media/external/video/media/1", "", "bad uri").forEach {
            assertFalse(it, isLocalAudioUri(it))
        }
    }

    @Test fun unsafeArtworkUriIsDropped() {
        val snapshot = QueueSnapshot(listOf(track(1).copy(artworkUri = "https://example.com/art.jpg")), 0, 0)
        assertNull(QueueSnapshot.decode(snapshot.encode())!!.tracks.first().artworkUri)
    }

    @Test fun noArtworkRoundTrips() {
        val snapshot = QueueSnapshot(listOf(track(1).copy(artworkUri = null)), 0, 0)
        assertEquals(snapshot, QueueSnapshot.decode(snapshot.encode()))
    }
}
