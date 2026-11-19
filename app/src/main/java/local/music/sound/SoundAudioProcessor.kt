package local.music.sound

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SoundBridge {
    @Volatile var settings = SoundSettings()
}

@UnstableApi
object ReplayGainMetadata {
    fun read(metadata: Metadata?): LoudnessInfo {
        val tags = mutableMapOf<String, String>()
        if (metadata != null) for (index in 0 until minOf(metadata.length(), 256)) {
            when (val entry = metadata[index]) {
                is TextInformationFrame -> if (entry.id == "TXXX" || entry.id == "TXX") {
                    entry.description?.takeIf { it.length <= 64 }?.let {
                        tags[it] = entry.values.firstOrNull().orEmpty().takeIf { value -> value.length <= 128 }.orEmpty()
                    }
                }
                is VorbisComment -> if (entry.key.length <= 64) tags[entry.key] = entry.value.takeIf { it.length <= 128 }.orEmpty()
                is InternalFrame -> if (entry.domain.equals("com.apple.iTunes", true) && entry.description.length <= 64)
                    tags[entry.description] = entry.text.takeIf { it.length <= 128 }.orEmpty()
            }
        }
        return LoudnessInfo.fromTags(tags)
    }
}

@UnstableApi
class SoundAudioProcessor(
    private val bridge: SoundBridge,
    private val report: (String, LoudnessInfo, Int) -> Unit,
) : BaseAudioProcessor() {
    var pendingLoudness = LoudnessInfo()
    var pendingMediaId = ""
    private var engine: SoundDsp? = null
    private var input = ShortArray(0)
    private var output = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 ||
            inputAudioFormat.sampleRate !in 8000..192000) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) return
        engine = SoundDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, bridge.settings, pendingLoudness)
        report(pendingMediaId, pendingLoudness, inputAudioFormat.sampleRate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val dsp = engine ?: return
        val count = inputBuffer.remaining() / 2
        ensureCapacity(count)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) input[i] = inputBuffer.short
        dsp.update(bridge.settings)
        write(dsp.process(input, count, output))
    }

    override fun onQueueEndOfStream() {
        val dsp = engine ?: return
        ensureCapacity(dsp.pendingFrames * dsp.channels)
        write(dsp.drain(output))
    }

    override fun onReset() { engine = null; input = ShortArray(0); output = ShortArray(0) }

    private fun ensureCapacity(count: Int) {
        if (input.size < count) input = ShortArray(count)
        if (output.size < count) output = ShortArray(count)
    }

    private fun write(count: Int) {
        val buffer = replaceOutputBuffer(count * 2)
        for (i in 0 until count) buffer.putShort(output[i])
        buffer.flip()
    }
}

@UnstableApi
class SoundRenderersFactory(
    context: Context,
    private val bridge: SoundBridge,
    private val report: (String, LoudnessInfo, Int) -> Unit,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink {
        val processor = SoundAudioProcessor(bridge, report)
        val sink = DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
            .setAudioProcessors(arrayOf(processor)).build()
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean =
                format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED

            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED

            override fun setOffloadMode(offloadMode: Int) { super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED) }

            override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
                // Stage metadata with the sink's pending format. It becomes live only when
                // Media3 drains the old track and flushes processors for the new one.
                processor.pendingLoudness = ReplayGainMetadata.read(audioSinkConfig.format.metadata)
                processor.pendingMediaId = runCatching {
                    val period = Timeline.Period()
                    val window = Timeline.Window()
                    val uid = audioSinkConfig.mediaPeriodId?.periodUid ?: return@runCatching ""
                    audioSinkConfig.timeline.getPeriodByUid(uid, period)
                    audioSinkConfig.timeline.getWindow(period.windowIndex, window).mediaItem.mediaId
                }.getOrDefault("")
                super.configure(audioSinkConfig)
            }
        }
    }
}
