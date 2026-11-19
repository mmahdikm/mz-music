package local.music.sound

import org.junit.Assert.*
import org.junit.Test

class SoundSettingsTest {
    @Test fun defaultsAreOriginalFlatAndNoLeveling() {
        val document = SoundDocument()
        assertFalse(document.settings.tuned)
        assertEquals(Leveling.OFF, document.settings.leveling)
        assertEquals(listOf("Flat", "Headphones", "Car"), document.presets.map { it.name })
        assertTrue(document.presets.all { it.gainsDb.all { gain -> gain == 0.0 } })
    }

    @Test fun settingsPresetsAndUnsavedEditsRoundTrip() {
        val settings = SoundSettings(true, List(10) { it / 2.0 }, -3.0, Leveling.ALBUM, "headphones")
        val document = SoundDocument(settings, SoundDocument.defaults() + SoundPreset("custom", "My pair", List(10) { -1.5 }, -1.0))
        assertEquals(document, SoundDocument.decode(document.encode()))
        assertEquals("Headphones (edited)", SoundDocument.decode(document.encode()).presetName)
    }

    @Test fun badVersionsAndCorruptJsonUseDefaults() {
        assertEquals(SoundDocument(), SoundDocument.decode(null))
        for (text in listOf("", "bad", "{\"version\":99}")) {
            val restored = SoundDocument.decode(text)
            assertTrue(restored.settings.privateListening)
            assertFalse(restored.settings.tuned)
            assertTrue(restored.settings.limitLevelingBoost)
        }
    }

    @Test fun malformedValuesAreFiniteBoundedAndRounded() {
        val settings = SoundSettings(gainsDb = listOf(Double.NaN, 500.0, -500.0, 1.2), preampDb = Double.POSITIVE_INFINITY).validated()
        assertEquals(listOf(0.0, 12.0, -12.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), settings.gainsDb)
        assertEquals(0.0, settings.preampDb, 0.0)
    }

    @Test fun flatPresetCannotBeOverwrittenByStoredData() {
        val document = SoundDocument(presets = listOf(SoundPreset("flat", "Hacked", List(10) { 12.0 }, -10.0)))
        assertEquals(SoundDocument.defaults(), SoundDocument.decode(document.encode()).presets)
    }

    @Test fun deletedPresetKeepsCurrentEdits() {
        val settings = SoundSettings(gainsDb = List(10) { 2.0 }, presetId = "missing")
        val restored = SoundDocument.decode(SoundDocument(settings).encode())
        assertEquals(settings.gainsDb, restored.settings.gainsDb)
        assertNull(restored.settings.presetId)
        assertEquals("Custom (unsaved)", restored.presetName)
    }

    @Test fun replayGainParsingHandlesCaseUnitsAndMissingPeaks() {
        val info = LoudnessInfo.fromTags(mapOf("replaygain_track_gain" to " -7.42 dB ",
            "REPLAYGAIN_ALBUM_GAIN" to "+2.5 DB", "REPLAYGAIN_TRACK_PEAK" to "0.987"))
        assertEquals(-7.42, info.trackGainDb!!, 0.0)
        assertEquals(2.5, info.albumGainDb!!, 0.0)
        assertEquals(.987, info.trackPeak!!, 0.0)
        assertNull(info.albumPeak)
    }

    @Test fun invalidReplayGainNeverMakesAnExtremeGain() {
        for (value in listOf("NaN", "Infinity", "garbage", "1000 dB", "-1000", "")) {
            assertNull(LoudnessInfo.fromTags(mapOf("REPLAYGAIN_TRACK_GAIN" to value)).trackGainDb)
        }
        assertNull(LoudnessInfo.fromTags(mapOf("REPLAYGAIN_TRACK_PEAK" to "-1")).trackPeak)
        assertTrue(LoudnessInfo().status(Leveling.ALBUM).contains("No album leveling data"))
    }
}
