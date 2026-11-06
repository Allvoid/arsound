package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.local.SoundCloudLikes;
import app.revanced.extension.soundcloud.shared.ForegroundWork;

/**
 * One transfer at a time, from a Yandex Music collection to private SoundCloud playlists: the tracks are read, each
 * is looked up on SoundCloud (a few at once, remembered in {@link TransferStore}), and the ones found become the
 * playlist in the same order; when the user asked for it, they are liked too. It runs on while the settings are
 * closed, in a foreground service with a notification ({@link ForegroundWork}), so a phone that ends apps in the
 * background does not end it; the screen follows it through a listener. A transfer the app was closed in the middle
 * of is remembered and can be gone on with.
 */
public final class TransferJob {
    public interface Listener {
        /** Called on the main thread whenever the state below changes. */
        void onChanged();
    }

    public enum Phase {IDLE, READING, SEARCHING, CREATING, LIKING, DONE, FAILED}

    private static final int SEARCH_THREADS = 3;
    /** This many failed searches in a row mean SoundCloud is out of reach: the connection is gone, not the songs. */
    private static final int UNREACHABLE_FAILURES = 8;
    /** The matches are saved this often while the search runs; the whole file is written each time. */
    private static final long SAVE_EVERY_MS = 15_000;
    /** The screen is told of the progress at most this often: a list with known matches goes by in a moment. */
    private static final long CHANGED_MS = 150;
    private static final AtomicBoolean changePosted = new AtomicBoolean();

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String title = "";
    private static volatile int total;
    private static volatile int checked;
    private static volatile int found;
    private static volatile int liked;
    /** What went wrong, for the user; null unless the phase is FAILED. */
    private static volatile String failure;
    private static volatile TransferRecord result;
    private static volatile Thread worker;

    private TransferJob() {
    }

    public static Phase phase() {
        return phase;
    }

    public static boolean isRunning() {
        Phase current = phase;
        return current == Phase.READING || current == Phase.SEARCHING || current == Phase.CREATING
                || current == Phase.LIKING;
    }

    /** One line about the transfer in progress or just finished. */
    public static String status() {
        switch (phase) {
            case READING:
                return text("Читаю список в Яндекс Музыке…", "Reading the list on Yandex Music…");
            case SEARCHING:
                return text("Ищу в SoundCloud: ", "Searching SoundCloud: ") + checked + text(" из ", " of ") + total
                        + text(", найдено ", ", found ") + found;
            case CREATING:
                return text("Создаю плейлист в SoundCloud…", "Making the SoundCloud playlist…");
            case LIKING:
                return text("Лайкаю треки: ", "Liking the tracks: ") + liked + text(" из ", " of ") + found;
            case DONE:
                TransferRecord entry = result;
                return entry == null ? "" : summary(entry);
            case FAILED:
                return failure == null ? "" : failure;
            default:
                return "";
        }
    }

    /** "Found 312 of 340: «Title»". */
    public static String summary(TransferRecord entry) {
        return text("Перенесено ", "Transferred ") + entry.found + text(" из ", " of ") + entry.total
                + text(" в «", " to \"") + entry.title + text("»", "\"")
                + (entry.playlists.size() > 1 ? text(" (плейлистов: ", " (playlists: ") + entry.playlists.size() + ")" : "")
                + (entry.liked == TransferRecord.LIKES_NOT_ASKED ? "" : text(", в лайках: ", ", in likes: ") + entry.liked + text(" из ", " of ") + entry.found);
    }

    /** The collection being transferred, empty before it is read. */
    public static String title() {
        return title;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * @param like Whether the tracks found are also liked.
     * @return False if a transfer is already running.
     */
    public static synchronized boolean start(YandexLink link, boolean like) {
        if (isRunning()) return false;
        title = "";
        total = 0;
        checked = 0;
        found = 0;
        liked = 0;
        failure = null;
        result = null;
        phase = Phase.READING;
        changed();
        Context context = Utils.getContext();
        if (context != null) {
            ForegroundWork.begin(context, text("Перенос из Яндекс Музыки", "Transfer from Yandex Music"), status());
        }
        Thread thread = new Thread(() -> run(link, like), "Arsound transfer");
        worker = thread;
        thread.start();
        return true;
    }

    /**
     * A transfer that the app was closed in the middle of, to give to {@link #resume}: its collection, its title and
     * "true" or "false" for its likes. Null if there is none; not one that runs now.
     */
    public static String[] interrupted() {
        if (isRunning()) return null;
        String[] marked = TransferStore.interrupted();
        return marked == null || YandexLink.fromKey(marked[0]) == null ? null : marked;
    }

    /**
     * Goes on with a transfer the app was closed in the middle of.
     *
     * @param marked What {@link #interrupted} gave.
     * @return False if it cannot.
     */
    public static boolean resume(String[] marked) {
        YandexLink link = YandexLink.fromKey(marked[0]);
        return link != null && start(link, Boolean.parseBoolean(marked[2]));
    }

    /** Forgets a transfer the app was closed in the middle of: the user does not want to go on with it. */
    public static void forgetInterrupted() {
        if (!isRunning()) TransferStore.clearRunning();
    }

    /** Stops the transfer; the tracks looked up so far are kept, so starting it again goes quicker. */
    public static void cancel() {
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    private static void run(YandexLink link, boolean like) {
        try {
            // Remembered first: a transfer the app is closed in the middle of can be gone on with.
            TransferStore.markRunning(link.key(), "", like);
            // Told before the long search, not after it.
            if (like && !SoundCloudLikes.isReady()) throw new SoundCloudSide.NotReadyException();
            YandexMusic.Collection collection = read(link);
            title = collection.title;
            total = collection.tracks.size();
            TransferStore.markRunning(link.key(), collection.title, like);
            phase = Phase.SEARCHING;
            changed();
            List<String> missing = new ArrayList<>();
            List<String> urns = search(collection.tracks, missing);
            if (urns.isEmpty()) {
                fail(text("Ни один трек не нашёлся в SoundCloud", "None of the tracks was found on SoundCloud"));
                return;
            }
            phase = Phase.CREATING;
            changed();
            List<String> playlists = SoundCloudSide.createPlaylists(link.key(), collection.title, urns);
            int likes = TransferRecord.LIKES_NOT_ASKED;
            if (like) {
                phase = Phase.LIKING;
                changed();
                likes = SoundCloudSide.like(urns, count -> {
                    liked = count;
                    changed();
                });
            }
            TransferRecord entry = new TransferRecord(link.key(), collection.title, System.currentTimeMillis(),
                    total, urns.size(), playlists, missing, likes);
            TransferStore.addToHistory(entry);
            TransferStore.clearRunning();
            result = entry;
            phase = Phase.DONE;
            Logger.printInfo(() -> "Transfer done: " + entry.found + " of " + entry.total + " from " + link);
            changed();
            if (LISTENERS.isEmpty()) toast(summary(entry));
        } catch (InterruptedException | java.io.InterruptedIOException ex) {
            fail(text("Перенос остановлен. Найденное запомнено, повтор пойдёт быстрее.",
                    "Transfer stopped. What was found is kept, a new try goes quicker."));
        } catch (YandexMusic.SignInExpiredException ex) {
            fail(text("Вход в Яндекс устарел: войдите ещё раз", "The Yandex sign-in expired: sign in again"));
        } catch (YandexMusic.NotAvailableException ex) {
            Logger.printInfo(() -> "Transfer: not available: " + ex.getMessage());
            fail(notAvailable(link));
        } catch (SoundCloudSide.NotReadyException ex) {
            fail(text("SoundCloud ещё не готов: откройте библиотеку и попробуйте снова",
                    "SoundCloud is not ready yet: open the library and try again"));
        } catch (SoundCloudSide.PartlyMadeException ex) {
            Logger.printException(() -> "Transfer: playlists made in part", ex);
            fail(text("Создано плейлистов: " + ex.made + " из " + ex.parts + ", дальше не вышло. Они в вашей библиотеке; "
                            + "повтор продолжит с того же места и не создаст их снова.",
                    "Playlists made: " + ex.made + " of " + ex.parts + ", then it failed. They are in your library; "
                            + "a new try goes on from there and does not make them again."));
        } catch (SoundCloudSide.LikesStoppedException ex) {
            Logger.printException(() -> "Transfer: the likes stopped", ex);
            fail(text("Плейлист создан, но SoundCloud перестал принимать лайки: поставлено " + ex.liked + " из " + ex.total
                            + ". Перенесите этот список снова с галочкой позже: плейлист не создастся второй раз, "
                            + "лайки продолжатся с того же места.",
                    "The playlist is made, but SoundCloud stopped taking likes: " + ex.liked + " of " + ex.total
                            + " given. Transfer this list again with the tick later: the playlist is not made a second "
                            + "time and the likes go on from there."));
        } catch (SoundCloudSide.UnknownOutcomeException ex) {
            fail(text("SoundCloud не ответил, создан ли плейлист «" + ex.name + "». Посмотрите библиотеку, прежде чем повторять.",
                    "SoundCloud did not say whether it made the playlist \"" + ex.name + "\". Look at your library before trying again."));
        } catch (Exception ex) {
            Logger.printException(() -> "Transfer failed", ex);
            fail(ex instanceof IOException
                    ? text("Нет связи: проверьте интернет и попробуйте снова", "No connection: check the internet and try again")
                    : text("Перенос не удался: ", "The transfer failed: ") + ex);
        } finally {
            synchronized (TransferJob.class) {
                // A new transfer may have started in the moment since the phase was set: its thread stays known.
                if (worker == Thread.currentThread()) worker = null;
            }
            Context context = Utils.getContext();
            if (context != null && !isRunning()) ForegroundWork.end(context);
        }
    }

    /** The transfers done, the newest first. */
    public static List<TransferRecord> history() {
        return TransferStore.history();
    }

    public static void forget(TransferRecord record) {
        TransferStore.removeFromHistory(record.time);
    }

    private static String notAvailable(YandexLink link) {
        boolean signedIn = YandexAccount.isSignedIn();
        if (link.kind == YandexLink.Kind.LIKES && !signedIn) {
            return text("Лайки этого профиля закрыты. Свои можно перенести, войдя в Яндекс на этом экране.",
                    "The likes of this profile are private. To transfer your own, sign in to Yandex on this screen.");
        }
        return signedIn
                ? text("Яндекс Музыка не отдала этот список: он закрыт или удалён", "Yandex Music did not give this list: it is private or removed")
                : text("Яндекс Музыка не отдала этот список: он закрыт или удалён. Если он ваш, войдите в Яндекс на этом экране.",
                "Yandex Music did not give this list: it is private or removed. If it is yours, sign in to Yandex on this screen.");
    }

    /** With the account when signed in; a token Yandex no longer takes is dropped for a public link. */
    private static YandexMusic.Collection read(YandexLink link) throws Exception {
        String token = YandexAccount.token();
        try {
            return YandexMusic.load(link, token);
        } catch (YandexMusic.SignInExpiredException ex) {
            YandexAccount.forgetToken();
            return YandexMusic.load(link, null);
        }
    }

    /** The urns found, in the order of the collection, each once; the labels of the tracks not found go to missing. */
    private static List<String> search(List<YandexTrack> tracks, List<String> missing) throws Exception {
        JSONObject matches = TransferStore.loadMatches();
        ExecutorService pool = Executors.newFixedThreadPool(SEARCH_THREADS);
        Set<String> urns = new LinkedHashSet<>();
        int failuresInRow = 0;
        long savedAt = System.currentTimeMillis();
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (YandexTrack track : tracks) {
                JSONObject kept = matches.optJSONObject(track.id);
                if (kept != null) {
                    String urn = kept.optString("urn");
                    answers.add(pool.submit(() -> urn));
                } else {
                    answers.add(pool.submit(() -> {
                        ClientProfiles.FoundTrack hit = SoundCloudSide.find(track);
                        return hit == null ? "" : "soundcloud:tracks:" + hit.id;
                    }));
                }
            }
            for (int i = 0; i < tracks.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                YandexTrack track = tracks.get(i);
                String urn;
                try {
                    // No time limit here: the search has its own (connect and read, three tries), and a limit shorter
                    // than its worst case only called a slow track "not found" while the search went on.
                    urn = answers.get(i).get();
                    failuresInRow = 0;
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Transfer: search failed for " + track.label() + ": " + ex);
                    // Searches failing one after another mean the connection is gone, found tracks or not: the
                    // transfer stops (what was found is kept) instead of calling the rest of the list "not found".
                    if (++failuresInRow >= UNREACHABLE_FAILURES) throw new IOException("SoundCloud search unreachable");
                    // Not looked at, not missing: it is not remembered, so a new try searches for it again.
                    missing.add(track.label() + text(" (не проверен: ошибка сети)", " (not checked: network error)"));
                    checked++;
                    changed();
                    continue;
                }
                if (!matches.has(track.id)) {
                    matches.put(track.id, new JSONObject().put("urn", urn).put("time", System.currentTimeMillis()));
                }
                if (urn.isEmpty()) missing.add(track.label());
                else urns.add(urn);
                checked++;
                found = urns.size();
                if (System.currentTimeMillis() - savedAt >= SAVE_EVERY_MS) {
                    TransferStore.saveMatches(matches);
                    savedAt = System.currentTimeMillis();
                }
                changed();
            }
        } finally {
            pool.shutdownNow();
            TransferStore.saveMatches(matches);
        }
        return new ArrayList<>(urns);
    }

    private static void fail(String message) {
        failure = message;
        phase = Phase.FAILED;
        changed();
        if (LISTENERS.isEmpty()) toast(message);
    }

    /** Tells the screen and the notification, at most once per {@link #CHANGED_MS}; the last state always arrives. */
    private static void changed() {
        if (!changePosted.compareAndSet(false, true)) return;
        Utils.runOnMainThreadDelayed(() -> {
            changePosted.set(false);
            Context context = Utils.getContext();
            if (context != null && isRunning()) ForegroundWork.update(context, status());
            for (Listener listener : LISTENERS) listener.onChanged();
        }, CHANGED_MS);
    }

    private static void toast(String message) {
        Context context = Utils.getContext();
        if (context == null) return;
        Utils.runOnMainThread(() -> Toast.makeText(context, text("Перенос из Яндекс Музыки: ", "Yandex Music transfer: ")
                + message, Toast.LENGTH_LONG).show());
    }
}
