package local.music.sound

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.round

val EQ_FREQUENCIES = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
enum class Leveling { OFF, TRACK, ALBUM }

data class SoundSettings(
    val tuned: Boolean = false,
    val gainsDb: List<Double> = List(10) { 0.0 },
    val preampDb: Double = 0.0,
    val leveling: Leveling = Leveling.OFF,
    val presetId: String? = "flat",
    val limitLevelingBoost: Boolean = true,
    val privateListening: Boolean = false,
) {
    fun validated() = copy(
        gainsDb = List(10) { step(gainsDb.getOrElse(it) { 0.0 }, -12.0, 12.0) },
        preampDb = step(preampDb, -12.0, 0.0),
    )
}

private fun step(value: Double, min: Double, max: Double): Double =
    if (value.isFinite()) round(value.coerceIn(min, max) * 2) / 2 else 0.0

data class SoundPreset(val id: String, val name: String, val gainsDb: List<Double>, val preampDb: Double) {
    val builtIn get() = id in listOf("flat", "headphones", "car")
    fun matches(settings: SoundSettings) = gainsDb == settings.gainsDb && preampDb == settings.preampDb
}

data class SoundDocument(
    val settings: SoundSettings = SoundSettings(),
    val presets: List<SoundPreset> = defaults(),
) {
    val presetName: String get() {
        val preset = presets.find { it.id == settings.presetId }
        return if (preset == null) "Custom (unsaved)" else
            preset.name + if (preset.matches(settings)) "" else " (edited)"
    }

    fun encode(): String = JSONObject().apply {
        put("version", 1)
        put("settings", encodeSettings(settings))
        put("presets", JSONArray().apply { presets.forEach { preset ->
            put(JSONObject().apply {
                put("id", preset.id); put("name", preset.name)
                put("gains", JSONArray(preset.gainsDb)); put("preamp", preset.preampDb)
            })
        } })
    }.toString()

    companion object {
        fun defaults() = listOf("flat" to "Flat", "headphones" to "Headphones", "car" to "Car")
            .map { SoundPreset(it.first, it.second, List(10) { 0.0 }, 0.0) }

        fun decode(value: String?): SoundDocument = runCatching {
            require(value != null && value.length <= 128 * 1024)
            val root = JSONObject(value ?: "")
            require(root.getInt("version") == 1)
            val obj = root.getJSONObject("settings")
            val settings = SoundSettings(
                obj.optBoolean("tuned"), readGains(obj.optJSONArray("gains")),
                obj.optDouble("preamp", 0.0),
                runCatching { Leveling.valueOf(obj.optString("leveling")) }.getOrDefault(Leveling.OFF),
                obj.optString("preset").takeIf { it.isNotBlank() },
                obj.optBoolean("limitBoost", true), obj.optBoolean("private", false),
            ).validated()
            val saved = root.optJSONArray("presets") ?: JSONArray()
            val presets = (0 until minOf(saved.length(), 50)).mapNotNull { index ->
                val item = saved.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").take(80)
                val name = item.optString("name").trim().take(40)
                if (id.isBlank() || name.isBlank() || id == "flat") null else {
                    val clean = SoundSettings(gainsDb = readGains(item.optJSONArray("gains")),
                        preampDb = item.optDouble("preamp", 0.0)).validated()
                    SoundPreset(id, name, clean.gainsDb, clean.preampDb)
                }
            }.distinctBy { it.id }
            val combined = defaults().map { builtIn ->
                presets.find { it.id == builtIn.id }?.copy(name = builtIn.name) ?: builtIn
            } + presets.filter { !it.builtIn }
            SoundDocument(settings.copy(presetId = settings.presetId?.takeIf { id -> combined.any { it.id == id } }), combined)
        }.getOrDefault(if (value == null) SoundDocument() else SoundDocument(SoundSettings(privateListening = true)))

        private fun readGains(array: JSONArray?): List<Double> = List(10) { array?.optDouble(it, 0.0) ?: 0.0 }
        private fun encodeSettings(settings: SoundSettings) = JSONObject().apply {
            put("tuned", settings.tuned); put("gains", JSONArray(settings.gainsDb))
            put("preamp", settings.preampDb); put("leveling", settings.leveling.name)
            put("preset", settings.presetId ?: "")
            put("limitBoost", settings.limitLevelingBoost); put("private", settings.privateListening)
        }
    }
}

data class LoudnessInfo(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
) {
    fun gain(mode: Leveling): Double? = when (mode) {
        Leveling.OFF -> null
        Leveling.TRACK -> trackGainDb
        Leveling.ALBUM -> albumGainDb
    }

    fun status(mode: Leveling): String = when {
        mode == Leveling.OFF -> "Volume leveling off"
        gain(mode) == null -> "No ${mode.name.lowercase(Locale.ROOT)} leveling data · Original level"
        else -> "${mode.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} gain ${db(gain(mode)!!)} dB"
    }

    fun appliedGain(settings: SoundSettings): Double? = gain(settings.leveling)?.let {
        if (settings.limitLevelingBoost) minOf(it, 6.0) else it
    }

    fun status(settings: SoundSettings): String {
        val original = gain(settings.leveling)
        val applied = appliedGain(settings)
        return if (original != null && applied != original)
            "${settings.leveling.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} gain ${db(applied!!)} dB · Tag ${db(original)} dB; boost limited"
        else status(settings.leveling)
    }

    companion object {
        fun fromTags(tags: Map<String, String>): LoudnessInfo {
            val values = tags.mapKeys { it.key.trim().uppercase(Locale.ROOT) }
            fun gain(key: String): Double? = values[key]?.trim()?.replace(Regex("(?i)\\s*dB$"), "")
                ?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..30.0 }
            fun peak(key: String): Double? = values[key]?.trim()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0 && it <= 64 }
            return LoudnessInfo(gain("REPLAYGAIN_TRACK_GAIN"), gain("REPLAYGAIN_ALBUM_GAIN"),
                peak("REPLAYGAIN_TRACK_PEAK"), peak("REPLAYGAIN_ALBUM_PEAK"))
        }
    }
}

fun db(value: Double): String = String.format(Locale.ROOT, "%+.1f", value)
