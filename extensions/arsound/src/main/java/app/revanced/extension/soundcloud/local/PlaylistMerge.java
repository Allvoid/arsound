package app.revanced.extension.soundcloud.local;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.offline.PlaylistPreloader;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Rx;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * "Merge playlists" in the menu of a playlist: the tracks of other playlists and albums of the library are
 * added to the end of this one.
 * <p>
 * The playlist whose menu was opened is the target. The user ticks the playlists to take tracks from and puts
 * them in order ({@link MergePicker}). Their tracks follow the target's own, in that order, each playlist in the
 * order it is shown in; a track the target has already, or an earlier playlist brought, is skipped. The playlists
 * taken from are not changed.
 * <p>
 * A playlist of the user is changed the way SoundCloud's own "Add to playlist" does it: its whole track list is
 * saved with {@code editPlaylistTracks}, which SoundCloud then sends to the server; the list is read once more right
 * before, so a track that came to it meanwhile is kept. The tracks of imported files stay on this phone. A playlist
 * of somebody else (or an album) cannot be edited on the server, so its new tracks are added on this phone only,
 * like "Add to playlist locally".
 * <p>
 * A playlist screen lists the tracks on SoundCloud first and the ones added on this phone after them. When that
 * would put the new tracks in another order than the one chosen (files among the tracks, or files the playlist had
 * already), the order is saved as the manual order of the playlist ({@link TrackOrder}).
 */
public final class PlaylistMerge {
    /** SoundCloud does not keep more tracks than this in one playlist. */
    static final int MAX_TRACKS = 500;
    private static final String ROW_TAG = "arsound_playlist_merge_row";
    private static final String URN_CLASS = "com.soundcloud.android.foundation.domain.Urn";

    /** One merge at a time: two would each write a track list that does not know of the other. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private PlaylistMerge() {
    }

    private static boolean russian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    static String text(String russian, String english) {
        return russian() ? russian : english;
    }

    /** "1 трек", "2 трека", "5 треков", "1 track", "2 tracks". */
    static String tracks(int count) {
        if (!russian()) return count + (count == 1 ? " track" : " tracks");
        int lastTwo = count % 100, last = count % 10;
        String word = last == 1 && lastTwo != 11 ? "трек"
                : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "трека" : "треков";
        return count + " " + word;
    }

    /** A playlist or album of the library, as the dialog lists it. */
    static final class Candidate {
        final String urn;
        final String title;
        /** The number of tracks the library shows for it: the ones on SoundCloud and the ones added on this phone. */
        final int count;
        /** The number of tracks SoundCloud lists in it; -1 if the playlist is not known to the library. */
        final int serverCount;
        /** What {@link LocalCovers#playlistArtwork} gave: a file, SoundCloud's link with the size open, or nothing. */
        final String cover;
        final boolean editable;
        final boolean album;
        /** A station or a playlist SoundCloud makes for the user: not a playlist to take tracks from. */
        final boolean generated;

        Candidate(String urn, String title, int count, int serverCount, String cover, boolean editable,
                  boolean album, boolean generated) {
            this.urn = urn;
            this.title = title;
            this.count = count;
            this.serverCount = serverCount;
            this.cover = cover;
            this.editable = editable;
            this.album = album;
            this.generated = generated;
        }
    }

    /** What the dialog needs: the playlist to merge into and the ones to take tracks from, in library order. */
    static final class Library {
        Candidate target;
        final List<Candidate> sources = new ArrayList<>();
    }

    /** What a merge did, for the message at the end. */
    private static final class Result {
        int added;
        int duplicates;
        int overLimit;
        final List<String> failed = new ArrayList<>();
        /** What went wrong, for the user; the playlist is unchanged unless {@link #touched}. */
        String error;
        /** The user stopped it before anything was saved. */
        boolean cancelled;
        boolean onPhoneOnly;
        boolean offline;
        /** Imported files that went into a playlist of the user: they stay on this phone. */
        int filesOnPhone;
        /** Tracks that could not be added on this phone after the playlist was saved. */
        int notOnPhone;
        /** The playlist was, or may have been, changed: the screens and the library have to read it again. */
        boolean touched;
    }

    // region Menu

    /** The row of the playlist menu. */
    static void addMenuRow(Dialog dialog, LinearLayout menuItems, String playlistUrn, Object menuData) {
        if (menuItems.findViewWithTag(ROW_TAG) != null) return;
        Context context = dialog.getContext();
        // Read now: the menu data is the one place that says if SoundCloud offers "Add music" for this playlist.
        boolean menuEditable = hasMenuItem(menuData, "PlaylistMenuItem$AddMusic");
        ViewGroup row = DownloadTrackPatch.createMenuRow(context,
                text("Совместить плейлисты", "Merge playlists"),
                "ic_actions_playlist_add_to_playlist", v -> {
                    Context activity = dialog.getOwnerActivity() != null ? dialog.getOwnerActivity() : context;
                    dialog.dismiss();
                    open(activity, playlistUrn, menuEditable);
                });
        row.setTag(ROW_TAG);
        menuItems.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Whether a list in the menu data holds an item of this class. The field names are obfuscated, so all lists are looked at. */
    private static boolean hasMenuItem(Object menuData, String className) {
        if (menuData == null) return false;
        try {
            for (Field field : menuData.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(menuData);
                if (!(value instanceof List)) continue;
                for (Object item : (List<?>) value) {
                    if (item != null && item.getClass().getName().contains(className)) return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the playlist menu", ex);
        }
        return false;
    }

    // endregion

    // region Library

    private static void open(Context activity, String targetUrn, boolean menuEditable) {
        Utils.runOnBackgroundThread(() -> {
            Library library = null;
            try {
                library = readLibrary(targetUrn, menuEditable);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not list the playlists to merge", ex);
            }
            Library found = library;
            Utils.runOnMainThread(() -> {
                if (found == null) {
                    toast(activity, text("Библиотека ещё не загружена, попробуйте через минуту",
                            "The library is not loaded yet, try again in a minute"));
                } else if (found.sources.isEmpty()) {
                    toast(activity, text("В библиотеке нет других плейлистов с треками",
                            "There are no other playlists with tracks in the library"));
                } else {
                    new MergePicker(activity, found, sources -> start(activity, found.target, sources)).show();
                }
            });
        });
    }

    /** @return The playlists, or null if the library is not loaded. Blocks: call it off the main thread. */
    private static Library readLibrary(String targetUrn, boolean menuEditable) {
        // The database only: it works without a network and does not wait for a sync.
        List<Object> items = PlaylistPreloader.libraryItems("LOCAL_ONLY");
        if (items.isEmpty()) return null;
        Library library = new Library();
        for (Object item : items) {
            Candidate candidate = candidate(item);
            if (candidate == null) continue;
            if (candidate.urn.equals(targetUrn)) {
                library.target = candidate;
            } else if (!candidate.generated && candidate.count > 0) {
                library.sources.add(candidate);
            }
        }
        if (library.target == null) {
            // A playlist that is not in the library, for example somebody else's: the menu says if it can be edited.
            String title = null;
            for (String[] recent : LocalAdditions.readRecentPlaylists()) {
                if (recent[0].equals(targetUrn)) title = recent[1];
            }
            if (title == null || title.isEmpty()) title = text("Этот плейлист", "This playlist");
            library.target = new Candidate(targetUrn, title, -1, -1, null, menuEditable, false, false);
        }
        return library;
    }

    private static Candidate candidate(Object item) {
        try {
            String urn = String.valueOf(call(item, "getUrn"));
            int soundCloudCount = (Integer) call(item, "getTracksCount");
            Object playlist = call(item, "getPlaylist");
            return new Candidate(urn,
                    String.valueOf(call(item, "getTitle")),
                    PlaylistTracks.shownCount(urn, soundCloudCount),
                    PlaylistTracks.serverCount(urn, soundCloudCount),
                    (String) call(playlist, "getArtworkImageUrl"),
                    Boolean.TRUE.equals(call(call(item, "getPermissions"), "isEditable")),
                    Boolean.TRUE.equals(call(item, "isAlbum")),
                    Boolean.TRUE.equals(call(item, "isStation")) || Boolean.TRUE.equals(call(item, "isSystemPlaylist")));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read a library playlist", ex);
            return null;
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    // endregion

    // region Merge

    private static void start(Context activity, Candidate target, List<Candidate> sources) {
        if (!RUNNING.compareAndSet(false, true)) {
            toast(activity, text("Совмещение уже идёт", "A merge is already running"));
            return;
        }
        Progress progress = new Progress(activity);
        Utils.runOnBackgroundThread(() -> {
            Result result = new Result();
            progress.attach(Thread.currentThread());
            try {
                merge(target, sources, progress, result);
            } catch (Throwable ex) {
                if (progress.cancelled()) {
                    // Cancel woke a read that was waiting for SoundCloud. Cancel and the start of the save exclude each
                    // other, so nothing was saved.
                    result.cancelled = true;
                } else {
                    Logger.printException(() -> "Could not merge the playlists", ex);
                    // The tracks may have been saved before the error: it is not said that nothing changed then.
                    result.error = result.touched
                            ? text("Что-то пошло не так, результат может быть неполным. Откройте плейлист и проверьте.",
                            "Something went wrong, the result may be partial. Open the playlist to check.")
                            : text("Что-то пошло не так. Плейлист не изменён.", "Something went wrong. The playlist was not changed.");
                }
            } finally {
                progress.detach();
                RUNNING.set(false);
            }
            Utils.runOnMainThread(() -> {
                progress.dismiss();
                // Open playlist screens and the library show the tracks and the numbers.
                if (result.added > 0 || result.touched) LocalAdditions.notifyPlaylistChanged(target.urn);
                if (result.cancelled) {
                    toast(activity, text("Совмещение отменено, плейлист не изменён", "Merge cancelled, the playlist was not changed"));
                } else {
                    showResult(activity, target, result);
                }
            });
        });
    }

    /** A track to add, in the order chosen. */
    private static final class Incoming {
        final Object urn;
        /** The urn text of a SoundCloud track, the file entry of an imported file. */
        final String entry;
        /** It goes to the track list of the playlist on SoundCloud; otherwise it is added on this phone only. */
        final boolean server;
        /** Left out at the last moment: the playlist has it by now, or it no longer fits. */
        boolean skipped;

        Incoming(Object urn, String entry, boolean server) {
            this.urn = urn;
            this.entry = entry;
            this.server = server;
        }
    }

    /**
     * Blocks: call it off the main thread. Either writes the new tracks or changes nothing; {@code result} says what
     * was done, also when an error stops the merge after the tracks were saved.
     *
     * @param progress Looked at between the playlists, until the tracks are saved.
     */
    private static void merge(Candidate target, List<Candidate> sources, Progress progress, Result result) throws Exception {
        Object operations = SavedPlaylist.playlistOperations();
        if (operations == null) {
            result.error = text("SoundCloud ещё запускается, повторите через минуту.", "SoundCloud is still starting, try again in a minute.");
            return;
        }
        Class<?> type = operations.getClass();
        Class<?> urnClass = Class.forName(URN_CLASS, false, type.getClassLoader());
        Method forPlaylist = urnClass.getMethod("forPlaylist", String.class);
        Object targetUrn = forPlaylist.invoke(null, idOf(target.urn));

        progress.update(text("Читаю «" + target.title + "»", "Reading \"" + target.title + "\""));
        // The tracks the target has on SoundCloud. An edit saves the whole list, so it has to be there in full.
        List<Object> existing = serverTracks(operations, urnClass, targetUrn);
        boolean complete = existing != null && !(existing.isEmpty() && target.serverCount != 0);
        if (target.editable && !complete) {
            result.error = text("Плейлист ещё не загружен. Откройте его, дождитесь списка треков и повторите.",
                    "The playlist is not loaded yet. Open it, wait for the tracks and try again.");
            return;
        }
        if (existing == null) existing = new ArrayList<>();

        // Everything the target holds already: its tracks on SoundCloud and the ones added on this phone.
        List<String> earlier = LocalAdditions.getEntries(target.urn);
        Set<String> present = new HashSet<>();
        for (Object urn : existing) present.add(String.valueOf(urn));
        present.addAll(earlier);

        List<Incoming> incoming = new ArrayList<>();
        Set<String> notFitted = new HashSet<>();
        int serverTracks = existing.size();
        int position = 0;
        for (Candidate source : sources) {
            if (progress.cancelled()) {
                result.cancelled = true;
                return;
            }
            position++;
            String step = position + "/" + sources.size();
            progress.update(text("Читаю «" + source.title + "» (" + step + ")", "Reading \"" + source.title + "\" (" + step + ")"));
            List<?> urns = readTracks(operations, urnClass, forPlaylist, source, progress);
            if (urns == null) {
                result.failed.add(source.title);
                continue;
            }
            for (Object urn : urns) {
                boolean file = isFile(urn);
                String entry = file ? LocalAdditions.entryOf(urn) : String.valueOf(urn);
                if (entry == null) continue;
                if (present.contains(entry)) {
                    result.duplicates++;
                } else if (file || !target.editable) {
                    // A file cannot be on SoundCloud, nor can anything on a playlist that is not the user's.
                    present.add(entry);
                    incoming.add(new Incoming(urn, entry, false));
                } else if (serverTracks >= MAX_TRACKS) {
                    notFitted.add(entry);
                } else {
                    present.add(entry);
                    incoming.add(new Incoming(urn, entry, true));
                    serverTracks++;
                }
            }
        }
        result.overLimit = notFitted.size();

        if (incoming.isEmpty()) {
            logMerge(target, sources, result);
            return;
        }
        // Cancel no longer works from here: the tracks are saved.
        if (!progress.commit()) {
            result.cancelled = true;
            return;
        }
        progress.update(text("Записываю в «" + target.title + "»", "Saving to \"" + target.title + "\""));

        // The whole list of the target is saved, so it is read again now: a track that came to it while the playlists
        // were read (added on the web or on another phone, then synced) must not be lost.
        Set<Object> merged = null;
        if (hasServerTracks(incoming)) {
            List<Object> latest = serverTracks(operations, urnClass, targetUrn);
            if (latest == null || (latest.isEmpty() && !existing.isEmpty())) {
                result.error = text("Не удалось перечитать плейлист перед записью. Он не изменён.",
                        "Could not read the playlist again before saving. It was not changed.");
                return;
            }
            merged = new LinkedHashSet<>(latest);
            Set<String> inList = new HashSet<>();
            for (Object urn : latest) inList.add(String.valueOf(urn));
            int size = latest.size();
            for (Incoming item : incoming) {
                if (!item.server) continue;
                if (inList.contains(item.entry)) {
                    item.skipped = true;
                    result.duplicates++;
                } else if (size >= MAX_TRACKS) {
                    item.skipped = true;
                    notFitted.add(item.entry);
                } else {
                    merged.add(item.urn);
                    size++;
                }
            }
            result.overLimit = notFitted.size();
        }
        int sent = 0;
        for (Incoming item : incoming) if (item.server && !item.skipped) sent++;

        // The screen lists the tracks on SoundCloud first and the ones added on this phone after them. If the order
        // chosen is another one, the playlist gets it as its own order. With the setting off nobody shows or uses that
        // order, and saving it would replace an arrangement the user may want back when the setting is turned on.
        List<String> wanted = null;
        if (target.editable && sent > 0 && Settings.isPlaylistOrderEnabled()
                && (!earlier.isEmpty() || filesBeforeTracks(incoming))) {
            wanted = chosenOrder(operations, urnClass, targetUrn, incoming);
        }

        if (sent > 0) {
            result.touched = true;
            Throwable failure = Rx.awaitCompletable(
                    type.getMethod("editPlaylistTracks", urnClass, Set.class).invoke(operations, targetUrn, merged),
                    30, TimeUnit.SECONDS);
            if (failure != null) {
                Logger.printException(() -> "Could not save the merged playlist", failure);
                // Nothing is added on the phone either: the target stays as it was. A save that is only late may still go through.
                boolean late = failure instanceof TimeoutException;
                result.touched = late;
                result.error = late
                        ? text("SoundCloud не подтвердил запись вовремя. Плейлист мог измениться: откройте его и проверьте.",
                        "The save did not finish in time. The playlist may have changed: open it to check.")
                        : text("Не удалось записать треки в плейлист. Он не изменён.",
                        "Could not save the tracks to the playlist. It was not changed.");
                return;
            }
            result.added += sent;
            // SoundCloud is out of reach also when the region guard blocks its requests, though the phone has a network.
            result.offline = !isOnline() || RegionGuard.blocksLastCountry();
            try {
                // Sends the change to SoundCloud, as its own "Add to playlist" sheet does.
                type.getMethod("syncPlaylist", urnClass).invoke(operations, targetUrn);
            } catch (Exception ex) {
                // The tracks are saved here already, and SoundCloud sends them with its next sync.
                Logger.printException(() -> "Could not ask SoundCloud to send the merged playlist", ex);
            }
        }

        List<String> onPhone = new ArrayList<>();
        for (Incoming item : incoming) if (!item.server) onPhone.add(item.entry);
        if (!onPhone.isEmpty()) {
            result.touched = true;
            try {
                int saved = LocalAdditions.addAll(target.urn, onPhone);
                // The list of local tracks names them by their titles: loaded one after another from now on, not when it is opened.
                LocalAdditions.rememberTitles(onPhone);
                result.added += saved;
                if (target.editable) result.filesOnPhone = saved;
                result.onPhoneOnly = !target.editable;
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not add the merged tracks on this phone", ex);
                result.notOnPhone = onPhone.size();
            }
        }
        if (wanted != null && result.notOnPhone == 0) TrackOrder.saveUrns(target.urn, wanted);
        logMerge(target, sources, result);
    }

    private static void logMerge(Candidate target, List<Candidate> sources, Result result) {
        Logger.printInfo(() -> "Merged " + sources.size() + " playlists into " + target.urn + ": added " + result.added
                + ", already there " + result.duplicates + ", over the limit " + result.overLimit + ", unreadable " + result.failed.size());
    }

    private static boolean isFile(Object urn) {
        return urn.getClass().getName().endsWith(".LocalTrackUrn");
    }

    private static boolean hasServerTracks(List<Incoming> incoming) {
        for (Incoming item : incoming) if (item.server) return true;
        return false;
    }

    /** Whether a file comes before a track of SoundCloud in the order chosen: the screen would put it after it. */
    private static boolean filesBeforeTracks(List<Incoming> incoming) {
        boolean file = false;
        for (Incoming item : incoming) {
            if (!item.server) file = true;
            else if (file) return true;
        }
        return false;
    }

    /**
     * The order of the target as its screen shows it now, then the tracks to add in the order chosen.
     *
     * @return The urn texts, or null if the list of the screen cannot be read: the playlist then keeps the order of the screen.
     */
    private static List<String> chosenOrder(Object operations, Class<?> urnClass, Object targetUrn, List<Incoming> incoming) {
        try {
            Object single = operations.getClass().getMethod("playlistTrackUrns", urnClass).invoke(operations, targetUrn);
            Object shown = Rx.blockingFirst(LocalAdditions.appendToTrackUrns(single, targetUrn), 20, TimeUnit.SECONDS);
            if (!(shown instanceof List)) return null;
            Set<String> order = new LinkedHashSet<>();
            for (Object urn : (List<?>) shown) order.add(String.valueOf(urn));
            for (Incoming item : incoming) if (!item.skipped) order.add(String.valueOf(item.urn));
            return new ArrayList<>(order);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the order of the playlist screen", ex);
            return null;
        }
    }

    /** The tracks a playlist has on SoundCloud, from the database: no local additions. Null if the list is not there. */
    private static List<Object> serverTracks(Object operations, Class<?> urnClass, Object playlistUrn) throws Exception {
        Object stored = Rx.blockingFirst(operations.getClass().getMethod("playlistTrackUrns", urnClass).invoke(operations, playlistUrn),
                20, TimeUnit.SECONDS);
        return stored instanceof List ? new ArrayList<>((List<?>) stored) : null;
    }

    /** The tracks of a playlist as they play: SoundCloud's and the ones added here, in the order the user set. */
    private static List<?> readTracks(Object operations, Class<?> urnClass, Method forPlaylist, Candidate source, Progress progress) {
        try {
            Object urn = forPlaylist.invoke(null, idOf(source.urn));
            if (source.serverCount > 0 && !hasStoredTracks(operations, urnClass, urn)) {
                // A playlist that was never opened has none of its SoundCloud tracks stored. SoundCloud loads it by itself
                // only when the list it reads is empty, but that list also holds the tracks added on this phone: load it here.
                if (RegionGuard.blocksLastCountry() || progress.cancelled()) return null;
                if (!PlaylistPreloader.syncAndWait(operations, urn) || !hasStoredTracks(operations, urnClass, urn)) return null;
            }
            Object single = operations.getClass().getMethod("trackUrnsForPlayback", urnClass).invoke(operations, urn);
            Object list = Rx.blockingFirst(single, 60, TimeUnit.SECONDS);
            if (!(list instanceof List)) return null;
            // A playlist that has tracks but gives none was not loaded.
            return ((List<?>) list).isEmpty() && source.count > 0 ? null : (List<?>) list;
        } catch (InterruptedException ex) {
            // Cancel was pressed while waiting for SoundCloud: the merge sees it and stops. Not an error to log.
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the tracks of " + source.urn, ex);
            return null;
        }
    }

    private static boolean hasStoredTracks(Object operations, Class<?> urnClass, Object urn) throws Exception {
        List<Object> stored = serverTracks(operations, urnClass, urn);
        return stored != null && !stored.isEmpty();
    }

    private static String idOf(String urn) {
        return urn.substring(urn.lastIndexOf(':') + 1);
    }

    private static boolean isOnline() {
        try {
            return Utils.isNetworkConnected();
        } catch (Exception ex) {
            return true;
        }
    }

    // endregion

    // region Messages

    private static void showResult(Context activity, Candidate target, Result result) {
        StringBuilder message = new StringBuilder();
        String title;
        if (result.error != null) {
            title = text("Не удалось совместить", "Could not merge");
            message.append(result.error);
            if (result.added > 0) message.append('\n').append(text("Добавлено ", "Added ")).append(tracks(result.added));
        } else {
            title = result.added > 0 ? text("Плейлисты совмещены", "Playlists merged")
                    : result.failed.isEmpty() ? text("Нечего добавлять", "Nothing to add")
                    : text("Не удалось совместить", "Could not merge");
            message.append(text("Добавлено ", "Added ")).append(tracks(result.added));
            if (result.duplicates > 0) {
                message.append(text(", " + result.duplicates + " уже были", ", " + result.duplicates + " were already there"));
            }
            if (result.overLimit > 0) {
                message.append('\n').append(text("Не поместилось: ", "Did not fit: ")).append(result.overLimit)
                        .append(text(" (в плейлисте не больше " + MAX_TRACKS + " треков)",
                                " (a playlist holds " + MAX_TRACKS + " tracks at most)"));
            }
            if (!result.failed.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (String name : result.failed) names.append(names.length() == 0 ? "" : ", ").append('«').append(name).append('»');
                message.append('\n').append(text("Не удалось прочитать: ", "Could not read: ")).append(names);
                message.append('\n').append(RegionGuard.blocksLastCountry()
                        ? text("Запросы к SoundCloud сейчас заблокированы защитой по региону.", "Requests to SoundCloud are blocked by the region guard now.")
                        : !isOnline() ? text("Нет сети: такие плейлисты читаются только с интернетом.", "No network: these playlists need the internet.")
                        : text("Проверьте сеть и повторите.", "Check the network and try again."));
            }
            if (result.notOnPhone > 0) {
                message.append('\n').append(text("Не удалось сохранить на телефоне: " + tracks(result.notOnPhone) + ".",
                        "Could not save on this phone: " + tracks(result.notOnPhone) + "."));
            }
            List<String> notes = new ArrayList<>();
            if (result.onPhoneOnly) {
                notes.add(text("«" + target.title + "» не ваш плейлист: треки добавлены только на этом телефоне и не попадут в SoundCloud.",
                        "\"" + target.title + "\" is not your playlist: the tracks are added on this phone only and do not go to SoundCloud."));
            }
            if (result.filesOnPhone > 0) {
                notes.add(text("Файлы с этого телефона (" + result.filesOnPhone + ") остаются только на нём и не попадут в SoundCloud.",
                        "The files from this phone (" + result.filesOnPhone + ") stay on this phone and do not go to SoundCloud."));
            }
            if (result.offline) {
                notes.add(text("Нет связи с SoundCloud (нет сети или включена защита по региону): изменения уйдут, когда она появится.",
                        "No connection to SoundCloud (no network, or the region guard is on): the changes go out when it is back."));
            }
            if (!notes.isEmpty()) message.append("\n\n").append(TextUtils.join("\n", notes));
        }

        boolean gone = activity instanceof Activity && (((Activity) activity).isFinishing() || ((Activity) activity).isDestroyed());
        if (gone) {
            // The screen was left while the merge ran: a dialog has no window to open in.
            Context app = Utils.getContext();
            if (app != null) toast(app, title + ". " + message.toString().split("\n")[0]);
            return;
        }
        new ArsoundDialog(activity).setTitle(title).setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    // endregion

    /**
     * A dialog with a spinner while the tracks are read and saved. It opens only if that takes a moment. Its Cancel
     * button asks the merge to stop before the tracks are saved, and wakes it from a read that waits for SoundCloud;
     * the dialog stays until the merge has stopped.
     */
    private static final class Progress {
        private static final long SHOW_AFTER_MS = 400;
        private static final int RUNNING_STATE = 0, CANCELLED_STATE = 1, SAVING_STATE = 2;

        private final Context context;
        /** One state for the button and for the merge, so Cancel and the start of the save cannot both win. */
        private final AtomicInteger state = new AtomicInteger(RUNNING_STATE);
        private final TextView label;
        private final View content;
        private volatile Dialog dialog;
        private volatile boolean finished;
        /** The thread that runs the merge, while it does. Guarded by this. */
        private Thread worker;

        Progress(Context context) {
            this.context = context;
            int accent = ArsoundTheme.palette(context, "special");
            float density = context.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(android.view.Gravity.CENTER_VERTICAL);
            box.setPadding(Math.round(24 * density), Math.round(8 * density), Math.round(24 * density), Math.round(8 * density));
            // The colours are set here, so the dialog leaves the texts alone.
            box.setTag(ArsoundDialog.KEEP_COLORS);
            ProgressBar bar = new ProgressBar(context);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
            box.addView(bar, new LinearLayout.LayoutParams(Math.round(32 * density), Math.round(32 * density)));
            label = new TextView(context);
            int style = Utils.getResourceIdentifier(ResourceType.STYLE, "Body.Secondary");
            if (style != 0) label.setTextAppearance(style);
            label.setTextColor(ArsoundTheme.palette(context, "secondary"));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.leftMargin = Math.round(16 * density);
            box.addView(label, labelParams);
            content = box;
            Utils.runOnMainThreadDelayed(this::show, SHOW_AFTER_MS);
        }

        private void show() {
            if (finished) return;
            dialog = new ArsoundDialog(context).setTitle(text("Совмещаю плейлисты", "Merging playlists"))
                    .setView(content).setCancelable(false)
                    .setNegativeButtonKeepingOpen(context.getString(android.R.string.cancel), (d, which) -> {
                        if (!state.compareAndSet(RUNNING_STATE, CANCELLED_STATE)) return;
                        label.setText(text("Отменяю…", "Cancelling…"));
                        showCancelState();
                        wake();
                    }).show();
            showCancelState();
        }

        /** The merge runs on this thread from now on. */
        synchronized void attach(Thread thread) {
            worker = thread;
        }

        /**
         * The merge is over. Cancel can no longer interrupt the thread, which goes back to the pool, and an interrupt
         * that came too late is cleared so it does not reach the next task.
         */
        synchronized void detach() {
            worker = null;
            Thread.interrupted();
        }

        /** Cancel has won: a read that waits for SoundCloud (up to a minute) is stopped instead of waited for. */
        private synchronized void wake() {
            if (worker != null) worker.interrupt();
        }

        /** The button works while the merge can still be stopped. */
        private void showCancelState() {
            Dialog shown = dialog;
            TextView button = shown == null ? null : ArsoundDialog.getButton(shown, DialogInterface.BUTTON_NEGATIVE);
            if (button == null) return;
            boolean open = state.get() == RUNNING_STATE;
            button.setEnabled(open);
            button.setAlpha(open ? 1f : 0.4f);
        }

        void update(String text) {
            // After Cancel the label keeps saying so.
            Utils.runOnMainThread(() -> {
                if (state.get() != CANCELLED_STATE) label.setText(text);
            });
        }

        /** The user pressed Cancel. */
        boolean cancelled() {
            return state.get() == CANCELLED_STATE;
        }

        /**
         * The tracks are about to be saved: from here Cancel does nothing.
         *
         * @return False if the user cancelled before.
         */
        boolean commit() {
            if (!state.compareAndSet(RUNNING_STATE, SAVING_STATE)) return false;
            Utils.runOnMainThread(this::showCancelState);
            return true;
        }

        void dismiss() {
            finished = true;
            try {
                if (dialog != null) dialog.dismiss();
            } catch (IllegalArgumentException ex) {
                // The window is gone with its screen.
                Logger.printInfo(() -> "The progress dialog was already closed");
            }
        }
    }
}
