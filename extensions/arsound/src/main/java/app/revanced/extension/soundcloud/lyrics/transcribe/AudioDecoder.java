package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes an audio file with Android's own decoders (MediaExtractor and MediaCodec) to what Whisper takes: mono
 * floats at 16 kHz. The whole song is held in memory: ten minutes are 38 MB.
 * <p>
 * Called by {@link TranscriptionJob}; the file comes from {@link TrackAudio}.
 */
final class AudioDecoder {
    private static final long TIMEOUT_US = 10_000;

    private AudioDecoder() {
    }

    /**
     * @param maxMs The decoding stops after this much sound.
     * @throws IOException If the file has no audio or the phone cannot decode it.
     */
    static float[] decode(File file, long maxMs, ResumableDownload.Cancel cancel) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getPath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount() && track < 0; i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                }
            }
            if (track < 0) throw new IOException("No audio in the file");
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            return run(extractor, codec, format, (int) (maxMs * Chunker.RATE / 1000), cancel);
        } catch (IllegalStateException | IllegalArgumentException | SecurityException ex) {
            throw new IOException("The phone cannot decode this audio: " + ex.getMessage(), ex);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // Already stopped or failed; it is released next.
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static float[] run(MediaExtractor extractor, MediaCodec codec, MediaFormat inputFormat, int maxSamples,
                               ResumableDownload.Cancel cancel) throws IOException {
        GrowableFloats output = new GrowableFloats(Chunker.RATE * 60);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        StreamResampler resampler = null;
        int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        boolean floatPcm = false;
        boolean inputDone = false;
        boolean outputDone = false;
        short[] shorts = new short[0];
        float[] floats = new float[0];
        float[] mono = new float[0];
        while (!outputDone && output.size() < maxSamples) {
            if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
            if (!inputDone) {
                int index = codec.dequeueInputBuffer(TIMEOUT_US);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = codec.getOutputFormat();
                int rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                resampler = new StreamResampler(rate, Chunker.RATE);
            } else if (index >= 0) {
                if (resampler == null) {
                    // A decoder that never announced its format: the input format is what there is.
                    resampler = new StreamResampler(inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), Chunker.RATE);
                }
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.order(ByteOrder.nativeOrder());
                    int samples = floatPcm ? info.size / 4 : info.size / 2;
                    int frames = samples / channels;
                    if (mono.length < frames) mono = new float[frames];
                    if (floatPcm) {
                        if (floats.length < samples) floats = new float[samples];
                        buffer.asFloatBuffer().get(floats, 0, samples);
                        PcmConverter.monoFromFloats(floats, frames, channels, mono);
                    } else {
                        if (shorts.length < samples) shorts = new short[samples];
                        buffer.asShortBuffer().get(shorts, 0, samples);
                        PcmConverter.monoFromShorts(shorts, frames, channels, mono);
                    }
                    resampler.push(mono, frames, output);
                }
                outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(index, false);
            }
        }
        if (resampler != null && outputDone) resampler.finish(output);
        if (output.size() == 0) throw new IOException("The decoder produced no sound");
        float[] all = output.toArray();
        return all.length > maxSamples ? java.util.Arrays.copyOf(all, maxSamples) : all;
    }
}
