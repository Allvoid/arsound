package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;
import android.os.PowerManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The work of recognising one song, start to finish, on a background thread: install what is missing, get the sound,
 * decode it, then listen to it in windows of up to 30 seconds, cleaning each window's text into lines. After every
 * window the progress is written to the {@link TranscriptStore}, so a job that is stopped (cancelled, or the process
 * killed) goes on from the next window when started again.
 * <p>
 * Run by {@link TranscriptionManager}, which owns the thread and passes the answers on to the main thread.
 */
final class TranscriptionJob {
    /** Hears about the job as it goes. Called on the job's thread. */
    interface Sink {
        /** The library or the model is being downloaded; {@code percent} is 0 to 100. */
        void installing(int percent, boolean library);

        /** Window number {@code current} (counting from 1) of about {@code total} is being listened to. */
        void listening(int current, int total);

        /** The phone is hot and the job rests before the next window. */
        void cooling();
    }

    /** The longest track that is listened to; the offer is not made for a longer one. */
    static final long MAX_TRACK_MS = 10 * 60_000L + 15_000L;
    static final long MIN_TRACK_MS = 20_000L;
    /** A window that has to be cut is about this long, so this many samples count as one window in the estimate. */
    private static final int WINDOW_ESTIMATE = 27 * Chunker.RATE;

    private final Context context;
    private final TranscriptStore store;
    private final AsrModel model;
    /** "auto", "ru" or "en", as in the settings. */
    private final String languageSetting;
    private final String urn;
    private final long durationMs;
    private final ResumableDownload.Cancel cancel;
    private final Sink sink;

    TranscriptionJob(Context context, TranscriptStore store, AsrModel model, String languageSetting, String urn,
                     long durationMs, ResumableDownload.Cancel cancel, Sink sink) {
        this.context = context;
        this.store = store;
        this.model = model;
        this.languageSetting = languageSetting;
        this.urn = urn;
        this.durationMs = durationMs;
        this.cancel = cancel;
        this.sink = sink;
    }

    /**
     * @return The finished transcript, already stored: with words, or marked as failed when nothing could be made out.
     * @throws JobFailed                    If the job could not be done.
     * @throws ResumableDownload.Cancelled  If it was cancelled; the progress so far is kept.
     */
    Transcript run() throws JobFailed, ResumableDownload.Cancelled {
        Transcript earlier = store.read(urn, model.id);
        if (earlier != null && earlier.isDone()) return earlier;

        PowerManager.WakeLock wake = acquireWakeLock();
        try {
            install();
            if (!NativeLibs.load(context)) throw new JobFailed(Failure.MODEL, null);
            float[] pcm = decode();
            return listen(pcm, earlier != null && earlier.state == Transcript.State.PARTIAL ? earlier : null);
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private void install() throws JobFailed, ResumableDownload.Cancelled {
        try {
            AsrInstaller.install(context, model, cancel, new AsrInstaller.Progress() {
                @Override
                public void onProgress(int percent, boolean library) {
                    sink.installing(percent, library);
                }
            });
        } catch (ResumableDownload.Cancelled ex) {
            throw ex;
        } catch (AsrInstaller.NoSpace ex) {
            throw new JobFailed(Failure.NO_SPACE, ex);
        } catch (ResumableDownload.BadChecksum ex) {
            throw new JobFailed(Failure.BAD_DOWNLOAD, ex);
        } catch (IOException ex) {
            throw new JobFailed(Failure.NETWORK, ex);
        }
    }

    private float[] decode() throws JobFailed, ResumableDownload.Cancelled {
        try (TrackAudio.Prepared audio = TrackAudio.prepare(context, urn, cancel)) {
            return AudioDecoder.decode(audio.file, MAX_TRACK_MS + 30_000L, cancel);
        } catch (ResumableDownload.Cancelled ex) {
            throw ex;
        } catch (TrackAudio.Unavailable ex) {
            throw new JobFailed(Failure.UNAVAILABLE, ex);
        } catch (IOException ex) {
            // The network failing while the stream is fetched is told apart from a file the phone cannot decode.
            boolean network = ex instanceof java.net.SocketException || ex instanceof java.net.UnknownHostException
                    || ex instanceof java.net.SocketTimeoutException || String.valueOf(ex.getMessage()).startsWith("HTTP ");
            throw new JobFailed(network ? Failure.NETWORK : Failure.AUDIO, ex);
        } catch (OutOfMemoryError ex) {
            throw new JobFailed(Failure.MEMORY, ex);
        }
    }

    private Transcript listen(float[] pcm, Transcript partial) throws JobFailed, ResumableDownload.Cancelled {
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        int start = 0;
        int done = 0;
        String language = fixedLanguage();
        if (partial != null && Math.abs(partial.durationMs - durationMs) <= 2000) {
            for (int i = 0; i < partial.lines.length; i++) {
                lines.add(partial.lines[i]);
                times.add(partial.times[i]);
            }
            start = (int) Math.min(pcm.length, partial.resumeMs * Chunker.RATE / 1000);
            done = partial.windowsDone;
            // A language an earlier version locked by mistake (Japanese in a Russian song) is not taken over.
            if (language.isEmpty() && LanguagePolicy.isExpected(partial.language, Locale.getDefault().getLanguage())) {
                language = partial.language;
            }
        }
        boolean autoLanguage = LanguagePolicy.AUTO.equals(languageSetting);

        try (WhisperRunner runner = new WhisperRunner(context, model, language, DeviceState.threads())) {
            SongListener ear = new SongListener(runner, language, autoLanguage, Locale.getDefault().getLanguage(),
                    Lang.russian(), lines, times);
            while (start < pcm.length) {
                if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                restIfHot();
                int end = Chunker.windowEnd(pcm, start);
                int total = done + windowsLeft(pcm, start);
                sink.listening(done + 1, Math.max(total, done + 1));
                ear.listen(pcm, start, end);
                done++;
                start = end;
                save(lines, times, ear.language(), done, done + windowsLeft(pcm, start), Chunker.toMs(start),
                        Transcript.State.PARTIAL);
            }
            return save(lines, times, ear.language(), done, done, Chunker.toMs(pcm.length),
                    lines.isEmpty() ? Transcript.State.FAILED : Transcript.State.DONE);
        } catch (ResumableDownload.Cancelled ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new JobFailed(Failure.MODEL, ex);
        } catch (OutOfMemoryError ex) {
            throw new JobFailed(Failure.MEMORY, ex);
        }
    }

    /** The language to start with: the one the settings fix, or none ("" lets Whisper find it). */
    private String fixedLanguage() {
        return "auto".equals(languageSetting) ? "" : languageSetting;
    }

    private Transcript save(List<String> lines, List<Long> times, String language, int done, int total, long resumeMs,
                            Transcript.State state) throws JobFailed {
        long[] stamps = new long[times.size()];
        for (int i = 0; i < stamps.length; i++) stamps[i] = times.get(i);
        Transcript transcript = new Transcript(urn, model.id, state, language, durationMs, System.currentTimeMillis(),
                done, total, resumeMs, lines.toArray(new String[0]), stamps);
        try {
            store.write(transcript);
        } catch (IOException ex) {
            throw new JobFailed(Failure.NO_SPACE, ex);
        }
        return transcript;
    }

    /** About how many windows the rest of the song needs, counting from a sample. */
    private static int windowsLeft(float[] pcm, int start) {
        return (int) Math.ceil((pcm.length - start) / (double) WINDOW_ESTIMATE);
    }

    /** When the phone is hot the job waits, a little when warm and long when hot, telling the page. */
    private void restIfHot() throws ResumableDownload.Cancelled {
        long pause = Guards.thermalPauseMs(DeviceState.thermalStatus(context));
        if (pause <= 0) return;
        sink.cooling();
        long until = System.currentTimeMillis() + pause;
        while (System.currentTimeMillis() < until) {
            if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResumableDownload.Cancelled();
            }
        }
    }

    private PowerManager.WakeLock acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power == null) return null;
            PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arsound:lyrics-recognition");
            lock.acquire(20 * 60_000L);
            return lock;
        } catch (RuntimeException ex) {
            // No permission or no service: the work goes on without keeping the processor awake.
            return null;
        }
    }
}
