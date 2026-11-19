package local.music.sound

import android.os.Bundle
import androidx.media3.session.SessionCommand

object SoundSession {
    const val ACTION = "local.music.SOUND"
    val command get() = SessionCommand(ACTION, Bundle.EMPTY)
    fun bundle(document: SoundDocument, ready: Boolean, loudness: LoudnessInfo, rate: Int): Bundle = Bundle().apply {
        putString("soundDocument", document.encode())
        putBoolean("soundReady", ready)
        putInt("soundRate", rate)
        putDouble("soundHeadroom", EqCurve(document.settings.gainsDb, rate.takeIf { it > 0 } ?: 44100).headroomDb())
        putString("soundLeveling", loudness.status(document.settings))
    }
}

data class SoundUiState(
    val ready: Boolean = false,
    val document: SoundDocument = SoundDocument(),
    val sampleRate: Int = 0,
    val headroomDb: Double = 0.0,
    val levelingStatus: String = "Volume leveling off",
    val error: String? = null,
    val storageMessage: String? = null,
) {
    val summary get() = if (!document.settings.tuned) "Original · App effects bypassed"
        else "Tuned · ${document.presetName} · Limiter on"
    companion object {
        fun fromBundle(bundle: Bundle) = SoundUiState(
            bundle.getBoolean("soundReady"), SoundDocument.decode(bundle.getString("soundDocument")),
            bundle.getInt("soundRate"), bundle.getDouble("soundHeadroom"),
            bundle.getString("soundLeveling") ?: "Volume leveling off",
            bundle.getString("soundError"), bundle.getString("storageMessage"),
        )
    }
}
