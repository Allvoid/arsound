package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.SavedPlaylist;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The "For you" playlist: songs picked from what you like, by Last.fm.
 * <p>
 * Seeds are the liked tracks, the tracks of library playlists, the imported files and the most played
 * tracks, read from the phone ({@link LibrarySeeds}). For each seed Last.fm names songs people listen to together
 * with it; for the main artists also similar artists and their best songs, more of them when Last.fm does not know
 * the songs themselves ({@link ForYouCandidates}). Songs already
 * in the library and disliked ones are left out, at most two per artist. The rest is looked up on SoundCloud
 * ({@link SoundCloudMatcher}) and put into a playlist that lives on this phone only ({@link ForYouPlaylist}), like
 * "Imported". Refreshed once a day and by hand.
 * <p>
 * This class is what the settings, the midnight job and the listening statistics call.
 */
public final class ForYou {
    public static final String ENABLED = "for_you_enabled";

    private static volatile boolean running;

    private ForYou() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, true);
    }

    public static boolean isRunning() {
        return running;
    }

    /** What the last refresh did, for the settings screen. */
    public static String status() {
        return ForYouPrefs.status();
    }

    /** Called when the app starts (from {@link SavedPlaylist}): plans the midnight update. */
    public static void onAppStart() {
        Context context = Utils.getContext();
        if (context == null) return;
        if (!isEnabled() || !LastFm.hasKey()) {
            ForYouJob.cancel(context);
            return;
        }
        ForYouJob.schedule(context, lastRefresh());
    }

    /** An older version kept the list on SoundCloud: it moves to this phone. Blocks; runs on a background thread. */
    public static void moveToPhone() throws Exception {
        ForYouPlaylist.moveToPhone();
    }

    /** Switched in the settings: the midnight update is planned or cancelled. */
    public static void setEnabled(boolean enabled) {
        Settings.putBoolean(ENABLED, enabled);
        onAppStart();
    }

    /** When the playlist was last updated (or found nothing to add), 0 if never. */
    public static long lastRefresh() {
        return ForYouPrefs.lastRefresh();
    }

    /** Artist and title of a SoundCloud upload: "Artist - Title" uploads name the artist in the title. */
    public static String[] artistAndTitle(String title, String uploader) {
        return ForYouNames.artistAndTitle(title, uploader);
    }

    public static String artistKey(String artist) {
        return ForYouNames.artistKey(artist);
    }

    /** Progress and the result of a refresh, on the main thread. May be null. */
    public interface Listener {
        void onProgress(String message);
    }

    /** The midnight update. Blocks. */
    static RefreshResult.Outcome refreshScheduled() {
        RefreshResult result = refreshNow(null);
        // Another refresh is running (the button): the job asks again later.
        return result == null ? RefreshResult.Outcome.RETRY : result.outcome;
    }

    /** Builds the playlist again. Blocks: call it off the main thread. */
    public static void refresh(Listener listener) {
        refreshNow(listener);
    }

    /** @return How the refresh ended, or null if another one was running. */
    private static RefreshResult refreshNow(Listener listener) {
        // The midnight job and the button can start together: only one of them builds, or two playlists appear.
        synchronized (ForYou.class) {
            if (running) return null;
            running = true;
        }
        RefreshProgress progress = new RefreshProgress(listener);
        RefreshResult result;
        try {
            result = build(progress);
        } catch (Exception ex) {
            Logger.printException(() -> "For you: refresh failed", ex);
            result = RefreshResult.retry(text("Ошибка: ", "Error: ") + ex.getMessage());
        } finally {
            running = false;
        }
        ForYouPrefs.recordAttempt(result);
        String message = result.message;
        Logger.printInfo(() -> "For you: " + message);
        progress.say(message);
        return result;
    }

    private static RefreshResult needsVpn() {
        return RefreshResult.retry(text("Нужен VPN: Last.fm и SoundCloud не работают с российского IP",
                "Needs a VPN: Last.fm and SoundCloud do not serve Russian IP addresses"));
    }

    private static RefreshResult build(RefreshProgress progress) throws Exception {
        Context context = Utils.getContext();
        if (context == null) throw new IllegalStateException("No context");
        if (!LastFm.hasKey()) return RefreshResult.done(text("Нет ключа Last.fm в сборке", "No Last.fm key in this build"));
        if ("RU".equals(RegionGuard.lastCountry())) return needsVpn();
        // The user's own block of another country: every request would fail with a network error.
        if (RegionGuard.blocksLastCountry()) {
            return RefreshResult.retry(text("Сеть выключена блоком по стране IP (Настройки → Arsound → Сеть)",
                    "The network is off by the IP country block (Settings → Arsound → Network)"));
        }

        progress.say(text("Собираю вашу библиотеку…", "Reading your library…"));
        LibrarySeeds library = LibrarySeeds.read(context);
        int seedCount = library.all().size();
        // Not "done": right after a login the library on the phone is still empty, and the next try is only half an hour away.
        if (seedCount == 0) {
            return RefreshResult.retry(text("В библиотеке нет треков, от которых можно оттолкнуться",
                    "No tracks in the library to start from"));
        }
        List<LibrarySeeds.Seed> picked = library.pick();
        Logger.printInfo(() -> "For you: " + seedCount + " seeds, asking about " + picked.size());

        ForYouCandidates candidates = new ForYouCandidates(library.knownKeys());
        ForYouCandidates.Status status = candidates.gather(picked, progress);
        Logger.printInfo(() -> "For you: " + candidates.summary());
        switch (status) {
            case NEEDS_VPN:
                return needsVpn();
            case NO_ANSWER:
                return RefreshResult.retry(text("Last.fm не ответил — проверьте интернет",
                        "Last.fm did not answer, check the connection"));
            default:
                break;
        }

        Set<String> previous = ForYouPrefs.previousKeys();
        List<ForYouCandidates.Candidate> ranked = candidates.ranked(previous);
        Logger.printInfo(() -> "For you: " + ranked.size() + " candidates");

        List<String> entries = SoundCloudMatcher.match(ranked, progress);
        if (entries == null) {
            return RefreshResult.retry(text("SoundCloud недоступен (российский IP или нет сети) — плейлист не изменён",
                    "SoundCloud is not reachable (Russian IP or no network), playlist unchanged"));
        }
        if (entries.isEmpty()) {
            return RefreshResult.done(text("Ничего нового не нашлось в SoundCloud", "Nothing new found on SoundCloud"));
        }

        progress.say(text("Обновляю плейлист…", "Updating the playlist…"));
        String urn = ForYouPlaylist.ensure();
        if (urn == null) {
            String title = ForYouPlaylist.title();
            return RefreshResult.retry(text("Не удалось создать плейлист «" + title + "» в SoundCloud",
                    "Could not create the \"" + title + "\" playlist on SoundCloud"));
        }
        LocalAdditions.setEntries(urn, entries);
        Utils.runOnMainThread(() -> LocalAdditions.notifyPlaylistChanged(urn));
        return RefreshResult.filled(text("в плейлисте " + entries.size() + " треков", entries.size() + " tracks in the playlist"));
    }
}
