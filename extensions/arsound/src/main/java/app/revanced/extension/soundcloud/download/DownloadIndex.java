package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /**
     * A second address the file of a track can be read by, when its row is not usable: the download manager's own
     * address of a file it wrote. Since Android 10 it is the one that always opens for the app that asked for the file.
     */
    static final String TRACK_READ_PREFIX = "track_read_";

    private static final long REFRESH_INTERVAL_MS = 30_000;
    /** The longest a track that is about to play waits for the first listing of the files, see {@link #resolveForPlayback}. */
    private static final long PLAYBACK_LISTING_WAIT_MS = 1_500;
    private static final Object REFRESH_LOCK = new Object();
    /** Woken when a listing is made, for those who wait for the first one. */
    private static final Object LISTED = new Object();
    private static final AtomicBoolean loggedMainThread = new AtomicBoolean();
    private static final AtomicBoolean loggedBackgroundThread = new AtomicBoolean();

    /** The files the media store lists, by name and by address. Null until the first listing is done. */
    private static volatile MediaSnapshot snapshot;
    private static final AtomicBoolean refreshQueued = new AtomicBoolean();
    private static volatile long lastRefreshAt;
    private static volatile long lastFailureAt;
    /**
     * When a listing was last tried, complete or not. A folder out of reach makes every listing incomplete, and
     * {@link #lastRefreshAt} stays old then: the time of the try keeps such listings as rare as complete ones.
     */
    private static volatile long lastAttemptAt;
    /** Guards {@link #changesWhileListing}. */
    private static final Object JOURNAL = new Object();
    /**
     * The changes the app made itself while a listing runs ({@link #noteSaved}, {@link #forget}): they go into the
     * listing in use, and are made again on the new one before it takes its place. Null while no listing runs.
     */
    private static List<java.util.function.Consumer<MediaSnapshot>> changesWhileListing;
    /** Whether the access to music was given when the app last came to the screen; null before the first time. */
    private static volatile Boolean lastGranted;
    /** Tracks whose missing file was already written down, so a list that asks every second does not fill the log. */
    private static final Set<String> diagnosed = ConcurrentHashMap.newKeySet();
    /** When a file was last asked of the system by name and not found, so a recount of many tracks asks once per name. */
    private static final Map<String, Long> missingLookups = new ConcurrentHashMap<>();

    private DownloadIndex() {
    }

    /** What the media store lists, or null before the first listing. */
    static MediaSnapshot snapshot() {
        return snapshot;
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
        return joinLabels(labels);
    }

    /** Folder names for the user, separated by commas. */
    private static String joinLabels(Iterable<String> labels) {
        StringBuilder place = new StringBuilder();
        for (String label : labels) place.append(place.length() == 0 ? "" : ", ").append(label);
        return place.toString();
    }

    /** The folders of all downloaded tracks, see {@link #placeOf}. */
    public static String downloadsPlace() {
        return placeOf(DownloadTrackPatch.getDownloadedTrackIds());
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
        /** The download manager's address of the file, or null if it did not write it. See {@link #TRACK_READ_PREFIX}. */
        final String readUri;

        Ref(String locationId, String name, String uri) {
            this(locationId, name, uri, null);
        }

        Ref(String locationId, String name, String uri, String readUri) {
            this.locationId = locationId;
            this.name = name;
            this.uri = uri;
            this.readUri = readUri;
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
            noteThread();
            boolean pathReadable = file != null && file.isFile() && file.length() > 0 && file.canRead();
            // From Android 11 a path to a file of the app works as it did. Before it (and for a file of another
            // app) the row is the way in, so it goes first there.
            if (pathReadable && (uri == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) return file.getPath();
            if (uri != null && OpenChecks.canOpen(context, uri)) return uri.toString();
            // A file the download manager wrote: its own address opens for the app that asked for the file.
            if (ref.readUri != null && OpenChecks.canOpen(context, Uri.parse(ref.readUri))) return ref.readUri;
            return pathReadable ? file.getPath() : null;
        }

        /** The content of the file, through its path or one of its addresses. */
        public InputStream open(Context context) throws IOException {
            if (file != null && file.isFile() && file.canRead()) return new FileInputStream(file);
            for (String address : new String[]{uri == null ? null : uri.toString(), ref.readUri}) {
                if (address == null) continue;
                try {
                    InputStream stream = context.getContentResolver().openInputStream(Uri.parse(address));
                    if (stream != null) return stream;
                } catch (IOException | RuntimeException ex) {
                    Logger.printInfo(() -> "Download index: cannot read " + address + ": " + ex);
                }
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
     * Whether a track in the playback queue can play from a file. Waits a moment for the first listing of the media
     * store when it is not done, which the main thread never does: it gets the answer the path alone gives. A listing of
     * a big folder or a slow card is not waited for to the end: the first track would be late for the whole listing.
     */
    static Entry resolveForPlayback(String trackId, Ref ref) {
        awaitListing(PLAYBACK_LISTING_WAIT_MS);
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
    static Entry find(Ref ref, boolean usable) {
        DownloadLocation location = ref.location();
        String[] names = namesOf(ref);

        MediaSnapshot listed = snapshot;
        if (listed == null) {
            requestFirstListing();
        } else {
            DownloadLocation.Row row = ref.uri == null ? null : listed.byUri.get(ref.uri);
            for (int i = 0; row == null && i < names.length; i++) row = listed.byName.get(MediaSnapshot.key(location.id(), names[i]));
            if (row != null) {
                // An empty file is there but not usable, as for a path below: a download cut short (the app or the
                // phone went down while it started) leaves one, and the player given it failed with "the format is
                // not recognised" instead of streaming the track. Only where the size is known for sure: the media
                // store gives it, a recorded row (size and time 0) and a picked folder may not.
                if (usable && row.size == 0 && row.modifiedMs > 0 && row.uri != null
                        && android.provider.MediaStore.AUTHORITY.equals(row.uri.getAuthority())) {
                    return null;
                }
                return new Entry(ref, row.name, row.uri, location.file(row.name), row.size, row.modifiedMs);
            }
        }

        // The media store does not list it (yet): a file of the download manager is listed only after a scan, and
        // before Android 10 nothing is listed. The path decides.
        File found = null;
        for (String name : names) {
            File file = location.file(name);
            if (file == null) continue;
            if (found == null) found = file;
            boolean exists = usable ? file.isFile() && file.length() > 0 && file.canRead() : file.exists();
            if (exists) return new Entry(ref, name, null, file, file.length(), file.lastModified());
        }
        if (!usable) return null;

        // Neither the listing nor the path has it, which is how a file looks since Android 10 when the listing is not
        // done yet, failed, or lacks a file the download manager wrote: the system is asked for this one name.
        Entry asked = lookUp(ref, location, names, listed);
        if (asked != null) return asked;
        long size = found == null ? 0 : found.length();
        long modified = found == null ? 0 : found.lastModified();
        // A file saved while the listing was being made is not in it: the address kept for the track decides, as it
        // does for a picked folder, which has no path to ask and no single name to look up.
        Context context = Utils.getContext();
        if (ref.uri != null && OpenChecks.canOpen(context, Uri.parse(ref.uri))) {
            return new Entry(ref, ref.name, Uri.parse(ref.uri), found, size, modified);
        }
        // The download manager's own address of a file it wrote opens for the app that asked for it.
        if (ref.readUri != null && OpenChecks.canOpen(context, Uri.parse(ref.readUri))) {
            return new Entry(ref, ref.name, null, found, size, modified);
        }
        return null;
    }

    /**
     * Asks the system for one file by name: one cheap question for a location of the media store, where the listing
     * would be a whole. Not on the main thread, and a name that is not there is not asked for again for a while.
     */
    private static Entry lookUp(Ref ref, DownloadLocation location, String[] names, MediaSnapshot listed) {
        Context context = Utils.getContext();
        if (context == null || Looper.myLooper() == Looper.getMainLooper()) return null;
        long now = SystemClock.elapsedRealtime();
        for (String name : names) {
            String key = MediaSnapshot.key(location.id(), name);
            Long missed = missingLookups.get(key);
            if (missed != null && now - missed < OpenChecks.GOOD_FOR_MS) continue;
            DownloadLocation.Row row = location.lookUp(context, name);
            if (row == null || !OpenChecks.canOpen(context, row.uri)) {
                missingLookups.put(key, now);
                continue;
            }
            // The next question is answered by the listing.
            if (listed != null) listed.add(row);
            return new Entry(ref, row.name, row.uri, location.file(row.name), row.size, row.modifiedMs);
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
            MediaSnapshot listed = snapshot;
            Context context = Utils.getContext();
            boolean granted = context != null && MusicAccess.isGranted(context);
            Logger.printInfo(() -> "Download index: no usable file for " + trackId + " (" + ref.locationId + ref.name
                    + "), listed " + (listed == null ? "not yet" : String.valueOf(listed.byName.containsKey(MediaSnapshot.key(ref.locationId, ref.name))))
                    + ", recorded row " + ref.uri + ", path " + (file == null ? "none"
                    : "exists " + file.exists() + " file " + file.isFile() + " length " + file.length()
                    + " canRead " + file.canRead()) + ", music access " + granted);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Download index: could not write down the missing file of " + trackId + ": " + ex);
        }
    }

    /** Writes down once for each kind of thread where a playback source is asked for, which decides how it may wait. */
    private static void noteThread() {
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        if ((main ? loggedMainThread : loggedBackgroundThread).compareAndSet(false, true)) {
            Logger.printInfo(() -> "Download index: a playback source is asked for on "
                    + (main ? "the main thread" : "a background thread (" + Thread.currentThread().getName() + ")"));
        }
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

    /**
     * Waits for the first listing of the media store if it is not done, which the main thread never does. For the
     * checks that count many tracks at once: without it every track would be found missing until the listing is there.
     */
    public static void awaitListing() {
        if (snapshot == null && Looper.myLooper() != Looper.getMainLooper()) refreshNow();
    }

    /**
     * Waits for the first listing at most this long, which the main thread never does. The listing runs on a thread of its
     * own and goes on after the wait; whoever waited then goes by the address recorded for the track.
     */
    private static void awaitListing(long maxMs) {
        if (snapshot != null || Looper.myLooper() == Looper.getMainLooper()) return;
        long started = SystemClock.elapsedRealtime();
        refreshAsync();
        synchronized (LISTED) {
            while (snapshot == null) {
                long left = started + maxMs - SystemClock.elapsedRealtime();
                // A listing that failed is not waited for either.
                if (left <= 0 || lastFailureAt > started) break;
                try {
                    LISTED.wait(left);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** Lists the media store again in the background. Requests made while one is waiting to start share it. */
    /** The same, unless a listing was made or tried within {@link #REFRESH_INTERVAL_MS}. */
    static void refreshAsyncIfStale() {
        if (isStale()) refreshAsync();
    }

    public static void refreshAsync() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        Utils.runOnBackgroundThread(() -> {
            refreshQueued.set(false);
            refreshNow();
        });
    }

    /**
     * Lists the media store again if the last listing is not recent. For the screens that open often: a folder of
     * thousands of files takes the system a while to list. What the app changes itself it writes into the listing
     * directly ({@link #noteSaved}, {@link #forget}), so a recent listing is as good as a new one.
     */
    static void refreshIfStale() {
        if (isStale()) refreshNow();
    }

    /** No listing yet, or the last one, complete or tried, is older than {@link #REFRESH_INTERVAL_MS}. */
    private static boolean isStale() {
        long now = SystemClock.elapsedRealtime();
        return snapshot == null || (now - lastRefreshAt > REFRESH_INTERVAL_MS && now - lastAttemptAt > REFRESH_INTERVAL_MS);
    }

    /** Makes a change of the app's own in the listing in use, and keeps it for the listing that runs, if one does. */
    private static void change(java.util.function.Consumer<MediaSnapshot> change) {
        synchronized (JOURNAL) {
            MediaSnapshot listed = snapshot;
            if (listed != null) change.accept(listed);
            if (changesWhileListing != null) changesWhileListing.add(change);
        }
    }

    /**
     * Lists the media store now. Call off the main thread. The lists of the app are told when it shows something
     * other than before, which includes the first listing: until then they went by the paths alone.
     */
    static void refreshNow() {
        Context context = Utils.getContext();
        if (context == null) return;
        boolean listedAll = false;
        synchronized (REFRESH_LOCK) {
            lastAttemptAt = SystemClock.elapsedRealtime();
            synchronized (JOURNAL) {
                changesWhileListing = new ArrayList<>();
            }
            try {
                MediaSnapshot before = snapshot;
                MediaSnapshot fresh = new MediaSnapshot();
                boolean complete = true;
                for (DownloadLocation location : knownLocations()) {
                    List<DownloadLocation.Row> rows = location.list(context);
                    if (rows == null) {
                        complete = false;
                        addUnreachable(fresh, before, location);
                    } else {
                        addListed(fresh, location, rows);
                    }
                }
                synchronized (JOURNAL) {
                    // What the app saved or removed while the system listed is not in the listing yet.
                    for (java.util.function.Consumer<MediaSnapshot> change : changesWhileListing) change.accept(fresh);
                    changesWhileListing = null;
                    snapshot = fresh;
                }
                // A listing that missed a location is made again at the next chance.
                if (complete) lastRefreshAt = SystemClock.elapsedRealtime();
                lastFailureAt = complete ? 0 : SystemClock.elapsedRealtime();
                listedAll = complete;
                if (before == null || !before.sameAs(fresh)) {
                    Logger.printInfo(() -> "Download index: " + fresh.byUri.size() + " files listed");
                    DownloadProgress.onFilesChanged();
                }
            } catch (Exception ex) {
                lastFailureAt = SystemClock.elapsedRealtime();
                Logger.printInfo(() -> "Download index: could not list the media store: " + ex);
            } finally {
                synchronized (JOURNAL) {
                    changesWhileListing = null;
                }
            }
        }
        // Those who wait for the first listing go on, with the listing or without it.
        synchronized (LISTED) {
            LISTED.notifyAll();
        }
        // Every folder answers: the ones that were out of reach are back, and the files that waited for them can go.
        if (listedAll) DownloadTrackPatch.retryPendingDeletes(context);
    }

    /**
     * A location the listing could not reach: a card that is out, or a folder whose access was taken back. Its tracks
     * are still downloaded, they only cannot be opened for now. What was known of them stays until it can be listed
     * again.
     */
    private static void addUnreachable(MediaSnapshot fresh, MediaSnapshot before, DownloadLocation location) {
        List<DownloadLocation.Row> unreachable = new ArrayList<>();
        if (before != null) {
            for (DownloadLocation.Row row : before.byUri.values()) {
                if (row.locationId.equals(location.id())) unreachable.add(row);
            }
        }
        // The app started with the card out, or the folder was not listed before: the recorded files
        // stand in for what the listing would say, so that these tracks are downloaded here as well.
        Set<String> keptNames = new HashSet<>();
        for (DownloadLocation.Row kept : unreachable) keptNames.add(kept.name);
        for (DownloadLocation.Row row : DownloadTrackPatch.recordedRows(location.id())) {
            if (keptNames.add(row.name)) unreachable.add(row);
        }
        long now = SystemClock.elapsedRealtime();
        for (DownloadLocation.Row row : unreachable) {
            fresh.add(row);
            // The files cannot be reached: the first tap on one of them already streams the track.
            OpenChecks.noteUnreachable(row.uri.toString(), now);
        }
    }

    /** What a location lists. A folder of the user has files of all kinds: only those of downloads are kept. */
    private static void addListed(MediaSnapshot fresh, DownloadLocation location, List<DownloadLocation.Row> rows) {
        Set<String> names = location.holdsOtherFiles() ? DownloadTrackPatch.recordedNames(location.id()) : null;
        for (DownloadLocation.Row row : rows) {
            if (names == null || names.contains(row.name) || EarlierDownloadNames.trackIdOf(row.name) != null) {
                fresh.add(row);
                // The location is back: a file that was taken for unreachable is asked about again.
                OpenChecks.noteReachable(row.uri.toString());
            }
        }
    }

    /**
     * A file was just saved: it is in the listing at once, so the lists find it without the system being asked to list
     * the whole location again, which for a folder of thousands of files takes seconds for each track of a playlist.
     */
    static void noteSaved(Context context, Ref file, long size) {
        if (file.uri == null) return;
        DownloadLocation.Row row = new DownloadLocation.Row(file.locationId, file.name, Uri.parse(file.uri), size,
                System.currentTimeMillis(), context.getPackageName());
        change(listed -> listed.add(row));
    }

    /** A file was removed: it is left out of the listing at once, so it is not shown as downloaded any more. */
    private static void forget(Ref ref, Uri removedRow) {
        change(listed -> {
            DownloadLocation.Row byName = listed.byName.get(MediaSnapshot.key(ref.locationId, ref.name));
            if (byName != null) listed.remove(byName);
            DownloadLocation.Row byUri = removedRow == null ? null : listed.byUri.get(removedRow.toString());
            if (byUri != null) listed.remove(byUri);
        });
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
            Utils.runOnBackgroundThread(() -> adoptOnAccess(context));
        } else if (isStale()) {
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
        MediaSnapshot listed = snapshot;
        for (String name : names) {
            DownloadLocation.Row row = listed == null ? null : listed.byName.get(MediaSnapshot.key(location.id(), name));
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
        // What the system answered for each name below. A folder of the user is listed as a whole to answer, which for a
        // file that is gone (moved by the user) would be done twice: here, and again to tell if it is really gone.
        Map<String, Uri> asked = new HashMap<>();
        if (!removed) {
            // Not in the snapshot, for instance a file just saved by the download manager: asked of the system.
            for (String name : names) {
                Uri row = location.findRow(context, name);
                asked.put(name, row);
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
                if (!removed && (!location.isAvailable(context)
                        || (asked.containsKey(name) ? asked.get(name) : location.findRow(context, name)) != null)) {
                    gone = false;
                }
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

    /**
     * Removes the other files named like the download of a track ("soundcloud-ID (1).m4a") that the app is allowed to
     * remove, once the user deleted the track: the copies that downloading again and again left behind would otherwise
     * be the track's files again at the next look. A copy of an earlier install cannot be removed without the user's
     * consent; {@link #duplicates} offers those.
     */
    static void deleteCopies(Context context, String trackId) {
        DuplicateFiles.deleteCopiesOf(context, trackId);
    }

    // endregion

    // region files the app has no record of

    /**
     * Looks for files of tracks the app has no record of (after a reinstall or a change of the signature), other than
     * the ones the user deleted. For the moment the access to music was given, which makes the files of an earlier
     * install visible; see {@link DownloadAdoption}. Call off the main thread.
     *
     * @return How many tracks were found.
     */
    private static int adoptOnAccess(Context context) {
        return DownloadAdoption.adopt(context, false);
    }

    /**
     * The same, at the start of the app, and only while the app knows of no download at all: that is the start after a
     * reinstall or a change of the package. Otherwise a track the user deleted could come back from a copy of it at
     * every start. Call off the main thread.
     */
    public static int adoptAtStart(Context context) {
        if (!DownloadTrackPatch.getDownloadedTracks().isEmpty()) return 0;
        return DownloadAdoption.adopt(context, false);
    }

    /** The same, because the user asked for it: also the tracks the user deleted. Call off the main thread. */
    public static int adoptManually(Context context) {
        return DownloadAdoption.adopt(context, true);
    }

    /** The extra copies of downloaded tracks: how many files and how many bytes. */
    public static final class Duplicates {
        public final int files;
        public final long bytes;
        final List<DownloadLocation.Row> rows;

        Duplicates(List<DownloadLocation.Row> rows) {
            this.rows = rows;
            long total = 0;
            for (DownloadLocation.Row row : rows) total += row.size;
            files = rows.size();
            bytes = total;
        }
    }

    /**
     * Files named like a download by an earlier version of a track that is downloaded from another file, or that the
     * user deleted: what is left by downloading the same tracks again and again before the app could tell they were
     * already there. Call off the main thread.
     */
    public static Duplicates duplicates() {
        return DuplicateFiles.find();
    }

    /**
     * Removes the extra copies the app is allowed to remove (its own).
     *
     * @param needsConsent Gets the rows of copies the system has to confirm (made by an earlier install).
     * @return How many files were removed.
     */
    public static int removeDuplicates(Context context, Duplicates duplicates, List<Uri> needsConsent) {
        return DuplicateFiles.remove(context, duplicates, needsConsent);
    }

    // endregion

    // region what a cut off download leaves

    /**
     * At the start of the app: removes the half written files a killed process left in a folder of the user, and the
     * earlier files of tracks that could not be removed while their card was out. Call off the main thread.
     */
    public static void sweepAtStart(Context context) {
        if (context == null) return;
        TreeFolder.sweepUnfinished(context);
        DownloadTrackPatch.retryPendingDeletes(context);
    }

    // endregion

    // region the places of downloads, for features that read the same folders

    /**
     * The ids, as documents of the phone's storage provider, of the files that tracks keep. The same file has the same id
     * whichever folder it is reached through, so a feature that walks a folder of its own can tell which files are
     * downloads of the app and leave them alone.
     */
    public static Set<String> downloadedDocumentIds() {
        Set<String> ids = new HashSet<>();
        for (String trackId : DownloadTrackPatch.getDownloadedTracks()) {
            Ref ref = DownloadTrackPatch.fileRefOf(trackId);
            String id = ref == null ? null : documentIdOf(ref.uri);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** The ids, as documents of the phone's storage provider, of the folders downloads are kept in. */
    public static Set<String> downloadFolderDocumentIds() {
        Set<String> ids = new HashSet<>();
        for (DownloadLocation location : knownLocations()) {
            String id = location.documentId();
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static String documentIdOf(String uri) {
        if (uri == null) return null;
        try {
            return DocumentsContract.getDocumentId(Uri.parse(uri));
        } catch (RuntimeException ex) {
            // A row of the media store is not a document.
            return null;
        }
    }

    // endregion

    // region folders that cannot be reached

    /** Downloaded tracks whose folder cannot be reached now: the card is out, or the folder or its access is gone. */
    public static final class Lost {
        public final int tracks;
        /** The folders, for the user, separated by commas. */
        public final String places;
        private final Set<String> trackIds;

        Lost(Set<String> trackIds, Set<String> labels) {
            this.trackIds = trackIds;
            tracks = trackIds.size();
            places = joinLabels(labels);
        }

        /** How many of these tracks are in the list. */
        public int countIn(Iterable<String> ids) {
            int count = 0;
            for (String id : ids) if (trackIds.contains(id)) count++;
            return count;
        }
    }

    /**
     * The tracks whose folder cannot be used now. Their record stays: their files are only out of reach, and a card
     * that is put back gives them back. Asks the system for each folder once. Call off the main thread.
     */
    public static Lost lost(Context context) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        Map<String, Boolean> available = new HashMap<>();
        for (String trackId : DownloadTrackPatch.getDownloadedTracks()) {
            Ref ref = DownloadTrackPatch.fileRefOf(trackId);
            if (ref == null) continue;
            DownloadLocation location = ref.location();
            Boolean reachable = available.get(location.id());
            if (reachable == null) available.put(location.id(), reachable = location.isAvailable(context));
            if (reachable) continue;
            ids.add(trackId);
            labels.add(location.label());
        }
        return new Lost(ids, labels);
    }

    /**
     * The folder of the file of a track, for the user, when it cannot be reached now; null if it can, or the track has
     * no file. May ask the system: call off the main thread.
     */
    static String unreachableFolder(Context context, String trackId) {
        Ref ref = DownloadTrackPatch.fileRefOf(trackId);
        if (ref == null) return null;
        DownloadLocation location = ref.location();
        return location.isAvailable(context) ? null : location.label();
    }

    /**
     * Drops the record of the tracks of a folder that cannot be reached, when the user chose to: a card that is gone for
     * good would otherwise keep its tracks as downloaded for ever, with no way to delete them. The files are not touched.
     *
     * @return How many tracks were forgotten.
     */
    public static int forgetLost(Lost lost) {
        int forgotten = DownloadTrackPatch.forgetDownloads(lost.trackIds);
        // The lists lose the download icons of these tracks.
        DownloadProgress.onDownloadsDeleted();
        return forgotten;
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
            MediaSnapshot listed = snapshot;
            if (listed != null && listed.byName.containsKey(MediaSnapshot.key(ref.locationId, ref.name))) continue;
            File file = ref.location().file(ref.name);
            if (file != null && file.exists() && !file.canRead()) return true;
        }
        return false;
    }

    // endregion
}
