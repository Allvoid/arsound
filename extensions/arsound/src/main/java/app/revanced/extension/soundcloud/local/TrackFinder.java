package app.revanced.extension.soundcloud.local;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.search.OtherSource;

/** Finding one wanted track for a batch run: on SoundCloud, on YouTube Music, or among the imported files. */
final class TrackFinder {
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** Tags of imported files read during this process (file path, time and size to the track), so a miss does not read them all again. */
    private static final Map<String, LocalMusic.Track> TAGS = new ConcurrentHashMap<>();

    private TrackFinder() {
    }

    /**
     * The full original of a track on SoundCloud: by the artist, with the wanted title, not a snippet or
     * another version. Null if the search has none. Blocks: call it off the main thread.
     */
    static ClientProfiles.FoundTrack findOnSoundCloud(String artist, String title) throws Exception {
        for (ClientProfiles.FoundTrack track : ClientProfiles.searchTracks(artist + " " + title)) {
            if (isWantedFullTrack(track, artist, title) && !TrackMatcher.isSnippet(track.title, title)) return track;
        }
        return null;
    }

    private static boolean isWantedFullTrack(ClientProfiles.FoundTrack track, String artist, String title) {
        return track.isFull() && TrackMatcher.isWantedTrack(track.user, track.title, artist, title);
    }

    /**
     * Finds one track: the original on SoundCloud first (often uncensored, and more tracks are there),
     * then YouTube Music, then the imported files.
     */
    static BatchOutcome findOne(Context context, String artist, String title) throws Exception {
        String key = artist + " " + title;
        ClientProfiles.FoundTrack snippet = null;
        try {
            for (ClientProfiles.FoundTrack track : ClientProfiles.searchTracks(artist + " " + title)) {
                if (!isWantedFullTrack(track, artist, title)) continue;
                if (TrackMatcher.isSnippet(track.title, title)) {
                    if (snippet == null) snippet = track;
                    continue;
                }
                return BatchOutcome.soundCloudTrack(key, track.title, track.id);
            }
        } catch (Exception ex) {
            // YouTube Music is still worth asking.
            Logger.printInfo(() -> "Batch: SoundCloud search failed for " + key + ": " + ex);
        }
        BatchOutcome fromOtherSource = downloadOne(context, artist, title);
        if (fromOtherSource.entry() != null) return fromOtherSource;
        // A snippet only when the full track is nowhere: better than nothing for unreleased songs.
        if (snippet != null) return BatchOutcome.soundCloudTrack(key, snippet.title, snippet.id);
        return fromOtherSource;
    }

    /** Downloads one track, or finds it among the imported files. */
    private static BatchOutcome downloadOne(Context context, String artist, String title) throws Exception {
        String key = artist + " " + title;
        OtherSource.Track match = null;
        for (OtherSource.Track track : OtherSource.search(artist + " " + title)) {
            if (TrackMatcher.normalize(track.artist).contains(TrackMatcher.normalize(artist))
                    && TrackMatcher.holdsTitle(track.title, title) && !TrackMatcher.isVariant(track.title, title)) {
                match = track;
                break;
            }
        }
        if (match == null) {
            // Not on YouTube Music: a file imported earlier (for example a track removed from SoundCloud) will do.
            // The title from the file tags (the file name may be a SoundCloud id). Only a title holding the
            // whole wanted one: a short title must not match a longer one.
            for (LocalMusic.Track existing : importedTracks(context)) {
                if (TrackMatcher.isImportedMatch(existing.title, title)) return BatchOutcome.imported(key, existing.title, existing.file);
            }
            return BatchOutcome.notFound(key);
        }

        // A track downloaded earlier is reused instead of downloading a copy.
        File file = null;
        String prefix = FileIo.safeName(match.artist + " - " + match.title) + ".";
        for (File existing : LocalMusic.getFiles(context)) {
            if (existing.getName().startsWith(prefix)) file = existing;
        }
        for (int attempt = 1; file == null; attempt++) {
            AudioStream stream = OtherSource.bestAudio(match.url);
            String name = match.artist + " - " + match.title + "." + OtherSource.extensionOf(stream);
            try {
                file = LocalMusic.importThroughPart(context, name, output ->
                        OtherSource.download(stream.getContent(), output, value -> {
                        }));
            } catch (Exception ex) {
                if (!(ex instanceof OtherSource.RefusedException) || attempt >= MAX_DOWNLOAD_ATTEMPTS) throw ex;
            }
        }
        if (!LocalCovers.hasCover(file)) LocalCovers.save(file, match.coverUrl);
        LocalMusic.onFileAdded();
        return BatchOutcome.downloaded(key, match.title, match.url, file);
    }

    /** Imported tracks with their tags. The tags of a file are read once: every miss of a long list asks for all of them. */
    private static List<LocalMusic.Track> importedTracks(Context context) throws InterruptedException {
        List<LocalMusic.Track> tracks = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (File file : LocalMusic.getFiles(context)) {
            // A track given up on after the time limit stops here instead of reading the rest of the files.
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String key = file.getPath() + '/' + file.lastModified() + '/' + file.length();
            current.add(key);
            LocalMusic.Track track = TAGS.get(key);
            if (track == null) {
                track = LocalMusic.readTrack(file);
                TAGS.put(key, track);
            }
            tracks.add(track);
        }
        // Files deleted or changed since are forgotten.
        TAGS.keySet().retainAll(current);
        return tracks;
    }
}
