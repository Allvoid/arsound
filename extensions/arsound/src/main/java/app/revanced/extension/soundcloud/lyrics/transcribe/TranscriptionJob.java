package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;
import android.os.PowerManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The work of recognising one song, start to finish, on a background thread: install what is missing, get the sound,
 * decode it (taking the voice out of the music, when the settings ask for it), then listen to it in windows of up to
 * 30 seconds, cleaning each window's text into lines. After every
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

        /** The voice is being taken out of the music; {@code percent} of the song is done. */
        void separating(int percent);

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
    /** Whether the voice is taken out of the music first ({@link MdxSeparator}). */
    private final boolean separate;
    /** The language the user said the song is in. */
    private final SongLanguage language;
    /** Whether the song is heard anew: a finished text or a partial one of an earlier try is not taken over. */
    private final boolean again;
    private final String urn;
    private final long durationMs;
    private final ResumableDownload.Cancel cancel;
    private final Sink sink;

    TranscriptionJob(Context context, TranscriptStore store, AsrModel model, boolean separate, SongLanguage language,
                     boolean again, String urn, long durationMs, ResumableDownload.Cancel cancel, Sink sink) {
        this.context = context;
        this.store = store;
        this.model = model;
        this.separate = separate;
        this.language = language;
        this.again = again;
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
    /** The finished text of a song heard again, kept until the new try finishes; null otherwise. */
    private Transcript keep;

    Transcript run() throws JobFailed, ResumableDownload.Cancelled {
        Transcript stored = store.read(urn, model.id);
        Transcript earlier = again ? null : stored;
        if (earlier != null && earlier.isDone()) return earlier;
        // Heard again ("another language"): the finished text stays in place until the new one is finished too.
        keep = again && stored != null && stored.isDone() ? stored : null;
        // A try cut short goes on only in the same language: an answer changed in between starts the song anew.
        Transcript partial = earlier != null && earlier.state == Transcript.State.PARTIAL && language.matches(earlier.language)
                ? earlier : null;

        // A library the phone refused is not downloaded again for nothing.
        if (NativeLibs.isRefused(context)) throw new JobFailed(Failure.MODEL, null);
        if (separate && OrtLibs.isRefused(context)) throw new JobFailed(Failure.MODEL, null);
        PowerManager.WakeLock wake = acquireWakeLock();
        try {
            install();
            if (!NativeLibs.load(context)) throw new JobFailed(Failure.MODEL, null);
            Samples pcm = decode();
            long start = System.nanoTime();
            Transcript transcript = listen(pcm, partial);
            String seconds = secondsSince(start);
            Logger.printInfo(() -> "Transcribe: recognised in " + seconds + " s");
            return transcript;
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private void install() throws JobFailed, ResumableDownload.Cancelled {
        try {
            AsrInstaller.install(context, model, separate, cancel, new AsrInstaller.Progress() {
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
            throw new JobFailed(DiskErrors.isNoSpace(ex) ? Failure.NO_SPACE : Failure.NETWORK, ex);
        }
    }

    private Samples decode() throws JobFailed, ResumableDownload.Cancelled {
        long start = System.nanoTime();
        try (TrackAudio.Prepared audio = TrackAudio.prepare(context, urn, cancel)) {
            String fetched = secondsSince(start);
            Logger.printInfo(() -> "Transcribe: the audio is ready in " + fetched + " s");
            start = System.nanoTime();
            // Room for the whole song is made at once: a buffer grown half again at a time held up to two and a half
            // songs of samples while it grew.
            int expected = Samples.expectedFor(durationMs);
            if (!separate) return AudioDecoder.decode(audio.file, MAX_TRACK_MS + 30_000L, cancel, expected);
            // Shown already while the model opens: the first time on a neural processor it is compiled for it.
            sink.separating(0);
            MdxSeparator opened;
            try {
                opened = MdxSeparator.open(AsrInstaller.voiceModelFile(context), DeviceState.threads());
            } catch (IOException ex) {
                // The model did not open: told as the model's failure, not as sound the phone cannot read.
                throw new JobFailed(DiskErrors.isNoSpace(ex) ? Failure.NO_SPACE : Failure.MODEL, ex);
            }
            try (MdxSeparator separator = opened) {
                Samples voice = AudioDecoder.decode(audio.file, MAX_TRACK_MS + 30_000L, cancel,
                        new VocalsTarget(separator, new VocalsTarget.Progress() {
                            @Override
                            public void beforePiece() throws IOException {
                                if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                                restIfHot();
                            }

                            @Override
                            public void separatedMs(long ms) {
                                sink.separating((int) Math.min(99, ms * 100 / Math.max(1, durationMs)));
                            }
                        }, expected));
                String separated = secondsSince(start);
                Logger.printInfo(() -> "Transcribe: the voice is separated in " + separated + " s");
                return voice;
            }
        } catch (ResumableDownload.Cancelled ex) {
            throw ex;
        } catch (TrackAudio.Unavailable ex) {
            throw new JobFailed(Failure.UNAVAILABLE, ex);
        } catch (VocalsTarget.SeparationFailed ex) {
            throw new JobFailed(Failure.MODEL, ex);
        } catch (IOException ex) {
            // The song copied into a file on a full storage: that is no fault of the sound or of the network.
            if (DiskErrors.isNoSpace(ex)) throw new JobFailed(Failure.NO_SPACE, ex);
            // The network failing while the stream is fetched is told apart from a file the phone cannot decode.
            boolean network = ex instanceof java.net.SocketException || ex instanceof java.net.UnknownHostException
                    || ex instanceof java.net.SocketTimeoutException || String.valueOf(ex.getMessage()).startsWith("HTTP ");
            throw new JobFailed(network ? Failure.NETWORK : Failure.AUDIO, ex);
        } catch (OutOfMemoryError ex) {
            throw new JobFailed(Failure.MEMORY, ex);
        }
    }

    private Transcript listen(Samples samples, Transcript partial) throws JobFailed, ResumableDownload.Cancelled {
        float[] pcm = samples.data;
        int length = samples.length;
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        int start = 0;
        int done = 0;
        String heardIn = fixedLanguage();
        if (partial != null && Math.abs(partial.durationMs - durationMs) <= 2000) {
            for (int i = 0; i < partial.lines.length; i++) {
                lines.add(partial.lines[i]);
                times.add(partial.times[i]);
            }
            start = (int) Math.min(length, partial.resumeMs * Chunker.RATE / 1000);
            done = partial.windowsDone;
            // The language found in the windows before is the song's (the partial text matched the answer).
            if (heardIn.isEmpty()) heardIn = partial.language;
        }
        boolean autoLanguage = heardIn.isEmpty();

        try (Recognizer runner = model.open(context, heardIn, DeviceState.threads())) {
            SongListener ear = new SongListener(runner, heardIn, autoLanguage, language == SongLanguage.OTHER,
                    Locale.getDefault().getLanguage(),
                    Lang.russian(), lines, times);
            while (start < length) {
                if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                restIfHot();
                int end = Chunker.windowEnd(pcm, length, start);
                int total = done + windowsLeft(length, start);
                sink.listening(done + 1, Math.max(total, done + 1));
                ear.listen(pcm, start, end);
                done++;
                start = end;
                save(lines, times, ear.language(), done, done + windowsLeft(length, start), Chunker.toMs(start),
                        Transcript.State.PARTIAL);
            }
            return save(lines, times, ear.language(), done, done, Chunker.toMs(length),
                    lines.isEmpty() ? Transcript.State.FAILED : Transcript.State.DONE);
        } catch (ResumableDownload.Cancelled ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new JobFailed(Failure.MODEL, ex);
        } catch (OutOfMemoryError ex) {
            throw new JobFailed(Failure.MEMORY, ex);
        }
    }

    /** The language to start with: the one the user named, or none ("" lets Whisper find it). */
    private String fixedLanguage() {
        return model.russianOnly() ? SongLanguage.RUSSIAN.code : language.code;
    }

    private Transcript save(List<String> lines, List<Long> times, String language, int done, int total, long resumeMs,
                            Transcript.State state) throws JobFailed {
        long[] stamps = new long[times.size()];
        for (int i = 0; i < stamps.length; i++) stamps[i] = times.get(i);
        Transcript transcript = new Transcript(urn, model.id, state, language, durationMs, System.currentTimeMillis(),
                done, total, resumeMs, lines.toArray(new String[0]), stamps);
        // A song heard again overwrote its finished text with the first window of the new try; a try cut short or
        // failed then left nothing. Only a finished new text replaces the old one.
        if (keep != null && state != Transcript.State.DONE) return transcript;
        try {
            store.write(transcript);
        } catch (IOException ex) {
            throw new JobFailed(Failure.NO_SPACE, ex);
        }
        return transcript;
    }

    /** Seconds since a {@link System#nanoTime} reading, for the log. */
    private static String secondsSince(long start) {
        return String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9);
    }

    /** About how many windows the rest of the song needs, counting from a sample. */
    private static int windowsLeft(int length, int start) {
        return (int) Math.ceil((length - start) / (double) WINDOW_ESTIMATE);
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
            lock.setReferenceCounted(false);
            // No time limit: the first install of the models over a slow network, or the voice of a long song on a slow
            // phone, takes longer than any fixed one. It is released in the finally of run(), whatever happens.
            lock.acquire();
            return lock;
        } catch (RuntimeException ex) {
            // No permission or no service: the work goes on without keeping the processor awake.
            return null;
        }
    }
}
