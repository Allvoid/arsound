package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.offline.PlaylistPreloader;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * One merge of playlists, from reading the playlists to writing the tracks. The order of the steps matters:
 * <ol>
 *     <li>read the target and the playlists to take tracks from, and make the {@link MergePlan};</li>
 *     <li>point of no return: {@link MergeProgress#commit} (Cancel does nothing from here);</li>
 *     <li>read the target again and save the tracks to SoundCloud (an own playlist);</li>
 *     <li>add the tracks that cannot go to SoundCloud on this phone, and save the order chosen.</li>
 * </ol>
 * Either writes the new tracks or changes nothing; the {@link MergeReport} says what was done, also when an error
 * stops the merge after the tracks were saved.
 */
final class MergeJob {
    private static final long SAVE_TIMEOUT_SECONDS = 30;
    private static final long READ_PLAYLIST_TIMEOUT_SECONDS = 60;
    private static final long READ_SCREEN_TIMEOUT_SECONDS = 20;

    private final MergeCandidate target;
    private final List<MergeCandidate> sources;
    private final MergeProgress progress;
    private final MergeReport report;

    private PlaylistOps operations;
    private Object targetUrn;

    MergeJob(MergeCandidate target, List<MergeCandidate> sources, MergeProgress progress, MergeReport report) {
        this.target = target;
        this.sources = sources;
        this.progress = progress;
        this.report = report;
    }

    /** Blocks: call it off the main thread. */
    void run() throws Exception {
        operations = PlaylistOps.current();
        if (operations == null) {
            report.error = text("SoundCloud ещё запускается, повторите через минуту.", "SoundCloud is still starting, try again in a minute.");
            return;
        }
        targetUrn = operations.playlistUrn(target.urn);

        progress.update(text("Читаю «" + target.title + "»", "Reading \"" + target.title + "\""));
        // The tracks the target has on SoundCloud. An edit saves the whole list, so it has to be there in full.
        List<Object> existing = operations.serverTracks(targetUrn);
        boolean complete = existing != null && !(existing.isEmpty() && target.serverCount != 0);
        if (target.editable && !complete) {
            report.error = text("Плейлист ещё не загружен. Откройте его, дождитесь списка треков и повторите.",
                    "The playlist is not loaded yet. Open it, wait for the tracks and try again.");
            return;
        }
        if (existing == null) existing = new ArrayList<>();

        List<String> earlier = LocalAdditions.getEntries(target.urn);
        MergePlan plan = collect(existing, earlier);
        if (plan == null) return;
        report.duplicates = plan.duplicates();
        report.overLimit = plan.overLimit();

        if (plan.isEmpty()) {
            logMerge();
            return;
        }
        // Cancel no longer works from here: the tracks are saved.
        if (!progress.commit()) {
            report.cancelled = true;
            return;
        }
        progress.update(text("Записываю в «" + target.title + "»", "Saving to \"" + target.title + "\""));

        Set<Object> merged = null;
        if (plan.hasServerTracks()) {
            merged = recheckBeforeSaving(plan, existing);
            if (merged == null) return;
        }
        int sent = plan.sentCount();

        // The screen lists the tracks on SoundCloud first and the ones added on this phone after them. If the order
        // chosen is another one, the playlist gets it as its own order. With the setting off nobody shows or uses that
        // order, and saving it would replace an arrangement the user may want back when the setting is turned on.
        List<String> wanted = null;
        if (target.editable && sent > 0 && Settings.isPlaylistOrderEnabled()
                && (!earlier.isEmpty() || plan.filesBeforeTracks())) {
            wanted = chosenOrder(plan);
        }

        if (sent > 0 && !saveToSoundCloud(merged, sent)) return;
        saveOnPhone(plan.phoneEntries());
        if (wanted != null && report.notOnPhone == 0) TrackOrder.saveUrns(target.urn, wanted);
        logMerge();
    }

    /**
     * Reads the playlists to take tracks from and plans what is added.
     *
     * @return The plan, or null if the user cancelled.
     */
    private MergePlan collect(List<Object> existing, List<String> earlier) throws Exception {
        // Everything the target holds already: its tracks on SoundCloud and the ones added on this phone.
        List<String> alreadyThere = new ArrayList<>();
        for (Object urn : existing) alreadyThere.add(String.valueOf(urn));
        alreadyThere.addAll(earlier);
        MergePlan plan = new MergePlan(alreadyThere, existing.size(), target.editable, PlaylistMerge.MAX_TRACKS);

        int position = 0;
        for (MergeCandidate source : sources) {
            if (progress.cancelled()) {
                report.cancelled = true;
                return null;
            }
            position++;
            String step = position + "/" + sources.size();
            progress.update(text("Читаю «" + source.title + "» (" + step + ")", "Reading \"" + source.title + "\" (" + step + ")"));
            List<?> urns = readTracks(source);
            if (urns == null) {
                report.failed.add(source.title);
                continue;
            }
            for (Object urn : urns) {
                if (urn == null) continue;
                boolean file = Urns.isLocalFile(urn);
                String entry = file ? LocalAdditions.entryOf(urn) : String.valueOf(urn);
                if (entry == null) continue;
                plan.offer(urn, entry, file);
            }
        }
        return plan;
    }

    /**
     * The whole list of the target is saved, so it is read again now: a track that came to it while the playlists
     * were read (added on the web or on another phone, then synced) must not be lost.
     *
     * @return The track list to save, or null if the target could not be read (the report has the error).
     */
    private Set<Object> recheckBeforeSaving(MergePlan plan, List<Object> existing) throws Exception {
        List<Object> latest = operations.serverTracks(targetUrn);
        if (latest == null || (latest.isEmpty() && !existing.isEmpty())) {
            report.error = text("Не удалось перечитать плейлист перед записью. Он не изменён.",
                    "Could not read the playlist again before saving. It was not changed.");
            return null;
        }
        List<String> latestEntries = new ArrayList<>();
        for (Object urn : latest) latestEntries.add(String.valueOf(urn));
        plan.recheck(latestEntries);
        Set<Object> merged = new LinkedHashSet<>(latest);
        for (MergePlan.Incoming item : plan.incoming()) {
            if (item.server && !item.skipped) merged.add(item.urn);
        }
        report.duplicates = plan.duplicates();
        report.overLimit = plan.overLimit();
        return merged;
    }

    /** @return False if the save failed (the report has the error). */
    private boolean saveToSoundCloud(Set<Object> merged, int sent) throws Exception {
        report.touched = true;
        Throwable failure = Rx.awaitCompletable(operations.editTracks(targetUrn, merged), SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (failure != null) {
            Logger.printException(() -> "Could not save the merged playlist", failure);
            // Nothing is added on the phone either: the target stays as it was. A save that is only late may still go through.
            boolean late = failure instanceof TimeoutException;
            report.touched = late;
            report.error = late
                    ? text("SoundCloud не подтвердил запись вовремя. Плейлист мог измениться: откройте его и проверьте.",
                    "The save did not finish in time. The playlist may have changed: open it to check.")
                    : text("Не удалось записать треки в плейлист. Он не изменён.",
                    "Could not save the tracks to the playlist. It was not changed.");
            return false;
        }
        report.added += sent;
        // SoundCloud is out of reach also when the region guard blocks its requests, though the phone has a network.
        report.offline = !MergeReport.isOnline() || RegionGuard.blocksLastCountry();
        try {
            // Sends the change to SoundCloud, as its own "Add to playlist" sheet does.
            operations.sync(targetUrn);
        } catch (Exception ex) {
            // The tracks are saved here already, and SoundCloud sends them with its next sync.
            Logger.printException(() -> "Could not ask SoundCloud to send the merged playlist", ex);
        }
        return true;
    }

    /** The tracks that cannot go to SoundCloud (files, tracks of somebody else's playlist) are added on this phone only. */
    private void saveOnPhone(List<String> onPhone) {
        if (onPhone.isEmpty()) return;
        report.touched = true;
        try {
            int saved = LocalAdditions.addAll(target.urn, onPhone);
            // The list of local tracks names them by their titles: loaded one after another from now on, not when it is opened.
            LocalAdditions.rememberTitles(onPhone);
            report.added += saved;
            if (target.editable) report.filesOnPhone = saved;
            report.onPhoneOnly = !target.editable;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not add the merged tracks on this phone", ex);
            report.notOnPhone = onPhone.size();
        }
    }

    private void logMerge() {
        Logger.printInfo(() -> "Merged " + sources.size() + " playlists into " + target.urn + ": added " + report.added
                + ", already there " + report.duplicates + ", over the limit " + report.overLimit + ", unreadable " + report.failed.size());
    }

    /**
     * The order of the target as its screen shows it now, then the tracks to add in the order chosen.
     *
     * @return The urn texts, or null if the list of the screen cannot be read: the playlist then keeps the order of the screen.
     */
    private List<String> chosenOrder(MergePlan plan) {
        try {
            Object single = operations.trackUrns(targetUrn);
            Object shown = Rx.blockingFirst(LocalAdditions.appendToTrackUrns(single, targetUrn), READ_SCREEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!(shown instanceof List)) return null;
            Set<String> order = new LinkedHashSet<>();
            for (Object urn : (List<?>) shown) order.add(String.valueOf(urn));
            for (MergePlan.Incoming item : plan.incoming()) if (!item.skipped) order.add(String.valueOf(item.urn));
            return new ArrayList<>(order);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the order of the playlist screen", ex);
            return null;
        }
    }

    /** The tracks of a playlist as they play: SoundCloud's and the ones added here, in the order the user set. */
    private List<?> readTracks(MergeCandidate source) {
        try {
            Object urn = operations.playlistUrn(source.urn);
            if (source.serverCount > 0 && !hasStoredTracks(urn)) {
                // A playlist that was never opened has none of its SoundCloud tracks stored. SoundCloud loads it by itself
                // only when the list it reads is empty, but that list also holds the tracks added on this phone: load it here.
                if (RegionGuard.blocksLastCountry() || progress.cancelled()) return null;
                if (!PlaylistPreloader.syncAndWait(operations.raw(), urn) || !hasStoredTracks(urn)) return null;
            }
            Object list = Rx.blockingFirst(operations.playbackUrns(urn), READ_PLAYLIST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    private boolean hasStoredTracks(Object urn) throws Exception {
        List<Object> stored = operations.serverTracks(urn);
        return stored != null && !stored.isEmpty();
    }
}
