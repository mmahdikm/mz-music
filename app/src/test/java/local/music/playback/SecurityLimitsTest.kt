package local.music.playback

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class SecurityLimitsTest {
    @Test fun exactUriGrammarRejectsAlternateRoutesAndTraversal() {
        for (uri in listOf("content://media/external/audio/../images/media/1",
            "content://media/external/audio/media/1?x=1", "content://media/external/audio/media/1#x",
            "content://media/external/audio/media/%31", "content://media/external/audio/albumart/1",
            "content://media/external/audio/media/9223372036854775808", "content://evil/external/audio/media/1")) {
            assertFalse(uri, isLocalAudioUri(uri))
        }
        assertTrue(isLocalAudioUri("content://media/external_primary/audio/media/1"))
        assertTrue(isLocalArtworkUri("content://media/external/audio/albumart/1"))
        assertFalse(isLocalArtworkUri("content://media/external/audio/media/1"))
    }
    @Test fun oversizedStoredQueuesAndJsonAreRejected() {
        val tracks = JSONArray()
        repeat(MAX_QUEUE_TRACKS + 1) { tracks.put(JSONObject().put("uri", "content://media/external/audio/media/$it")) }
        assertNull(QueueSnapshot.decode(JSONObject().put("version",1).put("tracks",tracks).toString()))
        assertNull(QueueSnapshot.decode(" ".repeat(MAX_QUEUE_JSON_CHARS + 1)))
    }
    @Test fun restoredMetadataHasBoundedLength() {
        val text = "x".repeat(10000)
        val saved = QueueSnapshot(listOf(SavedTrack("content://media/external/audio/media/1", text, text, text, null)),0,0)
        val restored = QueueSnapshot.decode(saved.encode())!!.tracks.single()
        assertEquals(MAX_METADATA_CHARS, restored.title.length)
        assertEquals(MAX_METADATA_CHARS, restored.artist.length)
        assertEquals(MAX_METADATA_CHARS, restored.album.length)
    }
}
