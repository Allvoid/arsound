package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The way in to the recognition of lyrics by a neural network: what the lyrics page and the settings screen ask and
 * start. Everything public here is called on the main thread (the answers to a few questions read files, see each).
 * <p>
 * One piece of work runs at a time, on a thread of its own with a low priority (not the lyrics lookup's thread: a
 * recognition takes minutes and must not hold up the search for the next track). The work is either a recognition
 * of one track ({@link #start}) or only the download of a model ({@link #startInstall}). Answers are passed on to the
 * listener on the main thread; a page that is rebuilt while the work goes on takes the listener over with
 * {@link #attach} and is told where the work has got to.
 * <p>
 * There is no model chosen for all songs: the user says the language of each song ({@link SongLanguage}), and that
 * decides the model. The answer is remembered for the artist, so the next song of the same artist offers it first.
 */
public final class TranscriptionManager {
    /** What a recognition tells the page. All calls are on the main thread. */
    public interface Listener {
        /** The library or the model is being downloaded. */
        void onInstalling(int percent, boolean library);

        /** The voice is being taken out of the music; {@code percent} of the song is done. */
        void onSeparating(int percent);

        /** Window {@code current} of about {@code total} is being listened to. */
        void onListening(int current, int total);

        /** The phone is hot and the work rests. */
        void onCooling();

        /** The work is done: the transcript has words, or is marked failed when nothing could be made out. */
        void onFinished(Transcript transcript);

        void onFailed(Failure failure);

        void onCancelled();
    }

    /** What a download of a model tells the settings screen. All calls are on the main thread. */
    public interface InstallListener {
        void onProgress(int percent);

        void onDone();

        void onFailed(Failure failure);

        void onCancelled();
    }

    /** One recognition in progress: what it is for, its cancel flag, who listens and what they were told last. */
    private static final class Run implements ResumableDownload.Cancel {
        final String urn;
        final long durationMs;
        volatile boolean cancelled;
        Listener listener;
        /** The last thing the listener was told, so a new listener gets it at once. */
        Event last;

        Run(String urn, long durationMs) {
            this.urn = urn;
            this.durationMs = durationMs;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** One thing to tell a listener; kept so that it can be told again to a listener that comes later. */
    private interface Event {
        void deliver(Listener listener);
    }

    private static final class Install implements ResumableDownload.Cancel {
        final AsrModel model;
        volatile boolean cancelled;
        InstallListener listener;

        Install(AsrModel model, InstallListener listener) {
            this.model = model;
            this.listener = listener;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable runnable) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    runnable.run();
                }
            }, "Arsound recognition");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static Run running;
    private static Install installing;
    private static TranscriptStore store;

    private TranscriptionManager() {
    }

    // region Questions

    /** Whether this phone can do it at all: a 64-bit ARM phone. Elsewhere the whole feature is hidden. */
    public static boolean isSupportedDevice() {
        return DeviceState.isSupportedDevice();
    }

    /** Whether the user has switched the recognition on (and it can work on this phone). */
    public static boolean isEnabled() {
        return isSupportedDevice() && Settings.isLyricsAiEnabled();
    }

    public static void setEnabled(boolean enabled) {
        Settings.setLyricsAiEnabled(enabled);
        if (!enabled) cancel();
    }

    /** Whether the voice is taken out of the music before the network listens. */
    public static boolean separatesVoice() {
        return BoolSettings.LYRICS_AI_SEPARATE.get();
    }

    // region The language of a song

    private static final String LANGUAGES = "arsound_song_languages";
    /** Artists remembered at most; the oldest answers go first. */
    private static final int MAX_ARTISTS = 300;

    /**
     * The answer to offer first for a song: the one given for the same artist before, else Russian when the title or
     * the artist is written in Russian letters; null when nothing suggests one.
     */
    public static SongLanguage suggestedLanguage(String artist, String title) {
        SharedPreferences remembered = languages();
        String key = artistKey(artist);
        if (remembered != null && key != null) {
            String value = remembered.getString(key, null);
            SongLanguage language = value == null ? null : SongLanguage.named(value.substring(value.indexOf('|') + 1));
            if (language != null) return language;
        }
        return hasCyrillic(artist) || hasCyrillic(title) ? SongLanguage.RUSSIAN : null;
    }

    /** Remembers the answer for the artist of the song. */
    public static void rememberLanguage(String artist, SongLanguage language) {
        SharedPreferences remembered = languages();
        String key = artistKey(artist);
        if (remembered == null || key == null) return;
        SharedPreferences.Editor editor = remembered.edit().putString(key, System.currentTimeMillis() + "|" + language.name());
        java.util.Map<String, ?> all = remembered.getAll();
        if (all.size() >= MAX_ARTISTS) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                String value = String.valueOf(entry.getValue());
                long at;
                try {
                    at = Long.parseLong(value.substring(0, Math.max(0, value.indexOf('|'))));
                } catch (NumberFormatException ex) {
                    at = 0;
                }
                if (at < oldestAt && !entry.getKey().equals(key)) {
                    oldestAt = at;
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) editor.remove(oldest);
        }
        editor.apply();
    }

    private static SharedPreferences languages() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(LANGUAGES, Context.MODE_PRIVATE);
    }

    private static String artistKey(String artist) {
        if (artist == null) return null;
        String key = artist.trim().toLowerCase(java.util.Locale.ROOT);
        return key.isEmpty() ? null : key.length() > 100 ? key.substring(0, 100) : key;
    }

    private static boolean hasCyrillic(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (LanguagePolicy.scriptOf(text.charAt(i)) == LanguagePolicy.Script.CYRILLIC) return true;
        }
        return false;
    }

    // endregion

    /**
     * Whether the page may offer to recognise this track: this phone can do it, and the track is a song of a
     * length that can be listened to, whose sound the code knows where to find. Whether the track is really open
     * to listening (not a preview, not protected) is found out when the work starts.
     */
    public static boolean canOffer(String urn, long durationMs) {
        return isSupportedDevice() && TrackAudio.isCandidate(urn)
                && durationMs >= TranscriptionJob.MIN_TRACK_MS && durationMs <= TranscriptionJob.MAX_TRACK_MS;
    }

    /**
     * The finished transcript to show for the track, or null: none stored, or the recognition is off. Reads a file:
     * not for the main thread's frames, but a small one.
     */
    public static Transcript stored(String urn, long durationMs) {
        if (!isEnabled()) return null;
        return store().latest(urn, durationMs);
    }

    /** Whether the last recognition of the track heard nothing, so it is not tried again unasked. Reads files. */
    public static boolean failedBefore(String urn) {
        Transcript last = store().latestTry(urn);
        return last != null && last.state == Transcript.State.FAILED;
    }

    public static boolean isInstalled(AsrModel model) {
        Context context = Utils.getContext();
        return context != null && AsrInstaller.missingBytes(context, model, separatesVoice()) == 0;
    }

    /** What must still be downloaded to recognise with the model, in bytes. */
    public static long missingBytes(AsrModel model) {
        Context context = Utils.getContext();
        return context == null ? 0 : AsrInstaller.missingBytes(context, model, separatesVoice());
    }

    /** Space the library and the models take on the phone. Walks files: off the main thread. */
    public static long installedBytes() {
        Context context = Utils.getContext();
        return context == null ? 0 : AsrInstaller.installedBytes(context);
    }

    /** Space one model takes. Walks files: off the main thread. */
    public static long modelBytes(AsrModel model) {
        Context context = Utils.getContext();
        return context == null ? 0 : AsrInstaller.modelBytes(context, model);
    }

    /** How the phone stands for a recognition with the model, and what has to be downloaded first. */
    public static Advice advise(AsrModel model) {
        Context context = Utils.getContext();
        if (context == null) return new Advice(null, false, new ArrayList<String>(), 0, false);
        Guards.Device device = DeviceState.read(context);
        String blocker = null;
        List<String> warnings = new ArrayList<>();
        for (Guards.Concern concern : Guards.concerns(device, model, separatesVoice())) {
            if (concern.blocking) blocker = concern.message();
            else warnings.add(concern.message());
        }
        return new Advice(blocker, Guards.lighterModelFits(device, model), warnings,
                AsrInstaller.missingBytes(context, model, separatesVoice()), DeviceState.isMetered(context));
    }

    // endregion

    // region Recognition of a track

    /** Whether a recognition of this track is in progress. */
    public static boolean isRunningFor(String urn) {
        return running != null && running.urn.equals(urn);
    }

    /**
     * Starts recognising a track. A recognition that is going on is stopped without telling its listener, because
     * this run speaks for the page now. A model download is stopped too, but its listener is still told, so the
     * settings row does not stay on the old progress. Whatever was done is kept.
     *
     * @param language The language the user said the song is in.
     * @param model    The model that listens: the language's own, or a lighter one when memory is short.
     * @param again    Whether the song is heard anew although it has a text (the answer was put right).
     */
    public static void start(final String urn, final long durationMs, final SongLanguage language, final AsrModel model,
                             final boolean again, Listener listener) {
        final Context context = Utils.getContext();
        if (context == null) {
            listener.onFailed(Failure.MODEL);
            return;
        }
        if (running != null) {
            running.listener = null;
            running.cancelled = true;
        }
        if (installing != null) installing.cancelled = true;
        final Run run = new Run(urn, durationMs);
        run.listener = listener;
        running = run;
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                work(context, run, model, language, again);
            }
        });
    }

    private static void work(Context context, final Run run, AsrModel model, SongLanguage language, boolean again) {
        TranscriptionJob.Sink sink = new TranscriptionJob.Sink() {
            @Override
            public void installing(final int percent, final boolean library) {
                tell(run, new Event() {
                    @Override
                    public void deliver(Listener listener) {
                        listener.onInstalling(percent, library);
                    }
                });
            }

            @Override
            public void separating(final int percent) {
                tell(run, new Event() {
                    @Override
                    public void deliver(Listener listener) {
                        listener.onSeparating(percent);
                    }
                });
            }

            @Override
            public void listening(final int current, final int total) {
                tell(run, new Event() {
                    @Override
                    public void deliver(Listener listener) {
                        listener.onListening(current, total);
                    }
                });
            }

            @Override
            public void cooling() {
                tell(run, new Event() {
                    @Override
                    public void deliver(Listener listener) {
                        listener.onCooling();
                    }
                });
            }
        };
        Event outcome;
        try {
            if (run.cancelled) throw new ResumableDownload.Cancelled();
            final Transcript result = new TranscriptionJob(context, store(), model, separatesVoice(), language, again,
                    run.urn, run.durationMs, run, sink).run();
            Logger.printInfo(() -> "Transcribe: " + run.urn + " -> " + result.state + ", " + result.lines.length + " lines");
            outcome = new Event() {
                @Override
                public void deliver(Listener listener) {
                    listener.onFinished(result);
                }
            };
        } catch (ResumableDownload.Cancelled ex) {
            outcome = new Event() {
                @Override
                public void deliver(Listener listener) {
                    listener.onCancelled();
                }
            };
        } catch (final JobFailed ex) {
            Logger.printInfo(() -> "Transcribe: " + run.urn + " failed: " + ex.failure + " " + ex.getCause());
            outcome = new Event() {
                @Override
                public void deliver(Listener listener) {
                    listener.onFailed(ex.failure);
                }
            };
        } catch (Throwable ex) {
            Logger.printException(() -> "Transcribe: the job crashed", ex);
            outcome = new Event() {
                @Override
                public void deliver(Listener listener) {
                    listener.onFailed(Failure.MODEL);
                }
            };
        }
        final Event last = outcome;
        Utils.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                // The listener is told, and the run is over: a page that attaches later asks the store instead.
                if (running == run) running = null;
                if (run.listener != null) last.deliver(run.listener);
            }
        });
    }

    /** Passes a progress event to the main thread, keeping it for a listener that attaches later. */
    private static void tell(final Run run, final Event event) {
        Utils.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (running != run) return;
                run.last = event;
                if (run.listener != null) event.deliver(run.listener);
            }
        });
    }

    /**
     * Makes the listener the one to hear about the recognition of the track, if one is in progress, and tells it
     * where the work has got to.
     *
     * @return Whether a recognition of the track is in progress.
     */
    public static boolean attach(String urn, Listener listener) {
        if (running == null || !running.urn.equals(urn)) return false;
        running.listener = listener;
        if (running.last != null) running.last.deliver(listener);
        return true;
    }

    /** The listener is gone (its page was reused for another track); the work goes on without telling anyone. */
    public static void detach(Listener listener) {
        if (running != null && running.listener == listener) running.listener = null;
    }

    /** Stops the recognition in progress. Its progress stays on the phone. */
    public static void cancel() {
        if (running != null) running.cancelled = true;
    }

    // endregion

    // region Download of a model

    public static boolean isInstalling() {
        return installing != null;
    }

    /** The model being downloaded on its own (from the settings); null if none is. */
    public static AsrModel installingModel() {
        Install current = installing;
        return current == null ? null : current.model;
    }

    /**
     * Downloads the library and the model, without recognising anything. A download that is going on is stopped
     * without telling its listener, because this one speaks for the screen now. A recognition is stopped too, but its
     * listener is still told, so the lyrics panel does not stay on the old progress.
     */
    public static void startInstall(final AsrModel model, InstallListener listener) {
        final Context context = Utils.getContext();
        if (context == null) {
            listener.onFailed(Failure.MODEL);
            return;
        }
        if (installing != null) {
            installing.listener = null;
            installing.cancelled = true;
        }
        if (running != null) running.cancelled = true;
        final Install install = new Install(model, listener);
        installing = install;
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                Failure failure = null;
                boolean cancelled = false;
                try {
                    AsrInstaller.install(context, model, separatesVoice(), install, new AsrInstaller.Progress() {
                        @Override
                        public void onProgress(final int percent, boolean library) {
                            Utils.runOnMainThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (installing == install && install.listener != null) install.listener.onProgress(percent);
                                }
                            });
                        }
                    });
                } catch (ResumableDownload.Cancelled ex) {
                    cancelled = true;
                } catch (AsrInstaller.NoSpace ex) {
                    failure = Failure.NO_SPACE;
                } catch (ResumableDownload.BadChecksum ex) {
                    failure = Failure.BAD_DOWNLOAD;
                } catch (java.io.IOException ex) {
                    Logger.printInfo(() -> "Transcribe: the download failed: " + ex);
                    failure = Failure.NETWORK;
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Transcribe: the download crashed", ex);
                    failure = Failure.NETWORK;
                }
                final Failure failed = failure;
                final boolean stopped = cancelled;
                Utils.runOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        if (installing == install) installing = null;
                        InstallListener listener = install.listener;
                        if (listener == null) return;
                        if (stopped) listener.onCancelled();
                        else if (failed != null) listener.onFailed(failed);
                        else listener.onDone();
                    }
                });
            }
        });
    }

    /** The settings screen is gone; the download goes on without telling anyone. */
    public static void detachInstall(InstallListener listener) {
        if (installing != null && installing.listener == listener) installing.listener = null;
    }

    /** Makes the listener the one to hear about the download in progress, if there is one. */
    public static boolean attachInstall(InstallListener listener) {
        if (installing == null) return false;
        installing.listener = listener;
        return true;
    }

    public static void cancelInstall() {
        if (installing != null) installing.cancelled = true;
    }

    // endregion

    // region Storage

    /**
     * Removes a model from the phone.
     *
     * @return False if the model is in use now and was left.
     */
    public static boolean deleteModel(AsrModel model) {
        Context context = Utils.getContext();
        if (context == null || running != null || installing != null) return false;
        AsrInstaller.deleteModel(context, model);
        return true;
    }

    /** Removes the library and every model. @return False if work is in progress and nothing was removed. */
    public static boolean deleteAllFiles() {
        Context context = Utils.getContext();
        if (context == null || running != null || installing != null) return false;
        AsrInstaller.deleteAll(context);
        return true;
    }

    /** How many songs have a recognised text on the phone. Reads files: off the main thread. */
    public static int transcriptCount() {
        return store().doneCount();
    }

    /** Space the recognised texts take. Reads files: off the main thread. */
    public static long transcriptBytes() {
        return store().size();
    }

    /** Removes every recognised text and unfinished work. Not on the main thread. */
    public static void clearTranscripts() {
        store().clear();
    }

    private static synchronized TranscriptStore store() {
        if (store == null) {
            Context context = Utils.getContext();
            File root = context == null ? new File(System.getProperty("java.io.tmpdir", ".")) : context.getFilesDir();
            store = new TranscriptStore(new File(new File(root, "arsound"), "transcripts"));
        }
        return store;
    }

    // endregion
}
