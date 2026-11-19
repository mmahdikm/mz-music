package local.music.sound

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.*
import org.junit.Test

class ReplayGainMetadataTest {
    @Test fun readsMp3Id3UserText() {
        val info = ReplayGainMetadata.read(Metadata(
            TextInformationFrame("TXXX", "replaygain_track_gain", listOf("-4.5 dB")),
            TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", listOf("-8 dB"))))
        assertEquals(-4.5, info.trackGainDb!!, 0.0)
        assertEquals(-8.0, info.albumGainDb!!, 0.0)
    }
    @Test fun readsFlacVorbisComments() {
        val info = ReplayGainMetadata.read(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "+3 dB")))
        assertEquals(3.0, info.trackGainDb!!, 0.0)
    }
    @Test fun readsM4aFreeformAndIgnoresUnrelatedDomains() {
        val info = ReplayGainMetadata.read(Metadata(
            InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-2 dB"),
            InternalFrame("unknown", "replaygain_album_gain", "5 dB")))
        assertEquals(-2.0, info.trackGainDb!!, 0.0)
        assertNull(info.albumGainDb)
    }
    @Test fun untaggedTrackDoesNotInheritPriorTrack() {
        ReplayGainMetadata.read(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-10 dB")))
        assertEquals(LoudnessInfo(), ReplayGainMetadata.read(null))
        assertEquals(LoudnessInfo(), ReplayGainMetadata.read(Metadata(VorbisComment("TITLE", "A song"))))
    }
}
