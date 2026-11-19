package local.music.sound

import kotlin.math.*

/** RBJ peaking filters; normalized coefficients and double-precision state. */
class EqCurve(val gains: List<Double>, val sampleRate: Int) {
    val coefficients = Array(10) { index ->
        val frequency = EQ_FREQUENCIES[index].toDouble()
        if (frequency >= sampleRate / 2.0 || gains[index] == 0.0) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        else {
            val a = 10.0.pow(gains[index] / 40)
            val w = 2 * PI * frequency / sampleRate
            val alpha = sin(w) / (2 * 1.4)
            val a0 = 1 + alpha / a
            doubleArrayOf((1 + alpha * a) / a0, -2 * cos(w) / a0,
                (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0)
        }
    }

    fun responseDb(frequency: Double): Double {
        val w = 2 * PI * frequency / sampleRate
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        var power = 1.0
        for (c in coefficients) {
            val nr = c[0] + c[1] * cw + c[2] * c2
            val ni = -c[1] * sw - c[2] * s2
            val dr = 1 + c[3] * cw + c[4] * c2
            val di = -c[3] * sw - c[4] * s2
            power *= (nr * nr + ni * ni) / (dr * dr + di * di)
        }
        return 10 * log10(power.coerceAtLeast(1e-30))
    }

    /** Dense logarithmic grid plus band centers; limiter handles residual transient peaks. */
    fun headroomDb(): Double {
        var peak = 0.0
        val high = sampleRate * .499
        for (i in 0..2048) peak = max(peak, responseDb(10.0 * (high / 10).pow(i / 2048.0)))
        for (f in EQ_FREQUENCIES) if (f < sampleRate / 2.0) peak = max(peak, responseDb(f.toDouble()))
        return -peak
    }
}

private class FilterBank(settings: SoundSettings, loudness: LoudnessInfo, sampleRate: Int, channels: Int) {
    val curve = EqCurve(settings.gainsDb, sampleRate)
    private val gain = 10.0.pow(((loudness.appliedGain(settings) ?: 0.0) +
        settings.preampDb + curve.headroomDb()) / 20)
    private val z1 = DoubleArray(10 * channels)
    private val z2 = DoubleArray(10 * channels)
    private val count = channels

    fun sample(input: Double, channel: Int): Double {
        var value = input * gain
        for (band in 0..9) {
            val c = curve.coefficients[band]
            if (c[0] == 1.0 && c[1] == 0.0) continue
            val i = band * count + channel
            val output = c[0] * value + z1[i]
            z1[i] = c[1] * value - c[3] * output + z2[i]
            z2[i] = c[2] * value - c[4] * output
            value = output
        }
        return if (value.isFinite()) value else 0.0
    }
}

/** Fixed-latency wet/dry paths. No allocations or disk access in either sample loop. */
class SoundDsp(val sampleRate: Int, val channels: Int, initial: SoundSettings, private val loudness: LoudnessInfo) {
    init { require(sampleRate in 8000..192000 && channels in 1..8) }
    val lookaheadFrames = max(1, (sampleRate * .005).roundToInt())
    private val capacity = lookaheadFrames + 1
    private val dry = DoubleArray(capacity * channels)
    private val wet = DoubleArray(capacity * channels)
    private val peaks = DoubleArray(capacity + 1)
    private val peakIds = LongArray(capacity + 1)
    private var peakHead = 0
    private var peakTail = 0
    private var written = 0L
    private var read = 0L
    private var limiterGain = 1.0
    private val release = exp(-1.0 / (sampleRate * .100))
    private val rampLength = max(1, (sampleRate * .020).roundToInt())
    private var settings = initial.validated()
    private var bank = FilterBank(settings, loudness, sampleRate, channels)
    private var nextBank: FilterBank? = null
    private var bankFade = 0
    private var requested = settings
    private var mix = if (settings.tuned) 1.0 else 0.0
    private var targetMix = mix
    val pendingFrames get() = (written - read).toInt()


    /** Called once per input buffer; new curves are prepared here, never per sample. */
    fun update(value: SoundSettings) {
        requested = value.validated()
        targetMix = if (requested.tuned) 1.0 else 0.0
        if (nextBank == null && (requested.gainsDb != settings.gainsDb ||
                requested.preampDb != settings.preampDb || requested.leveling != settings.leveling ||
                requested.limitLevelingBoost != settings.limitLevelingBoost)) {
            nextBank = FilterBank(requested, loudness, sampleRate, channels)
            bankFade = 0
            settings = requested
        }
    }

    fun process(input: ShortArray, sampleCount: Int, output: ShortArray): Int {
        require(sampleCount % channels == 0 && sampleCount <= input.size && output.size >= sampleCount)
        var produced = 0
        var offset = 0
        while (offset < sampleCount) {
            val slot = (written % capacity).toInt() * channels
            var peak = 0.0
            val incoming = nextBank
            val fade = if (incoming == null) 0.0 else (bankFade + 1).toDouble() / rampLength
            for (channel in 0 until channels) {
                val original = input[offset + channel] / 32768.0
                val old = bank.sample(original, channel)
                val filtered = if (incoming == null) old else old * (1 - fade) + incoming.sample(original, channel) * fade
                dry[slot + channel] = original
                wet[slot + channel] = filtered
                peak = max(peak, abs(filtered))
            }
            if (incoming != null && ++bankFade >= rampLength) { bank = incoming; nextBank = null }
            // Monotonic peak deque: amortized O(1) lookahead maximum.
            while (peakHead != peakTail && peakIds[peakHead] < read) peakHead = (peakHead + 1) % peaks.size
            while (peakHead != peakTail) {
                val last = (peakTail - 1 + peaks.size) % peaks.size
                if (peaks[last] > peak) break
                peakTail = last
            }
            peaks[peakTail] = peak; peakIds[peakTail] = written
            peakTail = (peakTail + 1) % peaks.size
            written++
            if (pendingFrames > lookaheadFrames) {
                emit(output, produced)
                produced += channels
            }
            offset += channels
        }
        return produced
    }

    fun drain(output: ShortArray): Int {
        require(output.size >= pendingFrames * channels)
        var produced = 0
        while (pendingFrames > 0) { emit(output, produced); produced += channels }
        return produced
    }

    private fun emit(output: ShortArray, offset: Int) {
        while (peakHead != peakTail && peakIds[peakHead] < read) peakHead = (peakHead + 1) % peaks.size
        val peak = if (peakHead == peakTail) 0.0 else peaks[peakHead]
        val desired = if (peak > CEILING) CEILING / peak else 1.0
        limiterGain = if (desired < limiterGain) desired else desired + (limiterGain - desired) * release
        mix = when {
            mix < targetMix -> min(targetMix, mix + 1.0 / rampLength)
            mix > targetMix -> max(targetMix, mix - 1.0 / rampLength)
            else -> mix
        }
        val slot = (read % capacity).toInt() * channels
        for (channel in 0 until channels) {
            val processed = (wet[slot + channel] * limiterGain).coerceIn(-CEILING, CEILING)
            val value = dry[slot + channel] * (1 - mix) + processed * mix
            var quantized = (value * 32768.0).roundToInt().coerceIn(-32768, 32767)
            if (mix == 1.0) quantized = quantized.coerceIn(-CEILING_PCM, CEILING_PCM)
            output[offset + channel] = quantized.toShort()
        }
        read++
    }

    companion object {
        val CEILING = 10.0.pow(-1.0 / 20)
        val CEILING_PCM = floor(CEILING * 32768).toInt()
    }
}
