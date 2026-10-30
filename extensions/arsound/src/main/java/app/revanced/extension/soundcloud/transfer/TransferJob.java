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
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.ClientProfiles;

/**
 * One transfer at a time, from a Yandex Music collection to private SoundCloud playlists: the tracks are read, each
 * is looked up on SoundCloud (a few at once, remembered in {@link TransferStore}), and the ones found become the
 * playlist in the same order. It runs on while the settings are closed; the screen follows it through a listener.
 */
public final class TransferJob {
    public interface Listener {
        /** Called on the main thread whenever the state below changes. */
        void onChanged();
    }

    public enum Phase {IDLE, READING, SEARCHING, CREATING, DONE, FAILED}

    private static final int SEARCH_THREADS = 3;
    /** This many failed searches in a row, with nothing found, mean SoundCloud is out of reach. */
    private static final int UNREACHABLE_FAILURES = 8;
    private static final int SAVE_EVERY = 25;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String title = "";
    private static volatile int total;
    private static volatile int checked;
    private static volatile int found;
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
        return current == Phase.READING || current == Phase.SEARCHING || current == Phase.CREATING;
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
                + (entry.playlists.size() > 1 ? text(" (плейлистов: ", " (playlists: ") + entry.playlists.size() + ")" : "");
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

    /** @return False if a transfer is already running. */
    public static synchronized boolean start(YandexLink link) {
        if (isRunning()) return false;
        title = "";
        total = 0;
        checked = 0;
        found = 0;
        failure = null;
        result = null;
        phase = Phase.READING;
        changed();
        Thread thread = new Thread(() -> run(link), "Arsound transfer");
        worker = thread;
        thread.start();
        return true;
    }

    /** Stops the transfer; the tracks looked up so far are kept, so starting it again goes quicker. */
    public static void cancel() {
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    private static void run(YandexLink link) {
        try {
            YandexMusic.Collection collection = read(link);
            title = collection.title;
            total = collection.tracks.size();
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
            List<String> playlists = SoundCloudSide.createPlaylists(collection.title, urns);
            TransferRecord entry = new TransferRecord(link.key(), collection.title, System.currentTimeMillis(),
                    total, urns.size(), playlists, missing);
            TransferStore.addToHistory(entry);
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
        } catch (Exception ex) {
            Logger.printException(() -> "Transfer failed", ex);
            fail(ex instanceof IOException
                    ? text("Нет связи: проверьте интернет и попробуйте снова", "No connection: check the internet and try again")
                    : text("Перенос не удался: ", "The transfer failed: ") + ex);
        } finally {
            worker = null;
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
                    urn = answers.get(i).get(90, TimeUnit.SECONDS);
                    failuresInRow = 0;
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Transfer: search failed for " + track.label() + ": " + ex);
                    // Every search failing means SoundCloud is out of reach, not that the songs are missing.
                    if (++failuresInRow >= UNREACHABLE_FAILURES && urns.isEmpty()) {
                        throw new IOException("SoundCloud search unreachable");
                    }
                    missing.add(track.label());
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
                if (checked % SAVE_EVERY == 0) TransferStore.saveMatches(matches);
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

    private static void changed() {
        Utils.runOnMainThread(() -> {
            for (Listener listener : LISTENERS) listener.onChanged();
        });
    }

    private static void toast(String message) {
        Context context = Utils.getContext();
        if (context == null) return;
        Utils.runOnMainThread(() -> Toast.makeText(context, text("Перенос из Яндекс Музыки: ", "Yandex Music transfer: ")
                + message, Toast.LENGTH_LONG).show());
    }
}
