package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The plain rules of the actions on the whole list of the "My Wave" tab (add to a playlist, like, download): which
 * tracks are taken, in what order, and the words of the result. No Android work here, so tests can run it.
 * <p>
 * The wave's list changes often, so an action takes a snapshot of the tracks at the moment of the tap
 * ({@link #snapshot}); tracks that come later are not in it, and a list that changes while the action runs does not
 * change the action.
 */
public final class WaveBatch {
    private WaveBatch() {
    }

    /** The tracks of the list at this moment: SoundCloud tracks only, each once, in the list's order. */
    public static List<String> snapshot(List<String> urns) {
        Set<String> taken = new LinkedHashSet<>();
        if (urns != null) {
            for (String urn : urns) {
                if (TrackUrn.isTrack(urn)) taken.add(TrackUrn.normalize(urn));
            }
        }
        return new ArrayList<>(taken);
    }

    /** The number in the urn ({@code soundcloud:tracks:123} gives {@code 123}). */
    public static String trackId(String urn) {
        return urn.substring(urn.lastIndexOf(':') + 1);
    }

    /**
     * The tracks to like, from the last to the first: the newest like is on top of the likes, so the list reads there
     * as it does in the wave. Tracks already liked are left alone, as a like again would move them up.
     */
    public static List<String> likePlan(List<String> urns, Set<String> alreadyLiked) {
        List<String> plan = new ArrayList<>(urns.size());
        for (int i = urns.size() - 1; i >= 0; i--) {
            if (!alreadyLiked.contains(urns.get(i))) plan.add(urns.get(i));
        }
        return plan;
    }

    /** How far an action is, 0 to 1; an empty one is done. */
    public static float fraction(int done, int total) {
        if (total <= 0) return 1f;
        return Math.max(0f, Math.min(1f, done / (float) total));
    }

    public static String addedSummary(int added, int total, String playlist) {
        if (total == 0) return text("В списке нет треков", "The list has no tracks");
        if (added == total) {
            return text("Добавлено в «" + playlist + "»: " + added + " из " + total + " (только на этом телефоне)",
                    "Added to \"" + playlist + "\": " + added + " of " + total + " (on this phone only)");
        }
        return text("Добавлено в «" + playlist + "»: " + added + " из " + total + ", остальные там уже были",
                "Added to \"" + playlist + "\": " + added + " of " + total + ", the rest were there already");
    }

    /**
     * @param liked   How many of the tracks are liked at the end, those liked before included.
     * @param stopped True when SoundCloud stopped taking likes.
     */
    public static String likedSummary(int liked, int total, boolean stopped) {
        String count = text("В лайках: " + liked + " из " + total, "In likes: " + liked + " of " + total);
        if (stopped) {
            return count + text(". SoundCloud перестал принимать лайки, попробуйте позже.",
                    ". SoundCloud stopped taking likes, try later.");
        }
        return count;
    }

    /**
     * @param done        Downloaded now, those that were on the phone before not counted.
     * @param already     Were on the phone before the tap.
     * @param offline     Protected tracks handed to SoundCloud's own offline library.
     * @param unavailable Not available to download (subscription, region, preview).
     * @param failed      Did not start or did not finish.
     */
    public static String downloadSummary(int done, int already, int offline, int unavailable, int failed, int total) {
        StringBuilder note = new StringBuilder(text("Скачано " + (done + already) + " из " + total,
                "Downloaded " + (done + already) + " of " + total));
        if (already > 0) note.append(text(" (уже были: " + already + ")", " (there before: " + already + ")"));
        if (offline > 0) note.append(text(", защищённых, в офлайн SoundCloud: " + offline, ", protected, in SoundCloud offline: " + offline));
        if (unavailable > 0) note.append(text(", недоступно: " + unavailable, ", not available: " + unavailable));
        if (failed > 0) note.append(text(", не удалось: " + failed, ", failed: " + failed));
        return note.toString();
    }
}
