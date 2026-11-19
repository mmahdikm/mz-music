package local.music.sound

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class SoundDspTest {
    private fun render(input: ShortArray, settings: SoundSettings, rate: Int = 48000, channels: Int = 2,
        loudness: LoudnessInfo = LoudnessInfo(), chunkFrames: Int = 137): ShortArray {
        val engine = SoundDsp(rate, channels, settings, loudness)
        val output = ArrayList<Short>()
        var offset = 0
        while (offset < input.size) {
            val count = min(chunkFrames * channels, input.size - offset)
            val block = input.copyOfRange(offset, offset + count)
            val buffer = ShortArray(count)
            engine.update(settings)
            val length = engine.process(block, count, buffer)
            repeat(length) { output.add(buffer[it]) }
            offset += count
        }
        val tail = ShortArray(engine.pendingFrames * channels)
        val length = engine.drain(tail)
        repeat(length) { output.add(tail[it]) }
        assertEquals(0, engine.pendingFrames)
        return output.toShortArray()
    }

    @Test fun originalIsSampleExactForAllChannelsRatesAndExtremeSettings() {
        for (rate in listOf(8000, 44100, 48000, 96000)) for (channels in listOf(1, 2, 6)) {
            val input = ShortArray(1403 * channels) { Random(it).nextInt(-32768, 32768).toShort() }
            val settings = SoundSettings(gainsDb = List(10) { 12.0 }, leveling = Leveling.TRACK)
            assertArrayEquals(input, render(input, settings, rate, channels, LoudnessInfo(trackGainDb = 30.0)))
        }
    }

    @Test fun quietFlatTunedIsSampleExact() {
        val input = ShortArray(2000) { (sin(it * .05) * 10000).toInt().toShort() }
        assertArrayEquals(input, render(input, SoundSettings(tuned = true)))
    }

    @Test fun limiterNeverExceedsCeilingWithExtremeGainAndTransientPeaks() {
        val input = ShortArray(12000) { index ->
            when (index % 5) { 0 -> 32767; 1 -> -32768; else -> (sin(index * .01) * 32000).toInt() }.toShort()
        }
        val settings = SoundSettings(tuned = true, gainsDb = List(10) { 12.0 }, leveling = Leveling.TRACK)
        val output = render(input, settings, loudness = LoudnessInfo(trackGainDb = 30.0), chunkFrames = 1)
        assertEquals(input.size, output.size)
        assertTrue(output.all { abs(it.toInt()) <= SoundDsp.CEILING_PCM })
        assertTrue(output.any { abs(it.toInt()) > 1000 })
    }

    @Test fun decreasingPeakDequeDoesNotOverflowOrLoseProtection() {
        val input = ShortArray(20000) { (32767 - (it / 2 % 10000) * 2).toShort() }
        val output = render(input, SoundSettings(tuned = true, leveling = Leveling.TRACK),
            loudness = LoudnessInfo(trackGainDb = 20.0))
        assertTrue(output.all { abs(it.toInt()) <= SoundDsp.CEILING_PCM })
    }

    @Test fun limiterPreservesStereoRatio() {
        val input = ShortArray(4000) { if (it % 2 == 0) 30000 else 15000 }
        val output = render(input, SoundSettings(tuned = true, leveling = Leveling.TRACK),
            loudness = LoudnessInfo(trackGainDb = 12.0))
        for (frame in output.indices step 2) assertEquals(output[frame] / 2.0, output[frame + 1].toDouble(), 1.0)
    }

    @Test fun filtersDoNotLeakBetweenChannels() {
        val input = ShortArray(8000) { if (it % 2 == 0) (sin(it * .07) * 10000).toInt().toShort() else 0 }
        val output = render(input, SoundSettings(tuned = true, gainsDb = List(10) { if (it == 5) 9.0 else 0.0 }))
        assertTrue(output.filterIndexed { index, _ -> index % 2 == 1 }.all { it.toInt() == 0 })
    }

    @Test fun eqHasExpectedCenterGainAndDisabledNyquistBands() {
        val curve = EqCurve(List(10) { if (it == 5) 6.0 else 0.0 }, 48000)
        assertEquals(6.0, curve.responseDb(1000.0), .0001)
        assertEquals(-6.0, curve.headroomDb(), .002)
        val disabled = EqCurve(List(10) { if (it >= 7) 12.0 else 0.0 }, 8000)
        assertEquals(0.0, disabled.responseDb(1000.0), .0001)
        assertEquals(0.0, disabled.headroomDb(), .0001)
    }

    @Test fun headroomAccountsForCombinedFilters() {
        val curve = EqCurve(List(10) { 12.0 }, 48000)
        val headroom = curve.headroomDb()
        assertTrue(headroom < -12.0)
        for (f in 20..23990 step 13) assertTrue(curve.responseDb(f.toDouble()) + headroom < .02)
    }

    @Test fun trackAlbumAndMissingTagsUseCorrectGain() {
        val input = ShortArray(3000) { 10000 }
        val info = LoudnessInfo(trackGainDb = -6.0, albumGainDb = -12.0)
        val track = render(input, SoundSettings(tuned = true, leveling = Leveling.TRACK), loudness = info)
        val album = render(input, SoundSettings(tuned = true, leveling = Leveling.ALBUM), loudness = info)
        assertEquals(5012.0, track.last().toDouble(), 1.0)
        assertEquals(2512.0, album.last().toDouble(), 1.0)
        assertArrayEquals(input, render(input, SoundSettings(tuned = true, leveling = Leveling.ALBUM),
            loudness = LoudnessInfo(trackGainDb = -6.0)))
        assertArrayEquals(input, render(input, SoundSettings(tuned = true, leveling = Leveling.TRACK)))
    }

    @Test fun shortStreamsAndDrainingPreserveExactFrameCount() {
        for (length in listOf(0, 1, 5, 239, 240, 241, 1500)) {
            val input = ShortArray(length) { 1000 }
            assertArrayEquals(input, render(input, SoundSettings(), channels = 1, chunkFrames = 1))
        }
    }

    @Test fun modeAndGainChangesRampWithoutHardSteps() {
        val engine = SoundDsp(48000, 1, SoundSettings(), LoudnessInfo())
        val input = ShortArray(2000) { 10000 }
        val output = ShortArray(2000)
        engine.process(input, input.size, output)
        engine.update(SoundSettings(tuned = true, preampDb = -12.0))
        val length = engine.process(input, input.size, output)
        for (i in 1 until length) assertTrue("step ${output[i] - output[i - 1]}", abs(output[i] - output[i - 1]) < 25)
        assertEquals(2512.0, output[length - 1].toDouble(), 2.0)
        engine.update(SoundSettings(tuned = false, preampDb = -12.0))
        engine.process(input, input.size, output)
        assertEquals(10000, output.last().toInt())
    }

    @Test fun newTrackEngineDoesNotRetainGainOrFilterHistory() {
        val input = ShortArray(1000) { 10000 }
        render(input, SoundSettings(tuned = true, leveling = Leveling.TRACK), loudness = LoudnessInfo(trackGainDb = 12.0))
        assertArrayEquals(input, render(input, SoundSettings(tuned = true, leveling = Leveling.TRACK)))
        assertTrue(render(ShortArray(1000), SoundSettings(tuned = true)).all { it.toInt() == 0 })
    }
}
