package app.revanced.extension.soundcloud.transfer;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The search of a transfer, the same for every source: each track of the collection is looked up on SoundCloud (a few
 * at once), or taken from the matches kept from before, and the urns found come back in the order of the collection,
 * each once. Pure: the lookup, the keeping and the progress are given to it, so it runs in tests without a phone.
 */
final class TrackSearch {
    /** Finds a track on SoundCloud: its urn, or "" if it is not there. */
    interface Finder {
        String urn(SourceTrack track) throws Exception;
    }

    interface Progress {
        /** After each track: how many are looked at and how many found so far. */
        void onTrack(int checked, int found);

        /** The matches changed and are worth writing now. */
        void save(JSONObject matches);
    }

    /** This many failed searches in a row mean SoundCloud is out of reach: the connection is gone, not the songs. */
    static final int UNREACHABLE_FAILURES = 8;
    /** The matches are saved this often while the search runs; the whole file is written each time. */
    private static final long SAVE_EVERY_MS = 15_000;

    /** "(not checked: network error)" after the label of a track whose search failed. */
    private final String notCheckedNote;
    private final int threads;

    TrackSearch(int threads, String notCheckedNote) {
        this.threads = threads;
        this.notCheckedNote = notCheckedNote;
    }

    /**
     * @param matches The kept matches, track id to {"urn", "time"}; the new ones are added to it.
     * @param missing The labels of the tracks not found are added to it.
     * @return The urns found, in the order of the collection, each once.
     * @throws IOException If SoundCloud stopped answering ({@link #UNREACHABLE_FAILURES} failures in a row).
     */
    List<String> run(List<SourceTrack> tracks, JSONObject matches, Finder finder, List<String> missing,
                     Progress progress) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> urns = new LinkedHashSet<>();
        int failuresInRow = 0;
        int checked = 0;
        long savedAt = System.currentTimeMillis();
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (SourceTrack track : tracks) {
                JSONObject kept = matches.optJSONObject(track.id);
                if (kept != null) {
                    String urn = kept.optString("urn");
                    answers.add(pool.submit(() -> urn));
                } else {
                    answers.add(pool.submit(() -> finder.urn(track)));
                }
            }
            for (int i = 0; i < tracks.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                SourceTrack track = tracks.get(i);
                String urn;
                try {
                    // No time limit here: the search has its own (connect and read, three tries), and a limit shorter
                    // than its worst case only called a slow track "not found" while the search went on.
                    urn = answers.get(i).get();
                    failuresInRow = 0;
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    // Searches failing one after another mean the connection is gone, found tracks or not: the
                    // transfer stops (what was found is kept) instead of calling the rest of the list "not found".
                    if (++failuresInRow >= UNREACHABLE_FAILURES) throw new IOException("SoundCloud search unreachable");
                    // Not looked at, not missing: it is not remembered, so a new try searches for it again.
                    missing.add(track.label() + notCheckedNote);
                    progress.onTrack(++checked, urns.size());
                    continue;
                }
                if (urn == null) urn = "";
                if (!matches.has(track.id)) {
                    matches.put(track.id, new JSONObject().put("urn", urn).put("time", System.currentTimeMillis()));
                }
                if (urn.isEmpty()) missing.add(track.label());
                else urns.add(urn);
                if (System.currentTimeMillis() - savedAt >= SAVE_EVERY_MS) {
                    progress.save(matches);
                    savedAt = System.currentTimeMillis();
                }
                progress.onTrack(++checked, urns.size());
            }
        } finally {
            pool.shutdownNow();
            progress.save(matches);
        }
        return new ArrayList<>(urns);
    }
}
