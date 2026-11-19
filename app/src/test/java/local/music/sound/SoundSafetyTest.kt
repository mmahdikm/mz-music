package local.music.sound

import org.junit.Assert.*
import org.junit.Test

class SoundSafetyTest {
    @Test fun defaultCapPreservesNegativeAndAlbumGainsButLimitsPositiveBoosts() {
        val tags = LoudnessInfo(trackGainDb=30.0,albumGainDb=-9.0)
        assertEquals(6.0,tags.appliedGain(SoundSettings(leveling=Leveling.TRACK))!!,0.0)
        assertEquals(-9.0,tags.appliedGain(SoundSettings(leveling=Leveling.ALBUM))!!,0.0)
        assertEquals(30.0,tags.appliedGain(SoundSettings(leveling=Leveling.TRACK,limitLevelingBoost=false))!!,0.0)
        assertTrue(tags.status(SoundSettings(leveling=Leveling.TRACK)).contains("boost limited"))
    }
    @Test fun capIsAppliedInAudioAndCanBeChangedSmoothly() {
        val settings = SoundSettings(tuned=true,leveling=Leveling.TRACK)
        val engine=SoundDsp(48000,1,settings,LoudnessInfo(trackGainDb=30.0))
        val input=ShortArray(2400) { 100 }; val output=ShortArray(2400)
        var count=engine.process(input,input.size,output)
        assertEquals(200,output[count-1].toInt())
        engine.update(settings.copy(limitLevelingBoost=false))
        count=engine.process(input,input.size,output)
        assertEquals(3162,output[count-1].toInt())
        for (i in 1 until count) assertTrue(kotlin.math.abs(output[i]-output[i-1]) < 10)
    }
    @Test fun privacyAndCapPreferencesRoundTripAndOldDocumentsGetSafeBoostDefault() {
        val saved=SoundDocument(SoundSettings(privateListening=true,limitLevelingBoost=false))
        assertEquals(saved,SoundDocument.decode(saved.encode()))
        val old=SoundDocument.decode("""{"version":1,"settings":{"tuned":true,"leveling":"TRACK"},"presets":[]}""")
        assertTrue(old.settings.limitLevelingBoost)
    }
    @Test fun unsupportedFormatsFailBeforeLargeDspAllocation() {
        for ((rate,channels) in listOf(Int.MAX_VALUE to 2,48000 to Int.MAX_VALUE, 7999 to 2,192001 to 2)) {
            try { SoundDsp(rate,channels,SoundSettings(),LoudnessInfo()); fail("format accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
