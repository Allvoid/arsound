package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Knows where the file of every downloaded track is, and whether the app can use it.
 * <p>
 * Before this class a file was looked for by building its path from a name and asking the file system whether it
 * could be read. Since Android 10 that fails for files the app made itself in Music: it sees them (the status of the
 * path works) but is not allowed to read or remove them by path, only through the media store. The check then said
 * "no file", the track was streamed instead of played from its file, and the same tracks were downloaded again on
 * every run of a playlist. Now each track remembers the media store row of its file ({@code track_uri_}) and the
 * location it was saved to ({@code track_dir_}), and the file is read and removed through that row. The row is a
 * media store row, or the document of a file in a folder the user picked (see {@link TreeFolder}): the rest of the app
 * does not tell them apart.
 * <p>
 * The media store is listed in the background into a snapshot, which the lists of the app read without waiting:
 * they ask for the state of a track on every cell they draw, on the main thread.
 */
public final class DownloadIndex {
    /** The media store row of the file of a track. */
    static final String TRACK_URI_PREFIX = "track_uri_";
    /** The id of the {@link DownloadLocation} the file of a track was saved to. Missing: the default location. */
    static final String TRACK_DIR_PREFIX = "track_dir_";

    /** Files this small are not a track, whatever their name: a failed download leaves a few bytes. */
    private static final long MIN_ADOPT_BYTES = 10 * 1024;
    /** Names made by earlier versions: "soundcloud-ID", "soundcloud-ID-ID" for a taken name, and the media store's " (1)". */
    private static final Pattern ID_NAME = Pattern.compile("^soundcloud-(\\d+)(?:-\\d+)*(?: \\(\\d+\\))?\\.(?:m4a|mp3|aac|opus)$");
    private static final Pattern COPY_SUFFIX = Pattern.compile(" \\((\\d+)\\)(?=\\.[^.]*$)");
    private static final long REFRESH_INTERVAL_MS = 30_000;
    /** How long the answer of opening a file stays good: playback asks again and again while a queue loads. */
    private static final long OPEN_CHECK_MS = 10_000;
    private static final Object REFRESH_LOCK = new Object();
    private static final Object ADOPT_LOCK = new Object();

    /** The files the media store lists, by name and by address. Null until the first listing is done. */
    private static volatile Snapshot snapshot;
    private static final AtomicBoolean refreshQueued = new AtomicBoolean();
    private static volatile long lastRefreshAt;
    private static volatile long lastFailureAt;
    /** Whether the access to music was given when the app last came to the screen; null before the first time. */
    private static volatile Boolean lastGranted;
    /** Tracks whose missing file was already written down, so a list that asks every second does not fill the log. */
    private static final Set<String> diagnosed = ConcurrentHashMap.newKeySet();
    private static final Map<String, long[]> openChecks = new ConcurrentHashMap<>();

    private DownloadIndex() {
    }

    /** What the media store lists in the known locations. The maps are changed in place when a file is removed. */
    private static final class Snapshot {
        final Map<String, DownloadLocation.Row> byName = new ConcurrentHashMap<>();
        final Map<String, DownloadLocation.Row> byUri = new ConcurrentHashMap<>();

        void add(DownloadLocation.Row row) {
            byName.put(key(row.locationId, row.name), row);
            byUri.put(row.uri.toString(), row);
        }

        void remove(DownloadLocation.Row row) {
            byName.remove(key(row.locationId, row.name));
            byUri.remove(row.uri.toString());
        }

        boolean sameAs(Snapshot other) {
            if (byUri.size() != other.byUri.size()) return false;
            for (Map.Entry<String, DownloadLocation.Row> entry : byUri.entrySet()) {
                DownloadLocation.Row row = other.byUri.get(entry.getKey());
                if (row == null || !row.sameAs(entry.getValue())) return false;
            }
            return true;
        }
    }

    /** The name for the user of the place new downloads go to, in the language of the app: "Music/Arsound". */
    public static String label() {
        return DownloadLocation.current().label();
    }

    /**
     * The folders the files of these tracks are in, for the user: one name, or several separated by commas when the
     * choice of the folder was changed after some of them were downloaded. The folder new downloads go to when none
     * of the tracks has a file.
     */
    public static String placeOf(Iterable<String> trackIds) {
        Set<String> labels = new LinkedHashSet<>();
        for (String trackId : trackIds) {
            Ref ref = DownloadTrackPatch.fileRefOf(trackId);
            if (ref != null) labels.add(ref.location().label());
        }
        if (labels.isEmpty()) return label();
        StringBuilder place = new StringBuilder();
        for (String label : labels) place.append(place.length() == 0 ? "" : ", ").append(label);
        return place.toString();
    }

    /** The folders of all downloaded tracks, see {@link #placeOf}. */
    public static String downloadsPlace() {
        return placeOf(DownloadTrackPatch.getDownloadedTrackIds());
    }

    private static String key(String locationId, String name) {
        return locationId + '\u0000' + name;
    }

    /**
     * Points at the file kept for a track: a name in a location, and the address of its row when it is known. It
     * says nothing about whether the file is there; {@link #resolve} finds out.
     */
    static final class Ref {
        final String locationId;
        final String name;
        /** The media store row or the document of a picked folder, or null if it was never recorded. */
        final String uri;

        Ref(String locationId, String name, String uri) {
            this.locationId = locationId;
            this.name = name;
            this.uri = uri;
        }

        DownloadLocation location() {
            return DownloadLocation.of(locationId);
        }

        /** Whether both name the same file; the address is not compared, as one of them may not know it. */
        boolean sameFileAs(Ref other) {
            return other != null && name.equals(other.name) && locationId.equals(other.locationId);
        }
    }

    /** A downloaded file as far as the app can tell: where it is, how big, and how the player can open it. */
    public static final class Entry {
        final Ref ref;
        final String name;
        /** The media store row or the document of a picked folder, or null if the file is only known by its path. */
        final Uri uri;
        /** The path, or null if the location has none. */
        final File file;
        final long size;
        final long modifiedMs;

        Entry(Ref ref, String name, Uri uri, File file, long size, long modifiedMs) {
            this.ref = ref;
            this.name = name;
            this.uri = uri;
            this.file = file;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }

        public String name() {
            return name;
        }

        public long size() {
            return size;
        }

        public long modifiedMs() {
            return modifiedMs;
        }

        /**
         * What to hand to the player: a path, or the address of the row where the path cannot be read (always so in a
         * picked folder, which has no path).
         * Null if the player could not open the file either, so the caller streams the track instead of playing a
         * file that is not there. May wait for the system: call off the main thread.
         */
        String playable(Context context) {
            boolean pathReadable = file != null && file.isFile() && file.length() > 0 && file.canRead();
            // From Android 11 a path to a file of the app works as it did. Before it (and for a file of another
            // app) the row is the way in, so it goes first there.
            if (pathReadable && (uri == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) return file.getPath();
            if (uri != null && canOpen(context, uri)) return uri.toString();
            return pathReadable ? file.getPath() : null;
        }

        /** The content of the file, through its path or its row. */
        public InputStream open(Context context) throws IOException {
            if (file != null && file.isFile() && file.canRead()) return new FileInputStream(file);
            if (uri != null) {
                InputStream stream = context.getContentResolver().openInputStream(uri);
                if (stream != null) return stream;
            }
            throw new FileNotFoundException(name);
        }

        /** The row of the file, asked of the system if it was never recorded; null if it lists none. */
        public Uri rowUri(Context context) {
            return uri != null ? uri : ref.location().findRow(context, name);
        }
    }

    // region finding a file

    /**
     * The file of a track as it stands, also one the app cannot read (made before a reinstall): for counting its
     * space and for removing it. Null if the track has no file recorded or it is gone.
     */
    public static Entry known(String trackId) {
        Ref ref = DownloadTrackPatch.fileRefOf(trackId);
        return ref == null ? null : find(ref, false);
    }

    static Entry known(Ref ref) {
        return find(ref, false);
    }

    /**
     * The file of a track that the app can use now, or null. Records the row it found, so the next lookup does not
     * depend on the snapshot, and writes down once why a recorded file was not found.
     */
    static Entry resolve(String trackId, Ref ref) {
        Entry entry = find(ref, true);
        if (entry == null) {
            diagnose(trackId, ref);
            return null;
        }
        if (entry.uri != null && !entry.uri.toString().equals(ref.uri)) {
            DownloadTrackPatch.recordUri(trackId, ref.name, entry.uri);
        }
        return entry;
    }

    /** Whether the file exists, also an empty one that is not usable yet. */
    static boolean hasFile(Ref ref) {
        return find(ref, false) != null;
    }

    /**
     * Whether a track in the playback queue can play from a file. Waits for the first listing of the media store when
     * it is not done, which the main thread never does: it gets the answer the path alone gives.
     */
    static Entry resolveForPlayback(String trackId, Ref ref) {
        if (snapshot == null && Looper.myLooper() != Looper.getMainLooper()) refreshNow();
        Entry entry = resolve(trackId, ref);
        if (entry == null && snapshot == null && ref.uri != null) {
            // No listing (asked on the main thread, or it failed): the recorded row is tried, and opening it settles it.
            return new Entry(ref, ref.name, Uri.parse(ref.uri), ref.location().file(ref.name), 0, 0);
        }
        return entry;
    }

    /**
     * @param usable True to find a file the app can read; false to find any file that is there.
     */
    private static Entry find(Ref ref, boolean usable) {
        DownloadLocation location = ref.location();
        String[] names = namesOf(ref);

        Snapshot listed = snapshot;
        if (listed == null) {
            requestFirstListing();
        } else {
            DownloadLocation.Row row = ref.uri == null ? null : listed.byUri.get(ref.uri);
            for (int i = 0; row == null && i < names.length; i++) row = listed.byName.get(key(location.id(), names[i]));
            if (row != null) {
                return new Entry(ref, row.name, row.uri, location.file(row.name), row.size, row.modifiedMs);
            }
        }

        // The media store does not list it (yet): a file of the download manager is listed only after a scan, and
        // before Android 10 nothing is listed. The path decides.
        for (String name : names) {
            File file = location.file(name);
            if (file == null) continue;
            boolean found = usable ? file.isFile() && file.length() > 0 && file.canRead() : file.exists();
            if (found) return new Entry(ref, name, null, file, file.length(), file.lastModified());
        }
        return null;
    }

    /** Early versions saved some files without an extension; such files may have been renamed to ".mp3" since. */
    private static String[] namesOf(Ref ref) {
        return ref.name.contains(".") ? new String[]{ref.name} : new String[]{ref.name, ref.name + ".mp3"};
    }

    private static void diagnose(String trackId, Ref ref) {
        if (trackId == null || !diagnosed.add(trackId + '/' + ref.name)) return;
        try {
            File file = ref.location().file(ref.name);
            Snapshot listed = snapshot;
            Context context = Utils.getContext();
            boolean granted = context != null && MusicAccess.isGranted(context);
            Logger.printInfo(() -> "Download index: no usable file for " + trackId + " (" + ref.locationId + ref.name
                    + "), listed " + (listed == null ? "not yet" : String.valueOf(listed.byName.containsKey(key(ref.locationId, ref.name))))
                    + ", recorded row " + ref.uri + ", path " + (file == null ? "none"
                    : "exists " + file.exists() + " file " + file.isFile() + " length " + file.length()
                    + " canRead " + file.canRead()) + ", music access " + granted);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Download index: could not write down the missing file of " + trackId + ": " + ex);
        }
    }

    /** Whether the player can open the row: a row of a file that is gone, or of an app that did not share it, cannot. */
    private static boolean canOpen(Context context, Uri uri) {
        String key = uri.toString();
        long now = SystemClock.elapsedRealtime();
        long[] known = openChecks.get(key);
        if (known != null && now - known[0] < OPEN_CHECK_MS) return known[1] == 1;
        boolean ok;
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            ok = descriptor != null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Download index: cannot open " + uri + ": " + ex);
            ok = false;
            // The listing may be out of date: the file can have been removed in a file manager.
            refreshAsync();
        }
        openChecks.put(key, new long[]{now, ok ? 1 : 0});
        return ok;
    }

    // endregion

    // region the snapshot

    /** The locations files can be in: where new ones go, the default one, and every one a track was saved to. */
    private static List<DownloadLocation> knownLocations() {
        Map<String, DownloadLocation> locations = new LinkedHashMap<>();
        DownloadLocation current = DownloadLocation.current();
        locations.put(current.id(), current);
        locations.put(DownloadLocation.DEFAULT.id(), DownloadLocation.DEFAULT);
        for (String id : DownloadTrackPatch.recordedLocationIds()) {
            if (!locations.containsKey(id)) locations.put(id, DownloadLocation.of(id));
        }
        return new ArrayList<>(locations.values());
    }

    private static void requestFirstListing() {
        // A listing that failed is not repeated for every cell of a list.
        if (SystemClock.elapsedRealtime() - lastFailureAt < REFRESH_INTERVAL_MS && lastFailureAt != 0) return;
        refreshAsync();
    }

    /** Lists the media store again in the background. Requests made while one is waiting to start share it. */
    public static void refreshAsync() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        Utils.runOnBackgroundThread(() -> {
            refreshQueued.set(false);
            refreshNow();
        });
    }

    /**
     * Lists the media store now. Call off the main thread. The lists of the app are told when it shows something
     * other than before, which includes the first listing: until then they went by the paths alone.
     */
    static void refreshNow() {
        Context context = Utils.getContext();
        if (context == null) return;
        synchronized (REFRESH_LOCK) {
            try {
                Snapshot before = snapshot;
                Snapshot fresh = new Snapshot();
                for (DownloadLocation location : knownLocations()) {
                    List<DownloadLocation.Row> rows = location.list(context);
                    if (rows == null) {
                        // A card that is out, or a folder whose access was taken back: its tracks are still downloaded,
                        // they only cannot be opened for now. What was known of them stays until it can be listed again.
                        if (before != null) {
                            for (DownloadLocation.Row row : before.byUri.values()) {
                                if (row.locationId.equals(location.id())) fresh.add(row);
                            }
                        }
                        continue;
                    }
                    for (DownloadLocation.Row row : rows) fresh.add(row);
                }
                snapshot = fresh;
                lastRefreshAt = SystemClock.elapsedRealtime();
                lastFailureAt = 0;
                if (before == null || !before.sameAs(fresh)) {
                    Logger.printInfo(() -> "Download index: " + fresh.byUri.size() + " files listed");
                    DownloadProgress.onFilesChanged();
                }
            } catch (Exception ex) {
                lastFailureAt = SystemClock.elapsedRealtime();
                Logger.printInfo(() -> "Download index: could not list the media store: " + ex);
            }
        }
    }

    /** A file was removed: it is not shown as downloaded in the meantime, before the listing is made again. */
    private static void forget(Ref ref, Uri removedRow) {
        Snapshot listed = snapshot;
        if (listed != null) {
            DownloadLocation.Row byName = listed.byName.get(key(ref.locationId, ref.name));
            if (byName != null) listed.remove(byName);
            DownloadLocation.Row byUri = removedRow == null ? null : listed.byUri.get(removedRow.toString());
            if (byUri != null) listed.remove(byUri);
        }
        refreshAsync();
    }

    /**
     * Injection point of the lifecycle: the app came to the screen. Lists the media store again once in a while,
     * and when access to music was just given, which makes the files of an earlier install visible, looks for tracks
     * the app has no record of.
     */
    public static void onActivityResumed(Activity activity) {
        Context context = activity.getApplicationContext();
        boolean granted = MusicAccess.isGranted(context);
        Boolean before = lastGranted;
        lastGranted = granted;
        if (granted && before != null && !before) {
            Utils.runOnBackgroundThread(() -> adopt(context));
        } else if (snapshot == null || SystemClock.elapsedRealtime() - lastRefreshAt > REFRESH_INTERVAL_MS) {
            refreshAsync();
        }
    }

    // endregion

    // region removing

    /**
     * Removes the file. The row goes first: since Android 10 an app can remove only through the media store a file in
     * Music, and then only one it made itself.
     *
     * @return True if the file is no longer there. False if it stays (a file made before a reinstall belongs to the old
     * install for Android, a file on a card that is out cannot be reached), then the record of the track is kept so the
     * settings can have it removed through the system, or it can be removed once the card is back.
     */
    static boolean delete(Context context, Ref ref) {
        DownloadLocation location = ref.location();
        String[] names = namesOf(ref);
        Set<Uri> rows = new LinkedHashSet<>();
        if (ref.uri != null) rows.add(Uri.parse(ref.uri));
        Snapshot listed = snapshot;
        for (String name : names) {
            DownloadLocation.Row row = listed == null ? null : listed.byName.get(key(location.id(), name));
            if (row != null) rows.add(row.uri);
        }

        boolean removed = false;
        Uri removedRow = null;
        for (Uri row : rows) {
            if (DownloadLocation.deleteRow(context, row)) {
                removed = true;
                removedRow = row;
            }
        }
        if (!removed) {
            // Not in the snapshot, for instance a file just saved by the download manager: asked of the system.
            for (String name : names) {
                Uri row = location.findRow(context, name);
                if (row == null || rows.contains(row)) continue;
                if (DownloadLocation.deleteRow(context, row)) {
                    removed = true;
                    removedRow = row;
                }
            }
        }

        boolean gone = true;
        for (String name : names) {
            File file = location.file(name);
            if (file == null) {
                // A location without paths: the file is gone when the system no longer lists it. A card that is out
                // cannot be asked, so its files stay, with their record, until the card or its access is back.
                if (!removed && (!location.isAvailable(context) || location.findRow(context, name) != null)) gone = false;
                continue;
            }
            if (!file.exists()) continue;
            if (file.isFile() && file.delete()) removed = true;
            else gone = false;
        }
        if (removed) forget(ref, removedRow);
        // Nothing to delete is the same result as a deleted file: the track is not downloaded any more.
        return gone;
    }

    // endregion

    // region files the app has no record of

    /**
     * Gives a track back its file when the app lost the record of it: after a reinstall, a change of the package or
     * of the signature (the files then belong to the old install for Android), or when the stored data was wiped.
     * Files made by earlier versions carry the track id in their name ("soundcloud-ID.m4a"), so they need no network.
     * A track that has a usable file is left alone; of several copies of one track one is picked: a copy the app
     * made itself (it can remove it), then the one without a " (n)", then the lowest n.
     * <p>
     * Needs the access to music to see the files of an earlier install. Call off the main thread.
     *
     * @return How many tracks were found.
     */
    public static int adopt(Context context) {
        if (context == null) return 0;
        synchronized (ADOPT_LOCK) {
            refreshNow();
            Snapshot listed = snapshot;
            if (listed == null) return 0;

            Map<String, List<DownloadLocation.Row>> byTrack = new HashMap<>();
            for (DownloadLocation.Row row : listed.byName.values()) {
                Matcher matcher = ID_NAME.matcher(row.name);
                if (!matcher.matches() || row.size < MIN_ADOPT_BYTES) continue;
                List<DownloadLocation.Row> rows = byTrack.get(matcher.group(1));
                if (rows == null) byTrack.put(matcher.group(1), rows = new ArrayList<>());
                rows.add(row);
            }

            String own = context.getPackageName();
            Map<String, DownloadLocation.Row> found = new HashMap<>();
            for (Map.Entry<String, List<DownloadLocation.Row>> entry : byTrack.entrySet()) {
                String trackId = entry.getKey();
                // A track on its way has its own record; a track with a usable file needs none.
                if (DownloadProgress.isAssembling(trackId) || DownloadTrackPatch.isDownloadPending(trackId)) continue;
                Ref ref = DownloadTrackPatch.fileRefOf(trackId);
                if (ref != null && find(ref, true) != null) continue;
                List<DownloadLocation.Row> rows = entry.getValue();
                Collections.sort(rows, preferredCopy(own));
                found.put(trackId, rows.get(0));
            }
            if (found.isEmpty()) {
                Logger.printInfo(() -> "Download index: no tracks to take over among " + byTrack.size() + " named by id");
                return 0;
            }
            DownloadTrackPatch.rememberAdopted(found);
            Logger.printInfo(() -> "Download index: took over the files of " + found.size() + " tracks");
            DownloadProgress.onFilesChanged();
            return found.size();
        }
    }

    private static Comparator<DownloadLocation.Row> preferredCopy(String ownPackage) {
        return (left, right) -> {
            boolean leftOwn = ownPackage.equals(left.owner);
            if (leftOwn != ownPackage.equals(right.owner)) return leftOwn ? -1 : 1;
            int byCopy = copyNumber(left.name) - copyNumber(right.name);
            return byCopy != 0 ? byCopy : left.name.length() - right.name.length();
        };
    }

    /** The n of " (n)" in a name the media store made for a taken one, 0 without it. */
    private static int copyNumber(String name) {
        Matcher matcher = COPY_SUFFIX.matcher(name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** The extra copies of downloaded tracks: how many files and how many bytes. */
    public static final class Duplicates {
        public final int files;
        public final long bytes;
        private final List<DownloadLocation.Row> rows;

        Duplicates(List<DownloadLocation.Row> rows) {
            this.rows = rows;
            long total = 0;
            for (DownloadLocation.Row row : rows) total += row.size;
            files = rows.size();
            bytes = total;
        }
    }

    /**
     * Files named like a download by an earlier version of a track that is downloaded from another file: what is left
     * by downloading the same tracks again and again before the app could tell they were already there.
     * Call off the main thread.
     */
    public static Duplicates duplicates() {
        refreshNow();
        Snapshot listed = snapshot;
        List<DownloadLocation.Row> extra = new ArrayList<>();
        if (listed == null) return new Duplicates(extra);

        Set<String> downloaded = DownloadTrackPatch.getDownloadedTracks();
        // The files a track keeps (and the one a download again will replace), the tracks that keep one, and the
        // tracks whose files are changing. A track that keeps none has its copies taken over by adopt, not removed.
        Set<String> kept = new HashSet<>();
        // The names alone as well: the same folder reached two ways (the media store and a picked folder) lists one
        // file twice, and that is not a copy to remove.
        Set<String> keptNames = new HashSet<>();
        Set<String> keeping = new HashSet<>();
        Set<String> busy = new HashSet<>();
        for (String trackId : downloaded) {
            if (DownloadProgress.isAssembling(trackId) || DownloadTrackPatch.isDownloadPending(trackId)) {
                busy.add(trackId);
                continue;
            }
            for (Ref ref : new Ref[]{DownloadTrackPatch.fileRefOf(trackId), DownloadTrackPatch.replacedRefOf(trackId)}) {
                Entry entry = ref == null ? null : find(ref, false);
                if (entry == null) continue;
                kept.add(key(ref.locationId, entry.name));
                keptNames.add(entry.name);
                keeping.add(trackId);
            }
        }
        for (DownloadLocation.Row row : listed.byName.values()) {
            Matcher matcher = ID_NAME.matcher(row.name);
            if (!matcher.matches() || !keeping.contains(matcher.group(1)) || busy.contains(matcher.group(1))) continue;
            if (!kept.contains(key(row.locationId, row.name)) && !keptNames.contains(row.name)) extra.add(row);
        }
        return new Duplicates(extra);
    }

    /**
     * Removes the extra copies the app is allowed to remove (its own).
     *
     * @param needsConsent Gets the rows of copies the system has to confirm (made by an earlier install).
     * @return How many files were removed.
     */
    public static int removeDuplicates(Context context, Duplicates duplicates, List<Uri> needsConsent) {
        int removed = 0;
        for (DownloadLocation.Row row : duplicates.rows) {
            if (DownloadLocation.deleteRow(context, row.uri)) {
                removed++;
            } else if (MediaStore.AUTHORITY.equals(row.uri.getAuthority())) {
                // Only the media store can ask the user about a file of another install; a picked folder cannot.
                needsConsent.add(row.uri);
            }
        }
        refreshAsync();
        return removed;
    }

    // endregion

    // region files that cannot be seen

    /**
     * Whether a downloaded file is there but the app can neither read it nor see its row: it was made by an earlier
     * install, and only the access to music shows it. Call off the main thread.
     */
    public static boolean hasUnreadableDownloads() {
        if (snapshot == null) refreshNow();
        for (String trackId : DownloadTrackPatch.getDownloadedTracks()) {
            Ref ref = DownloadTrackPatch.fileRefOf(trackId);
            if (ref == null) continue;
            Snapshot listed = snapshot;
            if (listed != null && listed.byName.containsKey(key(ref.locationId, ref.name))) continue;
            File file = ref.location().file(ref.name);
            if (file != null && file.exists() && !file.canRead()) return true;
        }
        return false;
    }

    // endregion
}
