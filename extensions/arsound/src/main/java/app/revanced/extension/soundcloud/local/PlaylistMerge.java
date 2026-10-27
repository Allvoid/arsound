package app.revanced.extension.soundcloud.local;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.offline.PlaylistPreloader;
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
 * saved with {@code editPlaylistTracks}, which SoundCloud then sends to the server. The tracks of imported files
 * stay on this phone. A playlist of somebody else (or an album) cannot be edited on the server, so its new tracks
 * are added on this phone only, like "Add to playlist locally".
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
        /** Set if nothing was changed: what went wrong, for the user. */
        String error;
        /** The user stopped it before anything was saved. */
        boolean cancelled;
        boolean onPhoneOnly;
        boolean offline;
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
            int serverCount = (Integer) call(item, "getTracksCount");
            Object playlist = call(item, "getPlaylist");
            return new Candidate(urn,
                    String.valueOf(call(item, "getTitle")),
                    PlaylistTracks.shownCount(urn, serverCount),
                    serverCount,
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
        AtomicBoolean cancelled = new AtomicBoolean();
        Progress progress = new Progress(activity, cancelled);
        Utils.runOnBackgroundThread(() -> {
            Result result = new Result();
            try {
                result = merge(target, sources, progress::update, cancelled);
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not merge the playlists", ex);
                result.error = text("Что-то пошло не так. Плейлист не изменён.", "Something went wrong. The playlist was not changed.");
            } finally {
                RUNNING.set(false);
            }
            Result done = result;
            Utils.runOnMainThread(() -> {
                progress.dismiss();
                // Open playlist screens and the library show the tracks and the numbers.
                if (done.added > 0) LocalAdditions.notifyPlaylistChanged(target.urn);
                if (done.cancelled) {
                    toast(activity, text("Совмещение отменено, плейлист не изменён", "Merge cancelled, the playlist was not changed"));
                } else {
                    showResult(activity, target, done);
                }
            });
        });
    }

    /**
     * Blocks: call it off the main thread. Either writes the new tracks or changes nothing.
     *
     * @param cancelled Set by the user; looked at between the playlists, until the tracks are saved.
     */
    private static Result merge(Candidate target, List<Candidate> sources, Consumer<String> progress,
                                AtomicBoolean cancelled) throws Exception {
        Result result = new Result();
        Object operations = SavedPlaylist.playlistOperations();
        if (operations == null) {
            result.error = text("SoundCloud ещё запускается, повторите через минуту.", "SoundCloud is still starting, try again in a minute.");
            return result;
        }
        Class<?> type = operations.getClass();
        Class<?> urnClass = Class.forName(URN_CLASS, false, type.getClassLoader());
        Method forPlaylist = urnClass.getMethod("forPlaylist", String.class);
        Object targetUrn = forPlaylist.invoke(null, idOf(target.urn));

        progress.accept(text("Читаю «" + target.title + "»", "Reading \"" + target.title + "\""));
        // The tracks the target has on SoundCloud. An edit saves the whole list, so it has to be there in full.
        Object stored = Rx.blockingFirst(type.getMethod("playlistTrackUrns", urnClass).invoke(operations, targetUrn),
                20, TimeUnit.SECONDS);
        List<Object> existing = new ArrayList<>();
        if (stored instanceof List) existing.addAll((List<?>) stored);
        boolean complete = stored instanceof List && !(existing.isEmpty() && target.serverCount != 0);
        if (target.editable && !complete) {
            result.error = text("Плейлист ещё не загружен. Откройте его, дождитесь списка треков и повторите.",
                    "The playlist is not loaded yet. Open it, wait for the tracks and try again.");
            return result;
        }

        // Everything the target holds already: its tracks on SoundCloud and the ones added on this phone.
        Set<String> present = new HashSet<>();
        for (Object urn : existing) present.add(String.valueOf(urn));
        present.addAll(LocalAdditions.getEntries(target.urn));

        Set<Object> merged = new LinkedHashSet<>(existing);
        List<String> onPhone = new ArrayList<>();
        Set<String> notFitted = new HashSet<>();
        int serverTracks = existing.size();
        int position = 0;
        for (Candidate source : sources) {
            if (cancelled.get()) {
                result.cancelled = true;
                return result;
            }
            position++;
            String step = position + "/" + sources.size();
            progress.accept(text("Читаю «" + source.title + "» (" + step + ")", "Reading \"" + source.title + "\" (" + step + ")"));
            List<?> urns = readTracks(operations, urnClass, forPlaylist, source);
            if (urns == null) {
                result.failed.add(source.title);
                continue;
            }
            for (Object urn : urns) {
                boolean file = urn.getClass().getName().endsWith(".LocalTrackUrn");
                String entry = file ? LocalAdditions.entryOf(urn) : String.valueOf(urn);
                if (entry == null) continue;
                if (present.contains(entry)) {
                    result.duplicates++;
                } else if (file || !target.editable) {
                    // A file cannot be on SoundCloud, nor can anything on a playlist that is not the user's.
                    present.add(entry);
                    onPhone.add(entry);
                } else if (serverTracks >= MAX_TRACKS) {
                    notFitted.add(entry);
                } else {
                    present.add(entry);
                    merged.add(urn);
                    serverTracks++;
                }
            }
        }
        result.overLimit = notFitted.size();

        if (cancelled.get()) {
            result.cancelled = true;
            return result;
        }
        int sent = merged.size() - existing.size();
        if (sent > 0) {
            progress.accept(text("Записываю в «" + target.title + "»", "Saving to \"" + target.title + "\""));
            Throwable failure = Rx.awaitCompletable(
                    type.getMethod("editPlaylistTracks", urnClass, Set.class).invoke(operations, targetUrn, merged),
                    30, TimeUnit.SECONDS);
            if (failure != null) {
                Logger.printException(() -> "Could not save the merged playlist", failure);
                // Nothing is added on the phone either: the target stays as it was.
                result.error = text("Не удалось записать треки в плейлист. Он не изменён.",
                        "Could not save the tracks to the playlist. It was not changed.");
                return result;
            }
            // Sends the change to SoundCloud, as its own "Add to playlist" sheet does.
            type.getMethod("syncPlaylist", urnClass).invoke(operations, targetUrn);
            result.added += sent;
            result.offline = !isOnline();
        }
        if (!onPhone.isEmpty()) {
            result.added += LocalAdditions.addAll(target.urn, onPhone);
            result.onPhoneOnly = !target.editable;
        }
        Logger.printInfo(() -> "Merged " + sources.size() + " playlists into " + target.urn + ": added " + result.added
                + ", already there " + result.duplicates + ", over the limit " + result.overLimit + ", unreadable " + result.failed.size());
        return result;
    }

    /** The tracks of a playlist as they play: SoundCloud's and the ones added here, in the order the user set. */
    private static List<?> readTracks(Object operations, Class<?> urnClass, Method forPlaylist, Candidate source) {
        try {
            Object urn = forPlaylist.invoke(null, idOf(source.urn));
            Object single = operations.getClass().getMethod("trackUrnsForPlayback", urnClass).invoke(operations, urn);
            // May load the playlist from SoundCloud first, if it was never opened.
            Object list = Rx.blockingFirst(single, 60, TimeUnit.SECONDS);
            if (!(list instanceof List)) return null;
            // A playlist that has tracks but gives none was not loaded.
            return ((List<?>) list).isEmpty() && source.count > 0 ? null : (List<?>) list;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the tracks of " + source.urn, ex);
            return null;
        }
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
            if (result.onPhoneOnly) {
                message.append("\n\n").append(text("«" + target.title + "» не ваш плейлист: треки добавлены только на этом телефоне и не попадут в SoundCloud.",
                        "\"" + target.title + "\" is not your playlist: the tracks are added on this phone only and do not go to SoundCloud."));
            } else if (result.offline) {
                message.append("\n\n").append(text("Нет сети: изменения уйдут в SoundCloud, когда она появится.",
                        "No network: the changes go to SoundCloud when it is back."));
            }
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

    /** A dialog with a spinner while the tracks are read and saved. It opens only if that takes a moment. */
    private static final class Progress {
        private static final long SHOW_AFTER_MS = 400;

        private final Context context;
        private final AtomicBoolean cancelled;
        private final TextView label;
        private final View content;
        private volatile Dialog dialog;
        private volatile boolean finished;

        Progress(Context context, AtomicBoolean cancelled) {
            this.context = context;
            this.cancelled = cancelled;
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
                    .setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.set(true)).show();
        }

        void update(String text) {
            Utils.runOnMainThread(() -> label.setText(text));
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
